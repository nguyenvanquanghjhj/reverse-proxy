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
foreach ($test in $tests) {
    $relative = $test.FullName.Substring($testRoot.Length + 1)
    $className = $relative.Substring(0, $relative.Length - 5).Replace('\', '.').Replace('/', '.')
    Write-Host "Running $className"
    & java -ea -cp "$mainClasses$([IO.Path]::PathSeparator)$testClasses" $className
    if ($LASTEXITCODE -ne 0) { throw "$className failed (exit $LASTEXITCODE)." }
}
Write-Host "Passed $($tests.Count) test mains."
