package mx.edu.up.computing.client;

import mx.edu.up.computing.common.JsonCodec;
import mx.edu.up.computing.common.MessageType;
import mx.edu.up.computing.common.NetworkMessage;
import mx.edu.up.computing.common.OperationType;
import mx.edu.up.computing.common.PersistenceRecord;
import mx.edu.up.computing.common.PersistenceService;
import mx.edu.up.computing.common.Role;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Cliente de Red de Capa 1 (Cliente Pesado).
 * Gestiona el socket TCP hacia el Middleware, emite solicitudes y escucha
 * asíncronamente las respuestas distribuidas por todos los servidores.
 */
public class CalculatorClient implements Runnable {

    public interface ClientEventListener {
        void onConnected(String host, int port, String clientId);
        void onDisconnected();
        void onResponseReceived(NetworkMessage res);
        void onLogMessage(String message);
        void onError(String error);
    }

    private final String clientId;
    private String host;
    private int port;
    private final PersistenceService persistence;
    private final List<ClientEventListener> listeners = new CopyOnWriteArrayList<>();

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private volatile boolean connected = false;
    private volatile boolean shouldRun = false;
    private Thread listenerThread;

    public CalculatorClient(String clientId, String host, int port) {
        this.clientId = clientId;
        this.host = host;
        this.port = port;
        this.persistence = new PersistenceService(Role.CLIENT, clientId);
    }

    public void addListener(ClientEventListener listener) {
        listeners.add(listener);
    }

    public synchronized boolean connect() {
        if (connected) return true;

        try {
            notifyLog("[CONEXIÓN] Conectando a Middleware en " + host + ":" + port + "...");
            socket = new Socket(host, port);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);

            // Paso 1: Registro como CLIENTE
            NetworkMessage registerMsg = NetworkMessage.createRegister(Role.CLIENT, clientId);
            sendRaw(registerMsg);

            // Leer confirmación del middleware
            String ackLine = in.readLine();
            if (ackLine != null) {
                NetworkMessage ack = JsonCodec.fromJson(ackLine);
                notifyLog("[REGISTRO] " + (ack != null ? ack.getErrorMessage() : "Registrado con éxito."));
            }

            connected = true;
            shouldRun = true;
            listenerThread = new Thread(this, "ClientListener-" + clientId);
            listenerThread.start();

            for (ClientEventListener l : listeners) {
                l.onConnected(host, port, clientId);
            }
            return true;

        } catch (IOException e) {
            notifyError("No se pudo conectar con el Middleware: " + e.getMessage());
            disconnect();
            return false;
        }
    }

    public synchronized void disconnect() {
        shouldRun = false;
        connected = false;
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {}

        for (ClientEventListener l : listeners) {
            l.onDisconnected();
        }
        notifyLog("[DESCONEXIÓN] Desconectado del Middleware.");
    }

    @Override
    public void run() {
        try {
            String line;
            while (shouldRun && (line = in.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                NetworkMessage msg = JsonCodec.fromJson(line);
                if (msg != null) {
                    handleIncomingMessage(msg);
                }
            }
        } catch (IOException e) {
            if (shouldRun) {
                notifyLog("[AVISO] Conexión cerrada por el servidor remoto: " + e.getMessage());
            }
        } finally {
            disconnect();
        }
    }

    /**
     * Procesa una respuesta recibida desde el Middleware.
     */
    private void handleIncomingMessage(NetworkMessage msg) {
        if (msg.getType() == MessageType.CALCULATE_RES) {
            notifyLog(String.format("[RESPUESTA RECIBIDA] De: %s | Tx: %s | Op: %s | Res: %s | Estatus: %s",
                    msg.getServerId(),
                    msg.getTxId(),
                    msg.getOperation(),
                    msg.getResult(),
                    msg.getStatus()));

            // Persistir el resultado recibido en disco local
            try {
                PersistenceRecord record = PersistenceRecord.forClientResultReceived(clientId, msg);
                persistence.appendRecord(record);
            } catch (Exception e) {
                notifyLog("[ERROR PERSISTENCIA] " + e.getMessage());
            }

            for (ClientEventListener l : listeners) {
                l.onResponseReceived(msg);
            }
        } else if (msg.getType() == MessageType.ERROR) {
            notifyError("[ERROR REMOTO] " + msg.getErrorMessage());
        }
    }

    /**
     * Envía una solicitud aritmética al Middleware para que la distribuya a todos los servidores.
     */
    public synchronized String sendCalculateRequest(OperationType op, double a, double b) {
        if (!connected) {
            notifyError("No conectado al Middleware. Por favor presiona 'Conectar'.");
            return null;
        }

        NetworkMessage req = NetworkMessage.createCalculateRequest(clientId, op, a, b);

        // Persistir la solicitud en el historial local antes de enviar
        try {
            PersistenceRecord record = PersistenceRecord.forClientRequest(clientId, req);
            persistence.appendRecord(record);
        } catch (Exception e) {
            notifyLog("[ERROR PERSISTENCIA SOLICITUD] " + e.getMessage());
        }

        sendRaw(req);
        notifyLog(String.format("[SOLICITUD ENVIADA] Tx: %s | %s (%.2f %s %.2f) enviada al Middleware.",
                req.getTxId(), op.name(), a, op.getSymbol(), b));

        return req.getTxId();
    }

    private void sendRaw(NetworkMessage msg) {
        if (out != null) {
            out.println(JsonCodec.toJsonLine(msg));
        }
    }

    private void notifyLog(String text) {
        for (ClientEventListener l : listeners) {
            l.onLogMessage(text);
        }
    }

    private void notifyError(String text) {
        for (ClientEventListener l : listeners) {
            l.onError(text);
        }
    }

    public boolean isConnected() { return connected; }
    public String getClientId() { return clientId; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public void setHost(String host) { this.host = host; }
    public void setPort(int port) { this.port = port; }
    public PersistenceService getPersistence() { return persistence; }
}
