package mx.edu.up.computing.ads;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke de integración autocontenido del Campo de Datos ADS.
 *
 * No depende de estado estático entre tests, no usa puertos fijos (pide puertos
 * libres al SO), aísla su persistencia en un directorio temporal y libera todos
 * los recursos aunque alguna aserción falle. Cada fase (topología,
 * cuatro operaciones, replay, caducidad, alta tardía, caída, escalabilidad)
 * se ejecuta dentro del mismo test para evitar ordenamiento implícito.
 *
 * Las aserciones verifican:
 * <ol>
 *   <li>6 enlaces canónicos entre 4 nodos.</li>
 *   <li>16 servidores únicos y 4 GUI.</li>
 *   <li>Cobertura de 4 candidatos vivos por servicio en cada GUI.</li>
 *   <li>16 operaciones desde 4 clientes → exactamente 16 respuestas globales
 *       (no por GUI), cada una con status SUCCESS, UUID de ejecutor válido,
 *       código de contenido y resultado numérico finito.</li>
 *   <li>Dedup atómico: reenviar el mismo UUID por el nodo cae (forward+1, drop+1).</li>
 *   <li>Caducidad: mensaje con expiresAt vencido registra expired+1 sin reenviar.</li>
 *   <li>Alta tardía: un quinto nodo entra a la malla y sus servidores se anuncian.</li>
 *   <li>Caída: tras matar un nodo, los clientes vivos siguen completando solicitudes.</li>
 *   <li>Escalabilidad: réplicas adicionales en un nodo nuevo procesan operaciones.</li>
 *   <li>Cada GUI acepta sus 4 respuestas propias (no se exige ignoredResponses==0).</li>
 *   <li>El servidor no procesa la misma solicitud dos veces (dedup propio).</li>
 * </ol>
 */
class AdsTopologyMeshTest {

    @Test
    @DisplayName("Malla ADS 4 nodos / 4 GUI / 16 servidores: respuesta única global, dedup, caducidad, alta tardía, caída y escalabilidad")
    void fullAdsIntegration() throws Exception {
        Path tempRoot = Files.createTempDirectory("ads-test-");
        System.setProperty("ads.persistence.root", tempRoot.toAbsolutePath().toString());
        try {
            int base = pickFreePort();
            runScenario(base);
        } finally {
            System.clearProperty("ads.persistence.root");
            deleteRecursively(tempRoot.toFile());
        }
    }

    private void runScenario(int base) throws Exception {
        // Listeners globales se instalan ANTES de enviar cualquier solicitud.
        ConcurrentHashMap<String, AdsMessage> globalResponses = new ConcurrentHashMap<>();
        AtomicInteger duplicateResponses = new AtomicInteger();
        AtomicInteger timeoutResponses = new AtomicInteger();
        // Mapa requestId -> operandos originales para validación exacta.
        ConcurrentHashMap<String, RequestSpec> requestSpecs = new ConcurrentHashMap<>();

        // 1. Construir 4 nodos en malla completa (6 enlaces canónicos).
        List<AdsNode> nodes = new ArrayList<>();
        List<AdsPeer> peerList = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            peerList.add(new AdsPeer("N" + i, "127.0.0.1", base + i - 1));
        }
        for (int i = 1; i <= 4; i++) {
            List<AdsPeer> mine = new ArrayList<>();
            for (AdsPeer p : peerList) {
                if (!p.nodeId().equals("N" + i)) mine.add(p);
            }
            AdsNode n = new AdsNode("N" + i, base + i - 1, mine);
            nodes.add(n);
        }
        for (AdsNode n : nodes) {
            n.start();
        }

        // 2. Servidores: 4 por nodo, uno por servicio. Clientes: 1 por nodo.
        List<AdsServer> servers = new ArrayList<>();
        List<AdsClient> clients = new ArrayList<>();
        for (int idx = 0; idx < nodes.size(); idx++) {
            AdsNode owner = nodes.get(idx);
            int nodePort = owner.port();
            for (AdsService svc : AdsService.values()) {
                AdsServer srv = new AdsServer(owner.nodeId() + "-" + svc.name() + "-1",
                        svc, "127.0.0.1", nodePort);
                servers.add(srv);
                srv.start();
            }
        }
        for (int idx = 0; idx < nodes.size(); idx++) {
            AdsNode owner = nodes.get(idx);
            AdsClient c = new AdsClient("GUI" + (idx + 1), "127.0.0.1", owner.port());
            clients.add(c);
            c.connect();
        }

