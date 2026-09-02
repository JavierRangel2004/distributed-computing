package mx.edu.up.computing.server;

import mx.edu.up.computing.common.JsonCodec;
import mx.edu.up.computing.common.MessageType;
import mx.edu.up.computing.common.NetworkMessage;
import mx.edu.up.computing.common.PersistenceRecord;
import mx.edu.up.computing.common.PersistenceService;
import mx.edu.up.computing.common.Role;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Servidor de Negocio de Capa 3.
 * Responsabilidades:
 * 1. Se conecta mediante socket TCP al Middleware e indica su rol SERVER y su ID único.
 * 2. Recibe solicitudes de operaciones aritméticas distribuidas por el middleware.
 * 3. Ejecuta la lógica matemática (+, -, *, / con validación de división entre cero).
 * 4. Persiste en almacenamiento no volátil local cada operación procesada.
 * 5. Retorna el resultado al Middleware para su distribución a los clientes.
 */
public class ComputeServer implements Runnable {
    private final String serverId;
    private final String host;
    private final int port;
    private final CalculatorEngine engine;
    private final PersistenceService persistence;
    private final AtomicLong operationsProcessed = new AtomicLong(0);

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private volatile boolean running = true;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public ComputeServer(String serverId, String host, int port) {
        this.serverId = serverId;
        this.host = host;
        this.port = port;
        this.engine = new CalculatorEngine();
        this.persistence = new PersistenceService(Role.SERVER, serverId);
    }

    public void start() {
        Thread worker = new Thread(this, "ServerThread-" + serverId);
        worker.start();
    }

    @Override
    public void run() {
        while (running) {
            try {
                log(String.format("Intentando conectar con el Middleware en %s:%d...", host, port));
                socket = new Socket(host, port);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);

                // Paso 1: Handshake de registro como SERVIDOR
                NetworkMessage registerMsg = NetworkMessage.createRegister(Role.SERVER, serverId);
                send(registerMsg);

                // Esperar confirmación del middleware
                String ackLine = in.readLine();
                if (ackLine != null) {
                    NetworkMessage ack = JsonCodec.fromJson(ackLine);
                    log("Handshake exitoso con Middleware: " + (ack != null ? ack.getErrorMessage() : "OK"));
                }

                log("==================================================================");
                log(">>> SERVIDOR DE CÁLCULO '" + serverId + "' ACTIVO Y CONECTADO <<<");
                log("Persistencia activa en: " + persistence.getDataFile().getAbsolutePath());
                log("Esperando solicitudes aritméticas...");
                log("==================================================================");

                // Bucle de lectura de peticiones del Middleware
                String line;
                while (running && (line = in.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    NetworkMessage req = JsonCodec.fromJson(line);
                    if (req != null && req.getType() == MessageType.CALCULATE_REQ) {
                        processRequest(req);
                    }
                }

            } catch (IOException e) {
                if (running) {
                    log("Conexión perdida con el Middleware (" + e.getMessage() + "). Reintentando en 3 segundos...");
                    closeSocket();
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ignored) {}
                }
            }
        }
    }

    /**
     * Procesa una solicitud aritmética, persiste el resultado y responde al Middleware.
     */
    private void processRequest(NetworkMessage req) {
        operationsProcessed.incrementAndGet();
        double a = req.getOperandA() != null ? req.getOperandA() : 0.0;
        double b = req.getOperandB() != null ? req.getOperandB() : 0.0;

        log(String.format("[PETICIÓN RECIBIDA] Tx: %s | Cliente: '%s' | Operación: %s | Operandos: %.2f %s %.2f",
                req.getTxId(),
                req.getClientId(),
                req.getOperation(),
                a,
                req.getOperation() != null ? req.getOperation().getSymbol() : "?",
                b));

        // 1. Ejecución de lógica de negocio (ALU de software)
        CalculatorEngine.CalculationResult calcRes = engine.calculate(req.getOperation(), a, b);

        String status = calcRes.getStatus();
        Double result = calcRes.getValue();
        String errorMsg = calcRes.getErrorMessage();

        if (calcRes.isSuccess()) {
            log(String.format("[CÁLCULO EXITOSO] Tx: %s -> Resultado = %.4f", req.getTxId(), result));
        } else {
            log(String.format("[ERROR EN CÁLCULO] Tx: %s -> %s (%s)", req.getTxId(), status, errorMsg));
        }

        // 2. Persistencia obligatoria en disco no volátil
        try {
            PersistenceRecord record = PersistenceRecord.forServerProcessed(serverId, req, result, status, errorMsg);
            persistence.appendRecord(record);
            log(String.format("[PERSISTENCIA] Registro guardado en disco para Tx: %s", req.getTxId()));
        } catch (Exception e) {
            log("[ERROR PERSISTENCIA] No se pudo guardar el registro: " + e.getMessage());
        }

        // 3. Envío de respuesta de cálculo al Middleware
        NetworkMessage res = NetworkMessage.createCalculateResponse(
                req.getTxId(),
                req.getClientId(),
                serverId,
                req.getOperation(),
                result,
                status,
                errorMsg
        );

        send(res);
        log(String.format("[RESPUESTA ENVIADA] Resultado de Tx: %s transmitido al Middleware.", req.getTxId()));
    }

    private synchronized void send(NetworkMessage msg) {
        if (out != null) {
            out.println(JsonCodec.toJsonLine(msg));
        }
    }

    private void closeSocket() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {}
    }

    public void stop() {
        running = false;
        closeSocket();
        log("Servidor '" + serverId + "' detenido.");
    }

    public String getServerId() { return serverId; }
    public long getOperationsProcessed() { return operationsProcessed.get(); }
    public PersistenceService getPersistence() { return persistence; }

    private void log(String message) {
        String time = LocalDateTime.now().format(TIME_FMT);
        System.out.println(String.format("[%s][SERVER %s] %s", time, serverId, message));
    }
}
