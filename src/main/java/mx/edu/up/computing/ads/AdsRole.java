package mx.edu.up.computing.ads;

/**
 * Roles visibles en el handshake HELLO del Campo de Datos ADS.
 * Cada conexión entrante a un nodo declara su rol en el primer mensaje.
 */
public enum AdsRole {
    NODE,
    GUI,
    SERVER;

    public static AdsRole fromString(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Rol nulo");
        }
        String upper = raw.trim().toUpperCase();
        return switch (upper) {
            case "NODE" -> NODE;
            case "GUI" -> GUI;
            case "SERVER" -> SERVER;
            default -> throw new IllegalArgumentException("Rol no reconocido: " + raw);
        };
    }
}