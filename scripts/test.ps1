$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
& (Join-Path $PSScriptRoot 'build.ps1')
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$testRoot = Join-Path $repoRoot 'src/test/java'
$testClasses = Join-Path $repoRoot 'target/test-classes'
$mainClasses = Join-Path $repoRoot 'target/classes'
New-Item -ItemType Directory -Path $testClasses -Force | Out-Null
$sources = @(Get-ChildItem -LiteralPath $testRoot -Recurse -Filter '*.java' -File | Sort-Object FullName)
$tests = @($sources | Where-Object { $_.Name -like '*Test.java' })
if ($tests.Count -eq 0) { throw 'No *Test.java test mains found.' }
$sourceList = Join-Path $repoRoot 'target/test-sources.txt'
$sourceLines = @($sources | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' })
[IO.File]::WriteAllLines($sourceList, $sourceLines, [Text.UTF8Encoding]::new($false))
& javac --release 21 -encoding UTF-8 -cp $mainClasses -d $testClasses "@$sourceList"
if ($LASTEXITCODE -ne 0) { throw "Test compilation failed (exit $LASTEXITCODE)." }
Push-Location $repoRoot
try {
    & java -ea -cp "$mainClasses$([IO.Path]::PathSeparator)$testClasses" com.example.proxy.TestSuite
    if ($LASTEXITCODE -ne 0) { throw "Java test suite failed (exit $LASTEXITCODE)." }
} finally { Pop-Location }
