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
  /intereses                   -> Muestra los Content Codes que el ACP filtra para el ASM
  /intereses +<CODIGO>         -> Agrega un nuevo interés (ej. /intereses +HUMEDAD)
  /intereses -<CODIGO>         -> Remueve un interés (ej. /intereses -TEMPERATURA)
  /estado                      -> Muestra el estado interno del ASM (ON/OFF, umbrales)
  /umbral <VALOR>              -> Modifica el umbral de disparo del Aire Acondicionado
  /peers                       -> Lista conexiones activas con otros nodos
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
