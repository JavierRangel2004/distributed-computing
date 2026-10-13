package mx.edu.up.computing.ads;

import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.File;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Interfaz Swing para una célula cliente ADS.
 *
 * Adaptación funcional del parcial 1 (no rediseño): muestra candidatos vivos
 * por servicio, fija el target desde la lista, expone el log de correlación y
 * persiste los registros en el directorio ADS aislado.
 */
public class AdsClientUi extends JFrame implements AdsClient.ClientListener {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final AdsClient client;
    private final javax.swing.Timer connectionPoller;
    private JTextField hostField;
    private JTextField portField;
    private JButton connectBtn;
    private JLabel statusPill;

    private JComboBox<AdsService> serviceCombo;
    private JLabel candidatesLabel;
    private JSpinner operandA;
    private JSpinner operandB;
    private JButton computeBtn;

    private DefaultTableModel liveTableModel;
    private JTable liveTable;
    private DefaultTableModel candidatesTableModel;
    private JTable candidatesTable;
    private DefaultTableModel persistenceTableModel;
    private JTable persistenceTable;
    private JLabel persistenceFileLabel;
    private JTextArea consoleArea;

    public AdsClientUi(AdsClient client) {
        this.client = client;
        this.client.addListener(this);
        configureLookAndFeel();
        build();
        setTitle("Calculadora ADS — " + client.clientId());
        setSize(1080, 720);
        setMinimumSize(new Dimension(900, 600));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        // El cliente se conecta explícitamente desde la UI; nunca se autoconecta aquí.
        // El estado real de conexión se observa con un Swing Timer que
        // llama isConnected() periódicamente (ver installConnectionPoller()).
        connectionPoller = installConnectionPoller();
        installFrameCloseHook();
        refreshConnectionStatusNow();
    }

    /**
     * Conecta el cliente usando los valores actuales de los campos de host/puerto.
     * Pensado para uso desde {@link ClientMain} tras mostrar la ventana.
     */
    public void connectFromUi() {
        toggleConnection();
    }

    private static void configureLookAndFeel() {
        try {
            FlatDarkLaf.setup();
        } catch (Exception e) {
            System.err.println("[AdsClientUi] FlatLaf no disponible: " + e.getMessage());
        }
    }

    private void build() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(new EmptyBorder(12, 14, 12, 14));
        setContentPane(root);

        root.add(buildTopBar(), BorderLayout.NORTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        split.setDividerLocation(380);
        split.setContinuousLayout(true);
        split.setLeftComponent(buildControls());
        split.setRightComponent(buildTabs());
        root.add(split, BorderLayout.CENTER);
        root.add(buildStatusBar(), BorderLayout.SOUTH);
    }

