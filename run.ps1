param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('server', 'client')]
    [string]$Mode
)

$ErrorActionPreference = 'Stop'
$maven = (Get-Command mvn.cmd -ErrorAction SilentlyContinue).Source
if (-not $maven) {
    $maven = 'C:\Program Files\JetBrains\IntelliJ IDEA 2024.3.7\plugins\maven\lib\maven3\bin\mvn.cmd'
}
if (-not (Test-Path -LiteralPath $maven)) { throw 'Cần cài Maven 3.9+ hoặc mở dự án bằng IntelliJ IDEA.' }

if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
    $jbr = 'C:\Program Files\JetBrains\IntelliJ IDEA 2024.3.7\jbr'
    if (-not (Test-Path -LiteralPath (Join-Path $jbr 'bin\javac.exe'))) { throw 'Cần JDK 21 (đặt JAVA_HOME).' }
    $env:JAVA_HOME = $jbr
}

Push-Location $PSScriptRoot
try {
    $repo = "-Dmaven.repo.local=$PSScriptRoot\.m2"
    if ($Mode -eq 'server') {
        & $maven $repo compile exec:java '-Dexec.mainClass=vn.lab.mail.ServerApp'
    } else {
        & $maven $repo javafx:run
    }
} finally {
    Pop-Location
}
