package mx.edu.up.computing;

import mx.edu.up.computing.client.CalculatorClient;
import mx.edu.up.computing.common.NetworkMessage;
import mx.edu.up.computing.common.OperationType;
import mx.edu.up.computing.common.PersistenceRecord;
import mx.edu.up.computing.middleware.MiddlewareServer;
import mx.edu.up.computing.server.ComputeServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Prueba de integración automatizada del Clúster Distribuido completo (3 Capas).
 * Verifica:
 * 1. Comunicación real por sockets TCP.
 * 2. Conexión de 2 servidores y 2 clientes al Middleware.
 * 3. Difusión obligatoria de peticiones a todos los servidores.
 * 4. Difusión obligatoria de resultados a todos los clientes.
 * 5. Ejecución correcta de suma, resta, multiplicación, división y error de división por cero.
 * 6. Persistencia no volátil en disco tanto en servidores como en clientes.
 *
 * Nota de diseño: por requerimiento de la rúbrica, el middleware difunde CADA respuesta a
 * TODOS los clientes conectados. Por lo tanto la bandeja de un cliente contiene también las
 * respuestas de transacciones originadas por el otro cliente, y toda aserción debe filtrarse
 * por el identificador de transacción (txId) en lugar de contar mensajes de forma global.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DistributedCalculatorTest {
    private static final int TEST_PORT = 5888;
    private static final int EXPECTED_SERVERS = 2;
    private static final long AWAIT_TIMEOUT_MS = 5_000L;
    private static final long POLL_INTERVAL_MS = 20L;

    private static MiddlewareServer middleware;
    private static ComputeServer server1;
    private static ComputeServer server2;
    private static CalculatorClient client1;
    private static CalculatorClient client2;

    private static final List<NetworkMessage> inbox1 = new CopyOnWriteArrayList<>();
    private static final List<NetworkMessage> inbox2 = new CopyOnWriteArrayList<>();

    @BeforeAll
    public static void setUpCluster() throws Exception {
        // 1. Iniciar Middleware
        middleware = new MiddlewareServer(TEST_PORT);
        middleware.start();
        Thread.sleep(300);

        // 2. Iniciar Servidor 1 y Servidor 2
        server1 = new ComputeServer("Test-Server-1", "localhost", TEST_PORT);
        server1.start();

        server2 = new ComputeServer("Test-Server-2", "localhost", TEST_PORT);
        server2.start();

        Thread.sleep(600);

        // 3. Iniciar Cliente 1 y Cliente 2 con una bandeja permanente de recepción cada uno
        client1 = new CalculatorClient("Test-Client-1", "localhost", TEST_PORT);
        assertTrue(client1.connect(), "Cliente 1 debió conectar exitosamente");
        client1.addListener(collectorInto(inbox1));

        client2 = new CalculatorClient("Test-Client-2", "localhost", TEST_PORT);
        assertTrue(client2.connect(), "Cliente 2 debió conectar exitosamente");
        client2.addListener(collectorInto(inbox2));

        Thread.sleep(400);

        assertEquals(EXPECTED_SERVERS, middleware.getServerCount(), "Debe haber exactamente 2 servidores registrados");
        assertEquals(2, middleware.getClientCount(), "Debe haber exactamente 2 clientes registrados");
    }

    @AfterAll
    public static void tearDownCluster() {
        if (client1 != null) client1.disconnect();
        if (client2 != null) client2.disconnect();
        if (server1 != null) server1.stop();
        if (server2 != null) server2.stop();
        if (middleware != null) middleware.stop();
    }

    @Test
    @Order(1)
    @DisplayName("Suma distribuida: 150 + 250 = 400 difundido por ambos servidores a ambos clientes")
    public void testDistributedAddition() {
        String txId = client1.sendCalculateRequest(OperationType.ADD, 150.0, 250.0);
        assertNotNull(txId, "El cliente debe devolver un txId al emitir la solicitud");

        List<NetworkMessage> atClient1 = awaitResponses(inbox1, txId, EXPECTED_SERVERS);
        List<NetworkMessage> atClient2 = awaitResponses(inbox2, txId, EXPECTED_SERVERS);

        for (NetworkMessage msg : atClient1) {
            assertEquals("SUCCESS", msg.getStatus());
            assertEquals(400.0, msg.getResult(), 0.001);
            assertEquals(OperationType.ADD, msg.getOperation());
        }
        for (NetworkMessage msg : atClient2) {
            assertEquals("SUCCESS", msg.getStatus());
            assertEquals(400.0, msg.getResult(), 0.001);
        }

        // Cada servidor conectado debe haber respondido exactamente una vez esta transacción
        assertEquals(EXPECTED_SERVERS, distinctServers(atClient1),
                "La respuesta debe provenir de servidores distintos, no duplicada por uno solo");
    }

    @Test
    @Order(2)
    @DisplayName("Resta y multiplicación: cada transacción se resuelve con sus propios operandos")
    public void testSubtractionAndMultiplication() {
        String subTx = client1.sendCalculateRequest(OperationType.SUBTRACT, 500.0, 125.5);
        for (NetworkMessage m : awaitResponses(inbox1, subTx, EXPECTED_SERVERS)) {
            assertEquals(374.5, m.getResult(), 0.001);
            assertEquals(OperationType.SUBTRACT, m.getOperation());
        }

        String mulTx = client2.sendCalculateRequest(OperationType.MULTIPLY, 12.5, 4.0);
        for (NetworkMessage m : awaitResponses(inbox2, mulTx, EXPECTED_SERVERS)) {
            assertEquals(50.0, m.getResult(), 0.001);
            assertEquals(OperationType.MULTIPLY, m.getOperation());
        }

        // La solicitud emitida por Client-2 también debe llegar difundida a Client-1
        assertEquals(EXPECTED_SERVERS, awaitResponses(inbox1, mulTx, EXPECTED_SERVERS).size(),
                "El middleware debe difundir a TODOS los clientes, incluido el que no originó la operación");
    }

    @Test
    @Order(3)
    @DisplayName("División y división entre cero: resultado exacto y error controlado sin caída del clúster")
    public void testDivisionAndDivisionByZero() {
        String divTx = client1.sendCalculateRequest(OperationType.DIVIDE, 100.0, 4.0);
        for (NetworkMessage m : awaitResponses(inbox1, divTx, EXPECTED_SERVERS)) {
            assertEquals("SUCCESS", m.getStatus());
            assertEquals(25.0, m.getResult(), 0.001);
        }

        String zeroTx = client1.sendCalculateRequest(OperationType.DIVIDE, 42.0, 0.0);
        for (NetworkMessage msg : awaitResponses(inbox1, zeroTx, EXPECTED_SERVERS)) {
            assertEquals("ERROR_DIVISION_BY_ZERO", msg.getStatus());
            assertNull(msg.getResult(), "Una división inválida no debe devolver valor numérico");
            assertNotNull(msg.getErrorMessage());
            assertTrue(msg.getErrorMessage().contains("División entre cero"));
        }

        // El clúster sigue operando después del error aritmético
        String afterTx = client1.sendCalculateRequest(OperationType.ADD, 1.0, 1.0);
        for (NetworkMessage m : awaitResponses(inbox1, afterTx, EXPECTED_SERVERS)) {
            assertEquals("SUCCESS", m.getStatus());
            assertEquals(2.0, m.getResult(), 0.001);
        }
    }

    @Test
    @Order(4)
    @DisplayName("Persistencia en disco: clientes y servidores registran sus transacciones en JSON")
    public void testPersistenceOnDisk() {
        File s1File = server1.getPersistence().getDataFile();
        File s2File = server2.getPersistence().getDataFile();
        File c1File = client1.getPersistence().getDataFile();
        File c2File = client2.getPersistence().getDataFile();

        assertTrue(s1File.exists(), "Archivo de persistencia de Server-1 debe existir");
        assertTrue(s2File.exists(), "Archivo de persistencia de Server-2 debe existir");
        assertTrue(c1File.exists(), "Archivo de persistencia de Client-1 debe existir");
        assertTrue(c2File.exists(), "Archivo de persistencia de Client-2 debe existir");

        List<PersistenceRecord> s1Records = server1.getPersistence().loadAll();
        List<PersistenceRecord> c1Records = client1.getPersistence().loadAll();

        assertFalse(s1Records.isEmpty(), "Server-1 debe tener transacciones registradas");
        assertFalse(c1Records.isEmpty(), "Client-1 debe tener transacciones registradas");

        assertTrue(s1Records.stream().allMatch(r -> "CALCULATION_PROCESSED".equals(r.getEventType())),
                "El servidor sólo persiste cálculos que él mismo procesó");
        assertTrue(c1Records.stream().anyMatch(r -> "REQUEST_EMITTED".equals(r.getEventType())),
                "El cliente debe persistir las solicitudes que emite");
        assertTrue(c1Records.stream().anyMatch(r -> "RESULT_RECEIVED".equals(r.getEventType())),
                "El cliente debe persistir los resultados que recibe");
        assertTrue(s1Records.stream().anyMatch(r -> "ERROR_DIVISION_BY_ZERO".equals(r.getStatus())),
                "El error aritmético debe quedar trazado en disco, no sólo en pantalla");
    }

    /**
     * Espera hasta que la bandeja acumule las respuestas de una transacción concreta.
     * Filtra por txId porque el middleware difunde toda respuesta a todos los clientes.
     */
    private static List<NetworkMessage> awaitResponses(List<NetworkMessage> inbox, String txId, int expected) {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            List<NetworkMessage> matches = responsesFor(inbox, txId);
            if (matches.size() >= expected) {
                assertEquals(expected, matches.size(),
                        "Se recibieron más respuestas de las esperadas para " + txId);
                return matches;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Espera interrumpida aguardando respuestas de " + txId);
            }
        }
        return fail("Timeout: se esperaban " + expected + " respuestas para " + txId
                + " y llegaron " + responsesFor(inbox, txId).size());
    }

    private static List<NetworkMessage> responsesFor(List<NetworkMessage> inbox, String txId) {
        return inbox.stream().filter(m -> txId.equals(m.getTxId())).toList();
    }

    private static long distinctServers(List<NetworkMessage> messages) {
        return messages.stream().map(NetworkMessage::getServerId).distinct().count();
    }

    private static CalculatorClient.ClientEventListener collectorInto(List<NetworkMessage> inbox) {
        return new CalculatorClient.ClientEventListener() {
            @Override public void onConnected(String host, int port, String clientId) {}
            @Override public void onDisconnected() {}
            @Override public void onResponseReceived(NetworkMessage res) { inbox.add(res); }
            @Override public void onLogMessage(String message) {}
            @Override public void onError(String error) {}
        };
    }
}
