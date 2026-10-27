param(
    [Parameter(Mandatory=$true)][string]$MySqlExe,
    [Parameter(Mandatory=$true)][string]$ClientConfig
)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$mysqlPath = (Resolve-Path -LiteralPath $MySqlExe).Path
$configPath = (Resolve-Path -LiteralPath $ClientConfig).Path
$seedText = Get-Content -LiteralPath (Join-Path $repoRoot 'scripts/sql/servo_controller_sample.sql') -Raw
$tables = @('sp_materile','sp_flow','sp_oper','sp_flow_oper_relation')

# Always override any host/port/database in the supplied private client file.
function Invoke-LocalSql([string]$Sql) {
    $result = $Sql | & $mysqlPath "--defaults-extra-file=$configPath" --host=127.0.0.1 --port=3308 --database=mes_study --default-character-set=utf8mb4 --batch --raw --skip-column-names
    if ($LASTEXITCODE -ne 0) { throw 'Local sample SQL failed; review the MySQL diagnostic above.' }
    return ($result -join "`n")
}
function Snapshot([bool]$ExistingOnly) {
    $result = @{}
    foreach ($table in $tables) {
        $condition = if ($ExistingOnly) { " WHERE id NOT LIKE 'sample-servo-%'" } else { '' }
        $result[$table] = Invoke-LocalSql "SELECT * FROM $table$condition ORDER BY id;"
    }
    return $result
}
function Assert-Same($Before, $After) {
    foreach ($table in $tables) {
        if ($Before[$table] -cne $After[$table]) { throw "Business rows changed unexpectedly: $table" }
    }
}

$beforeAll = Snapshot $false
$withoutOptIn = Invoke-LocalSql $seedText
if (-not $withoutOptIn.Contains('NO_CHANGE')) { throw 'Missing explicit opt-in did not refuse the seed.' }
Assert-Same $beforeAll (Snapshot $false)
$beforeExisting = Snapshot $true
$approvedSql = "SET @mes_servo_sample_confirm='LOCAL_LEARNING_ONLY';`n" + $seedText
$first = Invoke-LocalSql $approvedSql
if (-not $first.Contains('READY:')) { throw 'First seed application refused; inspect namespace collisions.' }
Assert-Same $beforeExisting (Snapshot $true)
$firstSnapshot = Snapshot $false
$second = Invoke-LocalSql $approvedSql
if (-not $second.Contains('READY:')) { throw 'Idempotent application refused.' }
Assert-Same $firstSnapshot (Snapshot $false)

# Simulate a conflicting expected PCBA label in temporary seed data only.
$conflictingSql = $approvedSql.Replace('教学样例：控制板PCBA','教学样例：冲突PCBA')
$conflict = Invoke-LocalSql $conflictingSql
if (-not $conflict.Contains('NO_CHANGE')) { throw 'Conflicting seed was not refused.' }
Assert-Same $firstSnapshot (Snapshot $false)
$counts = Invoke-LocalSql "SELECT (SELECT COUNT(*) FROM sp_materile WHERE id LIKE 'sample-servo-%'), (SELECT COUNT(*) FROM sp_flow WHERE id LIKE 'sample-servo-%'), (SELECT COUNT(*) FROM sp_oper WHERE id LIKE 'sample-servo-%'), (SELECT COUNT(*) FROM sp_flow_oper_relation WHERE id LIKE 'sample-servo-%');"
if ($counts -ne "6`t1`t6`t6") { throw 'Unexpected servo sample counts.' }
Write-Output 'PASS: local-only sample seed, no opt-in=no change, 6/1/6/6 records, repeat identical, conflict refused, preexisting rows preserved.'
