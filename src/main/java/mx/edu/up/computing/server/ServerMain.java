package mx.edu.up.computing.server;

/**
 * Punto de entrada para iniciar una instancia de Servidor de Negocio (Capa 3).
 */
public class ServerMain {
    public static void main(String[] args) {
        String serverId = "Server-1";
        String host = "localhost";
        int port = 5000;

        for (int i = 0; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("--id") || args[i].equalsIgnoreCase("-i")) {
                if (i + 1 < args.length) serverId = args[++i];
            } else if (args[i].equalsIgnoreCase("--host") || args[i].equalsIgnoreCase("-h")) {
                if (i + 1 < args.length) host = args[++i];
            } else if (args[i].equalsIgnoreCase("--port") || args[i].equalsIgnoreCase("-p")) {
                if (i + 1 < args.length) port = Integer.parseInt(args[++i]);
            }
        }

        ComputeServer server = new ComputeServer(serverId, host, port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        server.start();

        // Mantener el hilo principal vivo
        try {
            Thread.currentThread().join();
        } catch (InterruptedException ignored) {}
    }
}
