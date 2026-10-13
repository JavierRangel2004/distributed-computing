package mx.edu.up.computing.ads;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Nodo del Campo de Datos ADS.
 *
 * Responsabilidades:
 * <ol>
 *   <li>Aceptar conexiones entrantes desde células (GUI/SERVER) y nodos vecinos.</li>
 *   <li>Iniciar conexiones canónicas hacia los pares con ID mayor para evitar
 *       túneles paralelos; reconectar con backoff ante caídas.</li>
 *   <li>Recibir mensajes JSON línea por línea y aplicar deduplicación atómica
 *       antes de cualquier reenvío.</li>
 *   <li>Reenviar el mensaje a todos los demás pares y a todas las demás células
 *       conectadas localmente; respeta el sobre (createdAt, expiresAt) original.</li>
 *   <li>Persistir eventos relevantes en {@link AdsPersistence} para el historial
 *       aislado del parcial 2.</li>
 * </ol>
 *
 * El nodo nunca decide el servidor que responde: eso corresponde a la GUI al
 * fijar {@code targetServerId} en la solicitud.
 */
public class AdsNode {

    private final String nodeId;
    private final int port;
    private final List<AdsPeer> configuredPeers;
    private final Map<String, AdsPeer> peersById;
    private final AdsLogger logger;
    private final AdsDedup dedup = new AdsDedup();
    private final AdsPersistence persistence;

    private final Map<String, AdsConnection> peerConnections = new ConcurrentHashMap<>();
    private final Map<String, AdsConnection> cellConnections = new ConcurrentHashMap<>();
    private final Map<String, AdsRole> cellRoles = new ConcurrentHashMap<>();
    private final Map<String, String> cellNodes = new ConcurrentHashMap<>();

