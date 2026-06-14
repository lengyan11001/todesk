$ErrorActionPreference = "Stop"

$cargo = "$env:USERPROFILE\.cargo\bin\cargo.exe"
if (!(Test-Path $cargo)) {
  throw "cargo.exe not found. Install Rust first."
}

$vcvars = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
if (!(Test-Path $vcvars)) {
  throw "vcvars64.bat not found. Install Visual Studio 2022 Build Tools with C++ workload."
}

& cmd.exe /c "call `"$vcvars`" && cd /d `"$PSScriptRoot`" && `"$cargo`" build --release"
if ($LASTEXITCODE -ne 0) {
  throw "cargo build failed with exit code $LASTEXITCODE"
}

$exe = Join-Path $PSScriptRoot "target\release\bhzn-todesk-agent-rs.exe"
if (!(Test-Path $exe)) {
  throw "Build finished but exe was not found: $exe"
}

$dist = Join-Path $PSScriptRoot "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$agent = Join-Path $dist "BHZN-ToDesk-Agent.exe"
$setup = Join-Path $dist "BHZN-ToDesk-Agent-Setup.exe"
Copy-Item -LiteralPath $exe -Destination $agent -Force
Copy-Item -LiteralPath $exe -Destination $setup -Force

$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $agent).Hash
Write-Host "Built: $exe"
Write-Host "Agent: $agent"
Write-Host "Setup: $setup"
Write-Host "SHA256: $hash"
