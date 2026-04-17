@echo off
title SENTINEL-AGENT (port 8081)
cd /d "C:\Programming Projects\project-sentinel"
gradle :sentinel-agent:bootRun
pause
