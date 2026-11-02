param(
    [Parameter(Mandatory=$true)][string]$MySqlExe,
    [Parameter(Mandatory=$true)][string]$ClientConfig,
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9_-]{1,64}$')][string]$RoleId
)
$ErrorActionPreference='Stop'
$taskRepo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$taskMysql=(Resolve-Path -LiteralPath $MySqlExe).Path
$taskConfig=(Resolve-Path -LiteralPath $ClientConfig).Path
function Invoke-LocalSql([string]$Sql) {
    $taskResult=$Sql | & $taskMysql "--defaults-extra-file=$taskConfig" --host=127.0.0.1 --port=3308 --database=mes_study --default-character-set=utf8mb4 --batch --raw --skip-column-names
    if($LASTEXITCODE -ne 0) {throw 'R005 local SQL check failed.'}
    return ($taskResult -join "`n")
}
if((Invoke-LocalSql 'SELECT @@port,DATABASE();') -ne "3308`tmes_study") {throw 'Unexpected database target.'}
$taskLegacy=@('sp_materile','sp_flow','sp_oper','sp_flow_oper_relation')
function Snapshot-Legacy {
    $taskSnapshot=@{}
    foreach($taskTable in $taskLegacy) {$taskSnapshot[$taskTable]=Invoke-LocalSql "SELECT * FROM $taskTable ORDER BY id;"}
    return $taskSnapshot
}
function Assert-Legacy($Before) {
    $taskAfter=Snapshot-Legacy
    foreach($taskTable in $taskLegacy) {if($Before[$taskTable] -cne $taskAfter[$taskTable]) {throw "Legacy data changed: $taskTable"}}
}
$taskBefore=Snapshot-Legacy
$taskSchema=[IO.File]::ReadAllText((Join-Path $taskRepo 'scripts/sql/r005_work_order.sql'))
$taskPermissions=[IO.File]::ReadAllText((Join-Path $taskRepo 'scripts/sql/r005_multi_agent_permission.sql'))
$taskSeed=[IO.File]::ReadAllText((Join-Path $taskRepo 'scripts/sql/r005_work_order_sample.sql'))
Invoke-LocalSql $taskSchema | Out-Null
Invoke-LocalSql $taskSchema | Out-Null
$taskOrders=Invoke-LocalSql 'SELECT * FROM mes_work_order ORDER BY id;'
if(-not (Invoke-LocalSql $taskSeed).Contains('NO_CHANGE')) {throw 'Seed without explicit opt-in was not refused.'}
if($taskOrders -cne (Invoke-LocalSql 'SELECT * FROM mes_work_order ORDER BY id;')) {throw 'Unapproved seed modified orders.'}
if(-not (Invoke-LocalSql $taskPermissions).Contains('NO_CHANGE')) {throw 'Unselected role was granted permissions.'}
if(-not (Invoke-LocalSql ("SET @r005_role_id='$RoleId';`n"+$taskPermissions)).Contains('READY:')) {throw 'Selected local role was not granted.'}
$taskPermissionRows=Invoke-LocalSql "SELECT rm.id,rm.role_id,rm.menu_id FROM sp_sys_role_menu rm JOIN sp_sys_menu m ON m.id=rm.menu_id WHERE rm.role_id='$RoleId' AND m.permission LIKE 'mes:assistant:%' ORDER BY rm.id;"
Invoke-LocalSql ("SET @r005_role_id='$RoleId';`n"+$taskPermissions) | Out-Null
if($taskPermissionRows -cne (Invoke-LocalSql "SELECT rm.id,rm.role_id,rm.menu_id FROM sp_sys_role_menu rm JOIN sp_sys_menu m ON m.id=rm.menu_id WHERE rm.role_id='$RoleId' AND m.permission LIKE 'mes:assistant:%' ORDER BY rm.id;")) {throw 'Repeat grants duplicated role associations.'}
if(-not (Invoke-LocalSql ("SET @r005_sample_confirm='LOCAL_LEARNING_ONLY';`n"+$taskSeed)).Contains('READY:')) {throw 'Authorized teaching sample could not be established.'}
$taskSample=Invoke-LocalSql 'SELECT * FROM mes_work_order ORDER BY id;'
Invoke-LocalSql ("SET @r005_sample_confirm='LOCAL_LEARNING_ONLY';`n"+$taskSeed) | Out-Null
if($taskSample -cne (Invoke-LocalSql 'SELECT * FROM mes_work_order ORDER BY id;')) {throw 'Repeat sample changed an order or snapshot.'}
Assert-Legacy $taskBefore
Write-Output 'PASS: local schema and grants are idempotent; unapproved seed/role do nothing; sample state/snapshot and legacy rows preserved.'