    private JPanel buildTopBar() {
        JPanel bar = new JPanel(new BorderLayout(10, 5));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(60, 60, 60)),
                new EmptyBorder(0, 0, 10, 0)));

        JPanel title = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JLabel titleLabel = new JLabel("Calculadora ADS");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 17));
        JLabel idBadge = new JLabel("  " + client.clientId() + "  ");
        idBadge.setFont(new Font("SansSerif", Font.BOLD, 12));
        idBadge.setOpaque(true);
        idBadge.setBackground(new Color(41, 128, 185));
        idBadge.setForeground(Color.WHITE);
        idBadge.setBorder(BorderFactory.createLineBorder(new Color(52, 152, 219), 1, true));
        title.add(titleLabel);
        title.add(idBadge);
        bar.add(title, BorderLayout.WEST);

        JPanel conn = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        conn.add(new JLabel("Nodo Host:"));
        hostField = new JTextField(extractHost(client), 10);
        conn.add(hostField);
        conn.add(new JLabel("Puerto:"));
        portField = new JTextField(String.valueOf(extractPort(client)), 5);
        conn.add(portField);
        connectBtn = new JButton("Conectar");
        connectBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
        connectBtn.addActionListener(e -> toggleConnection());
        conn.add(connectBtn);
        statusPill = new JLabel("  ○ Sin enlace  ");
        statusPill.setOpaque(true);
        statusPill.setBackground(new Color(192, 57, 43));
        statusPill.setForeground(Color.WHITE);
        statusPill.setFont(new Font("SansSerif", Font.BOLD, 11));
        conn.add(statusPill);
        bar.add(conn, BorderLayout.EAST);
        return bar;
    }

    private JPanel buildControls() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(new EmptyBorder(5, 5, 5, 10));

        JPanel head = new JPanel(new GridLayout(3, 2, 4, 4));
        head.setBorder(BorderFactory.createTitledBorder("Operación y operandos"));
        head.add(new JLabel("Servicio:"));
        serviceCombo = new JComboBox<>(new DefaultComboBoxModel<>(AdsService.values()));
        serviceCombo.addActionListener(e -> refreshCandidatesTable());
        head.add(serviceCombo);
        head.add(new JLabel("Operando A:"));
        operandA = new JSpinner(new SpinnerNumberModel(10.0, -1e9, 1e9, 1.0));
        head.add(operandA);
        head.add(new JLabel("Operando B:"));
        operandB = new JSpinner(new SpinnerNumberModel(5.0, -1e9, 1e9, 1.0));
        head.add(operandB);
        panel.add(head, BorderLayout.NORTH);

        JPanel mid = new JPanel(new BorderLayout(6, 6));
        mid.setBorder(BorderFactory.createTitledBorder("Servidores candidatos (vivos)"));
        candidatesTableModel = new DefaultTableModel(
                new Object[]{"InstanceId", "Servicio", "Nodo", "Expira en"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        candidatesTable = new JTable(candidatesTableModel);
        candidatesTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        candidatesTable.setRowHeight(20);
        candidatesTable.setFont(new Font("SansSerif", Font.PLAIN, 11));
        DefaultTableCellRenderer center = new DefaultTableCellRenderer();
        center.setHorizontalAlignment(SwingConstants.CENTER);
        candidatesTable.getColumnModel().getColumn(3).setCellRenderer(center);
        mid.add(new JScrollPane(candidatesTable), BorderLayout.CENTER);
        candidatesLabel = new JLabel("Esperando anuncios");
        candidatesLabel.setFont(new Font("SansSerif", Font.ITALIC, 11));
        mid.add(candidatesLabel, BorderLayout.SOUTH);
        panel.add(mid, BorderLayout.CENTER);

        JPanel actions = new JPanel(new BorderLayout());
        computeBtn = new JButton("Enviar solicitud ADS");
        computeBtn.setFont(new Font("SansSerif", Font.BOLD, 13));
        computeBtn.setBackground(new Color(41, 128, 185));
        computeBtn.setForeground(Color.WHITE);
        computeBtn.addActionListener(e -> sendCalculation());
        actions.add(computeBtn, BorderLayout.CENTER);

        JButton refreshCandidates = new JButton("Actualizar candidatos");
        refreshCandidates.addActionListener(e -> refreshCandidatesTable());
        actions.add(refreshCandidates, BorderLayout.SOUTH);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private JTabbedPane buildTabs() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(new Font("SansSerif", Font.BOLD, 12));

        JPanel live = new JPanel(new BorderLayout(6, 6));
        live.setBorder(new EmptyBorder(8, 8, 8, 8));
        live.add(new JLabel("Resultados correlacionados (requestId, server en respuestas, status):"),
                BorderLayout.NORTH);
        liveTableModel = new DefaultTableModel(
                new Object[]{"Hora", "requestId", "Servidor", "Servicio", "Resultado", "Status"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        liveTable = new JTable(liveTableModel);
        liveTable.setRowHeight(22);
        liveTable.setFont(new Font("SansSerif", Font.PLAIN, 12));
        live.add(new JScrollPane(liveTable), BorderLayout.CENTER);
        JButton clear = new JButton("Limpiar tabla");
        clear.addActionListener(e -> liveTableModel.setRowCount(0));
        JPanel bbar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bbar.add(clear);
        live.add(bbar, BorderLayout.SOUTH);
        tabs.addTab("Resultados ADS", live);

        JPanel pers = new JPanel(new BorderLayout(6, 6));
        pers.setBorder(new EmptyBorder(8, 8, 8, 8));
        File f = client.persistence().getDataFile();
        persistenceFileLabel = new JLabel("Archivo ADS: " + f.getAbsolutePath());
        persistenceFileLabel.setFont(new Font("Monospaced", Font.PLAIN, 11));
        pers.add(persistenceFileLabel, BorderLayout.NORTH);
        persistenceTableModel = new DefaultTableModel(
                new Object[]{"recordId", "timestamp", "eventType", "requestId", "service", "a", "b", "result", "status", "error"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        persistenceTable = new JTable(persistenceTableModel);
        persistenceTable.setRowHeight(20);
        persistenceTable.setFont(new Font("SansSerif", Font.PLAIN, 11));
        pers.add(new JScrollPane(persistenceTable), BorderLayout.CENTER);
        JButton reload = new JButton("Recargar desde disco");
        reload.addActionListener(e -> reloadPersistence());
        JPanel pbar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        pbar.add(reload);
        pers.add(pbar, BorderLayout.SOUTH);
        tabs.addTab("Persistencia ADS", pers);

        JPanel cons = new JPanel(new BorderLayout(6, 6));
        cons.setBorder(new EmptyBorder(8, 8, 8, 8));
        consoleArea = new JTextArea();
        consoleArea.setEditable(false);
        consoleArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        consoleArea.setBackground(new Color(20, 20, 20));
        consoleArea.setForeground(new Color(0, 255, 128));
        cons.add(new JScrollPane(consoleArea), BorderLayout.CENTER);
        JButton clearCons = new JButton("Limpiar consola");
        clearCons.addActionListener(e -> consoleArea.setText(""));
        JPanel cbar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        cbar.add(clearCons);
        cons.add(cbar, BorderLayout.SOUTH);
        tabs.addTab("Consola ADS", cons);
        return tabs;
    }

    private JPanel buildStatusBar() {
        JPanel status = new JPanel(new BorderLayout());
        status.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(60, 60, 60)),
                new EmptyBorder(4, 8, 4, 8)));
        JLabel tip = new JLabel("ADS: emisión por contenido; la GUI fija un único ejecutor por solicitud.");
        tip.setFont(new Font("SansSerif", Font.ITALIC, 11));
        status.add(tip, BorderLayout.WEST);
        return status;
    }

    private void toggleConnection() {
        try {
            String host = hostField.getText().trim();
            int port = Integer.parseInt(portField.getText().trim());
            if (client.isConnected()) {
                client.disconnect();
                // El poller pintará el pill rojo en el siguiente tick.
            } else {
                client.setDestination(host, port);
                client.connect();
                // El poller pintará el pill verde SOLO cuando el cliente
                // realmente esté conectado (isConnected true). No etiquetamos
                // conectado de forma optimista antes del ACK.
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Conexión inválida: " + ex.getMessage(),
                    "Aviso ADS", JOptionPane.WARNING_MESSAGE);
        }
    }

    /**
     * Polling periódico del estado real del cliente. Evita inflar el pill
     * verde antes del primer ACK de HELLO.
     */
    private javax.swing.Timer installConnectionPoller() {
        javax.swing.Timer t = new javax.swing.Timer(500, e -> refreshConnectionStatusNow());
        t.setRepeats(true);
        t.start();
        return t;
    }

    private void refreshConnectionStatusNow() {
        boolean connected = client.isConnected();
        SwingUtilities.invokeLater(() -> {
            if (connected) {
                statusPill.setText("  ● Conectado  ");
                statusPill.setBackground(new Color(39, 174, 96));
                connectBtn.setText("Desconectar");
            } else {
                statusPill.setText("  ○ Desconectado  ");
                statusPill.setBackground(new Color(192, 57, 43));
                connectBtn.setText("Conectar");
            }
        });
    }

    /**
     * Cerrar esta ventana desconecta SOLO este cliente (no los demás).
     * No deja schedulers activos huérfanos.
     */
    private void installFrameCloseHook() {
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                connectionPoller.stop();
                client.disconnect();
            }
        });
    }

    private void sendCalculation() {
        AdsService svc = (AdsService) serviceCombo.getSelectedItem();
        double a = ((Number) operandA.getValue()).doubleValue();
        double b = ((Number) operandB.getValue()).doubleValue();
        client.sendRequest(svc, a, b);
    }

    private void refreshCandidatesTable() {
        SwingUtilities.invokeLater(this::refreshCandidatesTableImpl);
    }

    private void refreshCandidatesTableImpl() {
        candidatesTableModel.setRowCount(0);
        AdsService selected = (AdsService) serviceCombo.getSelectedItem();
        if (selected != null) {
            List<AdsAdvertisement> cands = client.registry().candidatesFor(selected);
            for (AdsAdvertisement ad : cands) {
                candidatesTableModel.addRow(new Object[]{
                        ad.instanceId(),
                        ad.service().name(),
                        ad.nodeId(),
                        Math.max(0, (ad.expiresAt() - System.currentTimeMillis()) / 1000) + "s"
                });
            }
            candidatesLabel.setText(cands.size() + " candidatos vivos para " + selected
                    + " (UUID único por instancia)");
        }
    }

    private void reloadPersistence() {
        SwingUtilities.invokeLater(() -> {
            persistenceTableModel.setRowCount(0);
            for (AdsRecord r : client.persistence().loadAll()) {
                persistenceTableModel.addRow(new Object[]{
                        r.getRecordId(),
                        r.getTimestamp(),
                        r.getEventType(),
                        r.getRequestId(),
                        r.getService(),
                        r.getOperandA(),
                        r.getOperandB(),
                        r.getResult(),
                        r.getStatus(),
                        r.getError()
                });
            }
        });
    }

    private static String extractHost(AdsClient client) {
        return client.nodeHost();
    }

    private static int extractPort(AdsClient client) {
        return client.nodePort();
    }

    private void appendConsole(String text) {
        String stamped = "[" + LocalTime.now().format(TIME_FMT) + "] " + text + "\n";
        consoleArea.append(stamped);
        consoleArea.setCaretPosition(consoleArea.getDocument().getLength());
    }

    @Override
    public void onAdvertisement(AdsAdvertisement ad) {
        SwingUtilities.invokeLater(() -> {
            refreshCandidatesTableImpl();
            appendConsole("ADVERTISE " + ad.service() + " uuid=" + ad.instanceId().substring(0, 8)
                    + " node=" + ad.nodeId() + " expira en " + Math.max(0, (ad.expiresAt() - System.currentTimeMillis()) / 1000) + "s");
        });
    }

    @Override
    public void onRequestSent(AdsMessage msg) {
        SwingUtilities.invokeLater(() -> appendLog("SEND", msg));
    }

    @Override
    public void onResponse(AdsMessage msg) {
        SwingUtilities.invokeLater(() -> {
            // La tabla mantiene IDs cortos para legibilidad; la consola lleva IDs completos.
            liveTableModel.insertRow(0, new Object[]{
                    LocalTime.now().format(TIME_FMT),
                    msg.getRequestId() == null ? "?" : msg.getRequestId().substring(0, 8),
                    msg.getTargetServerId() == null ? "?" : msg.getTargetServerId().substring(0, 8),
                    msg.getService(),
                    msg.getResult(),
                    msg.getStatus()
            });
            appendLog("RESP", msg);
            reloadPersistence();
        });
    }

    @Override
    public void onTimeout(String requestId, AdsService service, String targetServerId) {
        SwingUtilities.invokeLater(() -> {
            liveTableModel.insertRow(0, new Object[]{
                    LocalTime.now().format(TIME_FMT),
                    requestId.substring(0, 8),
                    targetServerId.substring(0, 8),
                    service.name(),
                    "—",
                    "TIMEOUT"
            });
            appendConsole("[TIMEOUT] requestId=" + requestId.substring(0, 8)
                    + " target=" + targetServerId.substring(0, 8) + " service=" + service);
        });
    }

    @Override
    public void onError(String text) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                text, "Aviso ADS", JOptionPane.WARNING_MESSAGE));
    }

    private void appendLog(String tag, AdsMessage msg) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(tag).append("] type=").append(msg.getType())
                .append(" code=").append(msg.getContentCode() == null ? "-" : msg.getContentCode())
                .append(" mid=").append(msg.getMessageId() == null ? "-" : msg.getMessageId())
                .append(" req=").append(msg.getRequestId() == null ? "-" : msg.getRequestId())
                .append(" srv=").append(msg.getTargetServerId() == null ? "-" : msg.getTargetServerId())
                .append(" svc=").append(msg.getService())
                .append(" a=").append(msg.getOperandA())
                .append(" b=").append(msg.getOperandB())
                .append(" result=").append(msg.getResult())
                .append(" status=").append(msg.getStatus());
        appendConsole(sb.toString());
    }
}