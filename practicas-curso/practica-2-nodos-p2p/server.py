import socket
import threading
import json
from client import enviar_mensaje_a_todos
from logger import registrar_evento
import peers

# Registro de identificadores únicos (UUIDs) de mensajes ya procesados
# para prevenir reenvíos cíclicos e infinitos en la malla (mecanismo Gossip).
mensajes_vistos = set()

# Registro de mensajes originados por nosotros que esperan acuse de recibo (ACK)
mis_mensajes = set()

mi_identificador = "Desconocido"

def set_identificador(node_id):
    """Establece el ID del nodo local para firmas y validaciones."""
    global mi_identificador
    mi_identificador = node_id

def procesar_mensaje(mensaje_str, addr):
    """
    Parsea y despacha un mensaje JSON recibido por la red según su tipo.
    Aplica el filtro de mensajes vistos para evitar tormentas de difusión.
    """
    try:
        datos = json.loads(mensaje_str)
        tipo = datos.get("type", "DESCONOCIDO")
        origen = datos.get("node_id", "Anónimo")
        msg_id = datos.get("msg_id")

        # 1. Filtro anti-bucles: si ya vimos este mensaje, lo descartamos
        if msg_id:
            if msg_id in mensajes_vistos:
                return
            mensajes_vistos.add(msg_id)

        # 2. Despacho según el tipo de mensaje
        if tipo == "HELLO":
            print(f"\n[👋 SALUDO] El nodo '{origen}' ({addr[0]}) nos saluda.")
            registrar_evento(f"MENSAJE RECIBIDO -> TIPO: HELLO, ORIGEN: {origen}, IP: {addr[0]}")
            return
        elif tipo == "PING":
            print(f"\n[🏓 PING] Latido de red recibido desde '{origen}'.")
            registrar_evento(f"MENSAJE RECIBIDO -> TIPO: PING, ORIGEN: {origen}, MSG_ID: {msg_id}")
            # Mecanismo Gossip: retransmitir a los demás pares
            enviar_mensaje_a_todos(datos)
            return
        elif tipo == "TEXT":
            texto = datos.get("text", "(sin contenido)")
            print(f"\n[💬 MENSAJE - {origen}]: {texto}")
            registrar_evento(f"MENSAJE RECIBIDO -> TIPO: TEXT, ORIGEN: {origen}, TEXTO: '{texto}'")
            # Mecanismo Gossip: retransmitir a la malla
            enviar_mensaje_a_todos(datos)

            # Si el mensaje viene de otro nodo y tiene ID, emitimos ACK de confirmación
            if msg_id and origen != mi_identificador:
                ack_msg = {
                    "type": "ACK",
                    "node_id": mi_identificador,
                    "ack_msg_id": msg_id
                }
                registrar_evento(f"GENERANDO ACK -> PARA EL MENSAJE: {msg_id}")
                enviar_mensaje_a_todos(ack_msg)
            return
        elif tipo == "ACK":
            ack_id = datos.get("ack_msg_id")
            if ack_id in mis_mensajes:
                print(f"\n[✅ ACK] '{origen}' recibió correctamente tu mensaje.")
                registrar_evento(f"ACK RECIBIDO -> ORIGEN: {origen} confirmó nuestro mensaje {ack_id}")
            # Reenviar el ACK para que otros intermediarios puedan entregarlo al emisor
            enviar_mensaje_a_todos(datos)
            return
        else:
            print(f"\n[?] Tipo de mensaje '{tipo}' no reconocido.")
            registrar_evento(f"ADVERTENCIA -> Mensaje desconocido de {origen}: {tipo}")
            return
    except json.JSONDecodeError:
        print(f"\n[!] Mensaje ignorado, no es JSON válido: {mensaje_str}")
        registrar_evento("ERROR -> Se recibió un mensaje que no es JSON válido.")
def manejar_cliente(conn, addr):
    """
    Gestiona la comunicación con un cliente que se conectó a nuestro servidor TCP.
    Usa un acumulador de buffer para soportar mensajes fragmentados delimitados por \\n.
    """
    direccion_peer = f"{addr[0]}:{addr[1]}"
    peers.conexiones_entrantes[direccion_peer] = conn
    
    print(f"\n[+] Nueva conexión entrante desde {addr[0]}:{addr[1]}")
    registrar_evento(f"CONEXIÓN ENTRANTE -> Aceptada desde {addr[0]}:{addr[1]}")
    print("nodo> ", end="", flush=True)

    buffer = ""
    try:
        while True:
            data = conn.recv(1024)
            if not data:
                break
                
            buffer += data.decode("utf-8", errors="replace")
            
            while "\n" in buffer:
                linea, buffer = buffer.split("\n", 1)
                linea = linea.strip()
                if linea:
                    procesar_mensaje(linea, addr)
                    
    except ConnectionResetError:
        pass
    except Exception as e:
        registrar_evento(f"EXCEPCIÓN EN CLIENTE {direccion_peer} -> {e}")
    finally:
        print(f"\n[-] Conexión cerrada con {addr[0]}:{addr[1]}")
        registrar_evento(f"DESCONEXIÓN ENTRANTE -> Cliente {addr[0]}:{addr[1]} se ha ido.")
        if direccion_peer in peers.conexiones_entrantes:
            del peers.conexiones_entrantes[direccion_peer]
        try:
            conn.close()
        except Exception:
            pass
        print("nodo> ", end="", flush=True)

def iniciar_servidor(host, port):
    """
    Inicia el servidor socket TCP del nodo en un puerto determinado
    y despacha cada conexión entrante a un hilo trabajador.
    """
    servidor = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    servidor.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    try:
        servidor.bind((host, int(port)))
        servidor.listen(5)
        print(f"[*] Servidor a la escucha en {host}:{port} (TCP)")
        registrar_evento(f"SERVIDOR INICIADO -> Escuchando en {host}:{port}")
        print("nodo> ", end="", flush=True)

        while True:
            conn, addr = servidor.accept()
            hilo_cliente = threading.Thread(target=manejar_cliente, args=(conn, addr))
            hilo_cliente.daemon = True
            hilo_cliente.start()

    except Exception as e:
        print(f"\n[!] Error en el servidor TCP: {e}")
        registrar_evento(f"ERROR CRÍTICO -> Falla en el servidor TCP: {e}")
    finally:
        try:
            servidor.close()
        except Exception:
            pass
