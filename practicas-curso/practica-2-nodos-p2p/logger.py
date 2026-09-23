import datetime
import os

LOG_DIR = 'logs'
BITACORA_FILE = os.path.join(LOG_DIR, 'bitacora.log')

def registrar_evento(evento):
    """
    Registra un evento con marca temporal en el archivo de bitácora.
    """
    timestamp = datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')
    linea = f"[{timestamp}] {evento}\n"
    
    if not os.path.exists(LOG_DIR):
        os.makedirs(LOG_DIR, exist_ok=True)
        
    try:
        with open(BITACORA_FILE, 'a', encoding='utf-8') as f:
            f.write(linea)
    except Exception as e:
        print(f"[!] Error escribiendo en bitácora: {e}")
