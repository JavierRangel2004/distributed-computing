import datetime
import os

LOG_DIR = 'logs'
BITACORA_FILE = os.path.join(LOG_DIR, 'bitacora.log')

def registrar_evento(etiqueta, detalle):
    """
    Registra un evento con marca temporal y etiqueta semántica ADS
    (RX, USE, IGNORE, TX, FWD, DROP, SYS) en logs/bitacora.log.
    """
    timestamp = datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')
    linea = f"[{timestamp}] [{etiqueta.upper():<6}] {detalle}\n"
    
    if not os.path.exists(LOG_DIR):
        os.makedirs(LOG_DIR, exist_ok=True)
        
    try:
        with open(BITACORA_FILE, 'a', encoding='utf-8') as f:
            f.write(linea)
    except Exception as e:
        print(f"[!] Error escribiendo en bitácora: {e}")
