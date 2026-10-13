package mx.edu.up.computing.ads;

import java.util.Objects;

/**
 * Anuncio inmutable de un servicio prestado por una célula servidor.
 *
 * El {@link #instanceId} es un UUID generado al arranque del servidor y se mantiene
 * estable durante toda la vida del proceso; nunca se reutiliza la etiqueta CLI.
 * El {@link #expiresAt} es el valor original emitido por el servidor; los GUIs
 * nunca renuevan el lease con su propio reloj (invariante del sistema).
 */
public final class AdsAdvertisement {

    private final String instanceId;
    private final AdsService service;
    private final String nodeId;
    private final String host;
    private final int port;
    private final long createdAt;
    private final long expiresAt;

    public AdsAdvertisement(String instanceId,
                            AdsService service,
                            String nodeId,
                            String host,
                            int port,
                            long createdAt,
                            long expiresAt) {
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.service = Objects.requireNonNull(service, "service");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String instanceId() { return instanceId; }
    public AdsService service() { return service; }
    public String nodeId() { return nodeId; }
    public String host() { return host; }
    public int port() { return port; }
    public long createdAt() { return createdAt; }
    public long expiresAt() { return expiresAt; }

    public boolean isAlive() {
        return System.currentTimeMillis() <= expiresAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AdsAdvertisement that)) return false;
        return instanceId.equals(that.instanceId);
    }

    @Override
    public int hashCode() {
        return instanceId.hashCode();
    }

    @Override
    public String toString() {
        return "AdsAd{" + instanceId + " " + service + " @" + nodeId + " " + host + ":" + port + "}";
    }
}