import socket
import json
from logger import registrar_evento
import peers

# Usamos el diccionario de conexiones salientes compartido en peers
conexiones_salientes = peers.conexiones_salientes

def conectar_a_peer(ip, puerto, mi_node_id):
    """
    Inicia una conexión TCP saliente hacia otro nodo peer y envía el saludo inicial (HELLO).
    """
    direccion = f"{ip}:{puerto}"
    
    if direccion in conexiones_salientes:
        print(f"\n[!] Ya estás conectado a {direccion}")
        print("nodo> ", end="", flush=True)
        return False
        
    print(f"\n[*] Intentando conectar al peer {direccion}...")
    registrar_evento(f"INTENTO CONEXIÓN SALIENTE -> Hacia {direccion}")
    
    try:
        cliente = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        cliente.settimeout(5.0)  # Timeout para evitar bloqueos indefinidos si el nodo no responde
        cliente.connect((ip, int(puerto)))
        cliente.settimeout(None) # Restablecer modo bloqueante normal para comunicación fluida
        
        conexiones_salientes[direccion] = cliente
        print(f"[+] Conectado exitosamente a {direccion}")
        registrar_evento(f"CONEXIÓN SALIENTE EXITOSA -> A {direccion}")
        
        # Enviar mensaje HELLO inicial según protocolo
        mensaje = json.dumps({"type": "HELLO", "node_id": mi_node_id}) + "\n"
        cliente.sendall(mensaje.encode("utf-8"))
        
        print("nodo> ", end="", flush=True)
        return True
        
    except ConnectionRefusedError:
        print(f"[-] Conexión rechazada por {direccion}. El nodo parece inactivo.")
        registrar_evento(f"FALLO CONEXIÓN SALIENTE -> {direccion} (Rechazada / Inactivo)")
        print("nodo> ", end="", flush=True)
        return False
    except Exception as e:
        print(f"[-] Error conectando a {direccion}: {e}")
        registrar_evento(f"FALLO CONEXIÓN SALIENTE -> {direccion} ({e})")
        print("nodo> ", end="", flush=True)
        return False

def enviar_mensaje_a_todos(mensaje_dict):
    """
    Serializa y envía un diccionario en formato JSON (una línea terminada en \n)
    a todos los peers en la lista de conexiones salientes activas.
    """
    mensaje_str = json.dumps(mensaje_dict) + "\n"
    datos_bytes = mensaje_str.encode("utf-8")
    
    for direccion, socket_cliente in list(conexiones_salientes.items()):
        try:
            socket_cliente.sendall(datos_bytes)
        except Exception as e:
            print(f"\n[!] Error enviando a {direccion}: {e}. Desconectando...")
            registrar_evento(f"DESCONEXIÓN SALIENTE -> Error de envío a {direccion}, removiendo socket.")
            try:
                socket_cliente.close()
            except Exception:
                pass
            if direccion in conexiones_salientes:
                del conexiones_salientes[direccion]
