param([switch]$Install)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$agentRoot = Join-Path $repoRoot 'agent'
$privateRoot = Join-Path $repoRoot '.local'
$configPath = Join-Path $privateRoot 'agent.env'
$pidPath = Join-Path $privateRoot 'agent.pid'
$pythonPath = Join-Path $agentRoot '.venv/Scripts/python.exe'
New-Item -ItemType Directory -Path $privateRoot -Force | Out-Null
if (-not (Test-Path -LiteralPath $configPath)) {
    $random = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($random) } finally { $generator.Dispose() }
    $sharedSecret = -join ($random | ForEach-Object { $_.ToString('x2') })
    $template = [IO.File]::ReadAllText((Join-Path $agentRoot '.env.example'))
    [IO.File]::WriteAllText($configPath, $template.Replace('REPLACE_WITH_AT_LEAST_32_RANDOM_CHARACTERS', $sharedSecret), (New-Object Text.UTF8Encoding($false)))
}
foreach ($line in [IO.File]::ReadAllLines($configPath)) {
    if ($line.Trim() -eq '' -or $line.TrimStart().StartsWith('#')) { continue }
    if ($line -notmatch '^([A-Z][A-Z0-9_]*)=(.*)$') { throw 'Invalid agent.env line; use KEY=value.' }
    $keyName = $Matches[1]; $valueText = $Matches[2].Trim()
    if ($keyName -notmatch '^(AGENT_[A-Z0-9_]+|MES_AGENT_(ENABLED|BASE_URL|SHARED_SECRET|CONNECT_TIMEOUT_MS|READ_TIMEOUT_MS)|JINA_API_KEY|DEEPSEEK_API_KEY)$') {
        throw "Unsupported agent setting: $keyName"
    }
    if ($valueText -match '[\x00-\x1f\x7f]') { throw "Invalid control character in $keyName" }
    if ($valueText.Length -ge 2 -and (($valueText.StartsWith('"') -and $valueText.EndsWith('"')) -or ($valueText.StartsWith("'") -and $valueText.EndsWith("'")))) {
        $valueText = $valueText.Substring(1, $valueText.Length - 2)
    }
    [Environment]::SetEnvironmentVariable($keyName, $valueText, 'Process')
}
if ($env:AGENT_HOST -notin @('127.0.0.1', 'localhost')) { throw 'Local launcher requires loopback AGENT_HOST.' }
if ($env:MES_AGENT_SHARED_SECRET.Length -lt 32 -or $env:MES_AGENT_SHARED_SECRET.StartsWith('REPLACE_')) { throw 'Configure a random shared service secret first.' }
$listenPort = 8001
if ($env:AGENT_PORT) { $listenPort = [int]$env:AGENT_PORT }
if ($listenPort -lt 1024 -or $listenPort -gt 65535) { throw 'Invalid AGENT_PORT.' }
if ($Install -or -not (Test-Path -LiteralPath $pythonPath)) {
    $uvCommand = Get-Command uv -ErrorAction Stop
    & $uvCommand.Source sync --frozen --project $agentRoot
    if ($LASTEXITCODE -ne 0) { throw 'Python dependency installation failed.' }
}
if (Test-Path -LiteralPath $pidPath) {
    $ownedPid = [int][IO.File]::ReadAllText($pidPath).Trim()
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId=$ownedPid" -ErrorAction SilentlyContinue
    if ($processInfo -and $processInfo.CommandLine.Contains($agentRoot) -and $processInfo.CommandLine.Contains('uvicorn')) {
        Write-Output "Agent already running (PID $ownedPid). Restart it after changing configuration."
        return
    }
}
if (Get-NetTCPConnection -State Listen -LocalPort $listenPort -ErrorAction SilentlyContinue) { throw "Port $listenPort is already used by another process." }
$env:PYTHONUTF8 = '1'
$arguments = @('-m', 'uvicorn', 'app.main:app', '--app-dir', ('"' + $agentRoot + '"'), '--host', '127.0.0.1', '--port', [string]$listenPort, '--workers', '1', '--no-access-log')
$process = Start-Process -FilePath $pythonPath -ArgumentList $arguments -WorkingDirectory $agentRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $privateRoot 'agent.log') -RedirectStandardError (Join-Path $privateRoot 'agent-error.log')
[IO.File]::WriteAllText($pidPath, [string]$process.Id)
$ready = $false
for ($attempt = 0; $attempt -lt 45; $attempt++) {
    if ($process.HasExited) { throw 'Agent exited; inspect .local/agent-error.log.' }
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:$listenPort/health" -TimeoutSec 1
        if ($health.framework -eq 'LANGGRAPH') { $ready = $true; break }
    } catch { Start-Sleep -Milliseconds 500 }
}
if (-not $ready) { throw 'Agent startup did not become ready; inspect .local/agent-error.log.' }
Write-Output "Agent ready on http://127.0.0.1:$listenPort; chat model configured: $($health.llmConfigured)."