    private ServerSocket serverSocket;
    private final ExecutorService ioPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ads-io");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService housekeeping = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ads-housekeeping");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running;
    private final Map<String, Long> dialBackoff = new ConcurrentHashMap<>();

    public AdsNode(String nodeId, int port, List<AdsPeer> configuredPeers) {
        this.nodeId = nodeId;
        this.port = port;
        this.configuredPeers = configuredPeers == null ? List.of() : List.copyOf(configuredPeers);
        Map<String, AdsPeer> byId = new HashMap<>();
        for (AdsPeer p : this.configuredPeers) {
            byId.put(p.nodeId(), p);
        }
        this.peersById = Collections.unmodifiableMap(byId);
        this.logger = new AdsLogger("NODE/" + nodeId);
        this.persistence = new AdsPersistence("nodes", nodeId);
    }

    public String nodeId() { return nodeId; }
    public int port() { return port; }
    public AdsDedup dedup() { return dedup; }
    public AdsPersistence persistence() { return persistence; }
    public AdsLogger logger() { return logger; }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        logger.info("START", "Nodo ADS " + nodeId + " escuchando en puerto " + port);

        ioPool.submit(this::acceptLoop);
        housekeeping.scheduleWithFixedDelay(this::housekeep, 1, 1, TimeUnit.SECONDS);

        // DialALL: cada peer configurable se diala siempre (sin filtro por ID).
        // La dirección canónica se decide en registerConnectionWithDirection
        // mediante atomic compute con Direction metadata. El menor entre
        // (self, peerId) prefiere OUTBOUND (su dial); el mayor prefiere INBOUND
        // (acepta el dial del menor). Ambos extremos convergen al mismo TCP.
        // Late join unilateral: si una conexión llega en dirección no-preferred
        // y NO existe la preferred, se acepta (no se descarta un canal vivo).
        for (AdsPeer peer : configuredPeers) {
            ioPool.submit(() -> dialPeerLoop(peer));
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // cierre best-effort
        }
        for (AdsConnection c : peerConnections.values()) {
            c.close();
        }
        for (AdsConnection c : cellConnections.values()) {
            c.close();
        }
        peerConnections.clear();
        cellConnections.clear();
        housekeeping.shutdownNow();
        ioPool.shutdownNow();
        logger.info("STOP", "Nodo " + nodeId + " detenido");
    }

    public boolean isRunning() {
        return running;
    }

    public List<String> activeCellIds() {
        return new ArrayList<>(cellConnections.keySet());
    }

    public List<String> activePeerIds() {
        return new ArrayList<>(peerConnections.keySet());
    }

    public int cellCount() {
        return cellConnections.size();
    }

    public int peerCount() {
        return peerConnections.size();
    }

    public void registerCellLocally(String cellId, AdsRole role, String originNodeId) {
        cellRoles.put(cellId, role);
        cellNodes.put(cellId, originNodeId == null ? nodeId : originNodeId);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = serverSocket.accept();
                s.setSoTimeout(0);
                ioPool.submit(() -> handleNewSocket(s));
            } catch (IOException e) {
                if (running) {
                    logger.warn("ACCEPT", "Aceptación interrumpida: " + e.getMessage());
                }
            }
        }
    }

    private void handleNewSocket(Socket s) {
        AdsConnection conn = null;
        try {
            conn = new AdsConnection(s, s.getRemoteSocketAddress().toString());
            String firstLine = conn.readLine();
            if (firstLine == null || firstLine.isBlank()) {
                logger.warn("HANDSHAKE", "Conexión cerrada antes del HELLO");
                conn.close();
                return;
            }
            AdsMessage hello = AdsMessage.fromJson(firstLine);
            if (hello == null || hello.getType() == null || hello.getType().isBlank()) {
                logger.warn("HANDSHAKE", "Mensaje inicial sin tipo");
                conn.close();
                return;
            }
            AdsMessageType t;
            try {
                t = AdsMessageType.fromString(hello.getType());
            } catch (IllegalArgumentException iae) {
                logger.warn("HANDSHAKE", "Tipo HELLO inválido: " + hello.getType());
                conn.close();
                return;
            }
            switch (t) {
                case HELLO_NODE -> registerPeerConnection(hello, conn);
                case HELLO_GUI -> registerCellConnection(hello, AdsRole.GUI, conn);
                case HELLO_SERVER -> registerCellConnection(hello, AdsRole.SERVER, conn);
                default -> {
                    logger.warn("HANDSHAKE", "Primer mensaje no es HELLO: " + t);
                    conn.close();
                }
            }
        } catch (Exception e) {
            logger.error("HANDSHAKE", "Fallo al aceptar conexión: " + e.getMessage(), e);
            if (conn != null) {
                conn.close();
            }
        }
    }

    private void registerPeerConnection(AdsMessage hello, AdsConnection conn) {
        String peerId = hello.getInstanceId();
        if (peerId == null || peerId.isBlank()) {
            peerId = hello.getOriginId();
        }
        if (peerId == null) {
            logger.warn("PEER", "HELLO_NODE sin instanceId ni originId");
            conn.close();
            return;
        }
        if (peerId.equals(nodeId)) {
            logger.event("PEER_SELF", "HELLO_NODE de mí mismo, descartado");
            conn.close();
            return;
        }
        registerConnectionWithDirection(peerId, conn, AdsDirection.INBOUND);
        AdsConnection winner = peerConnections.get(peerId);
        if (winner != conn) {
            // No ganó; perdimos el desempate y nuestra conn ya fue cerrada por register.
            logger.event("PEER_REJECTED",
                    "Conexión inbound de " + peerId + " no preferred, descartada");
            return;
        }
        AdsMessage ack = AdsMessage.create(AdsMessageType.HELLO_ACK);
        ack.setOriginId(nodeId);
        ack.setInstanceId(nodeId);
        ack.setSenderId(nodeId);
        ack.setSenderRole(AdsRole.NODE.name());
        ack.setStatus("SUCCESS");
        conn.send(ack);
        logger.event("PEER_UP", "Vecino conectado " + peerId + " (inbound preferred)");
        spawnReader(conn, AdsRole.NODE, peerId);
        dialBackoff.remove(peerId);
    }

    /**
     * Registro con dirección canónica. Una sola conexión por par:
     * <ul>
     *   <li>Si el par canónico (self, peerId) tiene self &lt; peerId,
     *       self es el menor → prefiere OUTBOUND (su dial).</li>
     *   <li>Si self &gt; peerId, self es el mayor → prefiere INBOUND
     *       (acepta el dial del menor).</li>
     *   <li>Sin existing: aceptamos la nueva (late join unilateral).</li>
     *   <li>Nueva dirección == preferred: reemplaza existing.</li>
     *   <li>Nueva dirección != preferred: cierra la nueva (existing preferred).</li>
     * </ul>
     * Ambos extremos convergen al mismo TCP físico usando la misma regla.
     */
    private void registerConnectionWithDirection(String peerId, AdsConnection conn, AdsDirection dir) {
        AdsConnection[] rejectedRef = new AdsConnection[1];
        boolean[] wonRef = new boolean[1];
        conn.setDirection(dir);
        peerConnections.compute(peerId, (k, existing) -> {
            boolean selfIsMin = nodeId.compareTo(peerId) < 0;
            AdsDirection preferred = selfIsMin ? AdsDirection.OUTBOUND : AdsDirection.INBOUND;
            if (existing == null) {
                wonRef[0] = true;
                return conn;
            }
            if (dir == preferred) {
                rejectedRef[0] = existing;
                wonRef[0] = true;
                return conn;
            }
            // Existing gana; el caller cerrará el conn perdedor.
            wonRef[0] = false;
            return existing;
        });
        if (!wonRef[0]) {
            conn.close();
            return;
        }
        if (rejectedRef[0] != null) {
            AdsConnection rejected = rejectedRef[0];
            rejected.close();
            logger.event("PEER_REPLACED",
                    "Conexión previa de " + peerId + " reemplazada por preferred " + dir);
        }
    }

    private void registerCellConnection(AdsMessage hello, AdsRole role, AdsConnection conn) {
        String cellId = hello.getInstanceId();
        if (cellId == null || cellId.isBlank()) {
            cellId = hello.getOriginId();
        }
        if (cellId == null) {
            logger.warn("CELL", "HELLO sin instanceId/originId");
            conn.close();
            return;
        }
        AdsConnection prior = cellConnections.put(cellId, conn);
        if (prior != null && prior != conn) {
            prior.close();
        }
        cellRoles.put(cellId, role);
        cellNodes.put(cellId, hello.getOriginId() == null ? nodeId : hello.getOriginId());

        AdsMessage ack = AdsMessage.create(AdsMessageType.HELLO_ACK);
        ack.setOriginId(nodeId);
        ack.setInstanceId(nodeId);
        ack.setSenderId(nodeId);
        ack.setSenderRole(AdsRole.NODE.name());
        ack.setStatus("SUCCESS");
        ack.setContentCode(role.name() + "_HELLO_ACK");
        conn.send(ack);
        logger.event(role.name() + "_UP", "Célula local " + cellId + " (" + role + ") en " + nodeId);
        spawnReader(conn, role, cellId);
    }

    private void spawnReader(AdsConnection conn, AdsRole role, String remoteId) {
        ioPool.submit(() -> {
            try {
                String line;
                while (running && (line = conn.readLine()) != null) {
                    if (line.isBlank()) continue;
                    try {
                        AdsMessage msg = AdsMessage.fromJson(line);
                        if (msg == null) continue;
                        onMessageReceived(role, remoteId, conn, msg);
                    } catch (Throwable perLine) {
                        logger.error("READ_PARSE",
                                role + " " + remoteId + " malformado: " + perLine.getMessage(), perLine);
                    }
                }
            } catch (IOException e) {
                logger.info("READ_END", role + " " + remoteId + " colgó: " + e.getMessage());
            } finally {
                onConnectionClosed(role, remoteId, conn);
            }
        });
    }

    private void onConnectionClosed(AdsRole role, String remoteId, AdsConnection conn) {
        if (role == AdsRole.NODE) {
            // Quitar solo si la entrada sigue siendo esta conexión; si la
            // dirección canónica fue reemplazada por otra, no borrar al ganador.
            if (peerConnections.remove(remoteId, conn)) {
                logger.event("PEER_DOWN", "Vecino " + remoteId + " desconectado");
            }
        } else {
            if (cellConnections.remove(remoteId, conn)) {
                cellRoles.remove(remoteId);
                cellNodes.remove(remoteId);
                logger.event(role.name() + "_DOWN", "Célula " + remoteId + " desconectada");
            }
        }
        conn.close();
    }

    private void onMessageReceived(AdsRole sourceRole, String sourceId, AdsConnection sourceConn, AdsMessage msg) {
        try {
            if (msg.getType() == null || msg.getType().isBlank()) {
                logger.warn("DROP_NO_TYPE", "Mensaje sin type de " + sourceId);
                return;
            }
            AdsMessageType type;
            try {
                type = AdsMessageType.fromString(msg.getType());
            } catch (IllegalArgumentException iae) {
                logger.warn("DROP_BAD_TYPE", "type=" + msg.getType() + " from=" + sourceId);
                return;
            }
            // RECV observado en este nodo (antes de cualquier filtro).
            logger.event("RECV",
                    "mid=" + msg.getMessageId() + " type=" + msg.getType()
                            + " code=" + (msg.getContentCode() == null ? "-" : msg.getContentCode())
                            + " from=" + sourceId + " hop=" + msg.getHopCount());
            if (msg.isExpired()) {
                dedup.recordExpired();
                logger.event("DROP_EXPIRED",
                        "messageId=" + msg.getMessageId() + " type=" + type + " from=" + sourceId);
                return;
            }
            String mid = msg.getMessageId();
            if (mid == null || mid.isBlank()) {
                logger.warn("DROP_NO_ID", "Mensaje sin messageId de " + sourceId);
                return;
            }
            if (!dedup.tryRegister(mid)) {
                logger.event("DROP_DUPLICATE",
                        "messageId=" + mid + " type=" + msg.getType() + " from=" + sourceId);
                return;
            }
            msg.bumpHop();
            if (msg.getSenderId() == null) {
                msg.setSenderId(sourceId);
                msg.setSenderRole(sourceRole.name());
            }

            switch (type) {
                case HELLO_NODE, HELLO_GUI, HELLO_SERVER, HELLO_ACK -> {
                    // HELLO solo aplica al socket que abrió la conexión; no se reenvía.
                    return;
                }
                case GOODBYE -> {
                    logger.event("GOODBYE", sourceId + " dijo goodbye");
                    sourceConn.close();
                    return;
                }
                case ERROR -> logger.warn("REMOTE_ERROR", msg.getError());
                default -> { /* sigue flujo normal */ }
            }

            forwardEverywhere(sourceConn, msg, sourceId);
        } catch (Throwable t) {
            // Sender malformado nunca debe tumbar al reader global.
            logger.error("RELAY_FAIL",
                    "Excepción procesando mensaje de " + sourceId + ": " + t.getMessage(), t);
        }
    }

    /**
     * Reenvío por contenido: TODOS los pares y TODAS las demás células reciben el
     * mensaje (excepto la conexión origen para no eco).
     *
     * El nodo NO filtra por {@code targetServerId} ni por servicio: el broadcast
     * es total. El arbitraje de ejecución ocurre en la célula servidora después
     * de la coincidencia de {@code contentCode}; el GUI acepta la primera respuesta
     * que coincida con su {@code requestId} + target fijado.
     *
     * Trade-off documentado: el emisor conoce la identidad lógica del ejecutor
     * (UUID de instancia publicado en anuncios); esto sacrifica anonimato del
     * routing a cambio de respuesta única determinista. No constituye acoplamiento
     * de transporte ni ruta punto-a-punto: si la instancia elegida cae, el
     * mensaje ya está en vuelo y no se reasigna (timeout visible).
     */
    private void forwardEverywhere(AdsConnection sourceConn, AdsMessage msg, String sourceId) {
        int fwdCount = 0;
        // Otros peers (broadcast por contenido).
        for (Map.Entry<String, AdsConnection> e : peerConnections.entrySet()) {
            AdsConnection c = e.getValue();
            if (c == sourceConn) continue;
            if (!c.isOpen()) continue;
            c.send(msg);
            fwdCount++;
        }
        // Otras células locales (broadcast por contenido).
        for (Map.Entry<String, AdsConnection> e : cellConnections.entrySet()) {
            AdsConnection c = e.getValue();
            if (c == sourceConn) continue;
            if (!c.isOpen()) continue;
            c.send(msg);
            fwdCount++;
        }
        // Log de relay aceptado (no sólo para drops). Esto cumple el requisito
        // del PDF de "recorrido observable en consola/bitácora" sin añadir
        // telemetría abstracta: queda en los logs por mensaje.
        logger.event("RELAY",
                "mid=" + msg.getMessageId() + " type=" + msg.getType()
                        + " code=" + (msg.getContentCode() == null ? "-" : msg.getContentCode())
                        + " req=" + (msg.getRequestId() == null ? "-" : msg.getRequestId())
                        + " from=" + sourceId + " hop=" + msg.getHopCount()
                        + " fanned=" + fwdCount);
    }

    private void dialPeerLoop(AdsPeer peer) {
        long backoff = AdsConstants.PEER_RECONNECT_MS;
        while (running) {
            AdsConnection existing = peerConnections.get(peer.nodeId());
            if (existing == null || !existing.isOpen()) {
                try {
                    Socket sock = new java.net.Socket();
                    sock.connect(new java.net.InetSocketAddress(peer.host(), peer.port()), 2_000);
                    AdsConnection conn = new AdsConnection(sock, peer.toString());
                    AdsMessage hello = AdsMessage.create(AdsMessageType.HELLO_NODE);
                    hello.setOriginId(nodeId);
                    hello.setInstanceId(nodeId);
                    hello.setSenderId(nodeId);
                    hello.setSenderRole(AdsRole.NODE.name());
                    hello.setHost(sock.getLocalAddress().getHostAddress());
                    hello.setPort(sock.getLocalPort());
                    conn.send(hello);
                    // El registro debe usar la identidad del PAR (peer.nodeId),
                    // no la nuestra, para no apuntar al contrato local.
                    registerOutboundPeer(peer.nodeId(), hello, conn);
                    backoff = AdsConstants.PEER_RECONNECT_MS;
                } catch (IOException e) {
                    long next = backoff;
                    dialBackoff.put(peer.nodeId(), next);
                    try {
                        Thread.sleep(next);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    backoff = Math.min(backoff * 2, 10_000L);
                }
            } else {
                try {
                    Thread.sleep(AdsConstants.PEER_RECONNECT_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Registro del par cuando nosotros somos los dialers: el ID del par viene
     * de la configuración. Aplicamos la misma regla de dirección que el accept
     * (direction metadata + atomic compute) para converger al mismo TCP en
     * ambos extremos sin "doble túnel" simultáneo.
     */
    private void registerOutboundPeer(String peerNodeId, AdsMessage hello, AdsConnection conn) {
        registerConnectionWithDirection(peerNodeId, conn, AdsDirection.OUTBOUND);
        AdsConnection winner = peerConnections.get(peerNodeId);
        if (winner != conn) {
            logger.event("PEER_REJECTED",
                    "Dial outbound a " + peerNodeId + " no preferred, descartado");
            return;
        }
        AdsMessage ack = AdsMessage.create(AdsMessageType.HELLO_ACK);
        ack.setOriginId(nodeId);
        ack.setInstanceId(nodeId);
        ack.setSenderId(nodeId);
        ack.setSenderRole(AdsRole.NODE.name());
        ack.setStatus("SUCCESS");
        conn.send(ack);
        logger.event("PEER_DIAL", "Dial a " + peerNodeId + " establecido (outbound preferred)");
        spawnReader(conn, AdsRole.NODE, peerNodeId);
        dialBackoff.remove(peerNodeId);
    }

    private void housekeep() {
        try {
            int purged = dedup.purgeExpired();
            if (purged > 0) {
                logger.info("HOUSEKEEP", "Dedup purgó " + purged + " IDs (size=" + dedup.size() + ")");
            }
        } catch (Throwable t) {
            logger.error("HOUSEKEEP", "Error purgando dedup: " + t.getMessage());
        }
    }

    /**
     * API usada por las pruebas y por la CLI para inyectar un mensaje y observar
     * el resultado, sin tener que esperar al siguiente ciclo de lectura.
     */
    public void injectForTest(String originInstanceId, AdsMessage msg) {
        AdsConnection c = cellConnections.get(originInstanceId);
        onMessageReceived(c == null ? AdsRole.GUI : cellRoles.getOrDefault(originInstanceId, AdsRole.GUI),
                originInstanceId, c, msg);
    }
}