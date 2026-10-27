function Get-Java21Tools {
    $compiler = (Get-Command javac -ErrorAction Stop).Source
    $jdkBin = Split-Path $compiler
    $tools = [pscustomobject]@{
        Javac = $compiler
        Java = Join-Path $jdkBin 'java.exe'
        Jar = Join-Path $jdkBin 'jar.exe'
    }
    $compilerVersion = & $tools.Javac -version 2>&1
    if ($LASTEXITCODE -ne 0 -or "$compilerVersion" -notmatch '^javac 21(?:\.|\s|$)') {
        throw "JDK 21 required; found $compilerVersion. Set JAVA_HOME and put its bin directory first in PATH."
    }
    $runtimeVersion = & $tools.Java --version
    if ($LASTEXITCODE -ne 0 -or "$runtimeVersion" -notmatch '^(openjdk|java) 21(?:\.|\s|-)') {
        throw "JDK 21 runtime required beside javac; found $runtimeVersion."
    }
    if (-not (Test-Path -LiteralPath $tools.Jar)) { throw "JDK jar tool missing: $($tools.Jar)" }
    return $tools
}
