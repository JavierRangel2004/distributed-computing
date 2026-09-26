import json
import os
import sys
import threading
import time
import uuid

# Forzamos UTF-8 en la consola. Sin esto, en muchas consolas de Windows
# (codepage cp1252/850 por defecto) el proceso se cae con UnicodeEncodeError
# en cuanto intenta imprimir un emoji (por ejemplo al recibir un HELLO).
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

from server import iniciar_servidor, set_identificador, registrar_mensaje_propio
from client import (conectar_a_peer, configurar_identidad, direcciones_salientes, detener_cliente,
                    enviar_mensaje_a_todos, hay_conexiones)
from logger import registrar_evento
import peers
import discovery
import content_log
from topology_api import iniciar_api

PING_INTERVALO_SEGUNDOS = 2

def hilo_ping_automatico(mi_node_id):
    """
    Envía un PING de latido cada PING_INTERVALO_SEGUNDOS a todos los peers
    conectados, para que la malla pueda verificar automáticamente quién
    sigue vivo (Actividad 5 de la práctica).
    """
    while True:
        time.sleep(PING_INTERVALO_SEGUNDOS)
        if hay_conexiones():
            msg_id = str(uuid.uuid4())
            registrar_mensaje_propio(msg_id)
            enviar_mensaje_a_todos({"type": "PING", "node_id": mi_node_id, "msg_id": msg_id})

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG_FILE = os.path.join(BASE, "conf", "config.json")
CONNECTIONS_FILE = os.path.join(BASE, "conf", "connections.json")

def cargar_configuracion():
    """
    Carga y devuelve la configuración desde el archivo JSON.
    """
    if not os.path.exists(CONFIG_FILE):
        print(f"[!] Error: No se encontró el archivo de configuración: {CONFIG_FILE}")
        return None
    
    try:
        with open(CONFIG_FILE, 'r', encoding='utf-8') as file:
            config = json.load(file)
            if not isinstance(config, dict):
                raise ValueError("la configuración debe ser un objeto JSON")
            if not isinstance(config.get("node_id"), str) or not config["node_id"].strip():
                raise ValueError("node_id debe ser texto no vacío")
            if not isinstance(config.get("host"), str) or not config["host"].strip():
                raise ValueError("host debe ser texto no vacío")
            port = config.get("port")
            if not isinstance(port, int) or isinstance(port, bool) or not 1 <= port <= 65535:
                raise ValueError("port debe ser un entero entre 1 y 65535")
            api_port = config.get("api_port", port + 1000)
            if not isinstance(api_port, int) or isinstance(api_port, bool) or not 1 <= api_port <= 65535:
                raise ValueError("api_port debe ser un entero entre 1 y 65535")
            config["api_port"] = api_port
            api_host = config.get("api_host", "0.0.0.0")
            if not isinstance(api_host, str) or not api_host.strip():
                raise ValueError("api_host debe ser texto no vacío")
            config["api_host"] = api_host
            return config
    except (json.JSONDecodeError, ValueError) as e:
        print(f"[!] Error en {CONFIG_FILE}: {e}")
        return None

