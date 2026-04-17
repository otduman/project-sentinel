# Project Sentinel — Local Startup Script
# Starts lab-rat and sentinel-agent as background jobs so you don't need separate terminals.
# Run this from the project root: .\run.ps1

$projectRoot = $PSScriptRoot

Write-Host ""
Write-Host "=== Project Sentinel ===" -ForegroundColor Cyan
Write-Host "Starting lab-rat and sentinel-agent..." -ForegroundColor Cyan
Write-Host ""

# Kill any leftover Gradle daemons from previous runs to avoid port conflicts
Write-Host "[*] Stopping any existing Gradle daemons..." -ForegroundColor Yellow
& gradle --stop 2>$null

# Start lab-rat in a new visible window so you can see its logs and trigger chaos endpoints
Write-Host "[*] Starting lab-rat on port 8080..." -ForegroundColor Green
Start-Process "$projectRoot\start-lab-rat.bat"

# Small delay so lab-rat starts binding its port before sentinel-agent tries to connect
Start-Sleep -Seconds 3

# Start sentinel-agent in a new visible window - loads GEMINI_API_KEY from .env automatically via build.gradle
Write-Host "[*] Starting sentinel-agent on port 8081..." -ForegroundColor Green
Start-Process "$projectRoot\start-sentinel-agent.bat"

Write-Host ""
Write-Host "=== All services starting ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "  lab-rat:        http://localhost:8080/actuator/health" -ForegroundColor White
Write-Host "  sentinel-agent: http://localhost:8081/api/sentinel/investigate" -ForegroundColor White
Write-Host "  dashboard:      http://localhost:5173  (run 'npm run dev' in sentinel-dashboard)" -ForegroundColor White
Write-Host "  prometheus:     http://localhost:9090" -ForegroundColor White
Write-Host "  grafana:        http://localhost:3000  (admin/admin)" -ForegroundColor White
Write-Host "  alertmanager:   http://localhost:9093" -ForegroundColor White
Write-Host ""
Write-Host "Chaos endpoints:" -ForegroundColor Yellow
Write-Host "  curl.exe http://localhost:8080/chaos/leak" -ForegroundColor White
Write-Host "  curl.exe http://localhost:8080/chaos/latency" -ForegroundColor White
Write-Host "  curl.exe http://localhost:8080/chaos/db-lock" -ForegroundColor White
Write-Host ""
Write-Host "Two windows opened - one per service. This window can be closed." -ForegroundColor DarkGray
