import threading
import content_codes as cc
from logger import registrar

class AireAcondicionadoASM:
    """
    Application Software Module: piensa y actúa.
    Simula un actuador de Aire Acondicionado inteligente.
    Si la temperatura es >= umbral (28°C), se enciende (ON).
    Si baja de 28°C, se apaga (OFF).
    Publica ESTADO_AIRE al cambiar de estado.
    """

    def __init__(self, umbral=28.0):
        self.umbral = float(umbral)
        self.aire = "OFF"
        self._lock = threading.Lock()

    def generar(self):
        """Los actuadores no emiten telemetría periódica espontánea."""
        return []

    def procesar(self, mensaje):
        """Recibe únicamente lo que el ACP consideró relevante (TEMPERATURA)."""
        if mensaje["content_code"] == cc.TEMPERATURA:
            try:
                temp = float(mensaje["data"])
            except (ValueError, TypeError):
                return None

            with self._lock:
                nuevo_estado = "ON" if temp >= self.umbral else "OFF"
                cambio = nuevo_estado != self.aire
                self.aire = nuevo_estado

            if cambio:
                registrar("ASM", f"Temperatura {temp}°C detectada -> Aire cambia a {self.aire}")
                print(f"\n[🧠 ASM DECISIÓN] Temp {temp}°C -> Aire Acondicionado pasa a {self.aire}!")
                return [(cc.ESTADO_AIRE, self.aire)]
            else:
                registrar("ASM", f"Temperatura {temp}°C recibida -> Aire se mantiene en {self.aire}")

        return None
