package mx.edu.up.computing.ads;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lanza la topología mínima del PDF oficial (pp. 2): 4 nodos en malla completa,
 * 4 GUI y 16 servidores (4 por nodo, uno por servicio). Cierre ordenado por
 * señal: apaga servidores, clientes y nodos con bounded shutdown.
 *
 * <pre>
 *   java mx.edu.up.computing.ads.DemoMain [--base 6101] [--nodes 4] [--gui]
 * </pre>
 *
 * Sin {@code --gui}: las GUI se construyen como células ADS (clientes lógicos)
 * pero NO se instancia {@link javax.swing.JFrame} ni ningún componente Swing;
 * útil para validación headless.
 */
public class DemoMain {

    public static void main(String[] args) throws Exception {
        int base = 6101;
        int nodesCount = 4;
        boolean gui = false;
        long boundedDelayMs = 5_000L;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--base" -> base = Integer.parseInt(args[++i]);
                case "--nodes" -> nodesCount = Integer.parseInt(args[++i]);
                case "--gui" -> gui = true;
                case "--bounded-ms" -> boundedDelayMs = Long.parseLong(args[++i]);
                default -> {
                    if (a.startsWith("--") || a.startsWith("-")) {
                        System.err.println("[DEMO] Aviso: opción no soportada " + a);
                    }
                }
            }
        }

        CopyOnWriteArrayList<AdsNode> nodeList = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<AdsServer> serverList = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<AdsClient> clientList = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<javax.swing.JFrame> frames = new CopyOnWriteArrayList<>();

        List<AdsPeer> peerList = new ArrayList<>();
        for (int n = 1; n <= nodesCount; n++) {
            peerList.add(new AdsPeer("N" + n, "127.0.0.1", base + n - 1));
        }
        for (int n = 1; n <= nodesCount; n++) {
            List<AdsPeer> myPeers = new ArrayList<>();
            for (AdsPeer p : peerList) {
                if (!p.nodeId().equals("N" + n)) {
                    myPeers.add(p);
                }
            }
            AdsNode node = new AdsNode("N" + n, base + n - 1, myPeers);
            nodeList.add(node);
        }
        for (AdsNode n : nodeList) {
            n.start();
        }

        for (int nIdx = 0; nIdx < nodesCount; nIdx++) {
            AdsNode owner = nodeList.get(nIdx);
            String nodeId = owner.nodeId();
            int nodePort = owner.port();
            AdsService[] services = AdsService.values();
            for (int s = 0; s < services.length; s++) {
                AdsService svc = services[s];
                String id = nodeId + "-" + svc.name() + "-" + (s + 1);
                AdsServer srv = new AdsServer(id, svc, "127.0.0.1", nodePort);
                serverList.add(srv);
                srv.start();
            }
        }

        for (int nIdx = 0; nIdx < nodesCount; nIdx++) {
            AdsNode owner = nodeList.get(nIdx);
            String cid = "GUI" + (nIdx + 1);
            AdsClient client = new AdsClient(cid, "127.0.0.1", owner.port());
            clientList.add(client);
            client.connect();
            final boolean showGui = gui;
            final int idxCopy = nIdx;
            if (showGui) {
                // Solo instanciar JFrame si --gui está activo.
                javax.swing.SwingUtilities.invokeLater(() -> {
                    AdsClientUi ui = new AdsClientUi(client);
                    ui.setSize(1080, 720);
                    ui.setLocation(100 + idxCopy * 40, 100 + idxCopy * 40);
                    ui.setVisible(true);
                    frames.add(ui);
                });
            }
        }

        final long boundedMsFinal = boundedDelayMs;
        System.out.println("[DEMO] Topología levantada: " + nodesCount + " nodos / "
                + serverList.size() + " servidores / " + clientList.size() + " clientes.");
        System.out.println("[DEMO] Apagado acotado a " + boundedMsFinal + " ms cuando se reciba SIGTERM/SIGINT.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            long deadline = System.currentTimeMillis() + boundedMsFinal;
            for (AdsClient c : clientList) {
                try { c.disconnect(); } catch (Throwable ignored) { }
            }
            for (AdsServer s : serverList) {
                try { s.stop(); } catch (Throwable ignored) { }
            }
            for (AdsNode n : nodeList) {
                try { n.stop(); } catch (Throwable ignored) { }
            }
            for (javax.swing.JFrame f : frames) {
                try {
                    javax.swing.SwingUtilities.invokeAndWait(f::dispose);
                } catch (Throwable ignored) { }
            }
            long remaining = deadline - System.currentTimeMillis();
            try {
                Thread.sleep(Math.max(remaining, 50));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.out.println("[DEMO] Shutdown ordenado completo.");
        }));

        Thread.currentThread().join();
    }
}