# Project Sentinel

Project Sentinel is an autonomous AI Site Reliability Engineer. It monitors a Spring Boot microservice (lab-rat), receives firing alerts from Prometheus via AlertManager, and uses a LangChain4j + Google Gemini agent to investigate root causes — fetching logs, dynamically profiling the live JVM, and proposing source-level code fixes — without human intervention. A real-time hex-grid dashboard tracks the health of every component.

## Architecture

| Component | Role |
|---|---|
| **lab-rat** | Intentionally fragile Spring Boot service (port 8080); exposes chaos endpoints to simulate memory leaks, latency, and DB locks |
| **sentinel-agent** | LangChain4j AI agent (port 8081); receives AlertManager webhooks and runs the investigate→profile→fix loop against lab-rat |
| **sentinel-core** | Shared library consumed by sentinel-agent; contains `LogFetcher` and `ProfilerAttacher` used as LLM tools |
| **sentinel-dashboard** | Vite + React dashboard (port 5173); polls all services and renders live status, heap usage, and alert history on a hex grid |
| **Prometheus** | Scrapes lab-rat metrics and evaluates alert rules (port 9090) |
| **AlertManager** | Receives fired alerts from Prometheus and routes them to the sentinel-agent webhook (port 9093) |
| **Grafana** | Visualization layer for Prometheus metrics (port 3000, admin/admin) |

## Prerequisites

- Java 21+
- Gradle 8+
- Node.js 18+ and npm 9+
- Docker Desktop (for the observability stack)
- A [Google Gemini API key](https://aistudio.google.com/app/apikey)

## Setup

```bash
git clone https://github.com/your-org/project-sentinel.git
cd project-sentinel
```

Copy the environment template and add your Gemini key:

```bash
cp .env.example .env
# Edit .env and set GEMINI_API_KEY=your_actual_key
```

Build the Java modules:

```bash
./gradlew build
```

## Running

**1. Start the observability stack (Prometheus, AlertManager, Grafana):**

```bash
docker compose up -d
```

**2. Start lab-rat and sentinel-agent:**

```powershell
.\run.ps1
```

This opens two terminal windows — one per Java service. lab-rat starts on port 8080 and sentinel-agent on port 8081.

**3. Start the dashboard:**

```bash
cd sentinel-dashboard
npm install
npm run dev
```

Open `http://localhost:5173`.

## Dashboard

The dashboard polls all five services every few seconds and renders their UP/DOWN state on a hex grid. It also displays:

- Live heap usage (MB) fetched via the sentinel-agent proxy
- Active, suppressed, and resolved alerts from AlertManager
- Per-service color coding: green (lab-rat), blue (sentinel), orange (Prometheus), yellow (AlertManager), purple (Grafana)

## How It Works

- An alert rule in Prometheus fires when lab-rat breaches a threshold (high heap, elevated latency, etc.) and forwards the alert to AlertManager.
- AlertManager routes the firing alert to the sentinel-agent webhook, triggering an investigation.
- The Sentinel AI agent follows a strict ReAct loop: it calls `fetchLatestErrors` to read recent WARN/ERROR log lines, then reasons over what they reveal.
- If logs indicate CPU pressure, memory growth, or a hung thread, the agent calls `getLabRatPid` and then `runDynamicProfiler` to attach a bytecode profiler to the live JVM and capture per-method execution times.
- The agent reasons over the profiler report, identifies the highest-cost method, and calls `proposeFix` with the target file and a corrected code block. The final output is a structured diagnostic report: Symptoms -> Evidence -> Root Cause -> Proposed Fix.

## Triggering Chaos

With both Java services running, hit lab-rat's chaos endpoints to fire real alerts:

```bash
curl http://localhost:8080/chaos/leak      # simulate memory leak
curl http://localhost:8080/chaos/latency   # simulate slow requests
curl http://localhost:8080/chaos/db-lock   # simulate thread contention
```

## Project Structure

```
project-sentinel/
├── lab-rat/                  # Target Spring Boot microservice with chaos endpoints
├── sentinel-core/            # Shared library: LogFetcher, ProfilerAttacher
├── sentinel-agent/           # AI agent: SreAgent (LangChain4j), SreTools, webhook controller
├── sentinel-dashboard/       # Vite + React monitoring dashboard
├── prometheus.yml            # Prometheus scrape and rule configuration
├── alert-rules.yml           # Alert threshold definitions
├── alertmanager.yml          # Alert routing to sentinel-agent webhook
├── docker-compose.yml        # Prometheus, AlertManager, Grafana
├── run.ps1                   # Starts lab-rat and sentinel-agent on Windows
└── .env.example              # Environment variable template
```

## License

MIT
