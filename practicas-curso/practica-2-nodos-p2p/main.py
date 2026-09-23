import json
import os
import sys
import threading
import time
import uuid
import argparse

# Forzar UTF-8 en la consola para evitar caídas por emojis en diversas plataformas
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

from server import iniciar_servidor, set_identificador, mensajes_vistos, mis_mensajes
from client import conectar_a_peer, conexiones_salientes, enviar_mensaje_a_todos
from logger import registrar_evento
import peers

PING_INTERVALO_SEGUNDOS = 2
ping_activo = True  # Control del latido periódico automático

def hilo_ping_automatico(mi_node_id):
    """
    Envía un PING de latido cada PING_INTERVALO_SEGUNDOS a todos los peers
    conectados (Actividad 5 de la práctica), permitiendo verificar liveness.
    """
    global ping_activo
    while True:
        time.sleep(PING_INTERVALO_SEGUNDOS)
        if ping_activo and conexiones_salientes:
            msg_id = str(uuid.uuid4())
            mensajes_vistos.add(msg_id)
            mis_mensajes.add(msg_id)
            enviar_mensaje_a_todos({
                "type": "PING",
                "node_id": mi_node_id,
                "msg_id": msg_id
            })

CONFIG_FILE = os.path.join("conf", "config.json")
CONNECTIONS_FILE = os.path.join("conf", "connections.json")

def cargar_configuracion():
    """
    Carga y devuelve la configuración desde el archivo JSON,
    o genera una por defecto si no existe.
    """
    if not os.path.exists("conf"):
        os.makedirs("conf", exist_ok=True)
        
    if not os.path.exists(CONFIG_FILE):
        default_config = {
            "node_id": "EQUIPO_03",
            "host": "0.0.0.0",
            "port": 8070
        }
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(default_config, f, indent=4)
        return default_config
    
    try:
        with open(CONFIG_FILE, "r", encoding="utf-8") as file:
            return json.load(file)
    except json.JSONDecodeError:
        print(f"[!] Error: El archivo {CONFIG_FILE} no tiene un formato JSON válido.")
        return None