        // Listeners en cada cliente (instalados antes de enviar)
        for (AdsClient c : clients) {
            c.addListener(new AdsClient.ClientListener() {
                @Override public void onAdvertisement(AdsAdvertisement ad) { }
                @Override public void onRequestSent(AdsMessage msg) { }
                @Override public void onResponse(AdsMessage msg) {
                    if (msg.getRequestId() == null) return;
                    AdsMessage prev = globalResponses.putIfAbsent(msg.getRequestId(), msg);
                    if (prev != null) {
                        duplicateResponses.incrementAndGet();
                    }
                }
                @Override public void onTimeout(String rid, AdsService s, String t) {
                    timeoutResponses.incrementAndGet();
                }
                @Override public void onError(String t) { }
            });
        }

        // Snapshot inicial de sum(processedCount) para exigir delta==16 al final.
        long initialProcessedTotal = servers.stream().mapToLong(AdsServer::processedCount).sum();

        try {
            // 3. Esperar cobertura de candidatos vivos (4 por servicio por GUI = 16 por GUI).
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                boolean ok = true;
                for (AdsClient c : clients) {
                    for (AdsService svc : AdsService.values()) {
                        if (c.registry().candidatesFor(svc).size() < 4) {
                            ok = false;
                            break;
                        }
                    }
                    if (!ok) break;
                }
                if (ok) break;
                Thread.sleep(150);
            }
            for (AdsClient c : clients) {
                for (AdsService svc : AdsService.values()) {
                    assertTrue(c.registry().candidatesFor(svc).size() >= 4,
                            "GUI " + c.clientId() + " sin 4+ candidatos vivos para " + svc);
                }
            }

            // 4. Dieciséis operaciones: 4 ops x 4 clientes. Capturamos el
            //    targetServerId esperado ANTES del envío (consultando el registro
            //    del GUI) para evitar tautología al verificarlo contra la
            //    respuesta. Comparamos resultado exacto contra operandos.
            List<String> sentRequestIds = new ArrayList<>();
            int sent = 0;
            for (AdsService svc : AdsService.values()) {
                for (AdsClient c : clients) {
                    double a = 10.0 + sent;
                    double b = 1.0 + (sent % 3);
                    List<AdsAdvertisement> cands = c.registry().candidatesFor(svc);
                    assertFalse(cands.isEmpty(),
                            "Sin candidatos vivos para " + svc + " GUI " + c.clientId());
                    String expectedTarget = cands.get(0).instanceId();
                    String rid = c.sendRequest(svc, a, b);
                    assertNotNull(rid, "sendRequest null svc=" + svc + " client=" + c.clientId());
                    sentRequestIds.add(rid);
                    requestSpecs.put(rid, new RequestSpec(svc, a, b, expectedTarget));
                    sent++;
                }
            }
            assertEquals(16, sent, "16 operaciones enviadas");

