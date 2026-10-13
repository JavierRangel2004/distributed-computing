package mx.edu.up.computing.ads;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Punto de entrada CLI del nodo del Campo de Datos ADS.
 *
 * <pre>
 *   java mx.edu.up.computing.ads.NodeMain --id N1 --port 6101 \
 *       --peers N2@127.0.0.1:6102,N3@127.0.0.1:6103,N4@127.0.0.1:6104
 * </pre>
 */
public class NodeMain {

    public static void main(String[] args) throws Exception {
        Config cfg = parse(args);
        AdsNode node = new AdsNode(cfg.id, cfg.port, cfg.peers);
        node.start();
        Runtime.getRuntime().addShutdownHook(new Thread(node::stop));
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        System.out.println("\n[NODE] Comandos: status, peers, cells, dedup, quit\n");
        String line;
        while ((line = reader.readLine()) != null) {
            String cmd = line.trim().toLowerCase();
            switch (cmd) {
                case "status" -> {
                    System.out.println("--------------------------------------------------");
                    System.out.println("  Nodo ADS " + cfg.id + " puerto " + cfg.port);
                    System.out.println("  Pares conectados: " + node.activePeerIds());
                    System.out.println("  Células locales: " + node.activeCellIds());
                    System.out.println("  Dedup: size=" + node.dedup().size()
                            + " forwards=" + node.dedup().forwardCount()
                            + " drops=" + node.dedup().dropCount()
                            + " expired=" + node.dedup().expiredCount());
                    System.out.println("--------------------------------------------------");
                }
                case "peers" -> System.out.println("Pares: " + node.activePeerIds());
                case "cells" -> System.out.println("Células: " + node.activeCellIds());
                case "dedup" -> System.out.println("Dedup size=" + node.dedup().size());
                case "quit", "exit" -> {
                    node.stop();
                    System.exit(0);
                }
                case "" -> { /* ignore */ }
                default -> System.out.println("Comando desconocido: " + cmd);
            }
        }
    }

    static Config parse(String[] args) {
        String id = "N1";
        int port = 6101;
        List<AdsPeer> peers = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--id" -> id = args[++i];
                case "--port", "-p" -> port = Integer.parseInt(args[++i]);
                case "--peers" -> {
                    String[] list = args[++i].split(",");
                    for (String spec : list) {
                        String[] parts = spec.trim().split("@");
                        String pid = parts[0];
                        String[] hp = parts[1].split(":");
                        peers.add(new AdsPeer(pid, hp[0], Integer.parseInt(hp[1])));
                    }
                }
                default -> {
                    if (a.startsWith("--") || a.startsWith("-")) {
                        System.err.println("[NODE] Aviso: opción no soportada " + a);
                    }
                }
            }
        }
        return new Config(id, port, peers);
    }

    record Config(String id, int port, List<AdsPeer> peers) { }
}