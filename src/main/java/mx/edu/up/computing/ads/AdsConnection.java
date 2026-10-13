package mx.edu.up.computing.ads;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Encapsula un socket TCP con su lector/escritor JSON línea por línea UTF-8.
 *
 * El lector se entrega al consumidor (cliente); el escritor es seguro contra
 * uso concurrente porque todos los mensajes ADS pasan por aquí serializados
 * en una sola línea terminada en '\n'.
 *
 * {@link #direction} indica si este extremo fue el dialer (OUTBOUND) o el
 * aceptador (INBOUND). Lo fija el nodo en el registro canónico.
 */
public final class AdsConnection implements Closeable {

    private final Socket socket;
    private final BufferedReader in;
    private final PrintWriter out;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final String remoteTag;
    private volatile boolean closed;
    private volatile AdsDirection direction;

    public AdsConnection(Socket socket, String remoteTag) throws IOException {
        this.socket = socket;
        this.remoteTag = remoteTag;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
    }

    public void setDirection(AdsDirection d) { this.direction = d; }
    public AdsDirection getDirection() { return direction; }

    public String readLine() throws IOException {
        if (closed) {
            return null;
        }
        return in.readLine();
    }

    public void send(AdsMessage msg) {
        if (closed || msg == null) {
            return;
        }
        String line = msg.toJson().replace('\n', ' ').replace('\r', ' ');
        writeLock.lock();
        try {
            if (closed) {
                return;
            }
            out.print(line);
            out.print('\n');
            out.flush();
        } finally {
            writeLock.unlock();
        }
    }

    public void sendRaw(String line) {
        if (closed || line == null) {
            return;
        }
        writeLock.lock();
        try {
            if (closed) {
                return;
            }
            out.print(line);
            if (!line.endsWith("\n")) {
                out.print('\n');
            }
            out.flush();
        } finally {
            writeLock.unlock();
        }
    }

    public String remoteTag() {
        return remoteTag;
    }

    public boolean isOpen() {
        return !closed && socket != null && !socket.isClosed() && socket.isConnected();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
            // cierre best-effort
        }
    }
}