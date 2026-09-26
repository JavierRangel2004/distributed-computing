#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "============================================================"
echo "🕸️ Iniciando NODO P2P DataField (EQUIPO_03)"
echo "   - TCP Port: 8070"
echo "   - HTTP Topology Port: 9070"
echo "============================================================"

PYTHON_BIN="python3"
if [ -x "/Users/javar/.local/bin/python3.13" ]; then
    PYTHON_BIN="/Users/javar/.local/bin/python3.13"
fi

exec "$PYTHON_BIN" src/main.py "$@"
