package mx.edu.up.computing.middleware;

import mx.edu.up.computing.common.JsonCodec;
import mx.edu.up.computing.common.NetworkMessage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Manejador de la conexión de un Cliente Pesado con el Middleware.
 */
public class ClientHandler implements Runnable {
    private final String clientId;
    private final Socket socket;
    private final MiddlewareServer server;
    private final BufferedReader in;
    private final PrintWriter out;
    private volatile boolean running = true;

    public ClientHandler(String clientId, Socket socket, MiddlewareServer server) throws IOException {
        this.clientId = clientId;
        this.socket = socket;
        this.server = server;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
    }

    @Override
    public void run() {
        try {
            String line;
            while (running && (line = in.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                NetworkMessage msg = JsonCodec.fromJson(line);
                if (msg != null) {
                    server.onClientMessage(this, msg);
                }
            }
        } catch (IOException e) {
            // Desconexión del cliente
        } finally {
            close();
            server.unregisterClient(clientId);
        }
    }

    public synchronized void sendMessage(NetworkMessage msg) {
        if (!running) return;
        String line = JsonCodec.toJsonLine(msg);
        out.println(line);
    }

    public void close() {
        running = false;
        try {
            if (!socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {}
    }

    public String getClientId() {
        return clientId;
    }

    public String getRemoteAddress() {
        return socket.getRemoteSocketAddress().toString();
    }
}
