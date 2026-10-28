$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $repo 'scripts\local\Start-Local.ps1'), [ref]$null, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Start-Local.ps1 has syntax errors.' }
$function = $ast.FindAll({ param($item) $item -is [Management.Automation.Language.FunctionDefinitionAst] -and $item.Name -eq 'Import-AgentSettings' }, $true) | Select-Object -First 1
if (-not $function) { throw 'Expected Import-AgentSettings is not implemented.' }
. ([scriptblock]::Create($function.Extent.Text))
$runtime = Join-Path $repo '.local\tests'
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$probe = Join-Path $runtime 'agent-settings-test.env'
$utf8 = New-Object Text.UTF8Encoding($false)
$allowed = @('MES_AGENT_ENABLED', 'MES_AGENT_BASE_URL', 'MES_AGENT_SHARED_SECRET', 'MES_AGENT_CONNECT_TIMEOUT_MS', 'MES_AGENT_READ_TIMEOUT_MS')
$saved = @{}
foreach ($key in $allowed) { $saved[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }
$sentinel = [Environment]::GetEnvironmentVariable('MES_AGENT_IMPORT_SENTINEL', 'Process')
$embeddingKey = [Environment]::GetEnvironmentVariable('JINA_API_KEY', 'Process')
try {
    [IO.File]::WriteAllText($probe, @'
# Only the five Java bridge settings may be imported.
MES_AGENT_ENABLED=true
MES_AGENT_BASE_URL=http://127.0.0.1:8001
MES_AGENT_SHARED_SECRET="test-only-32-character-secret-with=equals"
MES_AGENT_CONNECT_TIMEOUT_MS=3000
MES_AGENT_READ_TIMEOUT_MS=35000
MES_AGENT_IMPORT_SENTINEL=must-not-import
JINA_API_KEY=must-not-import
'@, $utf8)
    $output = @(Import-AgentSettings $probe)
    if ($output.Count) { throw 'Agent settings import must not print environment values.' }
    if ($env:MES_AGENT_ENABLED -ne 'true' -or $env:MES_AGENT_READ_TIMEOUT_MS -ne '35000') { throw 'Bridge settings did not import.' }
    if ($env:MES_AGENT_SHARED_SECRET -ne 'test-only-32-character-secret-with=equals') { throw 'Quoted secret / equals parsing failed.' }
    if ([Environment]::GetEnvironmentVariable('MES_AGENT_IMPORT_SENTINEL', 'Process') -ne $sentinel) { throw 'An unapproved environment key was imported.' }
    if ([Environment]::GetEnvironmentVariable('JINA_API_KEY', 'Process') -ne $embeddingKey) { throw 'The Java importer must not import embedding credentials.' }
    foreach ($url in @('http://localhost:8001', 'http://LOCALHOST:8001/', 'http://[::1]:8001/', 'https://127.0.0.1:8001')) {
        [IO.File]::WriteAllText($probe, "MES_AGENT_BASE_URL=$url", $utf8)
        Import-AgentSettings $probe
        if ($env:MES_AGENT_BASE_URL -ne $url) { throw 'A valid loopback address was changed or rejected.' }
    }
    foreach ($bad in @('MES_AGENT_ENABLED=maybe', 'MES_AGENT_READ_TIMEOUT_MS=0', 'MES_AGENT_CONNECT_TIMEOUT_MS=60001', 'MES_AGENT_SHARED_SECRET=short', 'MES_AGENT_BASE_URL=http://169.254.169.254', 'MES_AGENT_BASE_URL=http://10.1.2.3:8001', 'MES_AGENT_BASE_URL=http://172.16.1.2:8001', 'MES_AGENT_BASE_URL=https://192.168.1.2:8001', 'MES_AGENT_BASE_URL=http://127.1:8001', 'MES_AGENT_BASE_URL=http://localhost.evil:8001')) {
        [IO.File]::WriteAllText($probe, $bad, $utf8)
        $rejected = $false
        try { Import-AgentSettings $probe } catch { $rejected = $true }
        if (-not $rejected) { throw 'An invalid agent setting was accepted.' }
    }
    Import-AgentSettings (Join-Path $runtime 'not-present.env')
    Write-Output 'Restricted Java agent settings import: PASS'
} finally {
    foreach ($key in $allowed) { [Environment]::SetEnvironmentVariable($key, $saved[$key], 'Process') }
    if (Test-Path -LiteralPath $probe) { Remove-Item -LiteralPath $probe }
}
