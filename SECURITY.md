# Security — Project Sentinel

This is a **demo / portfolio project**. It is hardened where the cost is low, but it is not production-grade and should not be exposed to untrusted networks.

## Threat model assumed

- Single operator on a trusted local machine
- Docker host is the operator's laptop
- All ports bound to localhost; no internet exposure
- Lab-rat is intentionally fragile — its source code is the agent's only legitimate write target

## Pre-deployment checklist (the bare minimum before *any* non-loopback use)

1. **Rotate `AGENT_PATCH_SECRET`** in the root `.env` to a 32+ char random value. Do **not** use `admin!` or any value seen in this repo's history.
2. **Set `VITE_PATCH_TOKEN`** in `sentinel-dashboard/.env` to the same value. (Both files are gitignored.)
3. **Set `AGENT_WEBHOOK_SECRET`** in the root `.env` to a 32+ char random value distinct from the patch secret.
4. **Do not expose ports 8081 or 5173** to anything beyond localhost. The agent API and dashboard have no authentication on read endpoints (and the dashboard has none at all). Bind to 127.0.0.1 in `docker-compose.yml` if you need to be sure.
5. **Review the runbooks**, `lab-rat/src/`, and any patches Gemini has proposed before approving them. The path validator restricts file writes to `com.sentinel.lab_rat`, but Gemini-proposed *content* can be anything.

## What the code already enforces

| Control | Where |
|---|---|
| Bearer auth on patch state-changing endpoints (`approve`, `reject`, `rollback`) | [PatchController.requireAuth](sentinel-agent/src/main/java/com/sentinel/agent/PatchController.java) — fails closed if `agent.patch.secret` is unset |
| Path allowlist with multi-pass prefix strip + strict class-name regex | [PatchPathValidator](sentinel-agent/src/main/java/com/sentinel/agent/PatchPathValidator.java) |
| Symlink defence — `Path.toRealPath()` then `startsWith` re-check after resolution | [PatchApplier.apply](sentinel-agent/src/main/java/com/sentinel/agent/PatchApplier.java) |
| Drift detection — refuse overwrite if file on disk no longer matches `oldContent` | [PatchApplier.apply](sentinel-agent/src/main/java/com/sentinel/agent/PatchApplier.java) |
| Atomic write via temp file + `ATOMIC_MOVE`, with non-atomic fallback on bind-mount filesystems | [PatchApplier.apply](sentinel-agent/src/main/java/com/sentinel/agent/PatchApplier.java) |
| Backup written before every apply, named with patch UUID + timestamp | [PatchApplier.backupTarget](sentinel-agent/src/main/java/com/sentinel/agent/PatchApplier.java) |
| 64KB cap on both `oldCode` and `newCode` to prevent runaway payloads | [SreTools.proposeFix](sentinel-agent/src/main/java/com/sentinel/agent/SreTools.java) |
| Mandatory `oldCode` — refuses blind overwrites at both `proposeFix` and `apply` layers | [SreTools.proposeFix](sentinel-agent/src/main/java/com/sentinel/agent/SreTools.java), [PatchApplier.apply](sentinel-agent/src/main/java/com/sentinel/agent/PatchApplier.java) |
| Per-investigation token budget — bounds Gemini cost on a runaway ReAct loop | [BudgetedChatModel](sentinel-agent/src/main/java/com/sentinel/agent/BudgetedChatModel.java) |
| Episode-based dedupe — one investigation per active alert episode (FAILED excluded so retries still happen) | [InvestigationService.start](sentinel-agent/src/main/java/com/sentinel/agent/InvestigationService.java) |
| Webhook-payload field allowlist — only `commonLabels.alertname`, `commonLabels.severity`, `commonAnnotations.summary` reach the LLM | [AlertController.extractSafeString](sentinel-agent/src/main/java/com/sentinel/agent/AlertController.java) |
| CSP on the dashboard with explicit `connect-src` allowlist | [sentinel-dashboard/nginx.conf](sentinel-dashboard/nginx.conf) |
| Non-root container users for both `sentinel-agent` and `lab-rat` | Their respective `Dockerfile`s |

## Known debt (filed; not yet fixed)

| ID | Issue | Why deferred |
|---|---|---|
| **L-1** | `GET /api/patches/by-investigation/{id}` and `/api/patches/stats` are unauthenticated. They return LLM-generated source code — fine in this repo (lab-rat is public), exposing in a real deployment would leak proposed fixes. | The dashboard reads them; switching to authenticated GET requires browser-side credential handling. |
| **M-2** | Path validator is allowlist-by-shape (regex) rather than allowlist-by-name. A future legitimate class would need no code change to be patchable, but that's also the attack surface | Acceptable for a demo where the patchable services are explicitly registered in `PatchPathValidator.SERVICES`. |

## Resolved

| ID | Issue | Resolution |
|---|---|---|
| **H-3** | `agent.webhook.secret` was optional — unauthenticated `resolved` webhooks allowed token-burn DoS | `AlertController.validateConfig` now fails fast at startup if the secret is blank; mirrors the same fail-closed pattern used by `agent.patch.secret` |

## Reporting a security issue

This is not a maintained project — file an issue on the GitHub repo or email otarbayevduman@gmail.com.
