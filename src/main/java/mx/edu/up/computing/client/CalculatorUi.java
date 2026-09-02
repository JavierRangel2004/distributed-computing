package mx.edu.up.computing.client;

import mx.edu.up.computing.common.NetworkMessage;
import mx.edu.up.computing.common.OperationType;
import mx.edu.up.computing.common.PersistenceRecord;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Interfaz Gráfica de Escritorio (Capa 1 - Cliente Pesado).
 * Desarrollada específicamente para la aplicación calculadora distribuida.
 * Utiliza FlatLaf para un diseño moderno, claro y profesional.
 */
public class CalculatorUi extends JFrame implements CalculatorClient.ClientEventListener {
    private final CalculatorClient client;

    // Elementos de Conexión
    private JTextField hostField;
    private JTextField portField;
    private JButton connectBtn;
    private JLabel statusPill;

    // Pantalla de la Calculadora
    private JLabel formulaLabel;
    private JTextField displayField;

    // Estado de la calculadora
    private Double operandA = null;
    private OperationType pendingOperation = null;
    private boolean isTypingNewNumber = true;

    // Pestañas derechas
    private DefaultTableModel liveTableModel;
    private JTable liveTable;

    private DefaultTableModel persistenceTableModel;
    private JTable persistenceTable;
    private JLabel persistenceFileLabel;

    private JTextArea consoleArea;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public CalculatorUi(CalculatorClient client) {
        this.client = client;
        this.client.addListener(this);

        setTitle("Calculadora Distribuida 3 Capas — " + client.getClientId() + " (Cliente Pesado)");
        setSize(1050, 680);
        setMinimumSize(new Dimension(850, 550));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        initUi();
    }

    private void initUi() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(new EmptyBorder(12, 14, 12, 14));
        setContentPane(root);

        // 1. Barra superior: Conexión y Estado
        root.add(buildTopBar(), BorderLayout.NORTH);

        // 2. Panel central dividido: Izquierda Calculadora, Derecha Tabs
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(380);
        splitPane.setContinuousLayout(true);
        splitPane.setBorder(null);

        splitPane.setLeftComponent(buildCalculatorPanel());
        splitPane.setRightComponent(buildTabsPanel());

        root.add(splitPane, BorderLayout.CENTER);

