# ==============================================================================
# Lanzador del clúster de la Calculadora Distribuida (3 capas) en Windows.
# Abre 5 ventanas de PowerShell, una por proceso, para que el video muestre
# los logs de cada capa por separado.
#
# Uso:
#   .\run-demo-cluster.ps1                 arranca middleware + 2 servers + 2 clients
#   .\run-demo-cluster.ps1 -Port 5050      usa otro puerto
#   .\run-demo-cluster.ps1 -Reset          borra historiales previos y arranca
#   .\run-demo-cluster.ps1 -Stop           cierra todos los procesos Java del proyecto
#
# Si PowerShell bloquea la ejecución de scripts:
#   Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
# ==============================================================================
param(
    [int]$Port = 5000,
    [switch]$Reset,
    [switch]$Stop
)

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
Set-Location $Root
$Jar = Join-Path $Root "target\distributed-computing-1.0.0-jar-with-dependencies.jar"

function Stop-Cluster {
    $procs = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
        Where-Object { $_.CommandLine -like "*mx.edu.up.computing*" }
    if (-not $procs) {
        Write-Host "[CLUSTER] No hay procesos del proyecto en ejecucion."
        return
    }
    foreach ($p in $procs) {
        Write-Host ("  Cerrando PID {0}..." -f $p.ProcessId)
        Stop-Process -Id $p.ProcessId -Force
    }
    Write-Host "[CLUSTER] Todos los procesos han sido detenidos."
}

if ($Stop) { Stop-Cluster; exit 0 }

# --- 1. Toolchain --------------------------------------------------------------
Write-Host "[CHECK] Verificando toolchain..."
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw "Java no esta en el PATH. Instala Temurin 21+ desde https://adoptium.net/"
}
java -version
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw "Maven no esta en el PATH. Instala con 'choco install maven -y' o descarga el zip y agrega su carpeta bin al PATH."
}
mvn -version | Select-Object -First 1

# --- 2. Build ------------------------------------------------------------------
if (-not (Test-Path $Jar)) {
    Write-Host "[BUILD] Generando el Fat JAR (mvn clean package -DskipTests)..."
    mvn clean package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "La compilacion fallo." }
}
Write-Host ("[OK] Artefacto: {0}" -f $Jar)

# --- 3. Puerto libre -----------------------------------------------------------
$busy = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($busy) {
    $owner = (Get-Process -Id $busy[0].OwningProcess).ProcessName
    throw ("El puerto {0} esta ocupado por '{1}'. Cierra ese proceso o usa: .\run-demo-cluster.ps1 -Port 5050" -f $Port, $owner)
}

# --- 4. Reset de persistencia --------------------------------------------------
if ($Reset) {
    Write-Host "[RESET] Borrando historiales de persistencia previos..."
    New-Item -ItemType Directory -Force -Path "data\clients", "data\servers" | Out-Null
    Remove-Item "data\clients\*.json", "data\servers\*.json" -ErrorAction SilentlyContinue
}

# --- 5. Lanzamiento en 5 ventanas ---------------------------------------------
function Start-Window {
    param([string]$Title, [string]$JavaArgs)
    $inner = "`$Host.UI.RawUI.WindowTitle='$Title'; cd '$Root'; java -cp '$Jar' $JavaArgs"
    Start-Process powershell -ArgumentList "-NoExit", "-Command", $inner
}

Write-Host ("[1/5] Middleware (Capa 2) en el puerto {0}..." -f $Port)
Start-Window "1 - MIDDLEWARE" "mx.edu.up.computing.middleware.MiddlewareMain --port $Port"
Start-Sleep -Seconds 2

Write-Host "[2/5] Server-1 (Capa 3)..."
Start-Window "2 - SERVER-1" "mx.edu.up.computing.server.ServerMain --id Server-1 --port $Port"
Start-Sleep -Seconds 1

Write-Host "[3/5] Server-2 (Capa 3)..."
Start-Window "3 - SERVER-2" "mx.edu.up.computing.server.ServerMain --id Server-2 --port $Port"
Start-Sleep -Seconds 1

Write-Host "[4/5] Client-1 (Capa 1, GUI Swing)..."
Start-Window "4 - CLIENT-1" "mx.edu.up.computing.client.ClientMain --id Client-1 --port $Port --autoconnect"
Start-Sleep -Seconds 2

Write-Host "[5/5] Client-2 (Capa 1, GUI Swing)..."
Start-Window "5 - CLIENT-2" "mx.edu.up.computing.client.ClientMain --id Client-2 --port $Port --autoconnect"

Write-Host ""
Write-Host "[OK] Clúster activo: 1 middleware + 2 servidores + 2 clientes pesados."
Write-Host "     Para cerrar todo: .\run-demo-cluster.ps1 -Stop"
