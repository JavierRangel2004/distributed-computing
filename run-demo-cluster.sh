#!/usr/bin/env bash
# ==============================================================================
# Script maestro de Demostración del Clúster Distribuido Completo
# Requisitos del Profesor:
# - Al menos 2 Servidores conectados
# - Al menos 2 Clientes conectados
# - Middleware como Capa 2
# ==============================================================================
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

if [ -d "/opt/homebrew/opt/openjdk/bin" ]; then
    export PATH="/opt/homebrew/opt/openjdk/bin:$PATH"
fi

PID_FILE=".cluster_pids"

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

if [ "$1" == "--stop" ] || [ "$1" == "stop" ]; then
    stop_cluster
    exit 0
fi

# Asegurar compilación previa
JAR="target/distributed-computing-1.0.0-jar-with-dependencies.jar"
if [ ! -f "$JAR" ]; then
    echo "[BUILD] Compilando proyecto antes de iniciar clúster..."
    mvn clean package -DskipTests
fi

echo "=================================================================="
echo "    LANZAMIENTO DEL CLÚSTER DE CALCULADORA DISTRIBUIDA (3 CAPAS)  "
echo "=================================================================="

# Limpiar procesos previos si existieran
stop_cluster >/dev/null 2>&1 || true

mkdir -p logs

# 1. Iniciar Middleware en segundo plano
echo "1. Iniciando Middleware en puerto 5000..."
java -cp "$JAR" mx.edu.up.computing.middleware.MiddlewareMain --port 5000 > logs/middleware.log 2>&1 &
MID_PID=$!
echo $MID_PID > "$PID_FILE"
sleep 1

# 2. Iniciar Servidor 1 en segundo plano
echo "2. Conectando Servidor 1 (Server-1)..."
java -cp "$JAR" mx.edu.up.computing.server.ServerMain --id Server-1 --port 5000 > logs/server1.log 2>&1 &
S1_PID=$!
echo $S1_PID >> "$PID_FILE"
sleep 1

# 3. Iniciar Servidor 2 en segundo plano
echo "3. Conectando Servidor 2 (Server-2)..."
java -cp "$JAR" mx.edu.up.computing.server.ServerMain --id Server-2 --port 5000 > logs/server2.log 2>&1 &
S2_PID=$!
echo $S2_PID >> "$PID_FILE"
sleep 1

echo "=================================================================="
echo "Servidores y Middleware activos. Abriendo 2 Clientes Pesados (GUI)..."
echo "Para detener los servicios en segundo plano ejecute: ./run-demo-cluster.sh --stop"
echo "=================================================================="

# 4. Iniciar Cliente Pesado 1
java -cp "$JAR" mx.edu.up.computing.client.ClientMain --id Client-1 --port 5000 --autoconnect &
C1_PID=$!
echo $C1_PID >> "$PID_FILE"

# 5. Iniciar Cliente Pesado 2
java -cp "$JAR" mx.edu.up.computing.client.ClientMain --id Client-2 --port 5000 --autoconnect &
C2_PID=$!
echo $C2_PID >> "$PID_FILE"

echo "[OK] Clúster activo con 2 Servidores y 2 Clientes. Logs disponibles en logs/"
