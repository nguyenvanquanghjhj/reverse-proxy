param(
    [Parameter(Position=0)][ValidateSet('configure','preflight','start','status','stop','help')][string]$Action='help',
    [Parameter(ValueFromRemainingArguments=$true)][string[]]$Options
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if ($Action -notin @('configure','help')) {
    . (Join-Path $root 'scripts/java21.ps1')
    $jdk=Get-Java21Tools
    $env:PATH=(Split-Path $jdk.Java)+';'+$env:PATH
}
python (Join-Path $PSScriptRoot 'windows_nodes.py') $Action @Options
exit $LASTEXITCODE
