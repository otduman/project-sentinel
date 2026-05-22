# HANDOFF — Project Sentinel

Working document for any agent (or human) picking up this repo cold. Tells you what exists, why it's wired the way it is, the non-obvious gotchas, and where to look first.

## What this project is

**Project Sentinel** is a portfolio-grade autonomous AI Site Reliability Engineer demo. Prometheus + AlertManager fires alerts at a Spring Boot agent. The agent (LangChain4j + Google Gemini) runs a ReAct loop — fetches logs, profiles the live JVM via ByteBuddy, looks up runbooks — and proposes a source-level code fix. A React dashboard renders proposed patches as diffs; a human approves; the patch is atomically written to disk with backup + rollback + drift detection.

It is **not** a production deployment-ready tool. See [SECURITY.md](SECURITY.md) for what's not safe to expose, [VERSIONS.md](VERSIONS.md) for pinning policy.

## Module layout

| Module | What it does | Port |
|---|---|---|
| `lab-rat/` | Spring Boot chaos sandbox — memory leak / CPU spike / thread deadlock / DB lock / disk fill / latency endpoints. Original demo target. | 8080 |
| `order-service/` | Stub microservice — slow-query / stuck-thread / error-rate chaos endpoints. Added in the multi-service expansion. | 8082 |
| `payment-service/` | Stub microservice — downstream-timeout / memory-pressure / error-rate chaos endpoints. | 8083 |
| `sentinel-agent/` | The AI agent. Webhook receiver, LangChain4j wiring, patch-flow controllers, metrics. Postgres-backed. | 8081 |
| `sentinel-core/` | Shared library — `ProfilerAttacher` (ByteBuddy) and `LogFetcher`. Used by sentinel-agent. | — |
| `sentinel-dashboard/` | React 19 + Vite. Hex topology, INCIDENTS sidebar, patch approval UI, infrastructure status strip. | 5173 (nginx in container, host 5173:80) |
| `grafana/` | Auto-provisioning files (datasource + Sentinel self-observability dashboard JSON). Bind-mounted into the Grafana container. | — |
| Observability stack | Prometheus (9090), AlertManager (9093), Grafana (3000), Postgres (5432). | — |

## How the pieces talk

```
┌─────────┐  scrapes  ┌────────────┐ rules ┌──────────────┐ webhook  ┌────────────────┐
│ lab-rat │ ◀──────── │ Prometheus │ ────▶ │ AlertManager │ ───────▶ │ sentinel-agent │
└─────────┘           └────────────┘       └──────────────┘          └────────────────┘
   8080                    9090                  9093                       8081
┌──────────────┐                                                              │ ReAct loop
│ order-svc    │ ◀────── scrapes ───────                                      │ via LangChain4j
│ payment-svc  │ ◀────── scrapes ───────                                      ▼
│ sentinel-agent (self) ◀ scrapes ───────                              ┌──────────────┐
└──────────────┘                                                       │  Gemini API  │
                                                                       └──────────────┘
                                                                              │
┌─────────────────┐  /api/topology, /api/investigations, /api/patches/*       │
│ dashboard (5173)│ ◀──────── all traffic through sentinel-agent ─────────────┘
└─────────────────┘
```

The dashboard makes **only** `localhost:8081` HTTP calls — every other piece of infrastructure (Prometheus, AlertManager, Grafana, lab-rat health) is reached server-side through the agent. CSP enforces this.

## Critical gotchas — read before changing related code

