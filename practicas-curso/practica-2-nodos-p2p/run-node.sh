#!/bin/bash
# ==============================================================================
# Lanzador del Nodo P2P - Práctica 2 (Cómputo Distribuido / Malla ADS)
# ==============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "============================================================"
echo "🚀 Iniciando Nodo P2P para Práctica 2 (Malla ADS)"
echo "============================================================"

# Detectar IP en la red del módem del profesor (subred 192.168.8.x) o interfaces activas
MODEM_IP=$(ipconfig getifaddr en0 2>/dev/null || ifconfig | grep "inet 192.168.8" | awk '{print $2}' || true)
ALL_IPS=$(ifconfig | grep "inet " | grep -v 127.0.0.1 | awk '{print $2}' | tr '\n' ' ')

if [ -n "$MODEM_IP" ]; then
    echo "📡 IP detectada en interfaz Wi-Fi (en0): $MODEM_IP"
fi
echo "🌐 Todas las IPs locales disponibles: $ALL_IPS"
echo "------------------------------------------------------------"
echo "💡 Cuando te conectes al módem del profesor (GLSPT 1200):"
echo "   - Tu IP será algo como 192.168.8.X"
echo "   - El nodo del profesor suele estar en 192.168.8.1:8070"
echo "   - Para conectar con otro equipo usa: /conectar 192.168.8.Y:8070"
echo "============================================================"

# Usar python3 disponible (preferir python3.13 si está instalado, o python3 del sistema)
PYTHON_BIN="python3"
if [ -x "/Users/javar/.local/bin/python3.13" ]; then
    PYTHON_BIN="/Users/javar/.local/bin/python3.13"
fi

exec "$PYTHON_BIN" main.py "$@"
