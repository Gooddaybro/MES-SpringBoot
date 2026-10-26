$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$scriptPath = Join-Path $repo 'scripts\local\Start-Local.ps1'
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$null, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Start-Local.ps1 has syntax errors.' }
$assignment = $ast.FindAll({
    param($item)
    $item -is [System.Management.Automation.Language.AssignmentStatementAst] -and $item.Left.Extent.Text -eq '$arguments'
}, $true) | Select-Object -First 1
if (-not $assignment) { throw 'The Java argument construction was not found.' }

# Execute the actual argument construction with a checkout path containing spaces.
$jar = 'C:\Users\Test User\MES Project\mes\target\mes-1.0.0.jar'
$appConfig = 'C:\Users\Test User\MES Project\application-local.yml'
$logConfig = 'C:\Users\Test User\MES Project\logback-local.xml'
$logConfigUri = ([System.Uri]$logConfig).AbsoluteUri
$arguments = & ([scriptblock]::Create($assignment.Right.Extent.Text))
$runtime = Join-Path $repo '.local\tests'
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$probe = Join-Path $runtime 'startup-arguments.cjs'
[IO.File]::WriteAllText($probe, @'
const args = process.argv.slice(2);
const expected = [
  '--spring.config.additional-location=file:C:/Users/Test User/MES Project/application-local.yml',
  '--logging.config=file:C:/Users/Test User/MES Project/logback-local.xml',
  String.raw`C:\Users\Test User\MES Project\mes\target\mes-1.0.0.jar`
];
if (!expected.every(value => args.includes(value))) process.exit(19);
'@, (New-Object System.Text.UTF8Encoding($false)))
$nodePath = (Get-Command node).Source
$probeArguments = @('"' + $probe + '"') + $arguments
$process = Start-Process -FilePath $nodePath -ArgumentList $probeArguments -WindowStyle Hidden -PassThru -Wait
if ($process.ExitCode -ne 0) { throw "Java arguments split when the checkout path contains spaces (probe $($process.ExitCode))." }
Write-Output 'Startup paths containing spaces: PASS'
