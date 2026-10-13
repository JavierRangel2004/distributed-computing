package mx.edu.up.computing.ads;

import java.util.Objects;

/**
 * Identidad canónica de un nodo vecino en la malla. Su identidad estable es
 * {@link #nodeId}; {@code host} y {@code port} apuntan al ServerSocket del par.
 *
 * Las direcciones se comparan siempre por orden de {@code nodeId} para
 * resolver quién diala y quién acepta sin generar dos túneles paralelos.
 */
public final class AdsPeer {

    private final String nodeId;
    private final String host;
    private final int port;

    public AdsPeer(String nodeId, String host, int port) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
    }

    public String nodeId() { return nodeId; }
    public String host() { return host; }
    public int port() { return port; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AdsPeer other)) return false;
        return port == other.port
                && nodeId.equals(other.nodeId)
                && host.equals(other.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId, host, port);
    }

    @Override
    public String toString() {
        return nodeId + "@" + host + ":" + port;
    }
}