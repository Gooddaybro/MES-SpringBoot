$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$runtime = Join-Path $repo '.local'
$pidFile = Join-Path $runtime 'app.pid'
if (Test-Path -LiteralPath $pidFile) {
    $appId = [int](Get-Content -LiteralPath $pidFile)
    $app = Get-CimInstance Win32_Process -Filter "ProcessId=$appId" -ErrorAction SilentlyContinue
    if ($app -and $app.CommandLine.Contains((Join-Path $repo 'mes\target\mes-1.0.0.jar'))) {
        Stop-Process -Id $appId
        Wait-Process -Id $appId -Timeout 15 -ErrorAction SilentlyContinue
    }
}
$redisCli = Join-Path $runtime 'redis\redis-cli.exe'
if (Test-Path -LiteralPath $redisCli) {
    $listener = Get-NetTCPConnection -LocalPort 6380 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listener) {
        $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
        if ($owner.ExecutablePath -eq (Join-Path $runtime 'redis\redis-server.exe')) {
            & $redisCli -h 127.0.0.1 -p 6380 shutdown
            if (Get-Process -Id $owner.ProcessId -ErrorAction SilentlyContinue) {
                Wait-Process -Id $owner.ProcessId -Timeout 30
            }
        }
    }
}
$mysqlAdmin = 'D:\Program Files\mysql-8.0.46-winx64\bin\mysqladmin.exe'
$rootConfig = Join-Path $runtime 'mysql-root.ini'
if (Test-Path -LiteralPath $rootConfig) {
    $mysqlPidFile = Join-Path $runtime 'mysql.pid'
    $mysqlId = $null
    if (Test-Path -LiteralPath $mysqlPidFile) { $mysqlId = [int](Get-Content -LiteralPath $mysqlPidFile) }
    if ($mysqlId -and (Get-Process -Id $mysqlId -ErrorAction SilentlyContinue)) {
        & $mysqlAdmin "--defaults-file=$rootConfig" shutdown
        if ($LASTEXITCODE -ne 0) { throw 'Could not stop the study MySQL instance.' }
        if (Get-Process -Id $mysqlId -ErrorAction SilentlyContinue) {
            Wait-Process -Id $mysqlId -Timeout 30
        }
    }
}
Write-Host 'Local study processes stopped; database files are preserved.'
