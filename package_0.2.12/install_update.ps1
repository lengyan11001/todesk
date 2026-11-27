$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$newExe = Join-Path $here 'BHZN-ToDesk-Agent.exe'
if (!(Test-Path -LiteralPath $newExe)) { throw "找不到 BHZN-ToDesk-Agent.exe" }

$proc = Get-CimInstance Win32_Process -Filter "Name='BHZN-ToDesk-Agent.exe'" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($proc) {
  $exe = $proc.ExecutablePath
  $cmdline = [string]$proc.CommandLine
} else {
  $cmdline = ''
  $candidates = @(
    (Join-Path $env:LOCALAPPDATA 'BHZN-ToDesk\BHZN-ToDesk-Agent.exe'),
    (Join-Path $env:APPDATA 'BHZN-ToDesk\BHZN-ToDesk-Agent.exe')
  )
  $exe = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
}
if (!$exe -or !(Test-Path -LiteralPath $exe)) { throw "找不到已安装的 BHZN-ToDesk-Agent.exe，请先运行客户端远程支持" }

$config = ''
$m = [regex]::Match($cmdline, '--config\s+(?:"([^"]+)"|(\S+))')
if ($m.Success) { $config = if ($m.Groups[1].Success) { $m.Groups[1].Value } else { $m.Groups[2].Value } }

Write-Host "Agent : $exe"
Write-Host "Config: $config"
if ($proc) { Stop-Process -Id $proc.ProcessId -Force; Start-Sleep -Seconds 2 }

$bak = "$exe.bak_$(Get-Date -Format yyyyMMdd_HHmmss)"
Copy-Item -LiteralPath $exe -Destination $bak -Force
Copy-Item -LiteralPath $newExe -Destination $exe -Force

$argLine = '--headless --no-auto-install'
if ($config) { $argLine += ' --config "' + $config + '"' }
Start-Process -FilePath $exe -ArgumentList $argLine

$taskCmd = '"' + $exe + '" --check-update --headless --no-auto-install'
if ($config) { $taskCmd += ' --config "' + $config + '"' }
try {
  schtasks /Create /TN "BHZN-ToDesk-Agent-Update" /SC HOURLY /MO 4 /TR $taskCmd /F | Out-Null
  Write-Host "已注册每 4 小时自动更新任务"
} catch {
  Write-Warning "自动更新任务注册失败（可能需要管理员权限）：$($_.Exception.Message)"
}

Write-Host ""
Write-Host "完成：agent 已升级到 0.2.12-rs"
Write-Host "备份：$bak"
Write-Host "设备号和配置保持不变。"
