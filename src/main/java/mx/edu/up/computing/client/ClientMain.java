package mx.edu.up.computing.client;

import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.*;

/**
 * Punto de entrada para lanzar una instancia de Cliente Pesado (Capa 1).
 */
public class ClientMain {
    public static void main(String[] args) {
        String clientId = "Client-1";
        String host = "localhost";
        int port = 5000;
        boolean autoConnect = false;

        for (int i = 0; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("--id") || args[i].equalsIgnoreCase("-i")) {
                if (i + 1 < args.length) clientId = args[++i];
            } else if (args[i].equalsIgnoreCase("--host") || args[i].equalsIgnoreCase("-h")) {
                if (i + 1 < args.length) host = args[++i];
            } else if (args[i].equalsIgnoreCase("--port") || args[i].equalsIgnoreCase("-p")) {
                if (i + 1 < args.length) port = Integer.parseInt(args[++i]);
            } else if (args[i].equalsIgnoreCase("--autoconnect")) {
                autoConnect = true;
            }
        }

        // Configuración de FlatLaf Dark para estética moderna de escritorio
        try {
            FlatDarkLaf.setup();
        } catch (Exception e) {
            System.err.println("Aviso: No se pudo cargar FlatDarkLaf, usando tema por defecto.");
        }

        final String finalClientId = clientId;
        final String finalHost = host;
        final int finalPort = port;
        final boolean finalAutoConnect = autoConnect;

        SwingUtilities.invokeLater(() -> {
            CalculatorClient client = new CalculatorClient(finalClientId, finalHost, finalPort);
            CalculatorUi ui = new CalculatorUi(client);
            ui.setVisible(true);

            if (finalAutoConnect) {
                new Thread(client::connect).start();
            }
        });
    }
}