def imprimir_ayuda():
    print("""
=== Comandos disponibles ===
  /conectar IP:PUERTO    -> Establece conexión saliente con otro nodo
  /peers                 -> Muestra conexiones salientes y entrantes activas
  /ping                  -> Envía un PING manual a todos los nodos conectados
  /ping_auto [on|off]    -> Activa o desactiva el PING periódico de 2 segundos
  /enviar <mensaje>      -> Difunde un mensaje de texto por la malla
  /decir <mensaje>       -> Alias de /enviar
  /ayuda                 -> Muestra esta guía
  salir | exit | quit    -> Cierra el nodo y termina el proceso
============================
""")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Nodo P2P para Práctica 2 (Malla ADS)")
    parser.add_argument("--port", type=int, help="Puerto local para escuchar (ej. 8070)")
    parser.add_argument("--id", type=str, help="Identificador del nodo (ej. EQUIPO_03)")
    parser.add_argument("--host", type=str, help="Host local para bind (ej. 0.0.0.0)")
    args = parser.parse_args()

    print("=== Inicializando Nodo P2P (Malla ADS) ===")
    
    config = cargar_configuracion()
    if not config:
        print("[!] No se pudo inicializar la configuración. Saliendo...")
        sys.exit(1)

    mi_node_id = args.id or config.get("node_id", "EQUIPO_03")
    host = args.host or config.get("host", "0.0.0.0")
    port = args.port or config.get("port", 8070)

    # Cargar peers iniciales de connections.json
    peers_iniciales = []
    if os.path.exists(CONNECTIONS_FILE):
        try:
            with open(CONNECTIONS_FILE, "r", encoding="utf-8") as f:
                peers_iniciales = json.load(f)
        except Exception as e:
            print(f"[!] Error leyendo {CONNECTIONS_FILE}: {e}")
    else:
        # Crear plantilla vacía si no existe
        with open(CONNECTIONS_FILE, "w", encoding="utf-8") as f:
            json.dump(["192.168.8.1:8070"], f, indent=4)
        peers_iniciales = ["192.168.8.1:8070"]

    set_identificador(mi_node_id)

    print(f"[*] Nodo ID: {mi_node_id}")
    print(f"[*] Escuchando en: {host}:{port}")
    print(f"[*] Peers iniciales / Whitelist: {peers_iniciales}")
    print("=========================================\n")

    registrar_evento("=" * 50)
    registrar_evento(f"NODO INICIADO -> ID: {mi_node_id} | Escuchando en {host}:{port}")

    # 1. Iniciar servidor TCP en segundo plano
    hilo_servidor = threading.Thread(target=iniciar_servidor, args=(host, port))
    hilo_servidor.daemon = True
    hilo_servidor.start()

    # 2. Conectar automáticamente a los peers de la lista blanca tras un breve delay
    time.sleep(1)
    for peer in peers_iniciales:
        try:
            p_ip, p_puerto = peer.split(":")
            conectar_a_peer(p_ip, p_puerto, mi_node_id)
        except ValueError:
            print(f"[!] Formato de peer inválido: '{peer}'. Usa IP:PUERTO")

    # 3. Iniciar hilo de latido (PING automático cada 2s)
    hilo_ping = threading.Thread(target=hilo_ping_automatico, args=(mi_node_id,))
    hilo_ping.daemon = True
    hilo_ping.start()

    print("Escribe /ayuda para ver los comandos interactivos.")
    print("nodo> ", end="", flush=True)

    # 4. Consola interactiva CLI
    try:
        while True:
            comando = input()
            partes = comando.strip().split()

            if not partes:
                print("nodo> ", end="", flush=True)
                continue

            cmd = partes[0].lower()

            if cmd in ["salir", "exit", "quit"]:
                break

            elif cmd in ["/ayuda", "help", "/help", "?"]:
                imprimir_ayuda()
                print("nodo> ", end="", flush=True)

            elif cmd == "/conectar":
                if len(partes) == 2:
                    try:
                        c_ip, c_puerto = partes[1].split(":")
                        conectar_a_peer(c_ip, c_puerto, mi_node_id)
                    except ValueError:
                        print("[!] Uso: /conectar IP:PUERTO")
                else:
                    print("[!] Uso: /conectar IP:PUERTO")
                print("nodo> ", end="", flush=True)

            elif cmd == "/peers":
                salientes = list(conexiones_salientes.keys())
                entrantes = list(peers.conexiones_entrantes.keys())
                print(f"\n[*] Conexiones salientes (hacia donde enviamos): {salientes}")
                print(f"[*] Conexiones entrantes (clientes recibidos): {entrantes}")
                print("nodo> ", end="", flush=True)

            elif cmd == "/ping":
                msg_id = str(uuid.uuid4())
                mensajes_vistos.add(msg_id)
                mis_mensajes.add(msg_id)
                enviar_mensaje_a_todos({
                    "type": "PING",
                    "node_id": mi_node_id,
                    "msg_id": msg_id
                })
                print(f"[*] PING manual emitido (msg_id: {msg_id[:8]}...)")
                print("nodo> ", end="", flush=True)

            elif cmd == "/ping_auto":
                if len(partes) > 1 and partes[1].lower() in ["off", "0", "false", "no"]:
                    ping_activo = False
                    print("[*] Latido PING automático DESACTIVADO.")
                elif len(partes) > 1 and partes[1].lower() in ["on", "1", "true", "si"]:
                    ping_activo = True
                    print("[*] Latido PING automático ACTIVADO (cada 2s).")
                else:
                    estado = "ACTIVADO" if ping_activo else "DESACTIVADO"
                    print(f"[*] Estado PING automático: {estado}. Usa '/ping_auto on' o '/ping_auto off'")
                print("nodo> ", end="", flush=True)

            elif cmd in ["/enviar", "/decir"]:
                if len(partes) > 1:
                    mensaje_texto = " ".join(partes[1:])
                    msg_id = str(uuid.uuid4())
                    mensajes_vistos.add(msg_id)
                    mis_mensajes.add(msg_id)
                    enviar_mensaje_a_todos({
                        "type": "TEXT",
                        "node_id": mi_node_id,
                        "msg_id": msg_id,
                        "text": mensaje_texto
                    })
                    print(f"[*] Mensaje enviado a la malla (msg_id: {msg_id[:8]}...)")
                else:
                    print("[!] Uso: /enviar <texto>")
                print("nodo> ", end="", flush=True)

            else:
                print(f"[!] Comando no reconocido: '{comando}'. Escribe /ayuda para ver opciones.")
                print("nodo> ", end="", flush=True)

    except KeyboardInterrupt:
        pass

    print("\n[!] Apagando nodo...")
    registrar_evento("NODO APAGADO -> Proceso terminado por el usuario.")
    # Cerrar sockets salientes
    for direccion, s in list(conexiones_salientes.items()):
        try:
            s.close()
        except Exception:
            pass
    print("[*] Nodo finalizado limpiamente.")
