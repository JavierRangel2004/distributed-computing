package mx.edu.up.computing.ads;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdsDedupCacheTest {

    @Test
    void firstObservationAdvancesDuplicateDrops() {
        AdsDedup dedup = new AdsDedup();
        String id = UUID.randomUUID().toString();
        assertTrue(dedup.tryRegister(id), "primer mensaje debe avanzar");
        assertFalse(dedup.tryRegister(id), "duplicado debe descartarse");
        assertEquals(1, dedup.forwardCount());
        assertEquals(1, dedup.dropCount());
    }

    @Test
    void purgedEntriesDoNotRevive() {
        AdsDedup dedup = new AdsDedup(50L);
        String id = UUID.randomUUID().toString();
        assertTrue(dedup.tryRegister(id));
        try {
            Thread.sleep(120);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        int purged = dedup.purgeExpired();
        assertEquals(1, purged);
        assertFalse(dedup.alreadySeen(id));
        // Tras purga, el mensaje puede re-entrar (dedup ya no lo recuerda).
        assertTrue(dedup.tryRegister(id), "purgado != vigente; debe poder re-entrar");
    }

    @Test
    void concurrentRegistrationKeepsExactlyOneWinner() throws Exception {
        AdsDedup dedup = new AdsDedup();
        String id = UUID.randomUUID().toString();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(threads);
        int[] wins = new int[1];
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (dedup.tryRegister(id)) {
                        synchronized (wins) {
                            wins[0]++;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finish.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(finish.await(2, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(1, wins[0], "atomicidad: solo un ganador");
        assertEquals(1, dedup.forwardCount());
        assertEquals(threads - 1, dedup.dropCount());
    }
}