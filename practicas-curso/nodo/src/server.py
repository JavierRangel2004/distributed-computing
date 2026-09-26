import socket
import threading
import json
import uuid
from collections import deque

# Importamos la función para retransmitir a los demás (Clase 5)
from client import enviar_mensaje_a_todos, enviar_por_socket
# Importamos nuestro nuevo logger
from logger import registrar_evento
import peers
import discovery
import content_log

# --- CLASE 5 y 6: RASTREO DE MENSAJES ---
mensajes_vistos = set() # Evita bucles infinitos
mis_mensajes = set()    # Recuerda qué enviamos para saber si el ACK es para nosotros
_orden_vistos = deque()
_orden_propios = deque()
_lock_mensajes = threading.RLock()
MAX_MENSAJES_RECORDADOS = 10000
mi_identificador = "Desconocido" # Se configura al arrancar
mi_puerto = None
mi_api_puerto = None

def _recordar(conjunto, orden, msg_id):
    if msg_id in conjunto:
        return False
    conjunto.add(msg_id)
    orden.append(msg_id)
    while len(orden) > MAX_MENSAJES_RECORDADOS:
        conjunto.discard(orden.popleft())
    return True

def registrar_mensaje_propio(msg_id):
    with _lock_mensajes:
        _recordar(mensajes_vistos, _orden_vistos, msg_id)
        _recordar(mis_mensajes, _orden_propios, msg_id)

def _registrar_si_nuevo(msg_id):
    with _lock_mensajes:
        return _recordar(mensajes_vistos, _orden_vistos, msg_id)

def _es_mensaje_propio(msg_id):
    with _lock_mensajes:
        return msg_id in mis_mensajes

def set_identificador(node_id, puerto=None, api_puerto=None):
    global mi_identificador, mi_puerto, mi_api_puerto
    mi_identificador = node_id
    mi_puerto = puerto
    mi_api_puerto = api_puerto

TIPOS_VALIDOS = ("HELLO", "PING", "TEXT", "ACK", "CONTENT", "PEERS")
MAX_LINEA = 16384 # caracteres máximos de un mensaje sin salto de línea

def _texto_no_vacio(valor):
    return isinstance(valor, str) and valor.strip() != ""

def validar_mensaje(datos):
    """
    Devuelve None si el mensaje cumple el formato del protocolo, o un texto
    con el motivo por el que debe descartarse. Se aceptan campos extra.
    """
    if not isinstance(datos, dict):
        return "no es un objeto JSON"
    tipo = datos.get("type")
    if tipo not in TIPOS_VALIDOS:
        return f"type inválido: {repr(tipo)[:40]}"
    if not _texto_no_vacio(datos.get("node_id")):
        return "falta node_id (texto no vacío)"
    msg_id = datos.get("msg_id")
    if msg_id is not None and not _texto_no_vacio(msg_id):
        return "msg_id debe ser texto no vacío"
    if tipo in ("PING", "TEXT", "ACK", "CONTENT", "PEERS") and msg_id is None:
        return f"{tipo} sin msg_id"
    if tipo == "TEXT" and not isinstance(datos.get("text"), str):
        return "TEXT sin campo text (texto)"
    if tipo == "CONTENT":
        if not _texto_no_vacio(datos.get("content_code")):
            return "CONTENT sin content_code (texto no vacío)"
        if "data" not in datos:
            return "CONTENT sin campo data"
    if tipo == "ACK":
        if not _texto_no_vacio(datos.get("ack_msg_id")):
            return "ACK sin ack_msg_id (texto no vacío)"
    if tipo == "HELLO":
        role = datos.get("role", "unknown")
        if role not in ("node", "app", "unknown"):
            return "HELLO con role inválido"
        if role == "node" and datos.get("listen_port") is not None:
            port = datos["listen_port"]
            if not isinstance(port, int) or isinstance(port, bool) or not 1 <= port <= 65535:
                return "HELLO con listen_port inválido"
        if role == "node" and datos.get("api_port") is not None:
            port = datos["api_port"]
            if not isinstance(port, int) or isinstance(port, bool) or not 1 <= port <= 65535:
                return "HELLO con api_port inválido"
    if tipo == "PEERS":
        endpoints = datos.get("peers")
        if not isinstance(endpoints, list) or any(not isinstance(p, str) for p in endpoints):
            return "PEERS sin lista de direcciones"
    return None

def descartar_mensaje(motivo, crudo, direccion):
    muestra = repr(crudo[:60]) + ("..." if len(crudo) > 60 else "")
    registrar_evento(f"MENSAJE DESCARTADO -> {motivo} | PEER: {direccion} | CONTENIDO: {muestra}")

