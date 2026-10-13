# Lanzador minimo de DemoMain (mx.edu.up.computing.ads). Solo foreground.
[CmdletBinding()]
param(
    [int]$Nodes = 4,
    [int]$BasePort = 6101,
    [int]$BoundedMs = 5000,
    [switch]$Gui
)

Set-Location -LiteralPath $PSScriptRoot
$Jar = Join-Path $PSScriptRoot "target\distributed-computing-1.0.0-jar-with-dependencies.jar"

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw "Java no esta en el PATH. Instala JDK 21+ (Temurin) desde https://adoptium.net/"
}
& java -version

if (-not (Test-Path -LiteralPath $Jar)) {
    throw "No se encontro $Jar. Ejecuta 'mvn package' primero."
}

$jvmArgs = @('-cp', $Jar, 'mx.edu.up.computing.ads.DemoMain',
            '--base', $BasePort, '--nodes', $Nodes, '--bounded-ms', $BoundedMs)
if ($Gui) { $jvmArgs += '--gui' }

& java @jvmArgs
exit $LASTEXITCODE
