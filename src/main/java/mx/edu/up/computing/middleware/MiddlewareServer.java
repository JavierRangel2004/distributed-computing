package mx.edu.up.computing.middleware;

import mx.edu.up.computing.common.JsonCodec;
import mx.edu.up.computing.common.MessageType;
import mx.edu.up.computing.common.NetworkMessage;
import mx.edu.up.computing.common.Role;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Servidor Middleware de Capa 2.
 * Responsabilidades:
 * 1. Escucha en puerto TCP y distingue conexiones de CLIENTES y SERVIDORES mediante handshake.
 * 2. Recibe solicitudes de clientes y las retransmite a TODOS los servidores conectados.
 * 3. Recibe respuestas de los servidores y las retransmite a TODOS los clientes conectados.
 * 4. Gestiona la concurrencia y tolerancia a fallos ante desconexiones dinámicas.
 */
public class MiddlewareServer {
    private final int port;
    private ServerSocket serverSocket;
    private volatile boolean running = true;
    private final ExecutorService threadPool = Executors.newCachedThreadPool();

    private final Map<String, ClientHandler> activeClients = new ConcurrentHashMap<>();
    private final Map<String, ServerHandler> activeServers = new ConcurrentHashMap<>();

    private final AtomicLong totalRequestsRouted = new AtomicLong(0);
    private final AtomicLong totalResponsesRouted = new AtomicLong(0);

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public MiddlewareServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        log("==================================================================");
        log(">>> MIDDLEWARE INICIADO EN PUERTO " + port + " <<<");
        log("Arquitectura de 3 Capas: Sockets TCP y Enrutamiento de Difusión");
        log("Esperando conexiones entrantes de Clientes Pesados y Servidores...");
        log("==================================================================");

