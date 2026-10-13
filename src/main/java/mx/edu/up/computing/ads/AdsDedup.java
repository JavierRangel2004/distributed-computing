package mx.edu.up.computing.ads;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Registro atómico de IDs de mensaje para deduplicación y prevención de ciclos.
 *
 * Reglas duras:
 * <ol>
 *   <li>Toda marcación se hace con {@link ConcurrentHashMap#putIfAbsent(Object, Object)}
 *       antes de cualquier reenvío o ejecución; garantiza que solo el primer
 *       observador avanza.</li>
 *   <li>La expiración lógica del mensaje (expiresAt del sobre) se valida antes de
 *       tocar la memoria: un sobre expirado se descarta sin re-ingresar al dedup.</li>
 *   <li>Las entradas del cache se purgan después de {@link AdsConstants#DEDUP_TTL_MS}
 *       desde su primera inserción, pero un ID expulsado no revive aunque su
 *       sobre aún parezca vigente.</li>
 * </ol>
 *
 * La métrica de observabilidad queda disponible para SmokeMain y logs.
 */
public final class AdsDedup {

    private final long ttlMs;
    private final Map<String, Long> seenAt = new ConcurrentHashMap<>();
    private final AtomicLong drops = new AtomicLong();
    private final AtomicLong forwards = new AtomicLong();
    private final AtomicLong expirations = new AtomicLong();

    public AdsDedup() {
        this(AdsConstants.DEDUP_TTL_MS);
    }

    public AdsDedup(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    /**
     * Intenta registrar el mensaje antes de procesarlo o reenviarlo.
     *
     * @return {@code true} si es la primera vez que se observa el ID y debe avanzar.
     *         {@code false} si ya estaba registrado (DROP_DUPLICATE).
     */
    public boolean tryRegister(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long prev = seenAt.putIfAbsent(messageId, now);
        if (prev != null) {
            drops.incrementAndGet();
            return false;
        }
        forwards.incrementAndGet();
        return true;
    }

    /**
     * ¿El mensaje ya fue visto? Solo se usa en instrumentación; el control real es
     * {@link #tryRegister(String)}.
     */
    public boolean alreadySeen(String messageId) {
        if (messageId == null) {
            return false;
        }
        return seenAt.containsKey(messageId);
    }

    /**
     * Purga entradas más viejas que el TTL configurado. Llamar periódicamente.
     *
     * @return número de entradas purgadas en esta pasada.
     */
    public int purgeExpired() {
        long cutoff = System.currentTimeMillis() - ttlMs;
        int purged = 0;
        for (Map.Entry<String, Long> e : seenAt.entrySet()) {
            if (e.getValue() < cutoff) {
                if (seenAt.remove(e.getKey(), e.getValue())) {
                    purged++;
                }
            }
        }
        return purged;
    }

    /**
     * Registra que un mensaje fue descartado por estar expirado al llegar.
     */
    public void recordExpired() {
        expirations.incrementAndGet();
    }

    public long dropCount() {
        return drops.get();
    }

    public long forwardCount() {
        return forwards.get();
    }

    public long expiredCount() {
        return expirations.get();
    }

    public int size() {
        return seenAt.size();
    }
}