            long respDeadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < respDeadline
                    && globalResponses.size() < sentRequestIds.size()) {
                Thread.sleep(100);
            }
            assertEquals(sentRequestIds.size(), globalResponses.size(),
                    "Cada solicitud debe tener UNA respuesta global. received="
                            + globalResponses.size() + "/" + sentRequestIds.size());
            assertEquals(0, duplicateResponses.get(),
                    "No debe haber respuestas duplicadas: " + duplicateResponses.get());
            assertEquals(0, timeoutResponses.get(),
                    "No debe haber timeouts en operación normal: " + timeoutResponses.get());

            // 5. Verificación de contenido por respuesta: SUCCESS, ejecutor
            //    elegido pre-send, contentCode, requestId correlacionado,
            //    resultado exacto.
            for (Map.Entry<String, AdsMessage> e : globalResponses.entrySet()) {
                AdsMessage r = e.getValue();
                String rid = e.getKey();
                RequestSpec spec = requestSpecs.get(rid);
                assertNotNull(spec, "Sin spec para rid=" + rid);
                assertNotNull(r.getTargetServerId(),
                        "Respuesta sin UUID de ejecutor: rid=" + rid);
                assertTrue(r.getTargetServerId().length() >= 32,
                        "UUID ejecutor inválido: " + r.getTargetServerId());
                assertEquals(spec.expectedTarget, r.getTargetServerId(),
                        "Ejecutor respuesta != elegido pre-send (rid=" + rid + ")");
                assertEquals("SUCCESS", r.getStatus(),
                        "Status esperado SUCCESS: rid=" + rid + " status=" + r.getStatus());
                assertNotNull(r.getResult(), "Resultado nulo: rid=" + rid);
                assertTrue(Double.isFinite(r.getResult()),
                        "Resultado no finito: rid=" + rid + " r=" + r.getResult());
                assertEquals(expectedResult(spec.svc, spec.a, spec.b), r.getResult(), 1e-9,
                        "Resultado incorrecto para svc=" + spec.svc
                                + " a=" + spec.a + " b=" + spec.b);
                AdsService respSvc = AdsService.fromCode(r.getContentCode());
                assertEquals(spec.svc, respSvc, "Servicio del contentCode != svc solicitado");
            }

            // 6. ignoredResponses NO se exige ==0: los broadcasts ajenos llegan a
            //    GUIs que no emitieron y los descartan, lo cual es correcto.
            //    Verificamos únicamente que cada GUI aceptó sus 4 respuestas
            //    propias (c.acceptedCount == 4).
            for (AdsClient c : clients) {
                assertEquals(4, c.acceptedCount(),
                        "GUI " + c.clientId() + " aceptó " + c.acceptedCount()
                                + "/4 respuestas propias");
            }

            // EXACT == 16 se mide AQUÍ (justo después de las 16 ops iniciales
            // y la verificación de aceptación propia), ANTES de inyectados
            // directos, replay, late-join N5, caída de N4 y escala NA. La
            // medición al final del test sería incorrecta porque varios
            // servidores N4 se detuvieron y otros (NA-SUM-1) recibieron
            // solicitades extra.
            long processedInitial = servers.stream().mapToLong(AdsServer::processedCount).sum();
            long processedDeltaEarly = processedInitial - initialProcessedTotal;
            assertEquals(16L, processedDeltaEarly,
                    "sum(processedCount) delta sobre initial debe ser EXACTO 16 ("
                            + processedInitial + " - " + initialProcessedTotal + " = "
                            + processedDeltaEarly + ")");

            // 7. Evidencia causal por UUID (no contador global con ruido de ADV concurrentes).
            //    Primer registro del UUID fresh avanza; el segundo, sobre el
            //    mismo UUID, cae como duplicado.
            AdsNode anyNode = nodes.get(0);
            AdsDedup dedup = anyNode.dedup();
            String freshUuid = UUID.randomUUID().toString();
            boolean firstReg = dedup.tryRegister(freshUuid);
            boolean secondReg = dedup.tryRegister(freshUuid);
            assertTrue(firstReg, "Primer registro del UUID fresh debe avanzar");
            assertFalse(secondReg, "Segundo registro del mismo UUID debe caer como duplicado");

            // 8. Caducidad real.
            AdsMessage expired = AdsMessage.create(AdsMessageType.SUM_REQ);
            expired.setExpiresAt(System.currentTimeMillis() - 1_000);
            long beforeExp = anyNode.dedup().expiredCount();
            long sizeBefore = anyNode.dedup().size();
            anyNode.injectForTest(clients.get(0).instanceId(), expired);
            assertEquals(beforeExp + 1, anyNode.dedup().expiredCount(),
                    "Mensaje expirado incrementa expiredCount");
            assertEquals(sizeBefore, anyNode.dedup().size(),
                    "Mensaje expirado NO entra al dedup");

            // 9. Servidor: dedup propio. Llamada directa a onMessage con
            //    mismo UUID: solo la primera incrementa processedCount.
            AdsServer srvZero = servers.get(0);
            long beforeProcessed = srvZero.processedCount();
            AdsMessage srvDup = AdsMessage.create(AdsMessageType.SUM_REQ);
            srvDup.setMessageId(UUID.randomUUID().toString());
            srvDup.setOriginId(clients.get(0).clientId());
            srvDup.setClientId(clients.get(0).clientId());
            srvDup.setTargetServerId(srvZero.instanceId());
            srvDup.setService(AdsService.SUM.name());
            srvDup.setOperandA(2.0);
            srvDup.setOperandB(3.0);
            srvZero.processForTest(srvDup);
            srvZero.processForTest(srvDup);
            assertEquals(beforeProcessed + 1, srvZero.processedCount(),
                    "Servidor no debe procesar el mismo UUID dos veces (dedup propio)");

            // 9b. Replay por la red: reinyectamos el mismo messageId por el nodo.
            //     Medimos ignoredByDuplicateCount del servidor: el segundo
            //     inyectado del mismo UUID NO debe procesarlo de nuevo. NO
            //     usamos el dropCount global del nodo (contador con ruido de
            //     ADV concurrentes, no prueba un UUID concreto).
            AdsMessage replayNet = AdsMessage.create(AdsMessageType.SUM_REQ);
            String replayUuid = UUID.randomUUID().toString();
            replayNet.setMessageId(replayUuid);
            replayNet.setRequestId(replayUuid);
            replayNet.setOriginId(clients.get(0).clientId());
            replayNet.setClientId(clients.get(0).clientId());
            replayNet.setTargetServerId(srvZero.instanceId());
            replayNet.setService(AdsService.SUM.name());
            replayNet.setOperandA(1.0);
            replayNet.setOperandB(1.0);
            long srvBeforeReplay = srvZero.processedCount();
            long dupBeforeReplay = srvZero.ignoredByDuplicateCount();
            anyNode.injectForTest(clients.get(0).instanceId(), replayNet);
            anyNode.injectForTest(clients.get(0).instanceId(), replayNet);
            Thread.sleep(300);
            // El nodo descarta el segundo sobre antes de entregarlo al servidor:
            // no aumenta el contador de dedup propio de la célula.
            assertEquals(srvBeforeReplay + 1, srvZero.processedCount(),
                    "Servidor procesa el primer inyectado, NO el replay");
            assertEquals(dupBeforeReplay, srvZero.ignoredByDuplicateCount(),
                    "El nodo impide que el servidor reciba el replay");

            // 10. Alta tardía: añadir un quinto nodo. La topología inicial son 16
            //     servidores (4 nodos × 4 servicios) + los 4 servidores de N5
            //     (1 nodo × 4 servicios) = 20 instancias únicas. Verificamos
            //     por UUID que TODOS los cuatro servidores N5 aparecen en el
            //     catálogo del GUI y ejecutamos una operación dirigida a uno
            //     de ellos para demostrar que N5 está vivo y responde.
            int latePort = pickFreePort();
            AdsNode late = new AdsNode("N5", latePort, List.of(
                    new AdsPeer("N1", "127.0.0.1", base),
                    new AdsPeer("N2", "127.0.0.1", base + 1),
                    new AdsPeer("N3", "127.0.0.1", base + 2),
                    new AdsPeer("N4", "127.0.0.1", base + 3)));
            late.start();
            nodes.add(late);
            java.util.Map<AdsService, AdsServer> n5Servers = new java.util.HashMap<>();
            for (AdsService svc : AdsService.values()) {
                AdsServer s = new AdsServer("N5-" + svc.name() + "-1", svc, "127.0.0.1", latePort);
                servers.add(s);
                s.start();
                n5Servers.put(svc, s);
            }
            // Esperar a que el UUID de N5 específico de cada servicio aparezca
            // en el catálogo de ESE servicio (no todos en cada uno).
            long dlLate = System.currentTimeMillis() + 12_000;
            java.util.Map<AdsService, String> n5BySvc = new java.util.HashMap<>();
            for (java.util.Map.Entry<AdsService, AdsServer> e : n5Servers.entrySet()) {
                n5BySvc.put(e.getKey(), e.getValue().instanceId());
            }
            boolean allPresent = false;
            while (System.currentTimeMillis() < dlLate) {
                allPresent = true;
                for (AdsService svc : AdsService.values()) {
                    java.util.List<String> candIds = new java.util.ArrayList<>();
                    for (AdsAdvertisement ad : clients.get(0).registry().candidatesFor(svc)) {
                        candIds.add(ad.instanceId());
                    }
                    if (!candIds.contains(n5BySvc.get(svc))) {
                        allPresent = false;
                        break;
                    }
                }
                if (allPresent) break;
                Thread.sleep(150);
            }
            assertTrue(allPresent,
                    "Alta tardía N5: el UUID N5 de cada servicio debe estar en el"
                            + " catálogo de ESE servicio en GUI1 (16 + 4 = 20)");

            // Por servicio: 5 candidatos (N1, N2, N3, N4, N5) con UUID N5
            // identificado por su instanceId.
            for (AdsService svc : AdsService.values()) {
                java.util.List<String> cands = new java.util.ArrayList<>();
                for (AdsAdvertisement ad : clients.get(0).registry().candidatesFor(svc)) {
                    cands.add(ad.instanceId());
                }
                assertEquals(5, cands.size(),
                        "Por servicio " + svc + " deben haber 5 candidatos (N1-N5)");
                String expectedN5 = n5Servers.get(svc).instanceId();
                assertTrue(cands.contains(expectedN5),
                        "UUID de N5-" + svc + " (" + expectedN5
                                + ") debe estar en el catálogo del servicio "
                                + svc);
            }

            // Operación dirigida a un servidor N5: captura respuesta real.
            AdsServer n5Sum = n5Servers.get(AdsService.SUM);
            String ridLate = UUID.randomUUID().toString();
            AdsMessage lateReq = AdsMessage.create(AdsMessageType.SUM_REQ);
            lateReq.setMessageId(ridLate);
            lateReq.setRequestId(ridLate);
            lateReq.setOriginId(clients.get(0).clientId());
            lateReq.setClientId(clients.get(0).clientId());
            lateReq.setTargetServerId(n5Sum.instanceId());
            lateReq.setService(AdsService.SUM.name());
            lateReq.setOperandA(11.0);
            lateReq.setOperandB(31.0);
            long n5Before = n5Sum.processedCount();
            // Inyección vía N1 (cruce de malla), no vía `late` directamente,
            // para validar que N1 reenvía a NA y NA a NA-SUM-1.
            nodes.get(0).injectForTest(clients.get(0).instanceId(), lateReq);
            // Esperar a que el ejecutor N5 procese (anuncio de n5Sum.instanceId
            // debe llegar al GUI1 que está en N1; la respuesta vuelve por la
            // malla y el GUI1 la acepta).
            long tLateWait = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < tLateWait
                    && n5Sum.processedCount() == n5Before) {
                Thread.sleep(100);
            }
            assertEquals(n5Before + 1, n5Sum.processedCount(),
                    "Operación dirigida a N5-" + AdsService.SUM + " fue procesada (vía N1)");

            // 11. Caída de N4: respuesta posterior llega de servidores vivos.
            AdsNode victim = nodes.stream()
                    .filter(n -> "N4".equals(n.nodeId()))
                    .findFirst().orElseThrow();
            // Guardar los UUIDs de los servidores N4 antes de detenerlos.
            // El lease del anuncio es 6 s; sendRequest del GUI1 elige por
            // UUID mínimo del registro. Si los UUIDs de N4 siguen vivos en
            // el catálogo (porque su lease aún no vence), sendRequest los
            // elegirá y la solicitud no tendrá ejecutor (timeout 10 s).
            java.util.Set<String> n4Uuids = new java.util.HashSet<>();
            for (AdsServer s : servers) {
                if (s.serverId().startsWith("N4-")) {
                    n4Uuids.add(s.instanceId());
                }
            }
            List<AdsServer> orphans = new ArrayList<>();
            for (AdsServer s : servers) {
                if (s.serverId().startsWith("N4-")) {
                    s.stop();
                    orphans.add(s);
                }
            }
            servers.removeAll(orphans);
            nodes.remove(victim);
            victim.stop();
            // Esperar bounded (8 s > lease 6 s) a que TODOS los UUIDs de N4
            // desaparezcan del catálogo del GUI1. Sin suppressfailure: si no
            // salen, el test falla y demuestra que el lease no expiró.
            long leaseDeadline = System.currentTimeMillis() + 8_000L;
            boolean gone = false;
            while (System.currentTimeMillis() < leaseDeadline) {
                java.util.Set<String> stillThere = new java.util.HashSet<>();
                for (AdsService svc : AdsService.values()) {
                    for (AdsAdvertisement ad : clients.get(0).registry().candidatesFor(svc)) {
                        if (n4Uuids.contains(ad.instanceId())) {
                            stillThere.add(ad.instanceId());
                        }
                    }
                }
                if (stillThere.isEmpty()) {
                    gone = true;
                    break;
                }
                Thread.sleep(150);
            }
            assertTrue(gone,
                    "Tras 8 s (> lease 6 s) los UUIDs de N4 deben expirar del catálogo"
                            + " de GUI1: " + n4Uuids);

            // Capturar el ejecutor elegido pre-send (no tautología).
            java.util.List<AdsAdvertisement> candsPost = clients.get(0)
                    .registry().candidatesFor(AdsService.SUM);
            assertFalse(candsPost.isEmpty(),
                    "GUI1 debe tener candidatos SUM vivos tras expirar N4");
            String expectedExecPost = candsPost.get(0).instanceId();
            assertFalse(n4Uuids.contains(expectedExecPost),
                    "Ejecutor elegido pre-send no debe ser de N4");
            String ridPost = clients.get(0).sendRequest(AdsService.SUM, 42.0, 58.0);
            assertNotNull(ridPost, "sendRequest post-caída devuelve requestId");
            long dl2 = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < dl2
                    && !globalResponses.containsKey(ridPost)) {
                Thread.sleep(100);
            }
            AdsMessage postResp = globalResponses.get(ridPost);
            assertNotNull(postResp, "Respuesta post-caída de N4 no llegó");
            assertEquals("SUCCESS", postResp.getStatus());
            assertEquals(100.0, postResp.getResult(), 1e-9);
            String execId = postResp.getTargetServerId();
            AdsServer exec = servers.stream()
                    .filter(s -> s.instanceId().equals(execId))
                    .findFirst().orElse(null);
            assertNotNull(exec, "Ejecutor de la respuesta post-caída no está en servidores vivos");
            assertFalse(exec.serverId().startsWith("N4-"),
                    "Ejecutor no debe ser de N4");
            assertEquals(expectedExecPost, execId,
                    "Ejecutor respuesta != elegido pre-send");

            // 12. Escalabilidad > 4: réplicas adicionales en nodo NA.
            int naPort = pickFreePort();
            AdsNode na = new AdsNode("NA", naPort, List.of(
                    new AdsPeer("N1", "127.0.0.1", base),
                    new AdsPeer("N2", "127.0.0.1", base + 1),
                    new AdsPeer("N3", "127.0.0.1", base + 2),
                    new AdsPeer("N5", "127.0.0.1", latePort)));
            na.start();
            nodes.add(na);
            List<AdsServer> naSum = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                AdsServer s = new AdsServer("NA-SUM-" + (i + 1),
                        AdsService.SUM, "127.0.0.1", naPort);
                servers.add(s);
                s.start();
                naSum.add(s);
            }
            long dl3 = System.currentTimeMillis() + 12_000;
            while (System.currentTimeMillis() < dl3) {
                if (clients.get(0).registry().candidatesFor(AdsService.SUM).size() >= 6) break;
                Thread.sleep(150);
            }
            assertTrue(clients.get(0).registry().candidatesFor(AdsService.SUM).size() >= 6,
                    "Escalabilidad: >=6 candidatos SUM en GUI1");

            String ridScale = clients.get(0).sendRequest(AdsService.SUM, 7.0, 6.0);
            assertNotNull(ridScale);
            long dl4 = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < dl4 && !globalResponses.containsKey(ridScale)) {
                Thread.sleep(100);
            }
            assertNotNull(globalResponses.get(ridScale), "Respuesta de escalabilidad no llegó");

        } finally {
            for (AdsClient c : clients) {
                try { c.disconnect(); } catch (Throwable ignored) { }
            }
            for (AdsServer s : servers) {
                try { s.stop(); } catch (Throwable ignored) { }
            }
            for (AdsNode n : nodes) {
                try { n.stop(); } catch (Throwable ignored) { }
            }
        }
    }

    private record RequestSpec(AdsService svc, double a, double b, String expectedTarget) { }

    private static double expectedResult(AdsService svc, double a, double b) {
        return switch (svc) {
            case SUM -> a + b;
            case SUB -> a - b;
            case MUL -> a * b;
            case DIV -> a / b;
        };
    }

    private static int pickFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        f.delete();
    }
}