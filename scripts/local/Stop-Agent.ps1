$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$agentRoot = Join-Path $repoRoot 'agent'
$pidPath = Join-Path $repoRoot '.local/agent.pid'
if (-not (Test-Path -LiteralPath $pidPath)) { Write-Output 'No managed Agent PID.'; return }
$ownedPid = [int][IO.File]::ReadAllText($pidPath).Trim()
$processInfo = Get-CimInstance Win32_Process -Filter "ProcessId=$ownedPid" -ErrorAction SilentlyContinue
if ($processInfo) {
    if (-not ($processInfo.CommandLine.Contains($agentRoot) -and $processInfo.CommandLine.Contains('uvicorn'))) { throw 'PID is not this managed Agent; refusing to stop it.' }
    Stop-Process -Id $ownedPid -ErrorAction Stop
}
Remove-Item -LiteralPath $pidPath
Write-Output 'Managed Agent stopped.'
