import datetime
import os
import threading

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOG_DIR = os.path.join(BASE, "logs")
BITACORA_FILE = os.path.join(LOG_DIR, "bitacora.log")
_lock = threading.Lock()

def registrar_evento(evento):
    """
    Muestra el evento en consola con hora exacta y lo añade también
    al archivo bitacora.log. Es la única fuente de la bitácora, para
    que la captura de pantalla de la consola sea suficiente como entregable.
    """
    timestamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    linea = f"[{timestamp}] {evento}"

    with _lock:
        print(linea)
        if not os.path.exists(LOG_DIR):
            os.makedirs(LOG_DIR, exist_ok=True)
        try:
            with open(BITACORA_FILE, "a", encoding="utf-8") as f:
                f.write(linea + "\n")
        except OSError as e:
            print(f"[!] Error escribiendo en bitácora: {e}")
