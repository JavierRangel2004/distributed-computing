package mx.edu.up.computing.ads;

/**
 * Tipos de mensaje del protocolo ADS.
 * HELLO_*: declaración de rol (NODE, GUI, SERVER) y metadatos de identidad.
 * ADVERTISE: anuncio periódico del servicio prestado por una célula servidor
 *   con UUID único de instancia por arranque y expiración original.
 * SUM_REQ/SUM_RES, SUB_*, MUL_*, DIV_*: códigos de contenido del PDF oficial.
 * GOODBYE: cierre ordenado de una conexión.
 */
public enum AdsMessageType {
    HELLO_NODE,
    HELLO_GUI,
    HELLO_SERVER,
    HELLO_ACK,
    ADVERTISE,
    SUM_REQ,
    SUM_RES,
    SUB_REQ,
    SUB_RES,
    MUL_REQ,
    MUL_RES,
    DIV_REQ,
    DIV_RES,
    GOODBYE,
    ERROR;

    public static AdsMessageType fromString(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Tipo nulo");
        }
        try {
            return AdsMessageType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Tipo no reconocido: " + raw, e);
        }
    }
}