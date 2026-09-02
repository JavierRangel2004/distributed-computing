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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prueba de integración automatizada del Clúster Distribuido completo (3 Capas).
 * Verifica:
 * 1. Comunicación real por sockets TCP.
 * 2. Conexión de 2 servidores y 2 clientes al Middleware.
 * 3. Difusión obligatoria de peticiones a todos los servidores.
 * 4. Difusión obligatoria de resultados a todos los clientes.
 * 5. Ejecución correcta de suma, resta, multiplicación, división y error de división por cero.
 * 6. Persistencia no volátil en disco tanto en servidores como en clientes.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DistributedCalculatorTest {
    private static final int TEST_PORT = 5888;
    private static MiddlewareServer middleware;
    private static ComputeServer server1;
    private static ComputeServer server2;
    private static CalculatorClient client1;
    private static CalculatorClient client2;

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

        // 3. Iniciar Cliente 1 y Cliente 2
        client1 = new CalculatorClient("Test-Client-1", "localhost", TEST_PORT);
        assertTrue(client1.connect(), "Cliente 1 debió conectar exitosamente");

        client2 = new CalculatorClient("Test-Client-2", "localhost", TEST_PORT);
        assertTrue(client2.connect(), "Cliente 2 debió conectar exitosamente");

        Thread.sleep(400);

        assertEquals(2, middleware.getServerCount(), "Debe haber exactamente 2 servidores registrados");
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
    @DisplayName("Suma distribuida: 150 + 250 = 400 (Ambos servidores procesan y ambos clientes reciben)")
    public void testDistributedAddition() throws Exception {
        CountDownLatch latch = new CountDownLatch(4); // 2 respuestas en Client1 + 2 respuestas en Client2
        List<NetworkMessage> receivedClient1 = new CopyOnWriteArrayList<>();
        List<NetworkMessage> receivedClient2 = new CopyOnWriteArrayList<>();

        CalculatorClient.ClientEventListener l1 = createListener(latch, receivedClient1);
        CalculatorClient.ClientEventListener l2 = createListener(latch, receivedClient2);

        client1.addListener(l1);
        client2.addListener(l2);

        // Cliente 1 emite la solicitud
        String txId = client1.sendCalculateRequest(OperationType.ADD, 150.0, 250.0);
        assertNotNull(txId);

        boolean ok = latch.await(5, TimeUnit.SECONDS);
        assertTrue(ok, "Se debieron recibir las respuestas de ambos servidores en ambos clientes");

        // Validar que ambos clientes recibieron las respuestas de los 2 servidores
        assertEquals(2, receivedClient1.size());
        assertEquals(2, receivedClient2.size());

        for (NetworkMessage msg : receivedClient1) {
            assertEquals("SUCCESS", msg.getStatus());
            assertEquals(400.0, msg.getResult(), 0.001);
            assertEquals(OperationType.ADD, msg.getOperation());
        }

        for (NetworkMessage msg : receivedClient2) {
            assertEquals("SUCCESS", msg.getStatus());
            assertEquals(400.0, msg.getResult(), 0.001);
        }
    }

    @Test
    @Order(2)
    @DisplayName("Resta y Multiplicación: Evaluación correcta de operandos")
    public void testSubtractionAndMultiplication() throws Exception {
        CountDownLatch latchSub = new CountDownLatch(2);
        List<NetworkMessage> resSub = new CopyOnWriteArrayList<>();
        client1.addListener(createListener(latchSub, resSub));

        client1.sendCalculateRequest(OperationType.SUBTRACT, 500.0, 125.5);
        assertTrue(latchSub.await(5, TimeUnit.SECONDS));

        assertEquals(2, resSub.size());
        for (NetworkMessage m : resSub) {
            assertEquals(374.5, m.getResult(), 0.001);
        }

        CountDownLatch latchMul = new CountDownLatch(2);
        List<NetworkMessage> resMul = new CopyOnWriteArrayList<>();
        client2.addListener(createListener(latchMul, resMul));

        client2.sendCalculateRequest(OperationType.MULTIPLY, 12.5, 4.0);
        assertTrue(latchMul.await(5, TimeUnit.SECONDS));

        assertEquals(2, resMul.size());
        for (NetworkMessage m : resMul) {
            assertEquals(50.0, m.getResult(), 0.001);
        }
    }

    @Test
    @Order(3)
    @DisplayName("División entre cero: Manejo controlado de excepción aritmética")
    public void testDivisionByZero() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);
        List<NetworkMessage> responses = new CopyOnWriteArrayList<>();
        client1.addListener(createListener(latch, responses));

        client1.sendCalculateRequest(OperationType.DIVIDE, 42.0, 0.0);
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        assertEquals(2, responses.size());
        for (NetworkMessage msg : responses) {
            assertEquals("ERROR_DIVISION_BY_ZERO", msg.getStatus());
            assertNull(msg.getResult());
            assertNotNull(msg.getErrorMessage());
            assertTrue(msg.getErrorMessage().contains("División entre cero"));
        }
    }

    @Test
    @Order(4)
    @DisplayName("Persistencia en disco: Verificación de archivos JSON en clientes y servidores")
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
    }

    private CalculatorClient.ClientEventListener createListener(CountDownLatch latch, List<NetworkMessage> targetList) {
        return new CalculatorClient.ClientEventListener() {
            @Override public void onConnected(String host, int port, String clientId) {}
            @Override public void onDisconnected() {}
            @Override public void onResponseReceived(NetworkMessage res) {
                targetList.add(res);
                latch.countDown();
            }
            @Override public void onLogMessage(String message) {}
            @Override public void onError(String error) {}
        };
    }
}
