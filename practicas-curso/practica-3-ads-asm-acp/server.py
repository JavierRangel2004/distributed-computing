"""
server.py - Servidor TCP multihilo para el nodo ADS.
Acepta conexiones de la malla y despacha las líneas recibidas al ACP.
"""

import socket
import threading
from logger import registrar_evento
import peers

acp_instancia = None

def set_acp(acp):
    """Asocia la instancia global del ACP con el servidor."""
    global acp_instancia
    acp_instancia = acp

def manejar_cliente(conn, addr):
    """Atiende a un peer conectado con buffer para líneas terminadas en \\n."""
    direccion_peer = f"{addr[0]}:{addr[1]}"
    peers.conexiones_entrantes[direccion_peer] = conn

    registrar_evento("SYS", f"Conexión entrante aceptada desde {direccion_peer}")
    print(f"\n[+] Conexión entrante desde {direccion_peer}")
    print("ads> ", end="", flush=True)

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
                if linea and acp_instancia:
                    acp_instancia.procesar_paquete_red(linea, addr)
                    print("ads> ", end="", flush=True)

    except ConnectionResetError:
        pass
    except Exception as e:
        registrar_evento("SYS", f"Excepción en socket cliente {direccion_peer}: {e}")
    finally:
        registrar_evento("SYS", f"Conexión cerrada con {direccion_peer}")
        if direccion_peer in peers.conexiones_entrantes:
            del peers.conexiones_entrantes[direccion_peer]
        try:
            conn.close()
        except Exception:
            pass

def iniciar_servidor(host, port):
    """Abre el socket TCP y escucha en el puerto configurado."""
    servidor = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    servidor.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    try:
        servidor.bind((host, int(port)))
        servidor.listen(10)
        registrar_evento("SYS", f"Servidor escuchando en {host}:{port}")
        print(f"[*] Servidor ADS escuchando en {host}:{port} (TCP)")
        print("ads> ", end="", flush=True)

        while True:
            conn, addr = servidor.accept()
            hilo = threading.Thread(target=manejar_cliente, args=(conn, addr))
            hilo.daemon = True
            hilo.start()

    except Exception as e:
        registrar_evento("SYS", f"Error fatal en servidor TCP: {e}")
        print(f"\n[!] Error fatal en servidor TCP: {e}")
    finally:
        try:
            servidor.close()
        except Exception:
            pass
