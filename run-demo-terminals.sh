#!/usr/bin/env bash
# ==============================================================================
# Lanzador de la demo en 5 ventanas visibles de Terminal.app (macOS).
# Pensado para GRABAR el video: cada capa muestra su propio log en cámara,
# a diferencia de run-demo-cluster.sh, que manda los logs a logs/*.log.
#
# Uso:
#   ./run-demo-terminals.sh                arranca en el puerto 5050
#   ./run-demo-terminals.sh --port 5000    usa el puerto oficial (requiere
#                                          desactivar el receptor de AirPlay)
#   ./run-demo-terminals.sh --reset        borra historiales previos y arranca
#   ./run-demo-terminals.sh --stop         cierra todos los procesos del proyecto
# ==============================================================================
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

if [ -d "/opt/homebrew/opt/openjdk/bin" ]; then
    export PATH="/opt/homebrew/opt/openjdk/bin:$PATH"
fi

PORT=5050
RESET=0

while [ $# -gt 0 ]; do
    case "$1" in
        --port) PORT="$2"; shift 2 ;;
        --reset) RESET=1; shift ;;
        --stop|stop)
            pkill -f "mx.edu.up.computing" 2>/dev/null || true
            rm -f .cluster_pids
            echo "[CLUSTER] Procesos detenidos. Cierra las ventanas de Terminal a mano."
            exit 0 ;;
        *) echo "[ERROR] Opción desconocida: $1"; exit 1 ;;
    esac
done

JAR="target/distributed-computing-1.0.0-jar-with-dependencies.jar"
if [ ! -f "$JAR" ]; then
    echo "[BUILD] Generando el Fat JAR..."
    mvn clean package -DskipTests
fi

if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "[ERROR] El puerto $PORT está ocupado por:"
    lsof -nP -iTCP:"$PORT" -sTCP:LISTEN | tail -n +2
    echo "        Usa otro puerto: ./run-demo-terminals.sh --port 5060"
    exit 1
fi

if [ "$RESET" -eq 1 ]; then
    echo "[RESET] Borrando historiales de persistencia previos..."
    rm -f data/clients/*.json data/servers/*.json
fi

open_window() {
    local title="$1"
    local cmd="$2"
    osascript >/dev/null <<APPLESCRIPT
tell application "Terminal"
    activate
    do script "clear; cd '$DIR' && export PATH=\"/opt/homebrew/opt/openjdk/bin:\$PATH\" && $cmd"
    set custom title of front window to "$title"
end tell
APPLESCRIPT
}

JAVA_RUN="java -cp $JAR"

echo "=================================================================="
echo "    DEMO EN 5 TERMINALES — puerto $PORT"
echo "=================================================================="

echo "[1/5] Middleware (Capa 2)..."
open_window "1 · MIDDLEWARE" "$JAVA_RUN mx.edu.up.computing.middleware.MiddlewareMain --port $PORT"
sleep 2

echo "[2/5] Server-1 (Capa 3)..."
open_window "2 · SERVER-1" "$JAVA_RUN mx.edu.up.computing.server.ServerMain --id Server-1 --port $PORT"
sleep 1

echo "[3/5] Server-2 (Capa 3)..."
open_window "3 · SERVER-2" "$JAVA_RUN mx.edu.up.computing.server.ServerMain --id Server-2 --port $PORT"
sleep 1

echo "[4/5] Client-1 (Capa 1, GUI Swing)..."
open_window "4 · CLIENT-1" "$JAVA_RUN mx.edu.up.computing.client.ClientMain --id Client-1 --port $PORT --autoconnect"
sleep 2

echo "[5/5] Client-2 (Capa 1, GUI Swing)..."
open_window "5 · CLIENT-2" "$JAVA_RUN mx.edu.up.computing.client.ClientMain --id Client-2 --port $PORT --autoconnect"

echo ""
echo "[OK] Clúster en pantalla: 1 middleware + 2 servidores + 2 clientes pesados."
echo "     Para cerrar todo: ./run-demo-terminals.sh --stop"
