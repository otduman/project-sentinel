# Versions — Project Sentinel

Single source of truth for every external version this project depends on. Bumping anything here is a deliberate act — at minimum, update the pin AND this file in the same commit.

## Why pin

Container images tagged `:latest` and floating major-only tags (e.g. `gradle:9-jdk21-alpine`) silently roll forward. That makes "it worked yesterday, now Docker fails" mysteries — exactly the failure mode that bit us when Spring Boot 4.0 needed Gradle 9.x but the Docker image was still on 8.x. Every external version is now pinned to a specific patch (or major.minor where reasonable).

## Bumping policy

| Component class | Allowed pin shape | When to bump |
|---|---|---|
| Build toolchain (Gradle, Java, Node) | Exact patch (`9.4.1`, `20.18`) | When local wrapper changes — must change in lockstep |
| Frameworks (Spring Boot, LangChain4j, React) | Exact patch | Only with intent — read release notes |
| Observability stack (Prometheus, AlertManager, Grafana) | Exact patch | When you want a feature or fix; never just for novelty |
| Runtime base images (Temurin JRE, nginx) | Major.minor (`21-jre-alpine`, `1.27-alpine`) | Patch updates flow in automatically and are low-risk |
| LLM models (Gemini) | Exact model name in `application.properties` | When provider deprecates or you want quality bump |

## Build toolchain

| Tool | Version | Where pinned |
|---|---|---|
| Java (build + runtime) | 21 | `sentinel-agent/build.gradle` (`languageVersion = JavaLanguageVersion.of(21)`), `lab-rat/build.gradle`, all Dockerfiles (`-jdk21` / `-jre21` image tags) |
| Gradle | 9.4.1 | `gradle/wrapper/gradle-wrapper.properties` (the wrapper, source of truth), `sentinel-agent/Dockerfile` (`gradle:9.4.1-jdk21-alpine`), `lab-rat/Dockerfile` (same) |
| Node.js | 20.18 | `sentinel-dashboard/Dockerfile` (`node:20.18-alpine`); local dev should use Node 20.x via nvm or similar |
| npm | bundled with Node | n/a |

**When upgrading Gradle:** update `gradle-wrapper.properties` AND both Dockerfiles AND this file in one commit. Run `./gradlew compileJava compileTestJava` locally and `docker compose build sentinel-agent lab-rat` to verify.

## Application frameworks

| Framework | Version | Where pinned |
|---|---|---|
| Spring Boot | 4.0.5 | `sentinel-agent/build.gradle`, `lab-rat/build.gradle` (plugin block) |
| Spring Dependency Management plugin | 1.1.7 | Same as above |
| LangChain4j | 1.13.1 | `sentinel-agent/build.gradle` (both `langchain4j` and `langchain4j-google-ai-gemini`) |
| Mockito | (Spring Boot BOM) | `sentinel-agent/build.gradle` (`mockitoAgent` config inherits version from BOM) |

**Compatibility constraints:**
- Spring Boot 4.0.x requires Gradle 9.x and Java 21+.
- LangChain4j 1.13.x's `GoogleAiGeminiChatModel` is the only Spring-Boot-4-compatible release; the `-spring-boot-starter` is incompatible (see langchain4j#4268) — wire beans manually in `AgentConfiguration`.
- Gemini 3 multi-turn tool calling requires `returnThinking(true)` AND `sendThinking(true)` on the model builder (see `memory/project_gemini3_thought_signatures.md`).

## Observability stack (docker-compose images)

| Service | Image | Notes |
|---|---|---|
| Prometheus | `prom/prometheus:v3.0.1` | v3 series — `--web.enable-lifecycle` is on for hot config reload |
| AlertManager | `prom/alertmanager:v0.27.0` | Default `repeat_interval: 5m` drives the webhook dedup window in `InvestigationService.DEDUP_WINDOW` (10 min) — keep them aligned |
| Grafana | `grafana/grafana:11.4.0` | Datasource provisioning is manual for now |
| Postgres | `postgres:16-alpine` | Schema managed by Hibernate `ddl-auto=update` (Phase: replace with Flyway in a future iteration) |
| nginx (dashboard runtime) | `nginx:1.27-alpine` | Serves the static React bundle, enforces CSP — see `sentinel-dashboard/nginx.conf` |
| Eclipse Temurin JRE (runtime) | `eclipse-temurin:21-jre-alpine` | Runtime layer for both lab-rat and sentinel-agent images |

## Frontend

| Package | Version | Where pinned |
|---|---|---|
| React | ^19.2.4 | `sentinel-dashboard/package.json` |
| Vite | ^5.2.0 | `sentinel-dashboard/package.json` (devDependency) |
| TypeScript | ~5.2.2 | `sentinel-dashboard/package.json` |
| react-markdown | ^10.1.0 | `sentinel-dashboard/package.json` — renders investigation reports with full markdown formatting |
| remark-gfm | ^4.0.1 | `sentinel-dashboard/package.json` — GFM tables/strikethrough/checkboxes for markdown |

The `package-lock.json` pins the exact transitive tree — keep it committed.

## LLM models (Gemini)

| Role | Model | Where set |
|---|---|---|
| Primary | `gemini-3-flash-preview` | `sentinel-agent/src/main/resources/application.properties` (`gemini.model.primary`) — overridable via `GEMINI_MODEL_PRIMARY` env var |
| Fallback (rate-limit / overload) | `gemini-2.5-flash` | Same file (`gemini.model.fallback`) — overridable via `GEMINI_MODEL_FALLBACK` |

Token budget per investigation: `agent.gemini.token-budget=100000` in the same file (overridable via `AGENT_GEMINI_TOKEN_BUDGET`).

**Caveat — preview model risk:** `gemini-3-flash-preview` can be deprecated by Google with little notice (e.g. `gemini-3-pro-preview` was retired and replaced by `gemini-3.1-pro-preview` in March 2026). The `FallbackChatModel` will absorb 429/503/RESOURCE_EXHAUSTED errors but a model rename → 404 will fail fast — bump `gemini.model.primary` and rebuild when this happens.

## Where each version is checked at build / runtime

- `./gradlew compileJava` — fails immediately on Spring Boot / Gradle / LangChain4j mismatch.
- `docker compose build` — fails immediately on Docker image mismatch.
- `npm run build` (in `sentinel-dashboard/`) — TS strict mode + Vite catch React/library mismatches.
- `docker compose up` — fails fast if pinned image tags don't exist on Docker Hub.

If a build fails after a version bump anywhere, the failing log line points at the offending file. Update pin → re-run → commit.
