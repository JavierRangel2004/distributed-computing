"""
content_codes.py - Catálogo común de Content Codes para la Práctica 3 (ADS).
Define los tipos semánticos de datos transmitidos en el Campo de Datos.
"""

# Sensores y variables ambientales
TEMPERATURA = "TEMPERATURA"
HUMEDAD = "HUMEDAD"
PRESENCIA = "PRESENCIA"
LUMINOSIDAD = "LUMINOSIDAD"
HUMO = "HUMO"
CO2 = "CO2"
NIVEL_AGUA = "NIVEL_AGUA"
CONSUMO_ELECTRICO = "CONSUMO_ELECTRICO"

# Estados de actuadores y subsistemas
ESTADO_AIRE = "ESTADO_AIRE"
ESTADO_LUZ = "ESTADO_LUZ"
ESTADO_VENTILACION = "ESTADO_VENTILACION"
ESTADO_BOMBA = "ESTADO_BOMBA"
MODO_AHORRO = "MODO_AHORRO"
ALARMA = "ALARMA"

# Conjunto de todos los códigos reconocidos
CATALOGO_OFICIAL = {
    TEMPERATURA,
    HUMEDAD,
    PRESENCIA,
    LUMINOSIDAD,
    HUMO,
    CO2,
    NIVEL_AGUA,
    CONSUMO_ELECTRICO,
    ESTADO_AIRE,
    ESTADO_LUZ,
    ESTADO_VENTILACION,
    ESTADO_BOMBA,
    MODO_AHORRO,
    ALARMA,
}
