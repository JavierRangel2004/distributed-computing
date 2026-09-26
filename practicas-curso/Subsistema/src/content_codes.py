# Content Codes comunes de la práctica. Ningún equipo debe cambiarlos por su cuenta.
TEMPERATURA = "TEMPERATURA"
HUMEDAD = "HUMEDAD"
PRESENCIA = "PRESENCIA"
LUMINOSIDAD = "LUMINOSIDAD"
HUMO = "HUMO"
CO2 = "CO2"
NIVEL_AGUA = "NIVEL_AGUA"
CONSUMO_ELECTRICO = "CONSUMO_ELECTRICO"
ESTADO_AIRE = "ESTADO_AIRE"
ESTADO_LUZ = "ESTADO_LUZ"
ESTADO_VENTILACION = "ESTADO_VENTILACION"
ALARMA = "ALARMA"

TODOS = {v for k, v in list(globals().items()) if k.isupper() and isinstance(v, str)}
