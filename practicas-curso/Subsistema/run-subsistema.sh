#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "============================================================"
echo "🏢 Iniciando SUBSISTEMA ADS (AIRE_EQUIPO_03)"
echo "   - Conectando a Nodo local: 127.0.0.1:8070"
echo "   - Rol: APP (Aire Acondicionado Inteligente)"
echo "============================================================"

PYTHON_BIN="python3"
if [ -x "/Users/javar/.local/bin/python3.13" ]; then
    PYTHON_BIN="/Users/javar/.local/bin/python3.13"
fi

exec "$PYTHON_BIN" src/main.py "$@"
