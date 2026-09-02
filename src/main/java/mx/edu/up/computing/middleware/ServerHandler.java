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
 * Manejador de la conexión de un Servidor de Lógica de Negocio con el Middleware.
 */
public class ServerHandler implements Runnable {
    private final String serverId;
    private final Socket socket;
    private final MiddlewareServer middleware;
    private final BufferedReader in;
    private final PrintWriter out;
    private volatile boolean running = true;

    public ServerHandler(String serverId, Socket socket, MiddlewareServer middleware) throws IOException {
        this.serverId = serverId;
        this.socket = socket;
        this.middleware = middleware;
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
                    middleware.onServerMessage(this, msg);
                }
            }
        } catch (IOException e) {
            // Desconexión del servidor
        } finally {
            close();
            middleware.unregisterServer(serverId);
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

    public String getServerId() {
        return serverId;
    }

    public String getRemoteAddress() {
        return socket.getRemoteSocketAddress().toString();
    }
}
