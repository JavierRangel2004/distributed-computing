package mx.edu.up.computing.ads;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Registro distribuido de anuncios de servicio observado por un cliente GUI.
 *
 * Reglas:
 * <ul>
 *   <li>Cada anuncio reemplaza al anterior con el mismo {@code instanceId}.</li>
 *   <li>El lease se respeta a partir del {@code expiresAt} original; una
 *       entrega tardía no extiende el lease del anuncio anterior.</li>
 *   <li>{@link #candidatesFor(AdsService)} entrega candidatos vivos ordenados
 *       por {@code instanceId} ascendente (orden estable, evita empates).</li>
 * </ul>
 */
public final class AdsAdvertisementRegistry {

    private final Map<String, AdsAdvertisement> byInstance = new ConcurrentHashMap<>();
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    public void offer(AdsAdvertisement ad) {
        if (ad == null) {
            return;
        }
        received.incrementAndGet();
        AdsAdvertisement current = byInstance.get(ad.instanceId());
        if (current == null || current.expiresAt() < ad.expiresAt()) {
            byInstance.put(ad.instanceId(), ad);
        } else {
            dropped.incrementAndGet();
        }
    }

    public void remove(String instanceId) {
        if (instanceId != null) {
            byInstance.remove(instanceId);
        }
    }

    public AdsAdvertisement get(String instanceId) {
        return instanceId == null ? null : byInstance.get(instanceId);
    }

    public List<AdsAdvertisement> candidatesFor(AdsService service) {
        long now = System.currentTimeMillis();
        List<AdsAdvertisement> live = new ArrayList<>();
        for (AdsAdvertisement ad : byInstance.values()) {
            if (ad.service() == service && ad.expiresAt() > now) {
                live.add(ad);
            }
        }
        live.sort(Comparator.comparing(AdsAdvertisement::instanceId));
        return live;
    }

    public int purgeExpired() {
        long now = System.currentTimeMillis();
        int purged = 0;
        for (Map.Entry<String, AdsAdvertisement> e : byInstance.entrySet()) {
            if (e.getValue().expiresAt() <= now) {
                if (byInstance.remove(e.getKey(), e.getValue())) {
                    purged++;
                }
            }
        }
        return purged;
    }

    public int size() {
        return byInstance.size();
    }

    public long receivedCount() { return received.get(); }
    public long droppedCount() { return dropped.get(); }
}