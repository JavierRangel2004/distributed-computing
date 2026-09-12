#!/usr/bin/env bash
# ==============================================================================
# Script maestro de Demostración del Clúster Distribuido Completo
# Requisitos del Profesor:
# - Al menos 2 Servidores conectados
# - Al menos 2 Clientes conectados
# - Middleware como Capa 2
#
# Uso:
#   ./run-demo-cluster.sh                  arranca el clúster en el puerto 5000
#   ./run-demo-cluster.sh --port 5050      arranca en otro puerto (macOS: 5000
#                                          lo ocupa el receptor de AirPlay)
#   ./run-demo-cluster.sh --reset          borra historiales previos y arranca
#   ./run-demo-cluster.sh --stop           detiene todos los procesos
# ==============================================================================
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

if [ -d "/opt/homebrew/opt/openjdk/bin" ]; then
    export PATH="/opt/homebrew/opt/openjdk/bin:$PATH"
fi

PID_FILE=".cluster_pids"
PORT=5000
RESET=0
STOP=0

while [ $# -gt 0 ]; do
    case "$1" in
        --port) PORT="$2"; shift 2 ;;
        --reset) RESET=1; shift ;;
        --stop|stop) STOP=1; shift ;;
        *) echo "[ERROR] Opción desconocida: $1"; exit 1 ;;
    esac
done

stop_cluster() {
    echo "[CLUSTER] Deteniendo procesos en segundo plano..."
    if [ -f "$PID_FILE" ]; then
        while read -r pid; do
            if kill -0 "$pid" 2>/dev/null; then
                echo "  Matando PID $pid..."
                kill "$pid" 2>/dev/null || true
            fi
        done < "$PID_FILE"
        rm -f "$PID_FILE"
    fi
    pkill -f "mx.edu.up.computing" 2>/dev/null || true
    echo "[CLUSTER] Todos los procesos han sido detenidos."
}

if [ "$STOP" -eq 1 ]; then
    stop_cluster
    exit 0
fi

# Asegurar compilación previa
JAR="target/distributed-computing-1.0.0-jar-with-dependencies.jar"
if [ ! -f "$JAR" ]; then
    echo "[BUILD] Compilando proyecto antes de iniciar clúster..."
    mvn clean package -DskipTests
fi

# El puerto debe estar libre: en macOS el 5000 lo toma "AirPlay Receiver"
if command -v lsof >/dev/null 2>&1 && lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "[ERROR] El puerto $PORT ya está ocupado por:"
    lsof -nP -iTCP:"$PORT" -sTCP:LISTEN | tail -n +2
    echo "        En macOS suele ser el receptor de AirPlay (ControlCenter):"
    echo "        desactívalo en Ajustes > General > AirDrop y Handoff,"
    echo "        o usa otro puerto: ./run-demo-cluster.sh --port 5050"
    exit 1
fi

if [ "$RESET" -eq 1 ]; then
    echo "[RESET] Borrando historiales de persistencia previos..."
    rm -f data/clients/*.json data/servers/*.json
fi

echo "=================================================================="
echo "    LANZAMIENTO DEL CLÚSTER DE CALCULADORA DISTRIBUIDA (3 CAPAS)  "
echo "    Puerto del Middleware: $PORT                                  "
echo "=================================================================="

# Limpiar procesos previos si existieran
stop_cluster >/dev/null 2>&1 || true

mkdir -p logs

# 1. Iniciar Middleware en segundo plano
echo "1. Iniciando Middleware en puerto $PORT..."
java -cp "$JAR" mx.edu.up.computing.middleware.MiddlewareMain --port "$PORT" > logs/middleware.log 2>&1 &
MID_PID=$!
echo $MID_PID > "$PID_FILE"
sleep 1

# 2. Iniciar Servidor 1 en segundo plano
echo "2. Conectando Servidor 1 (Server-1)..."
java -cp "$JAR" mx.edu.up.computing.server.ServerMain --id Server-1 --port "$PORT" > logs/server1.log 2>&1 &
S1_PID=$!
echo $S1_PID >> "$PID_FILE"
sleep 1

# 3. Iniciar Servidor 2 en segundo plano
echo "3. Conectando Servidor 2 (Server-2)..."
java -cp "$JAR" mx.edu.up.computing.server.ServerMain --id Server-2 --port "$PORT" > logs/server2.log 2>&1 &
S2_PID=$!
echo $S2_PID >> "$PID_FILE"
sleep 1

echo "=================================================================="
echo "Servidores y Middleware activos. Abriendo 2 Clientes Pesados (GUI)..."
echo "Para detener los servicios en segundo plano ejecute: ./run-demo-cluster.sh --stop"
echo "=================================================================="

# 4. Iniciar Cliente Pesado 1
java -cp "$JAR" mx.edu.up.computing.client.ClientMain --id Client-1 --port "$PORT" --autoconnect &
C1_PID=$!
echo $C1_PID >> "$PID_FILE"

# 5. Iniciar Cliente Pesado 2
java -cp "$JAR" mx.edu.up.computing.client.ClientMain --id Client-2 --port "$PORT" --autoconnect &
C2_PID=$!
echo $C2_PID >> "$PID_FILE"

echo "[OK] Clúster activo con 2 Servidores y 2 Clientes. Logs disponibles en logs/"
