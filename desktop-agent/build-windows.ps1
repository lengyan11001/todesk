$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
if (-not (Test-Path ".venv\Scripts\python.exe")) {
  python -m venv .venv
}
.\.venv\Scripts\python.exe -m pip install --upgrade pip
.\.venv\Scripts\python.exe -m pip install -r requirements.txt pyinstaller
.\.venv\Scripts\pyinstaller.exe --clean --onefile --windowed --name BHZN-ToDesk-Agent .\bhzn_desktop_agent.py
Write-Host "Built: $PSScriptRoot\dist\BHZN-ToDesk-Agent.exe"
