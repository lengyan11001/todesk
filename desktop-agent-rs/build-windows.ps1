$ErrorActionPreference = "Stop"

$cargo = "$env:USERPROFILE\.cargo\bin\cargo.exe"
if (!(Test-Path $cargo)) {
  throw "cargo.exe not found. Install Rust first."
}

$vcvars = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
if (!(Test-Path $vcvars)) {
  throw "vcvars64.bat not found. Install Visual Studio 2022 Build Tools with C++ workload."
}

& cmd.exe /c "call `"$vcvars`" && `"$cargo`" build --release"
if ($LASTEXITCODE -ne 0) {
  throw "cargo build failed with exit code $LASTEXITCODE"
}

$exe = Join-Path $PSScriptRoot "target\release\bhzn-todesk-agent-rs.exe"
if (!(Test-Path $exe)) {
  throw "Build finished but exe was not found: $exe"
}

Write-Host "Built: $exe"
