package mx.edu.up.computing.common;

/**
 * Tipos de mensajes del protocolo de sockets de la calculadora distribuida.
 */
public enum MessageType {
    REGISTER,        // Cliente o Servidor se identifica ante el Middleware
    REGISTER_ACK,    // Middleware confirma registro exitoso
    CALCULATE_REQ,   // Petición de cálculo (Cliente -> Middleware -> Servidores)
    CALCULATE_RES,   // Respuesta de cálculo (Servidor -> Middleware -> Clientes)
    HEARTBEAT,       // Comprobación de estado
    ERROR            // Notificación de error en transporte o protocolo
}
