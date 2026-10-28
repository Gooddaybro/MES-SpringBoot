param(
    [string]$JdkHome = 'D:\Program Files\Java\jdk1.8.0_202',
    [string]$MySqlHome = 'D:\Program Files\mysql-8.0.46-winx64',
    [switch]$Rebuild
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$runtime = Join-Path $repo '.local'
$utf8 = New-Object System.Text.UTF8Encoding($false)
New-Item -ItemType Directory -Path $runtime -Force | Out-Null

function Write-Utf8([string]$Path, [string]$Content) {
    [System.IO.File]::WriteAllText($Path, $Content, $utf8)
}
function Import-AgentSettings([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $allowed = @('MES_AGENT_ENABLED', 'MES_AGENT_BASE_URL', 'MES_AGENT_SHARED_SECRET', 'MES_AGENT_CONNECT_TIMEOUT_MS', 'MES_AGENT_READ_TIMEOUT_MS')
    $settings = @{}
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        if ($line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') { continue }
        $name = $Matches[1]
        if ($allowed -cnotcontains $name) { continue }
        $value = $Matches[2].Trim()
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        if ($value -match '[\x00-\x1f\x7f]') { throw "Invalid control character in $name." }
        switch ($name) {
            'MES_AGENT_ENABLED' {
                if ($value -cnotin @('true', 'false')) { throw 'MES_AGENT_ENABLED must be true or false.' }
            }
            'MES_AGENT_SHARED_SECRET' {
                if ($value.Length -lt 32 -or $value.Length -gt 1024 -or [string]::IsNullOrWhiteSpace($value)) { throw 'MES_AGENT_SHARED_SECRET must contain 32 to 1024 characters.' }
            }
            'MES_AGENT_BASE_URL' {
                $uri = $null
                if (-not [Uri]::TryCreate($value, [UriKind]::Absolute, [ref]$uri) -or $uri.Scheme -notin @('http', 'https') -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or $uri.AbsolutePath -ne '/' -or $uri.Port -eq 0) {
                    throw 'MES_AGENT_BASE_URL must be a loopback HTTP root URL.'
                }
                if ($value -notmatch '^https?://(?:localhost|127\.0\.0\.1|\[::1\])(?::[0-9]+)?/?$') { throw 'MES_AGENT_BASE_URL must use localhost, 127.0.0.1 or ::1.' }
            }
            default {
                $milliseconds = 0
                if (-not [int]::TryParse($value, [ref]$milliseconds) -or $milliseconds -lt 1 -or $milliseconds -gt 60000) { throw "$name must be between 1 and 60000 milliseconds." }
            }
        }
        $settings[$name] = $value
    }
    foreach ($name in $settings.Keys) { [Environment]::SetEnvironmentVariable($name, $settings[$name], 'Process') }
}
function Test-LocalPort([int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $pending = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $pending.AsyncWaitHandle.WaitOne(200)) { return $false }
        $client.EndConnect($pending)
        return $true
    } catch { return $false } finally { $client.Dispose() }
}
function Wait-LocalPort([int]$Port) {
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        if (Test-LocalPort $Port) { return }
        Start-Sleep -Seconds 1
    }
    throw "Port $Port did not become ready. Inspect $runtime logs."
}
function Assert-OwnedPort([int]$Port, [string]$CommandFragment) {
    if (-not (Test-LocalPort $Port)) { return $false }
    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen | Select-Object -First 1
    $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    if (-not $owner.CommandLine.Contains($CommandFragment)) {
        throw "Port $Port is occupied by another application; it was left untouched."
    }
    return $true
}

$java = Join-Path $JdkHome 'bin\java.exe'
$mysqld = Join-Path $MySqlHome 'bin\mysqld.exe'
$mysql = Join-Path $MySqlHome 'bin\mysql.exe'
foreach ($required in @($java, $mysqld, $mysql)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Required program missing: $required" }
}
$env:JAVA_HOME = $JdkHome
$env:Path = (Join-Path $JdkHome 'bin') + ';' + $env:Path

$secretFile = Join-Path $runtime 'settings.json'
if (-not (Test-Path -LiteralPath $secretFile)) {
    Write-Utf8 $secretFile ((@{
        rootPassword = [guid]::NewGuid().ToString('N')
        appPassword = [guid]::NewGuid().ToString('N')
    }) | ConvertTo-Json)
}
$secrets = Get-Content -LiteralPath $secretFile -Raw | ConvertFrom-Json
$env:MES_DB_PASSWORD = $secrets.appPassword
Import-AgentSettings (Join-Path $runtime 'agent.env')

