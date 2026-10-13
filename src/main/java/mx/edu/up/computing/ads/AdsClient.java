package mx.edu.up.computing.ads;

import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Célula cliente GUI del Campo de Datos ADS.
 *
 * Arquitectura interna: dos executores independientes. El IO executor corre el
 * readLoop bloqueante; el heartbeat scheduler corre la reconexión y la
 * verificación de timeouts. Nunca se mezclan en un solo hilo, así el
 * timeout nunca se atasca detrás de un read().
 *
 * Validación de respuesta:
 * <ul>
 *   <li>requestId presente y correlacionado con pending</li>
 *   <li>contentCode == servicio esperado</li>
 *   <li>instanceId emisor == targetServerId pendiente</li>
 *   <li>clientId/originId del emisor coincide con nuestro clientId</li>
 *   <li>no expirado</li>
 * </ul>
 *
 * La transición pending → completed es atómica con {@link ConcurrentHashMap#compute}.
 */
public class AdsClient {

    private final String clientId;
    private final String instanceId;
    private volatile String nodeHost;
    private volatile int nodePort;
    private final AdsLogger logger;
    private final AdsPersistence persistence;
    private final AdsAdvertisementRegistry registry = new AdsAdvertisementRegistry();
    private final ConcurrentHashMap<String, PendingRequest> pending = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<ClientListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong ignoredResponses = new AtomicLong();
    private final AtomicLong timeouts = new AtomicLong();
    private final Object lifecycle = new Object();

    private volatile AdsConnection connection;
    private ExecutorService ioExecutor;
    private ScheduledExecutorService heartbeat;
    private volatile boolean running;

    public AdsClient(String clientId, String nodeHost, int nodePort) {
        this.clientId = clientId;
        this.instanceId = UUID.randomUUID().toString();
        this.nodeHost = nodeHost;
        this.nodePort = nodePort;
        this.logger = new AdsLogger("GUI/" + clientId + "/" + instanceId.substring(0, 8));
        this.persistence = new AdsPersistence("guis", clientId);
    }

    public String clientId() { return clientId; }
    public String instanceId() { return instanceId; }
    public AdsAdvertisementRegistry registry() { return registry; }
    public AdsPersistence persistence() { return persistence; }
    public AdsLogger logger() { return logger; }
    public String nodeHost() { return nodeHost; }
    public int nodePort() { return nodePort; }

    public void setDestination(String host, int port) {
        synchronized (lifecycle) {
            this.nodeHost = host;
            this.nodePort = port;
        }
    }

    public long sentCount() { return sent.get(); }
    public long receivedCount() { return received.get(); }
    public long acceptedCount() { return accepted.get(); }
    public long ignoredResponsesCount() { return ignoredResponses.get(); }
    public long timeoutCount() { return timeouts.get(); }

    public void addListener(ClientListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public boolean isConnected() {
        return connection != null && connection.isOpen();
    }

    /**
     * Inicia los executores. Idempotente: si ya están corriendo, no hace nada.
     * Llamadas repetidas (p.ej. tras disconnect/connect en UI) crean un
     * nuevo par de executores; el anterior fue terminado por disconnect().
     */
    public void connect() {
        synchronized (lifecycle) {
            if (running) {
                return;
            }
            running = true;
            String cid = clientId;
            ioExecutor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "ads-client-io-" + cid);
                t.setDaemon(true);
                return t;
            });
            heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ads-client-heartbeat-" + cid);
                t.setDaemon(true);
                return t;
            });
            // connectLoop bloquea en Socket.connect() + Thread.sleep(): NO
            // debe correr en el scheduled executor (bloquearía checkTimeouts
            // mientras el nodo está caído). Va al ioExecutor.
            ioExecutor.submit(this::connectLoop);
            heartbeat.scheduleWithFixedDelay(this::checkTimeouts,
                    500, 500, TimeUnit.MILLISECONDS);
        }
    }

    public void disconnect() {
        synchronized (lifecycle) {
            running = false;
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
    }

    private void connectLoop() {
        if (!running) return;
        if (connection != null && connection.isOpen()) return;
        long backoff = AdsConstants.PEER_RECONNECT_MS;
        while (running) {
            try {
                String host = nodeHost;
                int port = nodePort;
                Socket s = new Socket();
                s.connect(new java.net.InetSocketAddress(host, port), 2_000);
                AdsConnection conn = new AdsConnection(s, "node@" + host + ":" + port);
                AdsMessage hello = AdsMessage.create(AdsMessageType.HELLO_GUI);
                hello.setOriginId(clientId);
                hello.setInstanceId(instanceId);
                hello.setSenderId(instanceId);
                hello.setSenderRole(AdsRole.GUI.name());
                conn.send(hello);
                this.connection = conn;
                logger.event("NODE_LINK", "Conectado a nodo " + host + ":" + port);
                ioExecutor.submit(() -> readLoop(conn));
                return;
            } catch (IOException e) {
                if (!running) return;
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
            while (running && (line = conn.readLine()) != null) {
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
            if (running) {
                logger.info("READ_END", "Conexión cerrada: " + e.getMessage());
            }
        } finally {
            conn.close();
            if (connection == conn) {
                // Somos el dueño de la conexión actual; la limpiamos y
                // pedimos reconexión al ioExecutor (no al scheduled).
                connection = null;
                if (running) {
                    ioExecutor.submit(this::connectLoop);
                }
            }
            // Si connection != conn, otra ruta ya tomó el control; este
            // reader viejo termina silenciosamente sin duplicar reintentos.
        }
    }

    private void onMessage(AdsMessage msg) {
        AdsMessageType t = msg.getTypeEnum();
        if (t == null) return;
        switch (t) {
            case ADVERTISE -> handleAdvertise(msg);
            case SUM_RES, SUB_RES, MUL_RES, DIV_RES -> handleResponse(msg);
            default -> { /* otros tipos se ignoran para cliente */ }
        }
    }

    private void handleAdvertise(AdsMessage msg) {
        if (msg.getInstanceId() == null || msg.getService() == null) {
            return;
        }
        Long expiresAt = msg.getExpiresAt();
        Long createdAt = msg.getCreatedAt();
        long now = System.currentTimeMillis();
        // El emisor fija su lease; si falta o es inválido, descartamos.
        // El GUI nunca fabrica lease propio (decisión documentada).
        if (expiresAt == null || createdAt == null || expiresAt <= createdAt) {
            ignoredResponses.incrementAndGet();
            logger.event("ADV_INVALID_LEASE",
                    "instanceId=" + msg.getInstanceId() + " createdAt=" + createdAt
                            + " expiresAt=" + expiresAt);
            return;
        }
        if (expiresAt <= now) {
            // Ya vencido al recibirlo
            return;
        }
        AdsService svc = AdsService.fromCode(msg.getService());
        AdsAdvertisement ad = new AdsAdvertisement(
                msg.getInstanceId(),
                svc,
                msg.getNodeId(),
                msg.getHost(),
                msg.getPort() == null ? 0 : msg.getPort(),
                createdAt,
                expiresAt
        );
        registry.offer(ad);
        // No persistimos cada ADVERTISE_RX (O(n²) por 2s/anuncio por GUI).
        // El registro en memoria basta para la GUI; si se necesita snapshot
        // histórico, queda en consola/log y en el archivo de la celda.
        for (ClientListener l : listeners) {
            l.onAdvertisement(ad);
        }
    }

    /**
     * Manejo de respuesta con validación completa. La transición
     * pending → aceptado se hace atómicamente vía {@code compute} para que un
     * timeout concurrente no produzca dos notificaciones.
     */
    private void handleResponse(AdsMessage msg) {
        received.incrementAndGet();
        if (msg.isExpired()) {
            ignoredResponses.incrementAndGet();
            logger.event("RESPONSE_EXPIRED",
                    "requestId=" + msg.getRequestId() + " server=" + msg.getTargetServerId());
            return;
        }
        String rid = msg.getRequestId();
        if (rid == null) {
            ignoredResponses.incrementAndGet();
            return;
        }
        if (msg.getContentCode() == null) {
            ignoredResponses.incrementAndGet();
            logger.event("RESPONSE_NO_CODE", "requestId=" + rid);
            return;
        }
        AdsService svc;
        try {
            svc = AdsService.fromCode(msg.getContentCode());
        } catch (IllegalArgumentException e) {
            ignoredResponses.incrementAndGet();
            logger.event("RESPONSE_BAD_CODE", "requestId=" + rid + " code=" + msg.getContentCode());
            return;
        }
        if (msg.getTargetServerId() == null || msg.getInstanceId() == null
                || !msg.getTargetServerId().equals(msg.getInstanceId())) {
            ignoredResponses.incrementAndGet();
            logger.event("RESPONSE_BAD_INSTANCE",
                    "requestId=" + rid + " target=" + msg.getTargetServerId()
                            + " instance=" + msg.getInstanceId());
            return;
        }
        if (!clientId.equals(msg.getClientId()) && !clientId.equals(msg.getOriginId())) {
            ignoredResponses.incrementAndGet();
            logger.event("RESPONSE_BAD_ORIGIN",
                    "requestId=" + rid + " client=" + msg.getClientId()
                            + " origin=" + msg.getOriginId() + " self=" + clientId);
            return;
        }
        if (msg.getResult() != null && !Double.isFinite(msg.getResult())) {
            ignoredResponses.incrementAndGet();
            logger.event("RESPONSE_NON_FINITE",
                    "requestId=" + rid + " result=" + msg.getResult());
            return;
        }

        // Atomicidad: el compute es la única operación que muta el pending.
        // Devolvemos null al aceptar para consumir la clave (mismo remove
        // atómico que remove(key)), evitando el bug remove(key, AdsMessage)
        // contra un valor PendingRequest.
        AdsMessage[] acceptedRef = new AdsMessage[1];
        pending.compute(rid, (k, existing) -> {
            if (existing == null) {
                return null;
            }
            if (existing.completed) {
                return existing;
            }
            if (existing.service != svc) {
                return existing;
            }
            if (!existing.targetServerId.equals(msg.getTargetServerId())) {
                return existing;
            }
            existing.completed = true;
            acceptedRef[0] = msg;
            return null;
        });

        if (acceptedRef[0] == null) {
            ignoredResponses.incrementAndGet();
            logger.event("RESPONSE_NOT_MATCH",
                    "requestId=" + rid + " target=" + msg.getTargetServerId());
            return;
        }
        accepted.incrementAndGet();
        logger.event("RESPONSE",
                "type=" + msg.getType() + " code=" + msg.getContentCode()
                        + " mid=" + msg.getMessageId()
                        + " req=" + rid
                        + " server=" + msg.getTargetServerId()
                        + " status=" + msg.getStatus()
                        + " result=" + msg.getResult());
        AdsRecord rec = AdsRecord.fromMessage("GUI", clientId, "RESPONSE_ACCEPTED", msg);
        persistence.appendRecord(rec);
        for (ClientListener l : listeners) {
            l.onResponse(msg);
        }
    }

    public String sendRequest(AdsService service, double a, double b) {
        List<AdsAdvertisement> candidates = registry.candidatesFor(service);
        if (candidates.isEmpty()) {
            logger.warn("SEND", "Sin candidatos vivos para " + service);
            for (ClientListener l : listeners) {
                l.onError("No hay candidatos vivos para " + service);
            }
            return null;
        }
        AdsAdvertisement target = candidates.get(0);
        AdsMessageType type = AdsContentCodes.requestTypeFor(service);
        AdsMessage msg = AdsMessage.create(type);
        msg.setContentCode(type.name());
        msg.setOriginId(clientId);
        msg.setClientId(clientId);
        msg.setSenderId(instanceId);
        msg.setSenderRole(AdsRole.GUI.name());
        msg.setTargetServerId(target.instanceId());
        msg.setService(service.name());
        msg.setOperandA(a);
        msg.setOperandB(b);
        PendingRequest req = new PendingRequest();
        req.requestId = msg.getMessageId();
        req.service = service;
        req.targetServerId = target.instanceId();
        req.deadline = System.currentTimeMillis() + AdsConstants.REQUEST_TIMEOUT_MS;
        pending.put(req.requestId, req);
        sent.incrementAndGet();
        logger.event("SEND",
                "type=" + msg.getType() + " code=" + msg.getContentCode()
                        + " mid=" + msg.getMessageId()
                        + " target=" + req.targetServerId
                        + " service=" + service
                        + " a=" + a + " b=" + b);
        // Capturar connection una sola vez: evitar NPE si un read EOF la
        // pone a null entre el check y el send (carrera EDT vs IO).
        AdsConnection liveConn = connection;
        if (liveConn == null || !liveConn.isOpen()) {
            logger.warn("SEND", "Sin conexión al nodo; abortando");
            pending.remove(req.requestId);
            return null;
        }
        liveConn.send(msg);
        AdsRecord rec = AdsRecord.fromMessage("GUI", clientId, "REQUEST_SENT", msg);
        persistence.appendRecord(rec);
        for (ClientListener l : listeners) {
            l.onRequestSent(msg);
        }
        return req.requestId;
    }

    /**
     * Verificación periódica de timeouts. Atómica con {@code compute}: solo
     * el primer hilo en marcar {@code completed=true} dispara la notificación.
     */
    private void checkTimeouts() {
        long now = System.currentTimeMillis();
        for (PendingRequest req : pending.values()) {
            if (!req.completed && req.deadline < now) {
                String[] notifyRef = new String[1];
                AdsService[] svcRef = new AdsService[1];
                String[] targetRef = new String[1];
                pending.compute(req.requestId, (k, existing) -> {
                    if (existing == null || existing.completed) {
                        return existing;
                    }
                    existing.completed = true;
                    notifyRef[0] = k;
                    svcRef[0] = existing.service;
                    targetRef[0] = existing.targetServerId;
                    return null;
                });
                if (notifyRef[0] != null) {
                    timeouts.incrementAndGet();
                    logger.warn("TIMEOUT",
                            "requestId=" + notifyRef[0] + " target=" + targetRef[0]);
                    for (ClientListener l : listeners) {
                        l.onTimeout(notifyRef[0], svcRef[0], targetRef[0]);
                    }
                }
            }
        }
    }

    public interface ClientListener {
        void onAdvertisement(AdsAdvertisement ad);
        void onRequestSent(AdsMessage msg);
        void onResponse(AdsMessage msg);
        void onTimeout(String requestId, AdsService service, String targetServerId);
        void onError(String text);
    }

    private static final class PendingRequest {
        String requestId;
        AdsService service;
        String targetServerId;
        long deadline;
        volatile boolean completed;
    }
}