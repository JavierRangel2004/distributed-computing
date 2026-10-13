package mx.edu.up.computing.ads;

/**
 * Punto de entrada CLI para una célula servidor ADS.
 *
 * <pre>
 *   java mx.edu.up.computing.ads.ServerMain --id S1-SUM --service SUM \
 *       --host 127.0.0.1 --port 6101
 * </pre>
 *
 * El UUID de instancia se asigna al arrancar y se publica en cada ADVERTISE.
 */
public class ServerMain {

    public static void main(String[] args) {
        String id = "S1";
        AdsService service = null;
        String host = "127.0.0.1";
        int port = 6101;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--id", "-i" -> id = args[++i];
                case "--service", "-s" -> service = AdsService.fromCode(args[++i]);
                case "--host", "-h" -> host = args[++i];
                case "--port", "-p" -> port = Integer.parseInt(args[++i]);
                default -> {
                    if (a.startsWith("--") || a.startsWith("-")) {
                        System.err.println("[SRV] Aviso: opción no soportada " + a);
                    }
                }
            }
        }
        if (service == null) {
            System.err.println("[SRV] Servicio obligatorio: SUM|SUB|MUL|DIV");
            System.exit(2);
        }
        AdsServer server = new AdsServer(id, service, host, port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
        try {
            Thread.currentThread().join();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}