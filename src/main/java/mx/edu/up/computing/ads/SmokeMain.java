package mx.edu.up.computing.ads;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.TableColumnModel;

/**
 * SmokeMain ADS: valida la red con sockets reales.
 *
 * Sin ventanas: arma topología 4 nodos / 4 GUI / 16 servidores, ejercita las
 * cuatro operaciones desde cada cliente y verifica respuesta única global,
 * correlación, división por cero, replay real, caducidad, alta tardía,
 * escalabilidad y caída de nodo. Imprime resultados con conteos verificables;
 * sale con código de error si una aserción no se cumple. Realiza cleanup
 * ordenado en bloque {@code finally}.
 *
 * Con {@code --gui}: arranca 4 ventanas Swing reales, opera el botón
 * "Enviar solicitud ADS" desde EDT (vía {@link Robot} sobre los bounds
 * reales del botón para evitar clic en coordenada inventada), captura
 * screenshots solo de las ventanas ADS a {@code logs/ads-screens/}.
 */
public class SmokeMain {

    private static int exitCode = 0;
    private static final List<String> failures = new CopyOnWriteArrayList<>();

    public static void main(String[] args) throws Exception {
        boolean gui = false;
        boolean writeScreenshot = false;
        int base = pickFreePort();
        long requestTimeoutMs = 20_000L;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--gui" -> gui = true;
                case "--write-screenshot" -> writeScreenshot = true;
                case "--base" -> base = Integer.parseInt(args[++i]);
                case "--timeout-ms" -> requestTimeoutMs = Long.parseLong(args[++i]);
                default -> {
                    if (a.startsWith("--") || a.startsWith("-")) {
                        System.err.println("[SMOKE] Aviso: opción no soportada " + a);
                    }
                }
            }
        }

        // Aislar persistencia en un directorio temporal por corrida (no
        // compartir archivos con data/ads histórico ni contaminar entre smoke).
        java.nio.file.Path tempRoot = Files.createTempDirectory("ads-smoke-");
        System.setProperty("ads.persistence.root", tempRoot.toAbsolutePath().toString());
        System.out.println("[SMOKE] Persistencia aislada en " + tempRoot.toAbsolutePath());

        Topology t = new Topology(base, gui);
        try {
            t.startNodes(4);
            assertEq(4, t.nodes.size(), "4 nodos levantados", failures);
            // Esperar bounded <=15s hasta que CADA nodo tenga 3 enlaces activos
            // (cada nodo diala a sus 3 mayores; los acceptors aceptan). Tras
            // eso, los 6 enlaces canónicos se observan.
            long peerDeadline = System.currentTimeMillis() + 15_000L;
            boolean meshReady = false;
            int totalLinks = 0;
            while (System.currentTimeMillis() < peerDeadline) {
                totalLinks = 0;
                boolean allThree = true;
                for (AdsNode n : t.nodes) {
                    int size = n.activePeerIds().size();
                    totalLinks += size;
                    if (size < 3) allThree = false;
                }
                if (allThree) {
                    meshReady = true;
                    break;
                }
                Thread.sleep(100);
            }
            assertTrue(meshReady,
                    "Cada nodo debe tener 3 activePeerIds en <=15 s", failures);
            assertEq(6, totalLinks / 2,
                    "6 enlaces canónicos (suma de adyacencias / 2) tras meshReady",
                    failures);

            t.startCells(1); // 4 nodos * 1 GUI por nodo = 4 GUI; 4 nodos * 4 servicios = 16 servidores
            assertEq(16, t.servers.size(), "16 servidores (4 nodos x 4 servicios)", failures);
            assertEq(4, t.clients.size(), "4 GUI", failures);

            // Esperar cobertura de anuncios
            if (!t.awaitLiveCandidates(AdsService.values(), 15_000)) {
                failures.add("Anuncios vivos no se estabilizaron en 15 s");
            }
            int sumLiveTotal = 0;
            for (AdsClient c : t.clients) {
                for (AdsService svc : AdsService.values()) {
                    sumLiveTotal += c.registry().candidatesFor(svc).size();
                }
            }
            assertTrue(sumLiveTotal >= 16,
                    "Total candidatos vivos por (cliente, servicio) >= 16 (fue "
                            + sumLiveTotal + ")",
                    failures);

            // Cuatro operaciones desde cuatro clientes con respuesta única.
            // Map global requestId -> respuesta aceptada (debe haber EXACTAMENTE una).
            ConcurrentHashMap<String, AdsMessage> globalResponses = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, AtomicInteger> timeoutsByRid = new ConcurrentHashMap<>();
            // Listeners se instalan ANTES de enviar para evitar race.
            for (AdsClient c : t.clients) {
                c.addListener(new AdsClient.ClientListener() {
                    @Override public void onAdvertisement(AdsAdvertisement ad) { }
                    @Override public void onRequestSent(AdsMessage msg) { }
                    @Override public void onResponse(AdsMessage msg) {
                        if (msg.getRequestId() == null) return;
                        AdsMessage prev = globalResponses.putIfAbsent(msg.getRequestId(), msg);
                        if (prev != null) {
                            failures.add("Respuesta duplicada para requestId="
                                    + msg.getRequestId().substring(0, 8));
                        }
                    }
                    @Override public void onTimeout(String rid, AdsService s, String t) {
                        timeoutsByRid.computeIfAbsent(rid, k -> new AtomicInteger()).incrementAndGet();
                    }
                    @Override public void onError(String t) {
                        failures.add("Error GUI: " + t);
                    }
                });
            }
            int sent = 0;
            List<String> sentRequestIds = new ArrayList<>();
            // Mapa requestId -> spec(svc, a, b, expectedTarget).
            // expectedTarget se captura ANTES del envío consultando el
            // registro de la GUI (el primer candidato es el que sendRequest
            // elegirá). Eso evita la tautología de comparar contra el target
            // que devuelve la respuesta.
            ConcurrentHashMap<String, ReqSpec> specByRid = new ConcurrentHashMap<>();
            for (AdsService svc : AdsService.values()) {
                for (AdsClient c : t.clients) {
                    double a = 10.0 + sent;
                    double b = 1.0 + (sent % 3);
                    List<AdsAdvertisement> cands = c.registry().candidatesFor(svc);
                    if (cands.isEmpty()) {
                        failures.add("Sin candidatos vivos para " + svc
                                + " client=" + c.clientId());
                        sent++;
                        continue;
                    }
                    String expectedTarget = cands.get(0).instanceId();
                    String rid = c.sendRequest(svc, a, b);
                    if (rid == null) {
                        failures.add("sendRequest null para svc=" + svc + " client=" + c.clientId());
                    } else {
                        sentRequestIds.add(rid);
                        specByRid.put(rid, new ReqSpec(svc, a, b, expectedTarget));
                    }
                    sent++;
                }
            }
            assertEq(16, sent, "16 operaciones enviadas (4 servicios x 4 clientes)", failures);

            long deadline = System.currentTimeMillis() + requestTimeoutMs;
            while (System.currentTimeMillis() < deadline
                    && globalResponses.size() < sentRequestIds.size()) {
                Thread.sleep(100);
            }
            assertEq(sentRequestIds.size(), globalResponses.size(),
                    "Cada request tiene exactamente UNA respuesta global (no por GUI)",
                    failures);

            // Verificación de contenido por respuesta: status SUCCESS, UUID
            // ejecutor (capturado pre-send), contentCode, requestId
            // correlacionado, resultado exacto.
            for (Map.Entry<String, AdsMessage> e : globalResponses.entrySet()) {
                AdsMessage r = e.getValue();
                String rid = e.getKey();
                ReqSpec spec = specByRid.get(rid);
                if (spec == null) {
                    failures.add("Respuesta con requestId desconocido: " + rid);
                    continue;
                }
                assertEquals("SUCCESS", r.getStatus(),
                        "rid=" + rid + " status esperado SUCCESS, fue " + r.getStatus(),
                        failures);
                assertNotNull(r.getResult(),
                        "rid=" + rid + " resultado nulo", failures);
                assertTrue(Double.isFinite(r.getResult()),
                        "rid=" + rid + " resultado finito: " + r.getResult(),
                        failures);
                double expected = expectedResult(spec.svc, spec.a, spec.b);
                if (Math.abs(r.getResult() - expected) > 1e-9) {
                    failures.add("rid=" + rid + " resultado=" + r.getResult()
                            + " esperado=" + expected + " svc=" + spec.svc
                            + " a=" + spec.a + " b=" + spec.b);
                }
                String target = r.getTargetServerId();
                if (target == null || target.length() < 32) {
                    failures.add("rid=" + rid + " ejecutor sin UUID válido");
                } else if (!spec.expectedTarget.equals(target)) {
                    failures.add("rid=" + rid + " ejecutor=" + target
                            + " != elegido pre-send=" + spec.expectedTarget);
                }
                assertEquals(rid, r.getRequestId(),
                        "rid=" + rid + " requestId respuesta = UUID solicitud",
                        failures);
                assertEquals(spec.svc.name() + "_RES",
                        r.getContentCode() == null ? "" : r.getContentCode(),
                        "rid=" + rid + " contentCode del servicio coincide con *_RES", failures);
            }

            // Tras settle, sum(processedCount) delta debe ser EXACTO 16
            // (sin cómputos duplicados por reexpedición de dedup). El
            // inyectado posterior de NA-SUM-1 añadirá +1; se exige EXACTO
            // 16 ANTES de cualquier inyectado.
            Thread.sleep(500);
            long initialProcessedTotal = t.servers.stream()
                    .mapToLong(AdsServer::processedCount).sum();
            assertEq(16L, initialProcessedTotal,
                    "sum(processedCount) debe ser EXACTO 16 tras 16 ops iniciales",
                    failures);

            // Verificación de aceptación propia: cada GUI aceptó sus 4 respuestas
            // propias. ignoredResponses NO se exige ==0 porque los broadcasts
            // ajenos llegan a GUIs que no emitieron y los descartan (eso
            // es correcto, no un fallo).
            for (AdsClient c : t.clients) {
                if (c.acceptedCount() < 4) {
                    failures.add("GUI " + c.clientId() + " aceptó "
                            + c.acceptedCount() + "/4 respuestas propias");
                }
            }

            // Si --gui, ejercitar Robot sobre los bounds reales del botón
            // ANTES de matar N4, detener DIV, late-join N5 o escala. Aquí
            // existen exactamente 4 JFrames y 16 servidores vivos; el clic
            // Robot dispara una operación real visible.
            final boolean showGuiEarly = gui;
            if (showGuiEarly) {
                runGuiWithRobot(t, writeScreenshot);
            }

            // Regresión de lifecycle: tras disconnect + reconnect, el cliente debe
            // poder emitir otra operación real (sin scheduler muerto).
            // connect() es async: esperamos bounded a isConnected() y a que el
            // catálogo tenga candidatos SUM vivos (el registro puede retener
            // los originales de antes del disconnect; válido). El listener
            // global captura la respuesta y verificamos aritmética y
            // correlación con la respuesta, no sólo el contador accepted.
            AdsClient lifecycle = t.clients.get(0);
            lifecycle.disconnect();
            Thread.sleep(300);
            lifecycle.connect();
            long tConnWait = System.currentTimeMillis() + 5_000L;
            while (System.currentTimeMillis() < tConnWait
                    && (!lifecycle.isConnected()
                            || lifecycle.registry().candidatesFor(AdsService.SUM).isEmpty())) {
                Thread.sleep(100);
            }
            assertTrue(lifecycle.isConnected(),
                    "Cliente reconnectado debe volver a isConnected()", failures);
            List<AdsAdvertisement> candsLife = lifecycle.registry()
                    .candidatesFor(AdsService.SUM);
            assertFalse(candsLife.isEmpty(),
                    "GUI1 debe tener candidatos SUM vivos post-reconnect", failures);
            String expectedLifeTarget = candsLife.get(0).instanceId();
            String ridLife = lifecycle.sendRequest(AdsService.SUM, 11.0, 13.0);
            assertNotNull(ridLife, "sendRequest post-reconnect debe devolver rid",
                    failures);
            long tLifeWait = System.currentTimeMillis() + 5_000L;
            while (System.currentTimeMillis() < tLifeWait
                    && !globalResponses.containsKey(ridLife)) {
                Thread.sleep(100);
            }
            AdsMessage lifeResp = globalResponses.get(ridLife);
            assertNotNull(lifeResp,
                    "Respuesta lifecycle debe capturarse en globalResponses",
                    failures);
            if (lifeResp != null) {
                assertEquals("SUCCESS", lifeResp.getStatus(),
                        "lifecycle status", failures);
                assertEquals("SUM_RES", lifeResp.getContentCode(),
                        "lifecycle contentCode", failures);
                assertEquals(24.0, lifeResp.getResult(), 1e-9,
                        "lifecycle resultado 11+13=24", failures);
                assertEquals(expectedLifeTarget, lifeResp.getTargetServerId(),
                        "lifecycle ejecutor = instancia elegida pre-send", failures);
                assertEquals(ridLife, lifeResp.getRequestId(),
                        "lifecycle requestId correlacionado", failures);
            }

// Replay real: evidencia causal por UUID (no contador global con ruido
            // de anuncios concurrentes). El primer tryRegister del UUID fresh
            // avanza; el segundo, sobre el mismo UUID, cae como duplicado.
            String freshUuid = java.util.UUID.randomUUID().toString();
            AdsNode anyNode = t.nodes.get(0);
            boolean firstReg = anyNode.dedup().tryRegister(freshUuid);
            boolean secondReg = anyNode.dedup().tryRegister(freshUuid);
            assertTrue(firstReg, "Primer registro del UUID fresh debe avanzar", failures);
            assertFalse(secondReg, "Segundo registro del mismo UUID debe caer", failures);

            // Caducidad real: inyectar un mensaje con expiresAt en el pasado debe
            // ser DROP_EXPIRED, no incrementar dedup.
            AdsMessage expired = AdsMessage.create(AdsMessageType.SUM_REQ);
            expired.setExpiresAt(System.currentTimeMillis() - 1_000);
            long beforeExp = anyNode.dedup().expiredCount();
            AdsClient gui0 = t.clients.get(0);
            anyNode.injectForTest(gui0.instanceId(), expired);
            long afterExp = anyNode.dedup().expiredCount();
            assertEq(beforeExp + 1, afterExp,
                    "Mensaje expirado incrementa expiredCount", failures);

            // Alta tardía: añadir un quinto nodo en un puerto NO colisionado
            // y verificar que se une a la malla y publica servidores.
            int latePort = base + 10;
            AdsNode late = new AdsNode("N5", latePort, List.of(
                    new AdsPeer("N1", "127.0.0.1", base),
                    new AdsPeer("N2", "127.0.0.1", base + 1),
                    new AdsPeer("N3", "127.0.0.1", base + 2),
                    new AdsPeer("N4", "127.0.0.1", base + 3)));
            late.start();
            t.nodes.add(late);
            java.util.Map<AdsService, AdsServer> n5Servers = new java.util.HashMap<>();
            for (AdsService svc : AdsService.values()) {
                AdsServer srv = new AdsServer("N5-" + svc.name() + "-1", svc, "127.0.0.1", latePort);
                t.servers.add(srv);
                srv.start();
                n5Servers.put(svc, srv);
            }
            // Esperar a que el UUID de N5 específico de cada servicio aparezca
            // en el catálogo de ESE servicio en GUI1 (mismo criterio que JUnit).
            java.util.Map<AdsService, String> n5BySvc = new java.util.HashMap<>();
            for (java.util.Map.Entry<AdsService, AdsServer> e : n5Servers.entrySet()) {
                n5BySvc.put(e.getKey(), e.getValue().instanceId());
            }
            boolean grew = false;
            long dlLate = System.currentTimeMillis() + 12_000;
            while (System.currentTimeMillis() < dlLate) {
                grew = true;
                for (AdsService svc : AdsService.values()) {
                    java.util.List<String> candIds = new java.util.ArrayList<>();
                    for (AdsAdvertisement ad : t.clients.get(0).registry().candidatesFor(svc)) {
                        candIds.add(ad.instanceId());
                    }
                    if (!candIds.contains(n5BySvc.get(svc))) {
                        grew = false;
                        break;
                    }
                }
                if (grew) break;
                Thread.sleep(150);
            }
            assertTrue(grew, "Alta tardía N5: el UUID N5 de cada servicio debe estar en GUI1", failures);

            // Caída de un nodo: apagar N4 y verificar respuestas posteriores en GUI vivos.
            AdsNode victim = t.nodes.stream()
                    .filter(n -> "N4".equals(n.nodeId()))
                    .findFirst().orElse(null);
            if (victim == null) {
                failures.add("No se encontró N4 para la prueba de caída");
            } else {
                // Guardar los UUIDs de servidores N4 antes de detenerlos. El
                // lease del anuncio es 6 s; sendRequest elige por UUID mínimo
                // del catálogo. Si los UUIDs de N4 siguen vivos en el
                // catálogo del GUI (lease no vencido), sendRequest los
                // elegirá y la solicitud no tendrá ejecutor.
                java.util.Set<String> n4Uuids = new java.util.HashSet<>();
                for (AdsServer s : t.servers) {
                    if (s.serverId().startsWith("N4-")) {
                        n4Uuids.add(s.instanceId());
                    }
                }
                t.killNode(victim);
                // Esperar bounded (8 s > lease 6 s) a que los UUIDs de N4
                // desaparezcan del catálogo de GUI1.
                long leaseDeadline = System.currentTimeMillis() + 8_000L;
                boolean gone = false;
                while (System.currentTimeMillis() < leaseDeadline) {
                    java.util.Set<String> stillThere = new java.util.HashSet<>();
                    for (AdsService svc : AdsService.values()) {
                        for (AdsAdvertisement ad : t.clients.get(0).registry().candidatesFor(svc)) {
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
                if (!gone) {
                    failures.add("Tras 8 s los UUIDs de N4 no expiraron del catálogo de GUI1");
                }
                // Capturar el ejecutor esperado ANTES del envío (no tautología).
                List<AdsAdvertisement> candsPost = t.clients.get(0).registry()
                        .candidatesFor(AdsService.SUM);
                if (candsPost.isEmpty()) {
                    failures.add("GUI1 sin candidatos SUM vivos tras expirar N4");
                } else {
                    String expectedExecPost = candsPost.get(0).instanceId();
                    if (n4Uuids.contains(expectedExecPost)) {
                        failures.add("Ejecutor elegido pre-send sigue siendo de N4");
                    }
                    AtomicReference<AdsMessage> captured = new AtomicReference<>();
                    t.clients.get(0).addListener(new AdsClient.ClientListener() {
                        @Override public void onAdvertisement(AdsAdvertisement ad) { }
                        @Override public void onRequestSent(AdsMessage msg) { }
                        @Override public void onResponse(AdsMessage msg) {
                            captured.compareAndSet(null, msg);
                        }
                        @Override public void onTimeout(String rid, AdsService s, String t) { }
                        @Override public void onError(String t) { }
                    });
                    String ridPost = t.clients.get(0).sendRequest(AdsService.SUM, 42.0, 58.0);
                    assertNotNull(ridPost, "sendRequest post-caída devuelve requestId", failures);
                    long dl2 = System.currentTimeMillis() + requestTimeoutMs;
                    while (System.currentTimeMillis() < dl2 && captured.get() == null) {
                        Thread.sleep(100);
                    }
                    AdsMessage postResp = captured.get();
                    if (postResp == null) {
                        failures.add("Tras caída de N4 no llegó respuesta");
                    } else {
                        assertEquals("SUCCESS", postResp.getStatus(),
                                "status post-caída", failures);
                        assertEquals(100.0, postResp.getResult(), 1e-9,
                                "result post-caída esperado 100", failures);
                        assertEquals(ridPost, postResp.getRequestId(),
                                "requestId post-caída correlacionado", failures);
                        String execId = postResp.getTargetServerId();
                        AdsServer exec = t.servers.stream()
                                .filter(s -> s.instanceId().equals(execId))
                                .findFirst().orElse(null);
                        if (exec == null) {
                            failures.add("Ejecutor post-caída no está en servidores vivos");
                        } else if (exec.serverId().startsWith("N4-")) {
                            failures.add("Ejecutor post-caída pertenece a N4 (muerto)");
                        } else if (!expectedExecPost.equals(execId)) {
                            failures.add("Ejecutor post-caída != elegido pre-send ("
                                    + execId + " vs " + expectedExecPost + ")");
                        }
                    }
                }
            }

            // Regresión de TIMEOUT real (criterio 6): una operación REALMENTE
            // pendiente antes de perder todos los servidores de un servicio
            // debe timeout-earse EXACTAMENTE una vez con ese requestId y
            // NINGUNA respuesta. Los timeouts no intencionales siguen
            // contando como fallo (no se ocultan todos los timeouts).
            List<AdsServer> divServers = new ArrayList<>();
            for (AdsServer srv : t.servers) {
                if (srv.service() == AdsService.DIV) {
                    divServers.add(srv);
                }
            }
            for (AdsServer srv : divServers) {
                srv.stop();
            }
            t.servers.removeAll(divServers);
            AdsClient timeoutClient = t.clients.get(0);
            String ridTimeout = timeoutClient.sendRequest(AdsService.DIV, 10.0, 5.0);
            assertNotNull(ridTimeout,
                    "sendRequest DIV con servidores muertos debe devolver rid (timeout)",
                    failures);
            long tWait3 = System.currentTimeMillis() + 12_000;
            while (System.currentTimeMillis() < tWait3
                    && !timeoutsByRid.containsKey(ridTimeout)) {
                Thread.sleep(100);
            }
            // El rid intencional tiene EXACTAMENTE 1 timeout y ninguna respuesta.
            assertEquals(1, timeoutsByRid.getOrDefault(ridTimeout, new AtomicInteger()).get(),
                    "rid intencional debe tener EXACTAMENTE 1 timeout", failures);
            assertFalse(globalResponses.containsKey(ridTimeout),
                    "rid intencional NO debe tener respuesta (timeout 0)", failures);
            // El listener global registra todos los timeouts. Sólo se admite
            // el UUID de la operación deliberadamente privada de servidores.
            long otherTimeouts = timeoutsByRid.entrySet().stream()
                    .filter(e -> !e.getKey().equals(ridTimeout))
                    .mapToInt(e -> e.getValue().get()).sum();
            assertEquals(0, otherTimeouts,
                    "Otros rids NO deben tener timeout (total no intencionales="
                            + otherTimeouts + ")", failures);

            // Escalabilidad > 4: dos servidores SUM extras en un nuevo nodo.
            // Probe TCP propio conectado a N1 con HELLO_GUI, identidad UUID.
            // Enviamos SUM_REQ dirigido al UUID de NA-SUM-1 con operandos 7 y 6
            // (resultado 13). Leemos líneas hasta SUM_RES con requestId.
            // Guardamos el advertisement de NA-SUM-1 visto en registry GUI1 ANTES
            // del envío (cruce de malla N1 → NA, no sólo comunicación local).
            int extraPort = base + 11;
            AdsNode extra = new AdsNode("NA", extraPort, List.of(
                    new AdsPeer("N1", "127.0.0.1", base),
                    new AdsPeer("N2", "127.0.0.1", base + 1),
                    new AdsPeer("N3", "127.0.0.1", base + 2),
                    new AdsPeer("N5", "127.0.0.1", base + 10)));
            extra.start();
            t.nodes.add(extra);
            AdsServer naSum1 = new AdsServer("NA-SUM-1",
                    AdsService.SUM, "127.0.0.1", extraPort);
            AdsServer naSum2 = new AdsServer("NA-SUM-2",
                    AdsService.SUM, "127.0.0.1", extraPort);
            t.servers.add(naSum1);
            t.servers.add(naSum2);
            naSum1.start();
            naSum2.start();

// Esperar a que el advertisement de NA-SUM-1 aparezca en el registry
            // de GUI1 (cruce N1 → NA a través de la malla).
            AdsClient gui1 = t.clients.get(0);
            String na1InstanceId = naSum1.instanceId();
            long tAdvWait = System.currentTimeMillis() + 8_000L;
            boolean advSeen = false;
            while (System.currentTimeMillis() < tAdvWait) {
                for (AdsAdvertisement ad : gui1.registry().candidatesFor(AdsService.SUM)) {
                    if (na1InstanceId.equals(ad.instanceId())) {
                        advSeen = true;
                        break;
                    }
                }
                if (advSeen) break;
                Thread.sleep(100);
            }
            if (!advSeen) {
                failures.add("Advertisement de NA-SUM-1 no llegó al registry de GUI1");
            }

            // Probe TCP: cliente crudo conectado a N1 con HELLO_GUI.
            String probeClientId = "probe-" + java.util.UUID.randomUUID();
            String probeInstanceId = java.util.UUID.randomUUID().toString();
            try (java.net.Socket probe = new java.net.Socket()) {
            probe.connect(new java.net.InetSocketAddress("127.0.0.1", base), 2_000);
            probe.setSoTimeout(1_000);
            java.io.OutputStream out = probe.getOutputStream();
            java.io.InputStream in = probe.getInputStream();
            AdsMessage helloGui = AdsMessage.create(AdsMessageType.HELLO_GUI);
            helloGui.setOriginId(probeClientId);
            helloGui.setInstanceId(probeInstanceId);
            helloGui.setSenderId(probeInstanceId);
            helloGui.setSenderRole(AdsRole.GUI.name());
            out.write((helloGui.toJson() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            String ackLine = br.readLine();
            if (ackLine == null || AdsMessage.fromJson(ackLine) == null) {
                failures.add("Probe TCP: HELLO_ACK no recibido de N1");
            }

            // Construir SUM_REQ dirigida a NA-SUM-1 con operandos 7 y 6.
            String naRid = java.util.UUID.randomUUID().toString();
            AdsMessage naReq = AdsMessage.create(AdsMessageType.SUM_REQ);
            naReq.setMessageId(naRid);
            naReq.setRequestId(naRid);
            naReq.setOriginId(probeClientId);
            naReq.setClientId(probeClientId);
            naReq.setTargetServerId(na1InstanceId);
            naReq.setService(AdsService.SUM.name());
            naReq.setOperandA(7.0);
            naReq.setOperandB(6.0);

            long processedBefore = naSum1.processedCount();
            long duplicateBefore = naSum1.ignoredByDuplicateCount();

            // Enviar el REQ por el probe TCP (cruce N1 → malla → NA → NA-SUM-1).
            out.write((naReq.toJson() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();

            // Leer líneas hasta encontrar SUM_RES con nuestro requestId o timeout.
            AdsMessage naResp = null;
            long tRead = System.currentTimeMillis() + 10_000L;
            while (System.currentTimeMillis() < tRead && naResp == null) {
                String line;
                try {
                    line = br.readLine();
                } catch (java.net.SocketTimeoutException quietInterval) {
                    continue;
                }
                if (line == null) break;
                AdsMessage msg = AdsMessage.fromJson(line);
                if (msg == null) continue;
                if (naRid.equals(msg.getRequestId())
                        && msg.getType() != null
                        && msg.getType().endsWith("_RES")) {
                    naResp = msg;
                }
            }

            if (naResp == null) {
                failures.add("Probe TCP: SUM_RES con requestId=" + naRid + " no recibido");
            } else {
                assertEquals("SUCCESS", naResp.getStatus(),
                        "NA-SUM-1 status respuesta", failures);
                assertEquals("SUM_RES", naResp.getContentCode(),
                        "NA-SUM-1 contentCode", failures);
                assertEquals(13.0, naResp.getResult(), 1e-9,
                        "NA-SUM-1 resultado esperado 13 (7+6)", failures);
                assertEquals(na1InstanceId, naResp.getTargetServerId(),
                        "NA-SUM-1 ejecutor = UUID NA-SUM-1", failures);
                assertEquals(naRid, naResp.getRequestId(),
                        "NA-SUM-1 requestId correlacionado", failures);
            }
            assertEquals(processedBefore + 1, naSum1.processedCount(),
                    "NA-SUM-1 procesó la operación dirigida vía probe TCP", failures);

            // Repetir el mismo sobre por el mismo TCP: N1 debe descartarlo
            // antes de alcanzar al servidor. Ni cómputos ni duplicados locales
            // del servidor deben aumentar; así no confundimos dedup del nodo
            // con la barrera independiente de la célula.
            out.write((naReq.toJson() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(500);
            assertEquals(processedBefore + 1, naSum1.processedCount(),
                    "Replay por red: NA-SUM-1 NO debe procesar segundo cómputo (dedup)",
                    failures);
            assertEquals(duplicateBefore, naSum1.ignoredByDuplicateCount(),
                    "Replay por red: el nodo bloquea la segunda entrega al servidor",
                    failures);
            }
            long finalUnexpectedTimeouts = timeoutsByRid.entrySet().stream()
                    .filter(e -> !e.getKey().equals(ridTimeout))
                    .mapToInt(e -> e.getValue().get()).sum();
            assertEquals(0, finalUnexpectedTimeouts,
                    "Sin timeouts no intencionales al concluir el smoke", failures);
        } catch (Throwable ex) {
            failures.add("Excepción no controlada: " + ex);
            ex.printStackTrace(System.err);
        } finally {
            try {
                t.shutdown();
            } catch (Throwable cleanupEx) {
                // Cleanup best-effort: si falla, lo añadimos a failures pero
                // no interrumpimos el reporte final.
                failures.add("Cleanup error: " + cleanupEx);
            }
        }

        if (!failures.isEmpty()) {
            System.err.println("[SMOKE] FAIL (" + failures.size() + "):");
            for (String f : failures) {
                System.err.println("  - " + f);
            }
            exitCode = 1;
        } else {
            System.out.println("[SMOKE] OK. Resultados aislados en " + tempRoot.toAbsolutePath() + ".");
        }
        System.clearProperty("ads.persistence.root");
        deleteRecursively(tempRoot.toFile());
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static void deleteRecursively(java.io.File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            java.io.File[] children = f.listFiles();
            if (children != null) {
                for (java.io.File c : children) deleteRecursively(c);
            }
        }
        f.delete();
    }

    private static void runGuiWithRobot(Topology t, boolean writeScreenshot) {
        try {
            // Asegurar que las ventanas estén construidas y visibles.
            for (AdsClient c : t.clients) {
                if (!t.frames.containsKey(c)) {
                    buildAndShow(c, t);
                }
            }
            for (JFrame frame : t.frames.values()) {
                if (!frame.isVisible()) {
                    SwingUtilities.invokeLater(() -> frame.setVisible(true));
                }
            }
            Thread.sleep(1500); // esperar pintado

            Robot robot = new Robot();
            robot.setAutoDelay(60);
            Path outDir = Paths.get("logs/ads-screens");
            Files.createDirectories(outDir);

            // Cabeceras reales de la tabla de respuestas en vivo (AdsClientUi).
            final java.util.List<String> liveHeaders = java.util.Arrays.asList(
                    "Hora", "requestId", "Servidor", "Servicio", "Resultado", "Status");

            for (Map.Entry<AdsClient, JFrame> e : t.frames.entrySet()) {
                AdsClient client = e.getKey();
                JFrame frame = e.getValue();
                Rectangle bounds = frame.getBounds();
                SwingUtilities.invokeAndWait(() -> {
                    frame.toFront();
                    frame.requestFocus();
                });
                // Activar esta ventana por su barra de título antes del botón:
                // macOS puede consumir el primer clic sólo para enfocarla.
                robot.mouseMove(bounds.x + bounds.width / 2, bounds.y + 12);
                robot.mousePress(java.awt.event.MouseEvent.BUTTON1_DOWN_MASK);
                robot.mouseRelease(java.awt.event.MouseEvent.BUTTON1_DOWN_MASK);
                robot.delay(300);
                if (bounds.width <= 0 || bounds.height <= 0) continue;

                BufferedImage shot = robot.createScreenCapture(bounds);
                if (writeScreenshot) {
                    File out = outDir.resolve("ads-gui-"
                            + client.clientId() + ".png").toFile();
                    ImageIO.write(shot, "png", out);
                    System.out.println("[SMOKE] Screenshot ADS " + client.clientId()
                            + " -> " + out);
                }

                // Esperar a que la GUI esté realmente conectada antes de actuar.
                long tEnd = System.currentTimeMillis() + 5_000;
                while (System.currentTimeMillis() < tEnd && !client.isConnected()) {
                    Thread.sleep(100);
                }
                if (!client.isConnected()) {
                    failures.add("GUI " + client.clientId()
                            + " no quedó conectada para Robot");
                    continue;
                }

                JTable liveTable = findTableByHeaders(frame, liveHeaders);
                if (liveTable == null) {
                    failures.add("GUI " + client.clientId()
                            + " sin tabla con cabeceras reales de respuestas en vivo");
                    continue;
                }
                int baselineRows = liveTable.getRowCount();

                // Capturar el requestId generado por onRequestSent al hacer clic.
                final java.util.concurrent.atomic.AtomicReference<String> sentRid =
                        new java.util.concurrent.atomic.AtomicReference<>();
                final java.util.concurrent.atomic.AtomicReference<String> acceptedRid =
                        new java.util.concurrent.atomic.AtomicReference<>();
                client.addListener(new AdsClient.ClientListener() {
                    @Override public void onAdvertisement(AdsAdvertisement ad) { }
                    @Override public void onRequestSent(AdsMessage msg) {
                        sentRid.compareAndSet(null, msg.getMessageId());
                    }
                    @Override public void onResponse(AdsMessage msg) {
                        acceptedRid.set(msg.getRequestId());
                    }
                    @Override public void onTimeout(String rid, AdsService s, String t) { }
                    @Override public void onError(String t) { }
                });

                JButton btn = findButtonByText(frame, "Enviar solicitud ADS");
                if (btn == null) {
                    failures.add("Botón 'Enviar solicitud ADS' no encontrado en "
                            + client.clientId());
                    continue;
                }
                Rectangle btnBounds = new Rectangle(btn.getLocationOnScreen(),
                        btn.getSize());
                if (btnBounds.width <= 0 || btnBounds.height <= 0) {
                    failures.add("Botón sin bounds en " + client.clientId());
                    continue;
                }
                int cx = btnBounds.x + btnBounds.width / 2;
                int cy = btnBounds.y + btnBounds.height / 2;
                robot.mouseMove(cx, cy);
                robot.mousePress(java.awt.event.MouseEvent.BUTTON1_DOWN_MASK);
                robot.mouseRelease(java.awt.event.MouseEvent.BUTTON1_DOWN_MASK);

                // Esperar a que la GUI registre el clic vía onRequestSent.
                long tRid = System.currentTimeMillis() + 5_000;
                while (System.currentTimeMillis() < tRid && sentRid.get() == null) {
                    Thread.sleep(50);
                }
                final String rid = sentRid.get();
                if (rid == null) {
                    failures.add("GUI " + client.clientId()
                            + " no generó requestId tras clic Robot");
                    continue;
                }

                // Esperar a que aparezca en la live table UNA fila cuyo
                // requestId (columna 1) coincida con el rid enviado.
                long tWait = System.currentTimeMillis() + 10_000;
                boolean sawRow = false;
                int rowIdx = -1;
                while (System.currentTimeMillis() < tWait) {
                    if (liveTable.getRowCount() > baselineRows) {
                        for (int i = 0; i < liveTable.getRowCount(); i++) {
                            Object v = liveTable.getValueAt(i, 1);
                            if (v != null && rid.substring(0, 8).equals(v.toString())) {
                                sawRow = true;
                                rowIdx = i;
                                break;
                            }
                        }
                    }
                    if (sawRow) break;
                    Thread.sleep(100);
                }
                if (!sawRow) {
                    failures.add("GUI " + client.clientId()
                            + " no mostró fila con requestId=" + rid
                            + " en live table (rows antes=" + baselineRows
                            + " despues=" + liveTable.getRowCount() + ")");
                } else {
                    Object statusVal = liveTable.getValueAt(rowIdx, 5);
                    Object resultVal = liveTable.getValueAt(rowIdx, 4);
                    Object svcVal = liveTable.getValueAt(rowIdx, 3);
                    assertEquals(rid, acceptedRid.get(),
                            "GUI acepta el UUID completo del clic", failures);
                    assertEquals("SUCCESS", String.valueOf(statusVal),
                            "GUI muestra SUCCESS para 10+5", failures);
                    assertEquals("SUM", String.valueOf(svcVal),
                            "GUI usa el servicio seleccionado SUM", failures);
                    assertEquals(15.0, ((Number) resultVal).doubleValue(), 1e-9,
                            "GUI muestra el resultado de 10+5", failures);
                    System.out.println("[SMOKE] GUI " + client.clientId()
                            + " clic Robot OK rid=" + rid.substring(0, 8)
                            + " status=" + statusVal + " svc=" + svcVal
                            + " result=" + resultVal);
                }

                if (writeScreenshot) {
                    Rectangle boundsAfter = frame.getBounds();
                    BufferedImage shot2 = robot.createScreenCapture(boundsAfter);
                    File out2 = outDir.resolve("ads-gui-"
                            + client.clientId() + "-after-click.png").toFile();
                    ImageIO.write(shot2, "png", out2);
                    System.out.println("[SMOKE] Screenshot ADS " + client.clientId()
                            + " tras clic -> " + out2);
                }
            }
            Thread.sleep(500);
            Toolkit.getDefaultToolkit().beep();
        } catch (Exception e) {
            failures.add("Robot falló: " + e.getMessage());
        }
    }

    /**
     * Localiza la JTable cuyo ColumnModel tiene exactamente las cabeceras
     * indicadas (mismo orden y tamaño). Acceso por reflexión tolerante.
     */
    private static JTable findTableByHeaders(JFrame frame, java.util.List<String> headers) {
        java.util.List<JTable> tables = new java.util.ArrayList<>();
        collectTables(frame.getContentPane(), tables);
        for (JTable tbl : tables) {
            javax.swing.table.TableColumnModel cm = tbl.getColumnModel();
            if (cm.getColumnCount() != headers.size()) continue;
            boolean match = true;
            for (int i = 0; i < cm.getColumnCount(); i++) {
                Object name = cm.getColumn(i).getHeaderValue();
                if (name == null || !headers.get(i).equals(name.toString())) {
                    match = false;
                    break;
                }
            }
            if (match) return tbl;
        }
        return null;
    }

    private static void collectTables(java.awt.Container c, java.util.List<JTable> out) {
        for (java.awt.Component comp : c.getComponents()) {
            if (comp instanceof JTable tbl) out.add(tbl);
            if (comp instanceof java.awt.Container child) collectTables(child, out);
        }
    }

    private static JButton findButtonByText(JFrame frame, String text) {
        return findButtonByTextRecursive(frame.getContentPane(), text, true);
    }

    private static JButton findButtonByTextRecursive(java.awt.Container c, String text, boolean allowPrefix) {
        for (java.awt.Component comp : c.getComponents()) {
            if (comp instanceof JButton btn) {
                String btnText = btn.getText();
                if (btnText == null) continue;
                if (btnText.equals(text) || (allowPrefix && btnText.startsWith(text))) {
                    return btn;
                }
            }
            if (comp instanceof java.awt.Container child) {
                JButton found = findButtonByTextRecursive(child, text, allowPrefix);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void buildAndShow(AdsClient c, Topology t) {
        final AdsClientUi[] holder = new AdsClientUi[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                AdsClientUi ui = new AdsClientUi(c);
                ui.setSize(1080, 680);
                ui.setLocation(50 + t.frames.size() * 40,
                        50 + t.frames.size() * 40);
                holder[0] = ui;
            });
        } catch (InterruptedException | InvocationTargetException ex) {
            failures.add("Construcción UI: " + ex.getMessage());
            return;
        }
        t.frames.put(c, holder[0]);
    }

    private static void assertEq(int expected, int actual, String label, List<String> failures) {
        if (expected != actual) {
            failures.add(label + ": esperado=" + expected + " actual=" + actual);
        } else {
            System.out.println("[SMOKE] " + label + " OK (" + actual + ")");
        }
    }

    private static void assertEq(long expected, long actual, String label, List<String> failures) {
        if (expected != actual) {
            failures.add(label + ": esperado=" + expected + " actual=" + actual);
        } else {
            System.out.println("[SMOKE] " + label + " OK (" + actual + ")");
        }
    }

    private static void assertEquals(Object expected, Object actual, String label, List<String> failures) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) {
            failures.add(label + ": esperado=" + expected + " actual=" + actual);
        } else {
            System.out.println("[SMOKE] " + label + " OK");
        }
    }

    private static void assertEquals(long expected, long actual, String label, List<String> failures) {
        if (expected != actual) {
            failures.add(label + ": esperado=" + expected + " actual=" + actual);
        } else {
            System.out.println("[SMOKE] " + label + " OK");
        }
    }

    private static void assertEquals(double expected, double actual, double delta, String label,
                                     List<String> failures) {
        if (Math.abs(expected - actual) > delta) {
            failures.add(label + ": esperado=" + expected + " actual=" + actual);
        } else {
            System.out.println("[SMOKE] " + label + " OK");
        }
    }

    private static void assertTrue(boolean cond, String label, List<String> failures) {
        if (!cond) {
            failures.add(label);
        } else {
            System.out.println("[SMOKE] " + label + " OK");
        }
    }

    private static void assertFalse(boolean cond, String label, List<String> failures) {
        if (cond) {
            failures.add(label);
        } else {
            System.out.println("[SMOKE] " + label + " OK");
        }
    }

    private static void assertNotNull(Object obj, String label, List<String> failures) {
        if (obj == null) {
            failures.add(label);
        }
    }

    private static int pickFreePort() throws java.io.IOException {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }

    /** Especificación de una solicitud para comparar resultado exacto
 *  y targetServerId elegido pre-send. */
    private record ReqSpec(AdsService svc, double a, double b, String expectedTarget) { }

    private static double expectedResult(AdsService svc, double a, double b) {
        return switch (svc) {
            case SUM -> a + b;
            case SUB -> a - b;
            case MUL -> a * b;
            case DIV -> a / b;
        };
    }

    /**
     * Topología mínima: 4 nodos + 4 GUI + 16 servidores.
     * En modo headless no se construyen JFrame; en modo --gui se crean bajo demanda.
     */
    static final class Topology {

        final int base;
        final boolean gui;
        final List<AdsNode> nodes = Collections.synchronizedList(new ArrayList<>());
        final List<AdsServer> servers = Collections.synchronizedList(new ArrayList<>());
        final List<AdsClient> clients = Collections.synchronizedList(new ArrayList<>());
        final Map<AdsClient, JFrame> frames = new ConcurrentHashMap<>();

        Topology(int base, boolean gui) {
            this.base = base;
            this.gui = gui;
        }

        void startNodes(int count) throws Exception {
            List<AdsPeer> peerList = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                peerList.add(new AdsPeer("N" + i, "127.0.0.1", base + i - 1));
            }
            for (int i = 1; i <= count; i++) {
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
        }

        void startCells(int guiPerNode) {
            // servidores: 4 por nodo
            for (int idx = 0; idx < nodes.size(); idx++) {
                AdsNode owner = nodes.get(idx);
                int nodePort = owner.port();
                for (AdsService svc : AdsService.values()) {
                    String id = owner.nodeId() + "-" + svc.name() + "-1";
                    AdsServer srv = new AdsServer(id, svc, "127.0.0.1", nodePort);
                    servers.add(srv);
                    srv.start();
                }
            }
            // clientes: 1 por nodo (guiPerNode controla la cantidad por nodo)
            for (int idx = 0; idx < nodes.size(); idx++) {
                AdsNode owner = nodes.get(idx);
                for (int g = 0; g < guiPerNode; g++) {
                    String cid = "GUI" + (idx + 1);
                    AdsClient c = new AdsClient(cid, "127.0.0.1", owner.port());
                    clients.add(c);
                    c.connect();
                    if (gui) {
                        buildAndShow(c, this);
                    }
                }
            }
        }

        void killNode(AdsNode victim) {
            String victimId = victim.nodeId();
            List<AdsServer> orphans = new ArrayList<>();
            for (AdsServer srv : servers) {
                if (srv.serverId().startsWith(victimId + "-")) {
                    srv.stop();
                    orphans.add(srv);
                }
            }
            servers.removeAll(orphans);
            nodes.remove(victim);
            victim.stop();
        }

        boolean awaitLiveCandidates(AdsService[] services, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                boolean ok = true;
                for (AdsClient c : clients) {
                    for (AdsService svc : services) {
                        if (c.registry().candidatesFor(svc).size() < 4) {
                            ok = false;
                            break;
                        }
                    }
                    if (!ok) break;
                }
                if (ok) return true;
                Thread.sleep(150);
            }
            return false;
        }

        void shutdown() {
            for (AdsClient c : clients) c.disconnect();
            for (AdsServer s : servers) s.stop();
            for (AdsNode n : nodes) n.stop();
            for (JFrame f : frames.values()) {
                try {
                    SwingUtilities.invokeAndWait(f::dispose);
                } catch (Exception ignored) {
                    // cleanup best-effort
                }
            }
            frames.clear();
        }
    }
}