        threadPool.execute(this::acceptLoop);
    }

    private void acceptLoop() {
        while (running && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                threadPool.execute(() -> handleNewConnection(clientSocket));
            } catch (IOException e) {
                if (!running) break;
                log("Aviso en acceptLoop: " + e.getMessage());
            }
        }
    }

    /**
     * Handshake inicial: Lee el primer mensaje para identificar si es CLIENT o SERVER.
     */
    private void handleNewConnection(Socket socket) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);

            String firstLine = in.readLine();
            if (firstLine == null || firstLine.trim().isEmpty()) {
                socket.close();
                return;
            }

            NetworkMessage registerMsg = JsonCodec.fromJson(firstLine);
            if (registerMsg == null || registerMsg.getType() != MessageType.REGISTER) {
                out.println(JsonCodec.toJsonLine(NetworkMessage.createError("MIDDLEWARE", "Protocolo inválido. Se esperaba mensaje REGISTER")));
                socket.close();
                return;
            }

            Role role = registerMsg.getRole();
            String entityId = registerMsg.getSenderId();
            if (entityId == null || entityId.trim().isEmpty()) {
                entityId = (role == Role.CLIENT ? "Client-" : "Server-") + System.currentTimeMillis() % 10000;
            }

            if (role == Role.CLIENT) {
                ClientHandler handler = new ClientHandler(entityId, socket, this);
                activeClients.put(entityId, handler);
                log(String.format("[NUEVA CONEXIÓN] Cliente registrado: '%s' desde %s. (Total clientes: %d)",
                        entityId, handler.getRemoteAddress(), activeClients.size()));

                handler.sendMessage(NetworkMessage.createRegisterAck("MIDDLEWARE",
                        "Conectado exitosamente al Middleware como Cliente (" + entityId + ")"));
                threadPool.execute(handler);

            } else if (role == Role.SERVER) {
                ServerHandler handler = new ServerHandler(entityId, socket, this);
                activeServers.put(entityId, handler);
                log(String.format("[NUEVA CONEXIÓN] Servidor registrado: '%s' desde %s. (Total servidores: %d)",
                        entityId, handler.getRemoteAddress(), activeServers.size()));

                handler.sendMessage(NetworkMessage.createRegisterAck("MIDDLEWARE",
                        "Conectado exitosamente al Middleware como Servidor de Cálculo (" + entityId + ")"));
                threadPool.execute(handler);

            } else {
                out.println(JsonCodec.toJsonLine(NetworkMessage.createError("MIDDLEWARE", "Rol no soportado: " + role)));
                socket.close();
            }

        } catch (Exception e) {
            log("Error durante handshake con " + socket.getRemoteSocketAddress() + ": " + e.getMessage());
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    /**
     * Invocado cuando un Cliente envía un mensaje al Middleware.
     */
    public void onClientMessage(ClientHandler sender, NetworkMessage msg) {
        if (msg.getType() == MessageType.CALCULATE_REQ) {
            totalRequestsRouted.incrementAndGet();
            log(String.format("[PETICIÓN] De '%s' | Tx: %s | Operación: %s (%.2f %s %.2f)",
                    sender.getClientId(),
                    msg.getTxId(),
                    msg.getOperation(),
                    msg.getOperandA(),
                    msg.getOperation() != null ? msg.getOperation().getSymbol() : "?",
                    msg.getOperandB()));

            if (activeServers.isEmpty()) {
                log("[ALERTA] ¡No hay servidores de cálculo conectados! Notificando a cliente...");
                NetworkMessage errorRes = NetworkMessage.createCalculateResponse(
                        msg.getTxId(),
                        sender.getClientId(),
                        "MIDDLEWARE",
                        msg.getOperation(),
                        null,
                        "NO_SERVERS_AVAILABLE",
                        "No hay servidores de cálculo conectados al Middleware en este momento."
                );
                sender.sendMessage(errorRes);
                return;
            }

            // REGLA OBLIGATORIA: El middleware reenvía la solicitud a TODOS los servidores conectados
            log(String.format("[DIFUSIÓN A SERVIDORES] Reenviando Tx: %s a %d servidor(es) conectado(s)...",
                    msg.getTxId(), activeServers.size()));

            for (ServerHandler serverHandler : activeServers.values()) {
                serverHandler.sendMessage(msg);
            }
        } else {
            log("Mensaje de cliente no esperado: " + msg.getType());
        }
    }

    /**
     * Invocado cuando un Servidor envía un resultado de cálculo al Middleware.
     */
    public void onServerMessage(ServerHandler sender, NetworkMessage msg) {
        if (msg.getType() == MessageType.CALCULATE_RES) {
            totalResponsesRouted.incrementAndGet();
            String resStr = msg.getResult() != null ? String.valueOf(msg.getResult()) : "NULL";
            log(String.format("[RESULTADO] De '%s' | Tx: %s | Estatus: %s | Resultado: %s (Para cliente: '%s')",
                    sender.getServerId(),
                    msg.getTxId(),
                    msg.getStatus(),
                    resStr,
                    msg.getClientId()));

            // REGLA OBLIGATORIA: El middleware reenvía los resultados a TODOS los clientes conectados
            log(String.format("[DIFUSIÓN A CLIENTES] Retornando resultado de Tx: %s a %d cliente(s) conectado(s)...",
                    msg.getTxId(), activeClients.size()));

            for (ClientHandler clientHandler : activeClients.values()) {
                clientHandler.sendMessage(msg);
            }
        } else {
            log("Mensaje de servidor no esperado: " + msg.getType());
        }
    }

    public void unregisterClient(String clientId) {
        ClientHandler removed = activeClients.remove(clientId);
        if (removed != null) {
            log(String.format("[DESCONEXIÓN] Cliente '%s' desconectado. (Clientes restantes: %d)",
                    clientId, activeClients.size()));
        }
    }

    public void unregisterServer(String serverId) {
        ServerHandler removed = activeServers.remove(serverId);
        if (removed != null) {
            log(String.format("[DESCONEXIÓN] Servidor '%s' desconectado. (Servidores restantes: %d)",
                    serverId, activeServers.size()));
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}

        for (ClientHandler c : activeClients.values()) c.close();
        for (ServerHandler s : activeServers.values()) s.close();
        activeClients.clear();
        activeServers.clear();
        threadPool.shutdownNow();
        log("Middleware detenido correctamente.");
    }

    public int getPort() { return port; }
    public int getClientCount() { return activeClients.size(); }
    public int getServerCount() { return activeServers.size(); }
    public List<String> getClientIds() { return new ArrayList<>(activeClients.keySet()); }
    public List<String> getServerIds() { return new ArrayList<>(activeServers.keySet()); }
    public long getTotalRequestsRouted() { return totalRequestsRouted.get(); }
    public long getTotalResponsesRouted() { return totalResponsesRouted.get(); }

    private void log(String message) {
        String time = LocalDateTime.now().format(TIME_FMT);
        System.out.println(String.format("[%s][MIDDLEWARE] %s", time, message));
    }
}
