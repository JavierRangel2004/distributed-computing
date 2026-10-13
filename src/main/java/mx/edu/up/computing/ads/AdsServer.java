package mx.edu.up.computing.ads;

import java.io.IOException;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Célula servidor ADS: se conecta a su nodo y permanece a la escucha del broadcast.
 *
 * Responsabilidades:
 * <ol>
 *   <li>UUID único por instancia al arrancar (no reutiliza etiqueta CLI).</li>
 *   <li>Anuncios periódicos con lease propio; el cliente nunca renueva leases.</li>
 *   <li>Procesa solo cuando {@code contentCode} coincide con su servicio Y
 *      {@code targetServerId == instanceId}; sin reasignación de solicitud en vuelo.</li>
 *   <li>Dedup propio: además del del nodo, el servidor registra messageIds
 *      vistos para protegerse de broadcasts duplicados en conexiones paralelas.</li>
 *   <li>Valida expiresAt y que instanceId emisor del RES sea el propio.</li>
 * </ol>
 *
 * Separación IO/heartbeat: el scheduled executor maneja reconexión y
 * anuncios; el IO executor corre el readLoop bloqueante. Nunca se mezclan
 * en el mismo hilo (sin esto, el advertiseLoop nunca correría y discovery
 * fallaría).
 */
public class AdsServer {

    private final String serverId;
    private final String instanceId;
    private final AdsService service;
    private final String nodeHost;
    private final int nodePort;
    private final AdsCalculatorEngine engine;
    private final AdsLogger logger;
    private final AdsPersistence persistence;
    private final ConcurrentHashMap<String, Long> seenIds = new ConcurrentHashMap<>();
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong ignoredByTarget = new AtomicLong();
    private final AtomicLong ignoredByService = new AtomicLong();
    private final AtomicLong ignoredByExpired = new AtomicLong();
    private final AtomicLong ignoredByDuplicate = new AtomicLong();
    private final Object lifecycle = new Object();

    private volatile AdsConnection connection;
    private ExecutorService ioExecutor;
    private ScheduledExecutorService heartbeat;
    private volatile boolean runningFlag;

    public AdsServer(String serverId, AdsService service, String nodeHost, int nodePort) {
        this.serverId = serverId;
        this.instanceId = UUID.randomUUID().toString();
        this.service = service;
        this.nodeHost = nodeHost;
        this.nodePort = nodePort;
        this.engine = new AdsCalculatorEngine(service);
        this.logger = new AdsLogger("SRV/" + serverId + "/" + service + "/" + instanceId.substring(0, 8));
        this.persistence = new AdsPersistence("servers", serverId);
    }

    public String serverId() { return serverId; }
    public String instanceId() { return instanceId; }
    public AdsService service() { return service; }
    public long processedCount() { return processed.get(); }
    public long ignoredByTargetCount() { return ignoredByTarget.get(); }
    public long ignoredByServiceCount() { return ignoredByService.get(); }
    public long ignoredByExpiredCount() { return ignoredByExpired.get(); }
    public long ignoredByDuplicateCount() { return ignoredByDuplicate.get(); }
    public AdsPersistence persistence() { return persistence; }
    public boolean isRunning() { return runningFlag; }
    public boolean isConnected() { return connection != null && connection.isOpen(); }