$mysqlConfig = Join-Path $runtime 'mysql.ini'
$mysqlData = Join-Path $runtime 'mysql-data'
$sqlRuntime = $runtime.Replace('\', '/')
$sqlHome = $MySqlHome.Replace('\', '/')
Write-Utf8 $mysqlConfig @"
[mysqld]
basedir="$sqlHome"
datadir="$sqlRuntime/mysql-data"
port=3308
bind-address=127.0.0.1
mysqlx=0
character-set-server=utf8mb4
collation-server=utf8mb4_0900_ai_ci
innodb_buffer_pool_size=64M
max_connections=30
log-error="$sqlRuntime/mysql.log"
pid-file="$sqlRuntime/mysql.pid"
"@

if (-not (Test-Path -LiteralPath (Join-Path $mysqlData 'mysql'))) {
    Write-Host 'Initializing a separate MySQL study instance...'
    & $mysqld --no-defaults --initialize-insecure "--basedir=$MySqlHome" "--datadir=$mysqlData" --console
    if ($LASTEXITCODE -ne 0) { throw 'MySQL initialization failed.' }
}
if (-not (Assert-OwnedPort 3308 $mysqlConfig)) {
    Start-Process -FilePath $mysqld -ArgumentList "--defaults-file=`"$mysqlConfig`"" -WindowStyle Hidden
    Wait-LocalPort 3308
}

$rootClient = Join-Path $runtime 'mysql-root.ini'
$appClient = Join-Path $runtime 'mysql-client.ini'
Write-Utf8 $rootClient "[client]`nuser=root`npassword=$($secrets.rootPassword)`nhost=127.0.0.1`nport=3308`nprotocol=tcp`ndefault-character-set=utf8mb4`n"
Write-Utf8 $appClient "[client]`nuser=mes_study`npassword=$($secrets.appPassword)`nhost=127.0.0.1`nport=3308`nprotocol=tcp`ndefault-character-set=utf8mb4`n"
if (-not (Test-Path -LiteralPath (Join-Path $runtime 'database-ready'))) {
    Write-Host 'Importing the original schema into the new mes_study database...'
    $dumpPath = (Join-Path $repo 'scripts\sql\MySQL-sparchetype_20200330.sql').Replace('\', '/')
    $bootstrap = Join-Path $runtime 'bootstrap.sql'
    Write-Utf8 $bootstrap @"
ALTER USER 'root'@'localhost' IDENTIFIED BY '$($secrets.rootPassword)';
CREATE DATABASE IF NOT EXISTS mes_study CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'mes_study'@'127.0.0.1' IDENTIFIED BY '$($secrets.appPassword)';
GRANT ALL PRIVILEGES ON mes_study.* TO 'mes_study'@'127.0.0.1';
USE mes_study;
SOURCE $dumpPath;
"@
    & $mysql --no-defaults -h 127.0.0.1 -P 3308 -u root --default-character-set=utf8mb4 --execute "source $($bootstrap.Replace('\','/'))"
    if ($LASTEXITCODE -ne 0) { throw 'Database import failed; inspect bootstrap.sql before retrying.' }
    Write-Utf8 (Join-Path $runtime 'database-ready') 'Original March 30 schema imported.'
}

$redisDir = Join-Path $runtime 'redis'
$redisServer = Join-Path $redisDir 'redis-server.exe'
if (-not (Test-Path -LiteralPath $redisServer)) {
    Write-Host 'Downloading the Redis Windows portable development build...'
    $zip = Join-Path $runtime 'redis.zip'
    & curl.exe --fail --location --retry 2 --connect-timeout 15 --max-time 180 --silent --show-error --output $zip 'https://github.com/tporadowski/redis/releases/download/v5.0.14.1/Redis-x64-5.0.14.1.zip'
    if ($LASTEXITCODE -ne 0) { throw 'Redis download failed.' }
    Expand-Archive -LiteralPath $zip -DestinationPath $redisDir -Force
}
$redisConfig = Join-Path $runtime 'redis.conf'
Write-Utf8 $redisConfig "bind 127.0.0.1`nprotected-mode yes`nport 6380`ndir `"$sqlRuntime/redis`"`nsave `"`"`nappendonly no`nlogfile `"$sqlRuntime/redis.log`"`n"
if (-not (Assert-OwnedPort 6380 $redisConfig)) {
    Start-Process -FilePath $redisServer -ArgumentList "`"$redisConfig`"" -WorkingDirectory $redisDir -WindowStyle Hidden
    Wait-LocalPort 6380
}

$jar = Join-Path $repo 'mes\target\mes-1.0.0.jar'
if ($Rebuild -and (Assert-OwnedPort 9090 $jar)) {
    $listener = Get-NetTCPConnection -LocalPort 9090 -State Listen | Select-Object -First 1
    Stop-Process -Id $listener.OwningProcess
    Wait-Process -Id $listener.OwningProcess -Timeout 15 -ErrorAction SilentlyContinue
}
if ($Rebuild -or -not (Test-Path -LiteralPath $jar)) {
    Write-Host 'Building with JDK 8 and Maven...'
    & mvn -f (Join-Path $repo 'mes\pom.xml') -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' }
}
if (-not (Assert-OwnedPort 9090 $jar)) {
    $logConfig = Join-Path $PSScriptRoot 'logback-local.xml'
    $logConfigUri = ([System.Uri]$logConfig).AbsoluteUri
    $appConfig = Join-Path $PSScriptRoot 'application-local.yml'
    $arguments = @(
        "-Dlogback.configurationFile=$logConfigUri",
        '-Dfile.encoding=UTF-8', '-Xms128m', '-Xmx384m',
        '-jar', "`"$jar`"",
        '--spring.profiles.active=local',
        "`"--spring.config.additional-location=file:$($appConfig.Replace('\','/'))`"",
        "`"--logging.config=file:$($logConfig.Replace('\','/'))`""
    )
    $app = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $repo -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime 'app.log') -RedirectStandardError (Join-Path $runtime 'app-error.log')
    Write-Utf8 (Join-Path $runtime 'app.pid') ([string]$app.Id)
}
Wait-LocalPort 9090
Write-Host 'Ready: http://127.0.0.1:9090/login-ui'
Write-Host 'Study login: admin / 123 (original sample account, localhost only)'
