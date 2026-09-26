"""
main.py - Ejecutable principal de la Práctica 3 (ADS: ASM + ACP).
Reutiliza la malla P2P para comunicación por contenido (Data Field).
"""

import os
import sys
import json
import time
import threading
import argparse

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

from content_codes import CATALOGO_OFICIAL, TEMPERATURA, ESTADO_AIRE
from asm import AireAcondicionadoASM
from acp import ACP
from server import iniciar_servidor, set_acp
from client import conectar_a_peer, enviar_mensaje_a_todos, conexiones_salientes
from logger import registrar_evento
import peers
import random
from discovery import (
    escanear_y_conectar_malla,
    hilo_autodescubrimiento_periodico,
    detectar_prefijo_subred,
)
import discovery

CONFIG_FILE = os.path.join("conf", "config.json")
CONNECTIONS_FILE = os.path.join("conf", "connections.json")

def cargar_configuracion():
    if not os.path.exists("conf"):
        os.makedirs("conf", exist_ok=True)

    if not os.path.exists(CONFIG_FILE):
        cfg = {
            "node_id": "EQUIPO_03",
            "host": "0.0.0.0",
            "port": 8070,
            "umbral_temperatura": 28.0,
            "intereses": ["TEMPERATURA"]
        }
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=4)
        return cfg

    try:
        with open(CONFIG_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:
        print(f"[!] Error leyendo {CONFIG_FILE}: {e}")
        return None

def cargar_conexiones():
    if not os.path.exists(CONNECTIONS_FILE):
        peers_default = [
            "192.168.8.241:8070",
            "192.168.8.167:8070",
            "192.168.8.187:8070",
            "192.168.8.216:8070",
            "192.168.8.239:8070",
            "192.168.8.246:8070"
        ]
        with open(CONNECTIONS_FILE, "w", encoding="utf-8") as f:
            json.dump(peers_default, f, indent=4)
        return peers_default

    try:
        with open(CONNECTIONS_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:
        print(f"[!] Error leyendo {CONNECTIONS_FILE}: {e}")
        return []

def imprimir_ayuda():
    print("""
================ Comandos ADS (Práctica 3) ================
  /publicar <CODIGO> <VALOR>   -> Publica un Content Message a la malla (ej. /publicar TEMPERATURA 31.5)
  /sensor [on|off] [segundos]  -> Activa/desactiva la simulación de sensor periódico automático
  /estado                      -> Muestra el estado interno del ASM (ON/OFF, umbrales)
  /stats                       -> Muestra métricas de paquetes del ACP (RX, USE, IGNORE, TX, FWD, DROP)
  /bitacora [N]                -> Despliega las últimas N líneas del log de bitácora
  /intereses                   -> Muestra los Content Codes que el ACP filtra para el ASM
  /intereses +<CODIGO>         -> Agrega un nuevo interés (ej. /intereses +HUMEDAD)
  /intereses -<CODIGO>         -> Remueve un interés (ej. /intereses -TEMPERATURA)
  /umbral <VALOR>              -> Modifica el umbral de disparo del Aire Acondicionado
  /peers                       -> Lista conexiones activas con otros nodos
  /descubrir | /escanear       -> Escanea la subred y se conecta automáticamente a todos los peers activos
  /autodescubrir [on|off]      -> Activa/desactiva el escaneo periódico automático en segundo plano
  /conectar IP:PUERTO          -> Conecta manualmente con otro nodo
  /catalogo                    -> Lista todos los Content Codes oficiales de la práctica
  /ayuda                       -> Muestra esta ayuda
  salir | exit | quit          -> Cierra el nodo y finaliza
===========================================================
""")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Nodo ADS (ASM + ACP) - Práctica 3")
    parser.add_argument("--port", type=int, help="Puerto local TCP (ej. 8070)")
    parser.add_argument("--id", type=str, help="Identificador del nodo (ej. EQUIPO_03)")
    parser.add_argument("--umbral", type=float, help="Umbral de temperatura para el aire")
    args = parser.parse_args()

    print("============================================================")
    print("🏢 Nodo ADS — Práctica 3: Subsistema ASM + ACP")
    print("   'ASM piensa y actúa; ACP comunica y filtra'")
    print("============================================================")

    config = cargar_configuracion() or {}
    node_id = args.id or config.get("node_id", "EQUIPO_03")
    host = config.get("host", "0.0.0.0")
    port = args.port or config.get("port", 8070)
    umbral = args.umbral or config.get("umbral_temperatura", 28.0)
    intereses_cfg = config.get("intereses", [TEMPERATURA])

    # 1. Instanciar ASM y ACP
    asm = AireAcondicionadoASM(umbral_temperatura=umbral)
    acp = ACP(node_id=node_id, asm_instance=asm, intereses_iniciales=intereses_cfg)
    acp.set_emisor(enviar_mensaje_a_todos)
    set_acp(acp)

    print(f"[*] Nodo ID: {node_id}")
    print(f"[*] Subsistema: {asm.obtener_estado()['subsistema']}")
    print(f"[*] Umbral de activación: {umbral}°C")
    print(f"[*] Intereses ACP: {list(acp.intereses)}")
    print(f"[*] Escuchando en: {host}:{port}")
    print("============================================================\n")

    registrar_evento("SYS", f"NODO INICIADO -> {node_id} | Escuchando en {host}:{port}")

    # 2. Iniciar servidor TCP en hilo secundario
    hilo_server = threading.Thread(target=iniciar_servidor, args=(host, port), daemon=True)
    hilo_server.start()

    # 3. Conectar a peers iniciales
    peers_lista = cargar_conexiones()
    time.sleep(0.5)
    for p in peers_lista:
        try:
            p_ip, p_puerto = p.split(":")
            conectar_a_peer(p_ip, p_puerto, node_id)
        except Exception:
            pass

    # 4. Lanzar hilo de autodescubrimiento periódico en segundo plano (cada 15s)
    hilo_discovery = threading.Thread(
        target=hilo_autodescubrimiento_periodico,
        args=(node_id, 8070, conectar_a_peer, 15),
        daemon=True,
    )
    hilo_discovery.start()

    # Escaneo inicial rápido en segundo plano
    threading.Thread(
        target=escanear_y_conectar_malla,
        args=(node_id, 8070, conectar_a_peer, False),
        daemon=True,
    ).start()

    # 5. Lanzar hilo de simulación de sensor automático (apagado por defecto)
    sensor_activo = False
    sensor_intervalo = 5.0
    sensor_codigo = TEMPERATURA

    def hilo_sensor():
        while True:
            time.sleep(sensor_intervalo)
            if sensor_activo:
                if sensor_codigo == TEMPERATURA:
                    val = round(random.uniform(24.0, 32.5), 1)
                elif sensor_codigo == "HUMEDAD":
                    val = round(random.uniform(40.0, 85.0), 1)
                elif sensor_codigo == "PRESENCIA":
                    val = random.choice(["PRESENTE", "AUSENTE"])
                elif sensor_codigo == "CO2":
                    val = random.randint(350, 900)
                else:
                    val = random.randint(10, 100)
                print(f"\n[📡 SENSOR AUTO] Lectura simulada generada: {sensor_codigo} = {val}")
                acp.publicar_contenido(sensor_codigo, val)
                print("ads> ", end="", flush=True)

    threading.Thread(target=hilo_sensor, daemon=True).start()

    print("\nEscribe /ayuda para ver los comandos interactivos.")
    print("ads> ", end="", flush=True)

    # 4. Consola interactiva
    try:
        while True:
            cmd_line = input().strip()
            if not cmd_line:
                print("ads> ", end="", flush=True)
                continue

            partes = cmd_line.split()
            cmd = partes[0].lower()

            if cmd in ["salir", "exit", "quit"]:
                break

            elif cmd in ["/ayuda", "help", "/help", "?"]:
                imprimir_ayuda()
                print("ads> ", end="", flush=True)

            elif cmd == "/catalogo":
                print(f"Catálogo oficial de Content Codes:\n  {', '.join(sorted(CATALOGO_OFICIAL))}")
                print("ads> ", end="", flush=True)

            elif cmd == "/intereses":
                if len(partes) == 1:
                    print(f"[*] Intereses actuales del ACP: {list(acp.intereses)}")
                else:
                    arg = partes[1].upper()
                    if arg.startswith("+"):
                        codigo = arg[1:]
                        acp.agregar_interes(codigo)
                        print(f"[+] Interés agregado: {codigo}. Actuales: {list(acp.intereses)}")
                    elif arg.startswith("-"):
                        codigo = arg[1:]
                        acp.remover_interes(codigo)
                        print(f"[-] Interés removido: {codigo}. Actuales: {list(acp.intereses)}")
                    else:
                        print("[!] Usa /intereses +<CODIGO> o /intereses -<CODIGO>")
                print("ads> ", end="", flush=True)

            elif cmd == "/estado":
                info = asm.obtener_estado()
                print("\n=== Estado Interno del ASM ===")
                for k, v in info.items():
                    print(f"  {k}: {v}")
                print(f"  intereses_acp: {list(acp.intereses)}")
                print("==============================\n")
                print("ads> ", end="", flush=True)

            elif cmd == "/umbral":
                if len(partes) > 1:
                    try:
                        nuevo_u = float(partes[1])
                        asm.umbral = nuevo_u
                        print(f"[*] Umbral actualizado a {nuevo_u}°C")
                    except ValueError:
                        print("[!] Uso: /umbral <numero>")
                else:
                    print(f"[*] Umbral actual: {asm.umbral}°C")
                print("ads> ", end="", flush=True)

            elif cmd == "/publicar":
                if len(partes) >= 3:
                    code = partes[1].upper()
                    valor_str = " ".join(partes[2:])
                    # Intentar parsear como float o int si aplica
                    try:
                        if "." in valor_str:
                            val = float(valor_str)
                        else:
                            val = int(valor_str)
                    except ValueError:
                        val = valor_str

                    acp.publicar_contenido(code, val)
                else:
                    print("[!] Uso: /publicar <CODIGO> <VALOR> (ej. /publicar TEMPERATURA 31.0)")
                print("ads> ", end="", flush=True)

            elif cmd == "/peers":
                print(f"\n[*] Conexiones salientes: {list(conexiones_salientes.keys())}")
                print(f"[*] Conexiones entrantes: {list(peers.conexiones_entrantes.keys())}")
                print("ads> ", end="", flush=True)

            elif cmd == "/conectar":
                if len(partes) == 2:
                    try:
                        c_ip, c_puerto = partes[1].split(":")
                        conectar_a_peer(c_ip, c_puerto, node_id)
                    except ValueError:
                        print("[!] Uso: /conectar IP:PUERTO")
                else:
                    print("[!] Uso: /conectar IP:PUERTO")
                print("ads> ", end="", flush=True)

            elif cmd in ["/descubrir", "/escanear"]:
                threading.Thread(
                    target=escanear_y_conectar_malla,
                    args=(node_id, 8070, conectar_a_peer, True),
                    daemon=True,
                ).start()

            elif cmd == "/autodescubrir":
                if len(partes) > 1 and partes[1].lower() in ["off", "0", "false", "no"]:
                    discovery.auto_scan_activo = False
                    print("[*] Autodescubrimiento automático en segundo plano DESACTIVADO.")
                elif len(partes) > 1 and partes[1].lower() in ["on", "1", "true", "si"]:
                    discovery.auto_scan_activo = True
                    print("[*] Autodescubrimiento automático en segundo plano ACTIVADO (cada 15s).")
                else:
                    st = "ACTIVADO" if discovery.auto_scan_activo else "DESACTIVADO"
                    print(f"[*] Autodescubrimiento: {st}. Usa '/autodescubrir on' o '/autodescubrir off'")
                print("ads> ", end="", flush=True)

            elif cmd == "/sensor":
                if len(partes) > 1 and partes[1].lower() in ["on", "start", "1", "si"]:
                    sensor_activo = True
                    if len(partes) > 2:
                        try:
                            sensor_intervalo = float(partes[2])
                        except ValueError:
                            pass
                    if len(partes) > 3:
                        sensor_codigo = partes[3].upper()
                    print(f"[*] Simulación de sensor ACTIVADA: emitiendo {sensor_codigo} cada {sensor_intervalo}s.")
                elif len(partes) > 1 and partes[1].lower() in ["off", "stop", "0", "no"]:
                    sensor_activo = False
                    print("[*] Simulación de sensor DESACTIVADA.")
                else:
                    st = "ACTIVADO" if sensor_activo else "DESACTIVADO"
                    print(f"[*] Sensor automático: {st} ({sensor_codigo} cada {sensor_intervalo}s). Usa '/sensor on [segundos] [codigo]' o '/sensor off'")
                print("ads> ", end="", flush=True)

            elif cmd == "/stats":
                print("\n=== Métricas del ACP (Campo de Datos) ===")
                for tag, cant in acp.stats.items():
                    print(f"  [{tag:<6}]: {cant}")
                print("=========================================\n")
                print("ads> ", end="", flush=True)

            elif cmd == "/bitacora":
                n_lineas = 15
                if len(partes) > 1:
                    try:
                        n_lineas = int(partes[1])
                    except ValueError:
                        pass
                log_path = os.path.join("logs", "bitacora.log")
                if os.path.exists(log_path):
                    print(f"\n--- Últimas {n_lineas} líneas de bitácora ---")
                    with open(log_path, "r", encoding="utf-8") as lf:
                        lineas = lf.readlines()
                        for l in lineas[-n_lineas:]:
                            print("  " + l.strip())
                    print("-------------------------------------------\n")
                else:
                    print("[!] No hay archivo de bitácora generado aún.")
                print("ads> ", end="", flush=True)

            else:
                print(f"[!] Comando no reconocido: '{cmd_line}'. Escribe /ayuda")
                print("ads> ", end="", flush=True)

    except KeyboardInterrupt:
        pass

    print("\n[!] Apagando nodo ADS...")
    registrar_evento("SYS", "Nodo apagado por el usuario.")
    for direccion, s in list(conexiones_salientes.items()):
        try:
            s.close()
        except Exception:
            pass
    print("[*] Proceso finalizado limpiamente.")