def procesar_mensaje(mensaje_str, addr):
    """
    Lee el JSON, descarta lo que no cumpla el formato y ejecuta el protocolo
    del DataField (Repetición y ACKs).
    """
    direccion = f"{addr[0]}:{addr[1]}" # quién nos lo mandó a nosotros
    try:
        try:
            datos = json.loads(mensaje_str)
        except (ValueError, RecursionError):
            descartar_mensaje("no es JSON válido", mensaje_str, direccion)
            return

        motivo = validar_mensaje(datos)
        if motivo:
            descartar_mensaje(motivo, mensaje_str, direccion)
            return

        tipo = datos["type"]
        origen = datos["node_id"]
        msg_id = datos.get("msg_id")
        
        # ==============================================================
        # CLASE 5: PREVENCIÓN DE BUCLES (GOSSIP PROTOCOL)
        # ==============================================================
        if msg_id and not _registrar_si_nuevo(msg_id):
            return
        
        # ==============================================================
        # PROCESAMIENTO Y RESPUESTAS
        # ==============================================================
        if tipo == "HELLO":
            role = datos.get("role", "unknown")
            endpoint = None
            api_endpoint = None
            if role == "node" and datos.get("listen_port"):
                endpoint = f"{addr[0]}:{datos['listen_port']}"
                discovery.descubrir(endpoint, conectar=False)
            if role == "node" and datos.get("api_port"):
                api_endpoint = f"http://{addr[0]}:{datos['api_port']}/topology"
            peers.actualizar_identidad(direccion, origen, role, endpoint, api_endpoint)
            print(f"\n[👋 SALUDO] El nodo '{origen}' ({addr[0]}) nos saluda.")
            registrar_evento(f"MENSAJE RECIBIDO -> TIPO: HELLO, ORIGEN: {origen}, ROL: {role}, IP: {addr[0]}")
            if role == "node":
                anuncio_id = str(uuid.uuid4())
                registrar_mensaje_propio(anuncio_id)
                enviar_mensaje_a_todos({
                    "type": "PEERS", "node_id": mi_identificador,
                    "msg_id": anuncio_id, "peers": discovery.conocidos(),
                })
            
        elif tipo == "PING":
            print(f"\n[🏓 PING] Latido de red recibido desde '{origen}'.")
            registrar_evento(f"MENSAJE RECIBIDO -> TIPO: PING, ORIGEN: {origen}, MSG_ID: {msg_id}")
            # CLASE 5: Retransmitir al resto del DataField (menos a quien nos lo mandó)
            enviar_mensaje_a_todos(datos, excluir=direccion)
            
        elif tipo == "TEXT":
            texto = datos.get("text", "(sin contenido)")
            print(f"\n[💬 MENSAJE - {origen}]: {texto}")
            registrar_evento(f"MENSAJE RECIBIDO -> TIPO: TEXT, ORIGEN: {origen}, TEXTO: '{texto}'")
            
            # CLASE 5: Retransmitir (Repetir) el mensaje al resto de los nodos
            # (menos a quien nos lo mandó, que es justamente quien ya lo tiene)
            enviar_mensaje_a_todos(datos, excluir=direccion)

            # CLASE 6: Generar Acuse de Recibo (ACK) para el que lo mandó.
            # Este SÍ debe llegar a todos, incluyendo a quien nos mandó el TEXT
            # (es un mensaje nuevo nuestro, no una retransmisión).
            if msg_id and origen != mi_identificador:
                ack_msg = {
                    "type": "ACK",
                    "node_id": mi_identificador,
                    "msg_id": str(uuid.uuid4()),
                    "ack_msg_id": msg_id
                }
                registrar_mensaje_propio(ack_msg["msg_id"])
                registrar_evento(f"GENERANDO ACK -> PARA EL MENSAJE: {msg_id}")
                enviar_mensaje_a_todos(ack_msg)
                
        elif tipo == "ACK":
            ack_id = datos.get("ack_msg_id")
            # CLASE 6: Verificar si el acuse es para un mensaje que yo escribí
            if _es_mensaje_propio(ack_id):
                print(f"\n[✅ ACK] '{origen}' recibió correctamente tu mensaje.")
                registrar_evento(f"ACK RECIBIDO -> ORIGEN: {origen} confirmó nuestro mensaje {ack_id}")
            
            # CLASE 5: Retransmitir el ACK para que llegue al autor original
            # (menos a quien nos lo mandó)
            enviar_mensaje_a_todos(datos, excluir=direccion)

        elif tipo == "CONTENT":
            # El nodo solo es un medio de difusión: no interpreta el contenido
            # ni decide nada con él. Eso lo hacen el ACP y el ASM de cada subsistema.
            codigo = datos["content_code"]
            valor = repr(datos["data"])
            if len(valor) > 80:
                valor = valor[:80] + "..."
            print(f"\n[📦 CONTENT - {origen}]: {codigo} = {valor}")
            registrar_evento(f"MENSAJE RECIBIDO -> TIPO: CONTENT, ORIGEN: {origen}, CODE: {codigo}, DATA: {valor}, MSG_ID: {msg_id}")
            content_log.registrar(origen, codigo, datos["data"], msg_id, peers.identidad(direccion))
            enviar_mensaje_a_todos(datos, excluir=direccion)

        elif tipo == "PEERS":
            for endpoint in datos["peers"]:
                discovery.descubrir(endpoint, conectar=True)
            enviar_mensaje_a_todos(datos, excluir=direccion)

    except Exception as e:
        # Un fallo procesando un mensaje no debe tumbar el hilo de la conexión
        registrar_evento(f"ERROR -> Falla procesando mensaje de {direccion}: {e}")

