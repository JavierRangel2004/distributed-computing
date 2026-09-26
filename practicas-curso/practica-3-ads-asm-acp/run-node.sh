#!/bin/bash
# ==============================================================================
# Lanzador del Nodo ADS - Práctica 3 (ASM + ACP)
# ==============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "============================================================"
echo "🏢 Iniciando Nodo ADS - Práctica 3 (Subsistema ASM + ACP)"
echo "   Lógica: Aire Acondicionado Inteligente (Interés: TEMPERATURA)"
echo "============================================================"

MODEM_IP=$(ipconfig getifaddr en0 2>/dev/null || ifconfig | grep "inet 192.168.8" | awk '{print $2}' || true)
ALL_IPS=$(ifconfig | grep "inet " | grep -v 127.0.0.1 | awk '{print $2}' | tr '\n' ' ')

if [ -n "$MODEM_IP" ]; then
    echo "📡 IP Wi-Fi detectada (en0): $MODEM_IP"
fi
echo "🌐 IPs locales disponibles: $ALL_IPS"
echo "------------------------------------------------------------"
echo "💡 El nodo conectará automáticamente a la lista en conf/connections.json"
echo "   (incluyendo al profesor en 192.168.8.241:8070)"
echo "============================================================"

PYTHON_BIN="python3"
if [ -x "/Users/javar/.local/bin/python3.13" ]; then
    PYTHON_BIN="/Users/javar/.local/bin/python3.13"
fi

exec "$PYTHON_BIN" main.py "$@"
