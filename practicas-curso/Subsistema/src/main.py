import json
import os
import sys
import time

# Sin esto, en consolas de Windows con codepage cp1252 los acentos/emojis pueden fallar.
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

import logger
from acp import ACP
from asm_sensor import SensorTemperaturaASM
from asm_aire import AireAcondicionadoASM

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def cargar_configuracion(ruta):
    try:
        with open(ruta, "r", encoding="utf-8") as f:
            cfg = json.load(f)
    except OSError:
        sys.exit(f"[!] No se encontró el archivo de configuración: {ruta}")
    except ValueError:
        sys.exit(f"[!] {ruta} no es un JSON válido.")

    if not isinstance(cfg, dict):
        sys.exit("[!] La configuración debe ser un objeto JSON.")
    if not isinstance(cfg.get("node_id"), str) or not cfg["node_id"].strip():
        sys.exit("[!] Falta 'node_id' (texto) en la configuración.")
    if not isinstance(cfg.get("nodos"), list) or not cfg["nodos"]:
        sys.exit("[!] 'nodos' debe ser una lista no vacía de IP:PUERTO.")
    if any(not isinstance(nodo, str) or not nodo.strip() for nodo in cfg["nodos"]):
        sys.exit("[!] Cada elemento de 'nodos' debe ser texto no vacío.")
    if not isinstance(cfg.get("intereses"), list):
        sys.exit("[!] 'intereses' debe ser una lista de Content Codes.")
    if any(not isinstance(codigo, str) or not codigo.strip() for codigo in cfg["intereses"]):
        sys.exit("[!] Cada elemento de 'intereses' debe ser texto no vacío.")
    intervalo = cfg.get("intervalo_segundos", 3)
    if not isinstance(intervalo, (int, float)) or intervalo <= 0:
        sys.exit("[!] 'intervalo_segundos' debe ser un número mayor que 0.")
    cfg["intervalo_segundos"] = intervalo
    return cfg


if __name__ == "__main__":
    ruta = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.path.join(BASE, "conf", "config.json")
    cfg = cargar_configuracion(ruta)
    logger.configurar(os.path.join(os.path.dirname(os.path.dirname(ruta)), "logs"))

    tipo_sub = cfg.get("tipo", "aire").lower()
    if tipo_sub in ["aire", "actuador", "aire_acondicionado"]:
        asm = AireAcondicionadoASM(umbral=cfg.get("umbral", 28.0))
    else:
        asm = SensorTemperaturaASM()
    acp = ACP(cfg["node_id"], cfg["nodos"], cfg["intereses"], asm)

    logger.registrar("INICIO", f"Subsistema {cfg['node_id']} | nodos={cfg['nodos']} | intereses={cfg['intereses']}")
    acp.iniciar()

    try:
        while True:
            time.sleep(cfg["intervalo_segundos"])
            for codigo, dato in asm.generar():
                acp.publicar(codigo, dato)
    except KeyboardInterrupt:
        pass

    acp.detener()
    logger.registrar("FIN", "Subsistema detenido")
