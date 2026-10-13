package mx.edu.up.computing.ads;

/**
 * Punto de entrada CLI para una célula cliente ADS (GUI Swing).
 *
 * <pre>
 *   java mx.edu.up.computing.ads.ClientMain --id GUI1 --host 127.0.0.1 --port 6101
 * </pre>
 */
public class ClientMain {

    public static void main(String[] args) {
        String id = "GUI1";
        String host = "127.0.0.1";
        int port = 6101;
        boolean autoConnect = true;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--id", "-i" -> id = args[++i];
                case "--host", "-h" -> host = args[++i];
                case "--port", "-p" -> port = Integer.parseInt(args[++i]);
                case "--no-autoconnect" -> autoConnect = false;
                default -> {
                    if (a.startsWith("--") || a.startsWith("-")) {
                        System.err.println("[GUI] Aviso: opción no soportada " + a);
                    }
                }
            }
        }
        final String fId = id;
        final String fHost = host;
        final int fPort = port;
        final boolean fAuto = autoConnect;
        AdsClient client = new AdsClient(fId, fHost, fPort);
        javax.swing.SwingUtilities.invokeLater(() -> {
            AdsClientUi ui = new AdsClientUi(client);
            ui.setVisible(true);
            if (fAuto) {
                ui.connectFromUi();
            }
        });
    }
}