package mx.edu.up.computing.ads;

/**
 * Constantes operativas del Campo de Datos.
 * Las cifras son ajustables, pero sus valores por defecto son los asumidos por
 * la demo y por las pruebas automatizadas.
 */
public final class AdsConstants {

    /** Vida nominal de un mensaje en milisegundos (expiración inmutable en cada salto). */
    public static final long MESSAGE_TTL_MS = 30_000L;

    /** Retención del registro de deduplicación (no revive aunque su sobre haya expirado). */
    public static final long DEDUP_TTL_MS = 60_000L;

    /** Intervalo entre anuncios periódicos de cada célula servidor. */
    public static final long ADVERTISE_INTERVAL_MS = 2_000L;

    /** Lease del anuncio: el GUI descarta candidatos con {@code expiresAt} vencido. */
    public static final long ADVERTISE_LEASE_MS = 6_000L;

    /** Timeout visible para una solicitud en vuelo (sin reasignación). */
    public static final long REQUEST_TIMEOUT_MS = 10_000L;

    /** Intervalo de reintento de enlace dial entre nodos cuando se pierde la conexión. */
    public static final long PEER_RECONNECT_MS = 1_500L;

    /** Directorio aislado del parcial 1; nunca se mezclan historiales. */
    public static final String DATA_ROOT = "data/ads";

    private AdsConstants() {
    }
}