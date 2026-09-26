import datetime
import os
import threading

_lock = threading.Lock()
_archivo = None

def configurar(directorio_logs):
    global _archivo
    os.makedirs(directorio_logs, exist_ok=True)
    _archivo = os.path.join(directorio_logs, "bitacora.log")

def registrar(evento, detalle=""):
    """
    Eventos usados: CONN, DISCONN, RX, USE, IGNORE, TX, DROP, ASM, WARN, ERROR.
    Se imprime en consola y se guarda en logs/bitacora.log.
    """
    hora = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    linea = f"[{hora}] {evento:<7} | {detalle}"
    with _lock:
        print(linea, flush=True)
        if _archivo:
            try:
                with open(_archivo, "a", encoding="utf-8") as f:
                    f.write(linea + "\n")
            except OSError as e:
                print(f"[!] No se pudo escribir la bitácora: {e}", flush=True)
