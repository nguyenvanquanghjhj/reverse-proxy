$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$targetRoot = Join-Path $repoRoot 'target'
$repoPrefix = $repoRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar

# Remove only known build outputs inside this checkout. Reject links before recursion.
if (Test-Path -LiteralPath $targetRoot) {
    $targetItem = Get-Item -LiteralPath $targetRoot -Force
    if ($targetItem.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw "Refusing to clean linked target directory: $targetRoot"
    }
}
foreach ($name in @('classes', 'test-classes', 'reverse-proxy-demo.jar')) {
    $outputPath = Join-Path $targetRoot $name
    if (Test-Path -LiteralPath $outputPath) {
        $resolvedPath = (Resolve-Path -LiteralPath $outputPath).ProviderPath
        if (-not $resolvedPath.StartsWith($repoPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Build output is outside repository: $resolvedPath"
        }
        $outputItem = Get-Item -LiteralPath $resolvedPath -Force
        if ($outputItem.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw "Refusing to clean linked build output: $resolvedPath"
        }
        Remove-Item -LiteralPath $resolvedPath -Recurse -Force
    }
}
$classesPath = Join-Path $targetRoot 'classes'
New-Item -ItemType Directory -Path $classesPath -Force | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot 'src/main/java') -Recurse -Filter '*.java' -File | Sort-Object FullName)
if ($sources.Count -eq 0) { throw 'No main Java sources found.' }
$sourceList = Join-Path $targetRoot 'main-sources.txt'
$sourceLines = @($sources | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' })
[IO.File]::WriteAllLines($sourceList, $sourceLines, [Text.UTF8Encoding]::new($false))
& javac --release 21 -encoding UTF-8 -d $classesPath "@$sourceList"
if ($LASTEXITCODE -ne 0) { throw "javac failed (exit $LASTEXITCODE). No runnable JAR was created." }
$jarPath = Join-Path $targetRoot 'reverse-proxy-demo.jar'
& jar --create --file $jarPath --main-class com.example.proxy.Main -C $classesPath .
if ($LASTEXITCODE -ne 0) { throw "jar failed (exit $LASTEXITCODE)." }
Write-Host "Built $jarPath (Java 21 bytecode)."
