package mx.edu.up.computing.middleware;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Punto de entrada para iniciar la Capa 2 (Middleware).
 */
public class MiddlewareMain {
    public static void main(String[] args) {
        int port = 5000;

        for (int i = 0; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("--port") || args[i].equalsIgnoreCase("-p")) {
                if (i + 1 < args.length) {
                    port = Integer.parseInt(args[++i]);
                }
            }
        }

        try {
            MiddlewareServer server = new MiddlewareServer(port);
            server.start();

            // Hook para apagado limpio ante Ctrl+C
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

            // Interfaz de comandos por consola
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            System.out.println("\n[CONSOLA] Escribe 'status', 'clients', 'servers', 'help' o 'quit' para interactuar:\n");

            String line;
            while ((line = reader.readLine()) != null) {
                String cmd = line.trim().toLowerCase();
                if (cmd.equals("quit") || cmd.equals("exit")) {
                    server.stop();
                    System.exit(0);
                } else if (cmd.equals("status")) {
                    System.out.println("--------------------------------------------------");
                    System.out.println("  ESTADO ACTUAL DEL MIDDLEWARE");
                    System.out.println("  Puerto: " + server.getPort());
                    System.out.println("  Clientes conectados (" + server.getClientCount() + "): " + server.getClientIds());
                    System.out.println("  Servidores conectados (" + server.getServerCount() + "): " + server.getServerIds());
                    System.out.println("  Solicitudes ruteadas: " + server.getTotalRequestsRouted());
                    System.out.println("  Respuestas ruteadas:  " + server.getTotalResponsesRouted());
                    System.out.println("--------------------------------------------------");
                } else if (cmd.equals("clients")) {
                    System.out.println("Clientes activos: " + server.getClientIds());
                } else if (cmd.equals("servers")) {
                    System.out.println("Servidores de cálculo activos: " + server.getServerIds());
                } else if (cmd.equals("help")) {
                    System.out.println("Comandos disponibles: status, clients, servers, help, quit");
                }
            }

        } catch (Exception e) {
            System.err.println("[MIDDLEWARE] Error fatal al iniciar: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