def leer_conexion(conn, addr, direccion=None, clase="ENTRANTE"):
    """Lee mensajes delimitados por salto de línea desde cualquier socket."""
    direccion = direccion or f"{addr[0]}:{addr[1]}"
    buffer = ""
    while True:
        try:
            data = conn.recv(4096)
            if not data:
                break
            buffer += data.decode('utf-8', errors='replace')
            while '\n' in buffer:
                linea, buffer = buffer.split('\n', 1)
                if linea.strip():
                    procesar_mensaje(linea.strip(), addr)
            if len(buffer) > MAX_LINEA:
                descartar_mensaje(f"más de {MAX_LINEA} caracteres sin salto de línea", buffer, direccion)
                buffer = ""
        except (ConnectionResetError, OSError):
            break

    peers.eliminar(direccion, conn)
    registrar_evento(f"DESCONEXIÓN {clase} -> {direccion}")

def manejar_cliente(conn, addr):
    """
    Función que maneja la comunicación con un cliente específico.
    Se ejecuta en su propio hilo.
    """
    direccion = f"{addr[0]}:{addr[1]}"
    print(f"\n[+] Nueva conexión entrante desde {direccion}")
    registrar_evento(f"CONEXIÓN ENTRANTE -> Aceptada desde {direccion}")
    print("nodo> ", end="", flush=True) # Para mantener limpia la consola

    # Registramos esta conexión entrante en el registro compartido de peers,
    # para que enviar_mensaje_a_todos (ACKs, gossip, /ping, /enviar) también
    # le llegue a quien se conectó hacia nosotros.
    peers.registrar(direccion, conn, "entrante")
    try:
        enviar_por_socket(direccion, conn, {
            "type": "HELLO", "node_id": mi_identificador,
            "role": "node", "listen_port": mi_puerto,
            "api_port": mi_api_puerto,
        })
    except OSError:
        pass
    leer_conexion(conn, addr, direccion, "ENTRANTE")
    try:
        conn.close()
    except OSError:
        pass

def iniciar_servidor(host, port):
    """
    Inicia el servidor TCP y se queda escuchando conexiones.
    """
    servidor = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    # Esta opción permite reiniciar el script sin el error de "Puerto ya en uso"
    servidor.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    
    try:
        servidor.bind((host, port))
        servidor.listen(5) # Encola hasta 5 conexiones simultáneas
        print(f"[*] Servidor a la escucha en {host}:{port} (TCP)")
        registrar_evento(f"SERVIDOR INICIADO -> Escuchando en {host}:{port}")
        print("nodo> ", end="", flush=True)
        
        while True:
            # Aceptamos la conexión (esto bloquea hasta que alguien se conecta)
            conn, addr = servidor.accept()
            
            # Pasamos la conexión a un hilo nuevo para poder seguir escuchando a otros
            hilo_cliente = threading.Thread(target=manejar_cliente, args=(conn, addr))
            hilo_cliente.daemon = True # Si el programa principal muere, este hilo también
            hilo_cliente.start()
            
    except Exception as e:
        print(f"\n[!] Error en el servidor TCP: {e}")
        registrar_evento(f"ERROR CRÍTICO -> Falla en el servidor TCP: {e}")
    finally:
        servidor.close()