        // 3. Barra de estado inferior
        root.add(buildStatusBar(), BorderLayout.SOUTH);
    }

    private JPanel buildTopBar() {
        JPanel bar = new JPanel(new BorderLayout(10, 5));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(60, 60, 60)),
                new EmptyBorder(0, 0, 10, 0)
        ));

        // Izquierda: Título y badge de Cliente
        JPanel titlePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JLabel titleLabel = new JLabel("Calculadora Distribuida");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 17));

        JLabel clientBadge = new JLabel("  " + client.getClientId() + "  ");
        clientBadge.setFont(new Font("SansSerif", Font.BOLD, 12));
        clientBadge.setOpaque(true);
        clientBadge.setBackground(new Color(41, 128, 185));
        clientBadge.setForeground(Color.WHITE);
        clientBadge.setBorder(BorderFactory.createLineBorder(new Color(52, 152, 219), 1, true));

        titlePanel.add(titleLabel);
        titlePanel.add(clientBadge);
        bar.add(titlePanel, BorderLayout.WEST);

        // Derecha: Controles de conexión TCP al Middleware
        JPanel connPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));

        connPanel.add(new JLabel("Middleware Host:"));
        hostField = new JTextField(client.getHost(), 10);
        connPanel.add(hostField);

        connPanel.add(new JLabel("Puerto:"));
        portField = new JTextField(String.valueOf(client.getPort()), 5);
        connPanel.add(portField);

        connectBtn = new JButton("Conectar");
        connectBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
        connectBtn.addActionListener(e -> toggleConnection());
        connPanel.add(connectBtn);

        statusPill = new JLabel("  ○ Desconectado  ");
        statusPill.setOpaque(true);
        statusPill.setBackground(new Color(192, 57, 43));
        statusPill.setForeground(Color.WHITE);
        statusPill.setFont(new Font("SansSerif", Font.BOLD, 11));
        connPanel.add(statusPill);

        bar.add(connPanel, BorderLayout.EAST);
        return bar;
    }

    private JPanel buildCalculatorPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(new EmptyBorder(5, 5, 5, 10));

        // Pantalla LCD / Display
        JPanel screenPanel = new JPanel(new BorderLayout(4, 4));
        screenPanel.setBackground(new Color(28, 28, 30));
        screenPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(60, 60, 65), 1, true),
                new EmptyBorder(10, 14, 10, 14)
        ));

        formulaLabel = new JLabel("Listo para operar");
        formulaLabel.setFont(new Font("Monospaced", Font.PLAIN, 13));
        formulaLabel.setForeground(new Color(160, 160, 160));
        formulaLabel.setHorizontalAlignment(SwingConstants.RIGHT);

        displayField = new JTextField("0");
        displayField.setFont(new Font("SansSerif", Font.BOLD, 28));
        displayField.setHorizontalAlignment(SwingConstants.RIGHT);
        displayField.setBackground(new Color(28, 28, 30));
        displayField.setForeground(Color.WHITE);
        displayField.setBorder(null);
        displayField.setEditable(false);

        screenPanel.add(formulaLabel, BorderLayout.NORTH);
        screenPanel.add(displayField, BorderLayout.CENTER);
        panel.add(screenPanel, BorderLayout.NORTH);

        // Teclado Numérico y de Operaciones
        JPanel keypad = new JPanel(new GridLayout(5, 4, 6, 6));

        String[][] buttons = {
                {"C", "CE", "DEL", "÷"},
                {"7", "8", "9", "×"},
                {"4", "5", "6", "-"},
                {"1", "2", "3", "+"},
                {"±", "0", ".", "="}
        };

        for (String[] row : buttons) {
            for (String label : row) {
                JButton btn = new JButton(label);
                btn.setFont(new Font("SansSerif", Font.BOLD, 16));
                btn.setFocusPainted(false);

                if ("÷×-+=".contains(label)) {
                    btn.setBackground(new Color(230, 126, 34));
                    btn.setForeground(Color.WHITE);
                } else if ("C CE DEL".contains(label)) {
                    btn.setBackground(new Color(127, 140, 141));
                    btn.setForeground(Color.WHITE);
                } else {
                    btn.setBackground(new Color(45, 52, 54));
                    btn.setForeground(Color.WHITE);
                }

                btn.addActionListener(this::handleKeypadAction);
                keypad.add(btn);
            }
        }
        panel.add(keypad, BorderLayout.CENTER);

        // Entrada manual rápida opcional
        JPanel quickPanel = new JPanel(new GridBagLayout());
        quickPanel.setBorder(BorderFactory.createTitledBorder("Entrada Manual Rápida"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JTextField opAText = new JTextField("10", 6);
        JComboBox<String> opCombo = new JComboBox<>(new String[]{"+", "-", "×", "÷"});
        JTextField opBText = new JTextField("5", 6);
        JButton manualCalcBtn = new JButton("Enviar al Middleware");
        manualCalcBtn.setFont(new Font("SansSerif", Font.BOLD, 11));

        manualCalcBtn.addActionListener(e -> {
            try {
                double a = Double.parseDouble(opAText.getText().trim());
                double b = Double.parseDouble(opBText.getText().trim());
                OperationType op = OperationType.fromSymbolOrName((String) opCombo.getSelectedItem());
                triggerDistributedCalculation(op, a, b);
            } catch (Exception ex) {
                showError("Datos inválidos: " + ex.getMessage());
            }
        });

        gbc.gridx = 0; gbc.gridy = 0; quickPanel.add(opAText, gbc);
        gbc.gridx = 1; gbc.gridy = 0; quickPanel.add(opCombo, gbc);
        gbc.gridx = 2; gbc.gridy = 0; quickPanel.add(opBText, gbc);
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 3; quickPanel.add(manualCalcBtn, gbc);

        panel.add(quickPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JTabbedPane buildTabsPanel() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(new Font("SansSerif", Font.BOLD, 12));

        // Tab 1: Respuestas en Tiempo Real (Recepción de todos los servidores)
        tabs.addTab("Respuestas en Vivo (Broadcast)", buildLiveResponsesTab());

        // Tab 2: Persistencia Local en Disco
        tabs.addTab("Historial en Disco (JSON)", buildPersistenceTab());

        // Tab 3: Consola de Sockets
        tabs.addTab("Consola de Sockets", buildConsoleTab());

        return tabs;
    }

    private JPanel buildLiveResponsesTab() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        JLabel info = new JLabel("Respuestas recibidas desde el Middleware (difundidas por TODOS los servidores conectados):");
        info.setFont(new Font("SansSerif", Font.PLAIN, 12));
        panel.add(info, BorderLayout.NORTH);

        String[] cols = {"Tx ID", "Hora", "Operación", "Servidor", "Resultado", "Estatus"};
        liveTableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        liveTable = new JTable(liveTableModel);
        liveTable.setRowHeight(24);
        liveTable.setFont(new Font("SansSerif", Font.PLAIN, 12));

        // Alineación centrada para ciertas columnas
        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(JLabel.CENTER);
        liveTable.getColumnModel().getColumn(0).setPreferredWidth(85);
        liveTable.getColumnModel().getColumn(1).setPreferredWidth(65);
        liveTable.getColumnModel().getColumn(2).setPreferredWidth(85);
        liveTable.getColumnModel().getColumn(3).setPreferredWidth(85);
        liveTable.getColumnModel().getColumn(4).setPreferredWidth(95);
        liveTable.getColumnModel().getColumn(5).setPreferredWidth(120);
        liveTable.getColumnModel().getColumn(0).setCellRenderer(centerRenderer);
        liveTable.getColumnModel().getColumn(1).setCellRenderer(centerRenderer);
        liveTable.getColumnModel().getColumn(3).setCellRenderer(centerRenderer);

        panel.add(new JScrollPane(liveTable), BorderLayout.CENTER);

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton clearBtn = new JButton("Limpiar Tabla");
        clearBtn.addActionListener(e -> liveTableModel.setRowCount(0));
        btnBar.add(clearBtn);
        panel.add(btnBar, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel buildPersistenceTab() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        File file = client.getPersistence().getDataFile();
        persistenceFileLabel = new JLabel("Archivo de persistencia: " + file.getAbsolutePath());
        persistenceFileLabel.setFont(new Font("Monospaced", Font.PLAIN, 11));
        panel.add(persistenceFileLabel, BorderLayout.NORTH);

        String[] cols = {"ID Registro", "Timestamp", "Tipo Evento", "Tx ID", "Op", "A", "B", "Resultado", "Servidor", "Estatus"};
        persistenceTableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        persistenceTable = new JTable(persistenceTableModel);
        persistenceTable.setRowHeight(22);
        persistenceTable.setFont(new Font("SansSerif", Font.PLAIN, 11));
        panel.add(new JScrollPane(persistenceTable), BorderLayout.CENTER);

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton reloadBtn = new JButton("Recargar desde Disco");
        reloadBtn.addActionListener(e -> reloadPersistenceData());
        btnBar.add(reloadBtn);
        panel.add(btnBar, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel buildConsoleTab() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        consoleArea = new JTextArea();
        consoleArea.setEditable(false);
        consoleArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        consoleArea.setBackground(new Color(20, 20, 20));
        consoleArea.setForeground(new Color(0, 255, 128));

        panel.add(new JScrollPane(consoleArea), BorderLayout.CENTER);

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton clearBtn = new JButton("Limpiar Consola");
        clearBtn.addActionListener(e -> consoleArea.setText(""));
        btnBar.add(clearBtn);
        panel.add(btnBar, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel buildStatusBar() {
        JPanel status = new JPanel(new BorderLayout());
        status.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(60, 60, 60)),
                new EmptyBorder(4, 8, 4, 8)
        ));
        JLabel tip = new JLabel("Requisito Rúbrica: Las operaciones emitidas se difunden a TODOS los servidores y los resultados a TODOS los clientes.");
        tip.setFont(new Font("SansSerif", Font.ITALIC, 11));
        status.add(tip, BorderLayout.WEST);
        return status;
    }

    // --- Manejo del teclado interactivo ---

    private void handleKeypadAction(ActionEvent e) {
        String cmd = e.getActionCommand();

        if ("0123456789".contains(cmd)) {
            if (isTypingNewNumber || displayField.getText().equals("0")) {
                displayField.setText(cmd);
                isTypingNewNumber = false;
            } else {
                displayField.setText(displayField.getText() + cmd);
            }
        } else if (cmd.equals(".")) {
            if (isTypingNewNumber) {
                displayField.setText("0.");
                isTypingNewNumber = false;
            } else if (!displayField.getText().contains(".")) {
                displayField.setText(displayField.getText() + ".");
            }
        } else if (cmd.equals("±")) {
            try {
                double val = Double.parseDouble(displayField.getText());
                val = -val;
                displayField.setText(val == (long) val ? String.format("%d", (long) val) : String.valueOf(val));
            } catch (Exception ignored) {}
        } else if (cmd.equals("C")) {
            operandA = null;
            pendingOperation = null;
            displayField.setText("0");
            formulaLabel.setText("Listo para operar");
            isTypingNewNumber = true;
        } else if (cmd.equals("CE")) {
            displayField.setText("0");
            isTypingNewNumber = true;
        } else if (cmd.equals("DEL")) {
            String txt = displayField.getText();
            if (txt.length() > 1) {
                displayField.setText(txt.substring(0, txt.length() - 1));
            } else {
                displayField.setText("0");
                isTypingNewNumber = true;
            }
        } else if ("+-×÷".contains(cmd)) {
            try {
                operandA = Double.parseDouble(displayField.getText());
                pendingOperation = OperationType.fromSymbolOrName(cmd);
                formulaLabel.setText(String.format("%.2f %s", operandA, pendingOperation.getSymbol()));
                isTypingNewNumber = true;
            } catch (Exception ex) {
                showError("Número inválido: " + ex.getMessage());
            }
        } else if (cmd.equals("=")) {
            if (operandA != null && pendingOperation != null) {
                try {
                    double operandB = Double.parseDouble(displayField.getText());
                    formulaLabel.setText(String.format("%.2f %s %.2f =", operandA, pendingOperation.getSymbol(), operandB));
                    triggerDistributedCalculation(pendingOperation, operandA, operandB);
                    isTypingNewNumber = true;
                    // Resetear operando A para siguiente cálculo
                    operandA = null;
                    pendingOperation = null;
                } catch (Exception ex) {
                    showError("Operando inválido: " + ex.getMessage());
                }
            }
        }
    }

    private void triggerDistributedCalculation(OperationType op, double a, double b) {
        if (!client.isConnected()) {
            showError("No estás conectado al Middleware. Conéctate primero.");
            return;
        }

        displayField.setText("Procesando...");
        String txId = client.sendCalculateRequest(op, a, b);
        if (txId == null) {
            displayField.setText("Error");
        }
    }

    private void toggleConnection() {
        if (client.isConnected()) {
            client.disconnect();
        } else {
            try {
                String host = hostField.getText().trim();
                int port = Integer.parseInt(portField.getText().trim());
                client.setHost(host);
                client.setPort(port);
                boolean ok = client.connect();
                if (!ok) {
                    showError("No se pudo conectar a " + host + ":" + port + ". ¿El Middleware está corriendo?");
                }
            } catch (Exception e) {
                showError("Error en configuración de conexión: " + e.getMessage());
            }
        }
    }

    private void reloadPersistenceData() {
        persistenceTableModel.setRowCount(0);
        List<PersistenceRecord> records = client.getPersistence().loadAll();
        for (PersistenceRecord r : records) {
            persistenceTableModel.addRow(new Object[]{
                    r.getRecordId(),
                    r.getTimestamp(),
                    r.getEventType(),
                    r.getTxId(),
                    r.getOperation(),
                    r.getOperandA(),
                    r.getOperandB(),
                    r.getResult(),
                    r.getResponderServerId(),
                    r.getStatus()
            });
        }
    }

    // --- Implementación de ClientEventListener ---

    @Override
    public void onConnected(String host, int port, String clientId) {
        SwingUtilities.invokeLater(() -> {
            connectBtn.setText("Desconectar");
            connectBtn.setBackground(new Color(192, 57, 43));
            statusPill.setText("  ● Conectado  ");
            statusPill.setBackground(new Color(39, 174, 96));
            hostField.setEnabled(false);
            portField.setEnabled(false);
            reloadPersistenceData();
        });
    }

    @Override
    public void onDisconnected() {
        SwingUtilities.invokeLater(() -> {
            connectBtn.setText("Conectar");
            connectBtn.setBackground(null);
            statusPill.setText("  ○ Desconectado  ");
            statusPill.setBackground(new Color(192, 57, 43));
            hostField.setEnabled(true);
            portField.setEnabled(true);
        });
    }

    @Override
    public void onResponseReceived(NetworkMessage res) {
        SwingUtilities.invokeLater(() -> {
            String time = LocalTime.now().format(TIME_FMT);
            String opSymbol = res.getOperation() != null ? res.getOperation().getSymbol() : "?";
            String resultStr = res.getResult() != null ? String.valueOf(res.getResult()) : "ERROR";

            // Actualizar la pantalla LCD si corresponde a un cálculo exitoso o error
            if ("SUCCESS".equals(res.getStatus()) && res.getResult() != null) {
                displayField.setText(res.getResult() == (long) (double) res.getResult() ?
                        String.format("%d", (long) (double) res.getResult()) :
                        String.valueOf(res.getResult()));
            } else {
                displayField.setText(res.getStatus());
            }

            // Agregar a la tabla en vivo
            liveTableModel.insertRow(0, new Object[]{
                    res.getTxId(),
                    time,
                    opSymbol + " (" + (res.getOperation() != null ? res.getOperation().name() : "") + ")",
                    res.getServerId(),
                    resultStr,
                    res.getStatus()
            });

            // Actualizar tabla de persistencia en disco
            reloadPersistenceData();
        });
    }

    @Override
    public void onLogMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            String time = LocalTime.now().format(TIME_FMT);
            consoleArea.append("[" + time + "] " + message + "\n");
            consoleArea.setCaretPosition(consoleArea.getDocument().getLength());
        });
    }

    @Override
    public void onError(String error) {
        SwingUtilities.invokeLater(() -> {
            onLogMessage("[ERROR] " + error);
        });
    }

    private void showError(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Aviso del Sistema", JOptionPane.WARNING_MESSAGE);
    }
}
