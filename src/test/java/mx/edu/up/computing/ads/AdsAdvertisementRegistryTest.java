package mx.edu.up.computing.ads;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdsAdvertisementRegistryTest {

    @Test
    void candidatesExpireByOriginalExpiresAt() {
        AdsAdvertisementRegistry reg = new AdsAdvertisementRegistry();
        long now = System.currentTimeMillis();
        String inst = "srv-uuid-1";
        // Live ad con lease 60 s en el futuro (no expira durante el test).
        AdsAdvertisement liveAd = new AdsAdvertisement(
                inst, AdsService.SUM, "N1", "127.0.0.1", 6101, now, now + 60_000);
        reg.offer(liveAd);

        // Anuncio del MISMO instanceId ya vencido: NO desplaza al live.
        AdsAdvertisement expiredAdSame = new AdsAdvertisement(
                inst, AdsService.SUM, "N1", "127.0.0.1", 6101,
                now - 60_000, now - 1);
        reg.offer(expiredAdSame);

        // El live se conserva con su expiresAt original.
        AdsAdvertisement kept = reg.get(inst);
        assertNotNull(kept);
        assertEquals(now + 60_000, kept.expiresAt(),
                "El live conserva su lease original (no desplazado)");

        // candidatesFor devuelve SOLO el live.
        List<AdsAdvertisement> cands = reg.candidatesFor(AdsService.SUM);
        assertEquals(1, cands.size());
        assertEquals(inst, cands.get(0).instanceId());

        // purgeExpired no elimina el live (no está expirado).
        int purgedLive = reg.purgeExpired();
        assertEquals(0, purgedLive,
                "Live ad (lease 60 s) no debe purgarse");

        // Anuncio vencido de OTRA instanceId: NO aparece en candidatos y
        // SÍ se elimina con purgeExpired.
        AdsAdvertisement expiredOther = new AdsAdvertisement(
                "srv-uuid-2", AdsService.SUM, "N1", "127.0.0.1", 6101,
                now - 60_000, now - 1);
        reg.offer(expiredOther);
        List<AdsAdvertisement> cands2 = reg.candidatesFor(AdsService.SUM);
        assertEquals(1, cands2.size(),
                "El expired de otra instanceId no aparece en candidatos");
        assertEquals(inst, cands2.get(0).instanceId());
        assertEquals(1, reg.purgeExpired(),
                "Expired de otra instanceId se purga");
        // El live sigue presente; solo el expired other se purgeó.
        assertEquals(1, reg.size());
        assertEquals(inst, reg.get(inst).instanceId());
    }

    @Test
    void newerExpiresAtReplacesOlderForSameInstance() {
        AdsAdvertisementRegistry reg = new AdsAdvertisementRegistry();
        String inst = "srv-uuid-2";
        long now = System.currentTimeMillis();
        AdsAdvertisement older = new AdsAdvertisement(
                inst, AdsService.SUM, "N1", "127.0.0.1", 6101, now, now + 4_000);
        AdsAdvertisement newer = new AdsAdvertisement(
                inst, AdsService.SUM, "N1", "127.0.0.1", 6101, now + 1_000, now + 6_000);
        reg.offer(older);
        reg.offer(newer);
        AdsAdvertisement stored = reg.get(inst);
        assertNotNull(stored);
        assertEquals(now + 6_000, stored.expiresAt());
    }

    @Test
    void olderExpiresAtDoesNotOverwriteNewer() {
        AdsAdvertisementRegistry reg = new AdsAdvertisementRegistry();
        String inst = "srv-uuid-3";
        long now = System.currentTimeMillis();
        AdsAdvertisement newer = new AdsAdvertisement(
                inst, AdsService.SUM, "N1", "127.0.0.1", 6101, now, now + 6_000);
        AdsAdvertisement olderLate = new AdsAdvertisement(
                inst, AdsService.SUM, "N1", "127.0.0.1", 6101, now - 2_000, now + 4_000);
        reg.offer(newer);
        reg.offer(olderLate);
        AdsAdvertisement stored = reg.get(inst);
        assertNotNull(stored);
        assertEquals(now + 6_000, stored.expiresAt());
        assertTrue(reg.droppedCount() >= 1);
    }

    @Test
    void candidatesOrderedByInstanceIdAscending() {
        AdsAdvertisementRegistry reg = new AdsAdvertisementRegistry();
        long now = System.currentTimeMillis();
        reg.offer(new AdsAdvertisement("z", AdsService.SUM, "N1", "127.0.0.1", 6101, now, now + 5_000));
        reg.offer(new AdsAdvertisement("a", AdsService.SUM, "N1", "127.0.0.1", 6101, now, now + 5_000));
        reg.offer(new AdsAdvertisement("m", AdsService.SUM, "N1", "127.0.0.1", 6101, now, now + 5_000));
        List<AdsAdvertisement> cands = reg.candidatesFor(AdsService.SUM);
        assertEquals(3, cands.size());
        assertEquals("a", cands.get(0).instanceId());
        assertEquals("m", cands.get(1).instanceId());
        assertEquals("z", cands.get(2).instanceId());
    }

    @Test
    void removeClearsInstance() {
        AdsAdvertisementRegistry reg = new AdsAdvertisementRegistry();
        long now = System.currentTimeMillis();
        AdsAdvertisement ad = new AdsAdvertisement(
                "x", AdsService.SUM, "N1", "127.0.0.1", 6101, now, now + 5_000);
        reg.offer(ad);
        assertNotNull(reg.get("x"));
        reg.remove("x");
        assertNull(reg.get("x"));
    }
}