if __name__ == "__main__":
    print("=== Inicializando Nodo P2P ===")
    
    # Aseguramos que la carpeta conf existe para que el usuario sepa dónde poner los archivos
    os.makedirs(os.path.join(BASE, "conf"), exist_ok=True)
        
    config = cargar_configuracion()
    
    if config:
        mi_node_id = config.get('node_id')
        host = config.get('host')
        port = config.get('port')
        api_host = config['api_host']
        api_port = config['api_port']
        
        # Cargar lista de conexiones desde su propio archivo
        peers_iniciales = []
        if os.path.exists(CONNECTIONS_FILE):
            try:
                with open(CONNECTIONS_FILE, 'r', encoding='utf-8') as f:
                    peers_iniciales = json.load(f)
                    if not isinstance(peers_iniciales, list):
                        raise ValueError("debe contener una lista")
            except Exception as e:
                print(f"[!] Error leyendo {CONNECTIONS_FILE}: {e}")

        
        # Le pasamos nuestra identidad al servidor para que sepa quiénes somos
        set_identificador(mi_node_id, port, api_port)
        configurar_identidad(mi_node_id, port, api_port)
        discovery.configurar(
            CONNECTIONS_FILE,
            f"127.0.0.1:{port}",
            lambda ip, puerto: conectar_a_peer(ip, puerto, mi_node_id),
        )
        discovery.cargar(peers_iniciales)
        
        print(f"[*] Nodo ID: {mi_node_id}")
        print(f"[*] Servidor configurado en: {host}:{port}")
        print(f"[*] Lista blanca / Peers iniciales detectados: {len(peers_iniciales)}")
        print("==============================\n")
        
        registrar_evento("="*50)
        registrar_evento(f"NODO INICIADO -> ID: {mi_node_id} | Escuchando en {host}:{port}")
        
        # 1. Iniciar el servidor TCP (escucha) en segundo plano
        hilo_servidor = threading.Thread(target=iniciar_servidor, args=(host, port))
        hilo_servidor.daemon = True
        hilo_servidor.start()

        hilo_api = threading.Thread(
            target=iniciar_api,
            args=(api_host, api_port, mi_node_id, port),
            daemon=True,
        )
        hilo_api.start()
        print(f"[*] API de topología: http://127.0.0.1:{api_port}/topology")
        
        # 2. Conectar automáticamente a los peers de la lista blanca (config.json)
        # Lo hacemos con un pequeño retraso para asegurar que el servidor ya prendió
        time.sleep(1)
        for peer in peers_iniciales:
            try:
                p_ip, p_puerto = peer.split(':')
                conectar_a_peer(p_ip, p_puerto, mi_node_id)
            except ValueError:
                print(f"[!] Formato de peer inválido en config: {peer}. Usa IP:PUERTO")

        # 3. Hilo de latido: envía PING automáticamente cada 2 segundos (Actividad 5)
        hilo_ping = threading.Thread(target=hilo_ping_automatico, args=(mi_node_id,))
        hilo_ping.daemon = True
        hilo_ping.start()

        # 4. Bucle principal de la aplicación (Consola Interactiva)
        try:
            while True:
                comando = input()
                partes = comando.strip().split()
                
                if not partes:
                    print("nodo> ", end="", flush=True)
                    continue
                    
                cmd = partes[0].lower()
                
                if cmd in ['salir', 'exit', 'quit']:
                    break
                    
                elif cmd == '/conectar':
                    if len(partes) == 2:
                        try:
                            c_ip, c_puerto = partes[1].split(':')
                            conectar_a_peer(c_ip, c_puerto, mi_node_id)
                        except ValueError:
                            print("[!] Uso: /conectar IP:PUERTO")
                            print("nodo> ", end="", flush=True)
                    else:
                        print("[!] Uso: /conectar IP:PUERTO")
                        print("nodo> ", end="", flush=True)
                        
                elif cmd == '/peers':
                    salientes = direcciones_salientes()
                    direcciones = peers.direcciones()
                    entrantes = [d for d in direcciones if d not in salientes]
                    print(f"\n[*] Conexiones salientes (yo marqué): {salientes}")
                    print(f"[*] Conexiones entrantes (me marcaron): {entrantes}")
                    print("nodo> ", end="", flush=True)
                    
                elif cmd == '/ping':
                    msg_id = str(uuid.uuid4())
                    registrar_mensaje_propio(msg_id)
                    enviar_mensaje_a_todos({"type": "PING", "node_id": mi_node_id, "msg_id": msg_id})
                    print("nodo> ", end="", flush=True)

                elif cmd in ['/enviar', '/decir']:
                    if len(partes) > 1:
                        mensaje_texto = " ".join(partes[1:])
                        msg_id = str(uuid.uuid4())
                        registrar_mensaje_propio(msg_id)
                        enviar_mensaje_a_todos({"type": "TEXT", "node_id": mi_node_id, "msg_id": msg_id, "text": mensaje_texto})
                    else:
                        print("[!] Uso: /enviar Hola red!")
                    print("nodo> ", end="", flush=True)

                elif cmd == '/contenido':
                    if len(partes) >= 3:
                        codigo = partes[1].upper()
                        crudo = " ".join(partes[2:])
                        try:
                            dato = json.loads(crudo) # 29.5, true, "ON"...
                        except ValueError:
                            dato = crudo # si no es JSON, se envía como texto
                        msg_id = str(uuid.uuid4())
                        registrar_mensaje_propio(msg_id)
                        registrar_evento(f"CONTENT PUBLICADO -> {codigo} = {dato!r}")
                        content_log.registrar(mi_node_id, codigo, dato, msg_id, None)
                        enviar_mensaje_a_todos({"type": "CONTENT", "node_id": mi_node_id, "msg_id": msg_id, "content_code": codigo, "data": dato})
                    else:
                        print("[!] Uso: /contenido CODIGO VALOR   Ejemplo: /contenido TEMPERATURA 29.5")
                    print("nodo> ", end="", flush=True)

                else:
                    print(f"[!] Comando no reconocido: {comando}")
                    print("nodo> ", end="", flush=True)
                    
        except KeyboardInterrupt:
            # Captura Ctrl+C para salir limpiamente
            pass
            
        print("\n[!] Apagando nodo...")
        detener_cliente()
        registrar_evento("NODO APAGADO -> Proceso terminado por el usuario.")
