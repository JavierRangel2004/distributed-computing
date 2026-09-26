"""
discovery.py - Módulo de Autodescubrimiento P2P para ADS.
Escanea la subred local en paralelo para encontrar y conectar automáticamente
con otros nodos que tengan el puerto TCP abierto (8070) sin configuración manual.
"""

import socket
import time
import threading
from concurrent.futures import ThreadPoolExecutor
from logger import registrar_evento
import peers

auto_scan_activo = True
scan_en_progreso = False

def obtener_ips_locales():
    """Obtiene las IPs locales asignadas a esta máquina para no auto-escanearse."""
    ips = set()
    try:
        # Método 1: getaddrinfo sobre hostname
        nombre = socket.gethostname()
        for info in socket.getaddrinfo(nombre, None):
            ip = info[4][0]
            if not ip.startswith("127.") and ":" not in ip:
                ips.add(ip)
    except Exception:
        pass

    # Método 2: socket dummy para averiguar interfaz de salida
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ips.add(s.getsockname()[0])
        s.close()
    except Exception:
        pass

    return list(ips)

def detectar_prefijo_subred():
    """
    Detecta el prefijo de la subred local (ej. '192.168.8.').
    Prioriza el segmento del módem del aula ('192.168.8.') si está presente.
    """
    locales = obtener_ips_locales()
    for ip in locales:
        if ip.startswith("192.168.8."):
            return "192.168.8."
    for ip in locales:
        if ip.startswith("192.168.") or ip.startswith("172.") or ip.startswith("10."):
            partes = ip.split(".")
            return f"{partes[0]}.{partes[1]}.{partes[2]}."
    return "192.168.8."

def verificar_puerto(ip, puerto):
    """Prueba si un host tiene el puerto de nodo ADS abierto."""
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(0.25)
    try:
        s.connect((ip, int(puerto)))
        s.close()
        return ip
    except Exception:
        return None

def escanear_y_conectar_malla(mi_node_id, puerto_destino=8070, fn_conectar=None, verbose=True):
    """
    Escanea en paralelo todas las IPs de la subred y se conecta automáticamente
    a cualquier nodo que tenga el puerto abierto y aún no esté enlazado.
    """
    global scan_en_progreso
    if scan_en_progreso:
        return []
    scan_en_progreso = True

    prefijo = detectar_prefijo_subred()
    mis_ips = set(obtener_ips_locales())

    if verbose:
        print(f"\n[🔍 ESCANEO] Rastreando subred {prefijo}0/24 (puerto {puerto_destino})...")
    registrar_evento("SYS", f"Iniciando escaneo de autodescubrimiento en {prefijo}X:{puerto_destino}")

    candidatos = [f"{prefijo}{i}" for i in range(1, 255) if f"{prefijo}{i}" not in mis_ips]
    nodos_encontrados = []

    with ThreadPoolExecutor(max_workers=60) as executor:
        resultados = executor.map(lambda ip: verificar_puerto(ip, puerto_destino), candidatos)
        nodos_encontrados = [ip for ip in resultados if ip is not None]

    nuevos_conectados = 0
    for ip in nodos_encontrados:
        direccion = f"{ip}:{puerto_destino}"
        if direccion not in peers.conexiones_salientes:
            if fn_conectar:
                ok = fn_conectar(ip, puerto_destino, mi_node_id)
                if ok:
                    nuevos_conectados += 1
                    print(f"[✨ AUTODESCUBIERTO] Nuevo peer conectado automáticamente: {direccion}")
                    registrar_evento("SYS", f"Peer autodescubierto y enlazado: {direccion}")

    if verbose:
        print(f"[🔍 FIN ESCANEO] {len(nodos_encontrados)} nodos activos detectados ({nuevos_conectados} nuevos conectados).")
        print("ads> ", end="", flush=True)

    scan_en_progreso = False
    return nodos_encontrados

def hilo_autodescubrimiento_periodico(mi_node_id, puerto_destino, fn_conectar, intervalo_segundos=15):
    """
    Rastrea periódicamente en segundo plano la subred para incorporar automáticamente
    compañeros que vayan encendiendo su computadora durante la clase.
    """
    global auto_scan_activo
    while True:
        time.sleep(intervalo_segundos)
        if auto_scan_activo:
            escanear_y_conectar_malla(mi_node_id, puerto_destino, fn_conectar, verbose=False)