### Gemini 3 requires `returnThinking + sendThinking`
[`AgentConfiguration.buildGemini()`](sentinel-agent/src/main/java/com/sentinel/agent/AgentConfiguration.java) sets `.returnThinking(true).sendThinking(true)` on `GoogleAiGeminiChatModel`. Without these, **any Gemini 3 model fails with `400 INVALID_ARGUMENT: Function call is missing a thought_signature` on the second multi-turn tool call.** This is mandatory for Gemini 3, harmless for 2.5. Tracking issue: [langchain4j#4097](https://github.com/langchain4j/langchain4j/issues/4097). See also `memory/project_gemini3_thought_signatures.md`.

### LangChain4j Spring Boot starter is incompatible with Spring Boot 4.0
We use the bare `dev.langchain4j:langchain4j` + `langchain4j-google-ai-gemini` artifacts and wire beans manually in [AgentConfiguration.java](sentinel-agent/src/main/java/com/sentinel/agent/AgentConfiguration.java). Do NOT add `langchain4j-spring-boot-starter` — see [langchain4j#4268](https://github.com/langchain4j/langchain4j/issues/4268). The bean wiring is intentional, not a TODO.

### Decorator stack around the chat model
`BudgetedChatModel(FallbackChatModel(primary, secondary, metrics))`. Order matters:
- **BudgetedChatModel** caps input tokens per investigation, enforced via a `ThreadLocal` (`MemoryIdContext`) and a per-memory-id counter
- **FallbackChatModel** transparently falls back to `gemini-2.5-flash` when the primary (default `gemini-3-flash-preview`) returns 429 / 503 / RESOURCE_EXHAUSTED / timeout
- The fallback wraps the primary so the budget is enforced once per logical call regardless of which model answered

Tag `MemoryIdContext.set(investigationId)` on the executor thread that runs `sreAgent.investigate(...)`, or `proposeFix` can't resolve the active investigation and patch-row insert silently fails.

### Episode-based webhook dedupe
[`InvestigationService.start()`](sentinel-agent/src/main/java/com/sentinel/agent/InvestigationService.java) uses `findFirstByAlertNameAndAlertResolvedAtIsNullAndStatusNotAndStartedAtAfterOrderByStartedAtDesc(name, "FAILED", cutoff)`. The semantics matter:
- An "active episode" is an investigation whose `alertResolvedAt` is null AND whose status is NOT `FAILED`
- A `FAILED` investigation is explicitly excluded so a Gemini timeout doesn't deduplicate forever — next firing webhook gets a fresh shot
- The 3-hour `EPISODE_BACKSTOP` is the safety net for missed `resolved` webhooks
- AlertManager's `status: "resolved"` webhook calls `resolveEpisode(alertName)` which bulk-sets `alertResolvedAt` so the next firing genuinely starts a new investigation

### Patch flow safety layers
[`PatchPathValidator.normalise()`](sentinel-agent/src/main/java/com/sentinel/agent/PatchPathValidator.java) uses **multi-pass prefix stripping** (loop until no prefix matches). The previous single-pass version would silently break the entire apply pipeline because it couldn't re-validate the canonical path it had itself stored. Strict regex `^[A-Z][A-Za-z0-9_]*\\.java$` after stripping — rejects path traversal, lowercase classnames, non-`.java` files.

[`PatchApplier`](sentinel-agent/src/main/java/com/sentinel/agent/PatchApplier.java) layers:
1. Re-validate path via `PatchPathValidator`
2. `toRealPath()` then verify resolved path still starts with the lab-rat package (symlink defence)
3. **Drift detection** — read file from disk, compare to `patch.getOldContent()` (with CRLF/LF normalisation). Refuse overwrite if differs.
4. Write a backup before touching the target (named with patch UUID + epoch ms)
5. Temp-file + `ATOMIC_MOVE` (falls back to `REPLACE_EXISTING` on bind-mount filesystems that don't support atomic moves — Docker Desktop Windows)

Both `proposeFix` (tool) and `PatchApplier.apply` reject null/blank `oldContent` — fixed in the post-Phase-2 audit. `agent.patch.secret` is mandatory at the controller (fail-closed) — closes the audit's CRITICAL-1.

**Currently patch-applicable services are restricted to `lab-rat` only** (validator scope). Order/payment incidents complete through Root Cause + Proposed Fix in prose but the agent does NOT call `proposeFix` for them (prompted explicitly).

### Topology API drives the dashboard
The dashboard has no hardcoded service list. [`TopologyController`](sentinel-agent/src/main/java/com/sentinel/agent/TopologyController.java) walks Prometheus's `/api/v1/targets` and returns `{ services: [...], infrastructure: [...] }`. The 3 known infra hosts (prom/AM/grafana) are health-checked directly because we want them to show "down" precisely when they're down.

`ServiceId` is **open (`string`)** — any service Prometheus discovers shows up as a hex. Curated colors for known names in `KNOWN_SERVICES`; everything else cycles through `DEFAULT_PALETTE` by discovery index. Positions: `DEFAULT_SERVICE_AXIAL` fixes positions for known names, `hexFlowerPositions(N)` generates the rest.

### Dashboard environment & CSP
- `.env` (gitignored) holds runtime values. `.env.development` is intentionally NOT used in production builds — Vite only reads it in dev mode. Production `npm run build` reads `.env`. **If you add a new `VITE_*` var, it must go in `.env` (or be passed via Docker `--build-arg`) — putting it in `.env.development` is a silent dead-end.**
- `VITE_PATCH_TOKEN` MUST match `AGENT_PATCH_SECRET` in the root `.env`. Mismatch → dashboard sends wrong Bearer → agent returns 401 → silent Approve failure.
- CSP `connect-src` is locked to `'self' http://localhost:8081`. Tightened to this in Step 4. Adding any new browser-side fetch destination requires adding it to [`sentinel-dashboard/nginx.conf`](sentinel-dashboard/nginx.conf).

### Gradle 9.4.1 worker bug with spaces in path
The user's repo path contains a space (`Programming Projects`). Gradle 9.4.1's test worker bug means `./gradlew test` fails locally with `Could not find or load main class GradleWorkerMain`. Workaround: run tests via IntelliJ (Build Tools → Gradle → "Run tests using: IntelliJ IDEA"). Or run inside Docker (path has no space there). Compile is unaffected — only test execution.

### Docker build doesn't run tests
All three Dockerfiles use `-x test`. **Compiles are checked at Docker build time, tests are not.** Run tests locally (IntelliJ) before pushing if behaviour matters.

## Conventions that aren't obvious from code

- **Always use specialized subagents** when a task matches their description (Security Engineer for audits, Plan agent for architectural planning, Explore agent for broad code searches, etc.) — see `memory/feedback_use_specialized_agents.md`
- **Pin every external version** to a specific patch in `VERSIONS.md`. No `:latest`, no major-only tags. When bumping the Gradle wrapper, bump the Dockerfile `gradle:9.4.1-jdk21-alpine` tag and `VERSIONS.md` in the same commit.
- **Commit messages are wide** — concrete, mention specific files when the why isn't obvious, no marketing language
- **Don't add backwards-compatibility shims** unless explicitly asked. Old patterns get replaced, not deprecated.

## Recent iteration history

1. **Initial commits** (`cf081d1` → `bb1aca2`) — base scaffolding, Postgres, SSE, Docker hardening
2. **`45a21d1`** — non-root containers, optional webhook auth
3. **`e4b25ec`** — LangChain4j 1.13 + Gemini 3 + token budget + auto-fallback, Docker stack
4. **`0f488b0`** — report parser fixes, webhook dedup v1, dashboard overhaul
5. **`c68cebd`** — Phase 1 patch approval gate (record-only), episode-based dedupe, version pinning
6. **`3329402`** — Phase 2 patch applier (auto-apply approved patches with rollback)
7. **`0570556`** — Self-observability metrics + topology-driven dashboard (Steps 1-4 of polish iteration)
8. **`d6c678e`** — Click-to-filter sidebar + alert-aware hex borders + Grafana auto-provisioning (Step 5)
9. **(uncommitted)** — Multi-service expansion: `order-service` + `payment-service` modules, per-service alert rules, runbooks, agent + dashboard awareness

## Known debt (filed, not fixed)

From [SECURITY.md](SECURITY.md):
- **H-3** — `agent.webhook.secret` is optional. Anyone reachable on port 8081 can fake `status=resolved` webhooks → DoS via token burn.
- **L-1** — `GET /api/patches/by-investigation/*` and `/api/patches/stats` are unauthenticated. Returns LLM-generated source. Fine for demo, not for real deploy.
- **M-2** — `PatchPathValidator` is allowlist-by-regex-shape, not allowlist-by-explicit-name. Acceptable for one-package demo, fragile if generalised.

Plus:
- **Patch flow is lab-rat only.** Order/payment incidents stop at "Proposed Fix in prose". Generalising the validator and applier to multi-service is plausible work for the next iteration.
- **Hardcoded `liveMetricFor()`** — only `lab-rat` (heap) and `sentinel-agent` (active investigations) have inline hex metrics. Adding metrics for order/payment requires a code edit in `App.tsx`. Could be data-driven via Prometheus annotations later.
- **Docker Scout** finds CVEs in alpine / JRE / npm transitives. Most are unexploitable in our threat model (non-root, localhost-bound, no SSH) but a `apk update && apk upgrade` pass during base-image refresh would clean the report.

## Where to look first when something breaks

| Symptom | Likely cause | First place to check |
|---|---|---|
| Approve button does nothing | `VITE_PATCH_TOKEN` doesn't match `AGENT_PATCH_SECRET` | F12 Network → expect 401 if mismatch; both `.env` files |
| Investigation never completes, no errors | Gemini timeout, fallback didn't engage | sentinel-agent logs for `Primary model failed with retriable error` |
| Whole investigation report renders as one block | LLM emitted heading shape the regex doesn't recognise | `InvestigationService.SECTION` pattern + the report at `/api/investigations/{id}` |
| Dashboard crashes with `e[h] is undefined` | A hex render path indexed an undefined service position | Check `liveCenters` and `CONNECTIONS` filtering in `HexMap` |
| Hex doesn't glow for an alert | `SERVICE_ALERT_KEYWORDS_MAP` doesn't match the alert name | The map in `App.tsx`, near the top |
| Service not in dashboard despite being in compose | Not added as Prometheus scrape job, or job name doesn't match | `prometheus.yml` → `/api/v1/targets` → `/api/topology` |

## Useful CLAUDE memory notes

Located under `~/.claude/projects/c--Programming-Projects-project-sentinel/memory/`:
- `project_architecture.md` — Module roles, ports, tech stack
- `project_fixes_applied.md` — Bugs/issues fixed in the first refactor session
- `feedback_use_specialized_agents.md` — Always delegate to matching subagent type
- `project_gemini3_thought_signatures.md` — The thought_signatures gotcha above
- `project_security_debt_phase2.md` — Audit findings filed at Phase 2 push

## Test workflow

- **Compile sanity:** `./gradlew compileJava compileTestJava` (works regardless of path-with-spaces bug)
- **Unit tests:** run inside IntelliJ (Build Tools → Gradle → "Run tests using: IntelliJ IDEA")
- **End-to-end smoke:** `docker compose up -d --build` then follow the smoke-test checklist (in this commit's PR or HANDOFF history)
- **Dashboard type-check + build:** `cd sentinel-dashboard && npm run build`

## Quick-reference commands

```bash
# Full stack from scratch
docker compose down -v
docker compose up -d --build

# Rebuild just one service
docker compose up -d --build sentinel-agent

# Watch the agent's logs
docker compose logs -f sentinel-agent

# Inspect investigations
curl -s http://localhost:8081/api/investigations | python -m json.tool

# Check what Prometheus is scraping
curl -s http://localhost:9090/api/v1/targets | python -m json.tool

# Inspect topology as the dashboard sees it
curl -s http://localhost:8081/api/topology | python -m json.tool

# Verify Sentinel's own metrics
curl -s http://localhost:8081/actuator/prometheus | grep "^sentinel_"
```
