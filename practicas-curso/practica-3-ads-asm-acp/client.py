"""
client.py - Gestor de transporte saliente TCP hacia la malla ADS.
"""

import socket
import json
from logger import registrar_evento
import peers

conexiones_salientes = peers.conexiones_salientes

def conectar_a_peer(ip, puerto, mi_node_id):
    """Establece conexión saliente hacia un peer y envía saludo inicial."""
    direccion = f"{ip}:{puerto}"

    if direccion in conexiones_salientes:
        return False

    registrar_evento("SYS", f"Intentando conectar a {direccion}")
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(4.0)
        s.connect((ip, int(puerto)))
        s.settimeout(None)

        conexiones_salientes[direccion] = s
        registrar_evento("SYS", f"Conexión saliente establecida con {direccion}")
        print(f"[+] Conectado a peer {direccion}")

        # Saludo inicial
        saludo = json.dumps({"type": "HELLO", "node_id": mi_node_id}) + "\n"
        s.sendall(saludo.encode("utf-8"))
        return True

    except Exception as e:
        registrar_evento("SYS", f"Fallo al conectar con {direccion}: {e}")
        return False

def enviar_mensaje_a_todos(paquete_dict):
    """Difunde un diccionario serializado como JSON terminado en \\n."""
    payload = (json.dumps(paquete_dict) + "\n").encode("utf-8")

    for direccion, sock in list(conexiones_salientes.items()):
        try:
            sock.sendall(payload)
        except Exception as e:
            registrar_evento("SYS", f"Fallo enviando a {direccion}: {e}, removiendo socket.")
            try:
                sock.close()
            except Exception:
                pass
            if direccion in conexiones_salientes:
                del conexiones_salientes[direccion]