    public void start() {
        synchronized (lifecycle) {
            if (runningFlag) return;
            runningFlag = true;
            String sid = serverId;
            ioExecutor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "ads-server-io-" + sid);
                t.setDaemon(true);
                return t;
            });
            heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ads-server-heartbeat-" + sid);
                t.setDaemon(true);
                return t;
            });
            // connectLoop bloquea en Socket.connect() + sleep(): va al
            // ioExecutor para no interrumpir advertiseLoop ni discovery.
            ioExecutor.submit(this::connectLoop);
            heartbeat.scheduleWithFixedDelay(this::advertiseLoop,
                    0, AdsConstants.ADVERTISE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    public void stop() {
        synchronized (lifecycle) {
            runningFlag = false;
            if (connection != null) {
                connection.close();
                connection = null;
            }
            if (heartbeat != null) {
                heartbeat.shutdownNow();
                heartbeat = null;
            }
            if (ioExecutor != null) {
                ioExecutor.shutdownNow();
                ioExecutor = null;
            }
        }
        logger.info("STOP", "Servidor " + serverId + " detenido");
    }

    private void connectLoop() {
        if (!runningFlag) return;
        if (connection != null && connection.isOpen()) return;
        long backoff = AdsConstants.PEER_RECONNECT_MS;
        while (runningFlag) {
            try {
                Socket s = new Socket();
                s.connect(new java.net.InetSocketAddress(nodeHost, nodePort), 2_000);
                AdsConnection conn = new AdsConnection(s, "node@" + nodeHost + ":" + nodePort);
                AdsMessage hello = AdsMessage.create(AdsMessageType.HELLO_SERVER);
                hello.setOriginId(serverId);
                hello.setInstanceId(instanceId);
                hello.setSenderId(instanceId);
                hello.setSenderRole(AdsRole.SERVER.name());
                hello.setService(service.name());
                hello.setNodeId(deriveNodeId());
                conn.send(hello);
                this.connection = conn;
                logger.event("NODE_LINK", "Conectado a nodo " + nodeHost + ":" + nodePort);
                ioExecutor.submit(() -> readLoop(conn));
                return;
            } catch (IOException e) {
                if (!runningFlag) return;
                logger.warn("NODE_LINK", "Fallo al conectar a nodo: " + e.getMessage());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoff = Math.min(backoff * 2, 10_000L);
            }
        }
    }

    private void readLoop(AdsConnection conn) {
        try {
            String line;
            while (runningFlag && (line = conn.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    AdsMessage msg = AdsMessage.fromJson(line);
                    if (msg == null) continue;
                    onMessage(msg);
                } catch (Throwable perLine) {
                    logger.error("READ_PARSE",
                            "Línea malformada descartada: " + perLine.getMessage(), perLine);
                }
            }
        } catch (IOException e) {
            if (runningFlag) {
                logger.info("READ_END", "Conexión cerrada: " + e.getMessage());
            }
        } finally {
            conn.close();
            if (connection == conn) {
                // Dueño: limpiamos estado y pedimos reconexión al ioExecutor.
                connection = null;
                if (runningFlag) {
                    ioExecutor.submit(this::connectLoop);
                }
            }
            // Si connection != conn, otro reader ya es dueño; terminamos sin
            // duplicar reintentos (no nueva abstracción necesaria).
        }
    }

    private void advertiseLoop() {
        if (!runningFlag || connection == null || !connection.isOpen()) {
            return;
        }
        AdsMessage ad = AdsMessage.create(AdsMessageType.ADVERTISE);
        ad.setOriginId(serverId);
        ad.setInstanceId(instanceId);
        ad.setSenderId(instanceId);
        ad.setSenderRole(AdsRole.SERVER.name());
        ad.setService(service.name());
        ad.setNodeId(deriveNodeId());
        ad.setHost(nodeHost);
        ad.setPort(nodePort);
        // Lease del anuncio: 6 s desde la emisión, no el TTL por defecto
        // del sobre (30 s). El GUI nunca debe fabricar lease.
        long now = System.currentTimeMillis();
        ad.setCreatedAt(now);
        ad.setExpiresAt(now + AdsConstants.ADVERTISE_LEASE_MS);
        connection.send(ad);
    }

    private String deriveNodeId() {
        int dash = serverId.indexOf('-');
        if (dash <= 0) return "";
        return serverId.substring(0, dash);
    }

    /**
     * Arbitraje: el servidor recibe el broadcast (el nodo NO enruta por
     * target). Decide ejecutar primero por {@code contentCode} y después
     * por identidad lógica del ejecutor (targetServerId).
     *
     * Validaciones locales adicionales:
     * <ul>
     *   <li>expiresAt no vencido</li>
     *   <li>messageId no visto antes (dedup servidor además del nodo)</li>
     *   <li>clientId/originId presentes</li>
     * </ul>
     */
    private void onMessage(AdsMessage msg) {
        AdsMessageType t = msg.getTypeEnum();
        if (t == null) return;
        if (!AdsContentCodes.isRequest(t)) {
            return;
        }
        if (msg.isExpired()) {
            ignoredByExpired.incrementAndGet();
            logger.event("IGNORE_EXPIRED",
                    "msgId=" + msg.getMessageId() + " from=" + msg.getSenderId());
            return;
        }
        String mid = msg.getMessageId();
        if (mid == null) return;
        Long prev = seenIds.putIfAbsent(mid, System.currentTimeMillis());
        if (prev != null) {
            ignoredByDuplicate.incrementAndGet();
            logger.event("IGNORE_DUPLICATE", "msgId=" + mid);
            return;
        }
        // purga oportunista: si supera TTL, lo olvidamos
        purgeSeenIds();

        AdsService requested = AdsContentCodes.serviceOfRequest(t);
        if (requested != service) {
            ignoredByService.incrementAndGet();
            logger.event("IGNORE_SERVICE",
                    "msgId=" + msg.getMessageId() + " service=" + requested + " (yo=" + service + ")");
            return;
        }
        String target = msg.getTargetServerId();
        if (target == null || !instanceId.equals(target)) {
            ignoredByTarget.incrementAndGet();
            logger.event("IGNORE_TARGET",
                    "msgId=" + msg.getMessageId() + " target=" + target + " (yo=" + instanceId + ")");
            return;
        }
        if (msg.getClientId() == null || msg.getOriginId() == null) {
            logger.warn("DROP_NO_CLIENTID", "msgId=" + mid);
            return;
        }

        AdsCalculatorEngine.AdsCalculationResult result = engine.compute(msg);
        AdsMessageType responseType = AdsContentCodes.responseTypeFor(service);
        AdsMessage response = AdsMessage.create(responseType);
        response.setRequestId(msg.getMessageId());
        response.setContentCode(responseType.name());
        // originId del RESPONSE = instanceId del servidor emisor.
        response.setOriginId(instanceId);
        response.setClientId(msg.getClientId());
        response.setTargetServerId(instanceId);
        response.setInstanceId(instanceId);
        response.setSenderId(instanceId);
        response.setSenderRole(AdsRole.SERVER.name());
        response.setService(service.name());
        response.setOperandA(msg.getOperandA());
        response.setOperandB(msg.getOperandB());
        if (result.isSuccess()) {
            response.setResult(result.value());
            response.setStatus(result.status());
        } else {
            response.setStatus(result.status());
            response.setError(result.errorMessage());
        }
        processed.incrementAndGet();
        logger.event("COMPUTE",
                "type=" + msg.getType() + " code=" + msg.getContentCode()
                        + " mid=" + msg.getMessageId()
                        + " op=" + service
                        + " a=" + msg.getOperandA() + " b=" + msg.getOperandB()
                        + " status=" + response.getStatus()
                        + (response.getResult() != null ? " result=" + response.getResult() : ""));
        if (connection != null && connection.isOpen()) {
            connection.send(response);
            logger.event("RESPONSE_SENT",
                    "type=" + response.getType() + " code=" + response.getContentCode()
                            + " mid=" + response.getMessageId()
                            + " req=" + response.getRequestId()
                            + " srv=" + response.getTargetServerId()
                            + " status=" + response.getStatus()
                            + (response.getResult() != null ? " result=" + response.getResult() : ""));
        }
        AdsRecord rec = AdsRecord.fromMessage("SERVER", serverId, "COMPUTE_" + service, response);
        persistence.appendRecord(rec);
    }

    private void purgeSeenIds() {
        long cutoff = System.currentTimeMillis() - AdsConstants.DEDUP_TTL_MS;
        seenIds.entrySet().removeIf(e -> e.getValue() < cutoff);
    }

    /**
     * Acceso de prueba para validar el dedup propio del servidor. Llamar con
     * dos mensajes del mismo messageId y verificar que {@code processedCount}
     * aumenta a lo sumo en uno.
     */
    public void processForTest(AdsMessage msg) {
        onMessage(msg);
    }
}