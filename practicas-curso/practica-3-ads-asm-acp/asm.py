"""
asm.py - Application Software Module (ASM) para ADS.
"ASM piensa y actúa; ACP comunica y filtra".

Contiene exclusivamente la lógica local del subsistema:
- Mantiene el estado interno sin depender de un servidor central.
- Interpreta los datos entregados por el ACP.
- Decide autónomamente si debe cambiar su estado y emitir nuevo contenido.
"""

from content_codes import (
    TEMPERATURA,
    ESTADO_AIRE,
    HUMEDAD,
    PRESENCIA,
    LUMINOSIDAD,
    HUMO,
    CO2,
    ALARMA,
    ESTADO_LUZ,
    ESTADO_VENTILACION,
)

class AireAcondicionadoASM:
    """
    Subsistema Actuador: Climatización / Aire Acondicionado inteligente.
    - Interés primario: TEMPERATURA
    - Produce: ESTADO_AIRE ('ON' / 'OFF')
    - Regla local: Si TEMPERATURA >= umbral -> 'ON', caso contrario -> 'OFF'.
    """

    def __init__(self, umbral_temperatura=28.0):
        self.umbral = float(umbral_temperatura)
        self.estado = "OFF"
        self.ultima_temperatura = None
        self.total_decisiones = 0

    def procesar(self, mensaje):
        """
        Procesa un mensaje que el ACP ya filtró y validó como relevante.
        Retorna una tupla (content_code, nuevo_valor) si se genera nueva información,
        o None si no hubo cambio de estado que deba publicarse.
        """
        codigo = mensaje.get("content_code")
        dato = mensaje.get("data")

        if codigo == TEMPERATURA:
            try:
                temp_val = float(dato)
            except (ValueError, TypeError):
                return None

            self.ultima_temperatura = temp_val
            nuevo_estado = "ON" if temp_val >= self.umbral else "OFF"
            self.total_decisiones += 1

            if nuevo_estado != self.estado:
                anterior = self.estado
                self.estado = nuevo_estado
                print(f"\n[🧠 ASM DECISIÓN] Temperatura {temp_val}°C detectada (Umbral: {self.umbral}°C).")
                print(f"    Aire Acondicionado cambió estado: {anterior} -> {self.estado} (Publicando a la malla)")
                return (ESTADO_AIRE, self.estado)
            else:
                print(f"\n[🧠 ASM INFO] Temperatura {temp_val}°C recibida. Estado se mantiene en {self.estado}.")
                return None

        return None

    def obtener_estado(self):
        """Devuelve un diccionario representativo del estado local del subsistema."""
        return {
            "subsistema": "Aire Acondicionado (Actuador)",
            "estado_actual": self.estado,
            "umbral_temperatura": self.umbral,
            "ultima_temperatura_registrada": self.ultima_temperatura,
            "decisiones_tomadas": self.total_decisiones,
        }
