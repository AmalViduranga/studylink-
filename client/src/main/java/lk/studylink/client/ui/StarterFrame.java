package lk.studylink.client.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableModel;
import lk.studylink.client.rooms.RoomClient;
import lk.studylink.shared.config.StudyLinkConfig;
import lk.studylink.shared.model.FeatureServiceSpec;

/**
 * Main Swing desktop window for the StudyLink student client rendered with FlatLaf Light on the
 * Swing Event Dispatch Thread (SRS Section 2, F02–F04, F06–F07, F13, T02, T04).
 *
 * <p>Hosts both Member 1's interactive {@link RoomLifecyclePanel} (TCP room control and session
 * lifecycle) and Member 5's {@link ChatPanel} (Java NIO live room chat). Connecting and joining
 * or switching a room automatically connects {@link ChatPanel} to the active room-control host and
 * the {@code chatNioTcp} port returned by {@code HELLO}, while leaving, disconnecting, losing
 * connection, or closing the window cleanly unsubscribes and disposes both services off the EDT.
 */
public final class StarterFrame extends JFrame {

    private static final String UI_RESOURCE_PATH = "/studylink-ui.properties";

    private final RoomLifecyclePanel roomLifecyclePanel;
    private final ChatPanel chatPanel;
    private final AtomicBoolean cleanedUp = new AtomicBoolean(false);

    public StarterFrame(StudyLinkConfig config, String configSource) {
        this(
                config,
                configSource,
                new RoomClient(Objects.requireNonNull(config, "config must not be null")),
                new ChatPanel(config)
        );
    }

    public StarterFrame(StudyLinkConfig config, String configSource, RoomClient roomClient) {
        this(
                config,
                configSource,
                roomClient,
                new ChatPanel(Objects.requireNonNull(config, "config must not be null"))
        );
    }

    public StarterFrame(
            StudyLinkConfig config,
            String configSource,
            RoomClient roomClient,
            ChatPanel chatPanel
    ) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(configSource, "configSource must not be null");
        Objects.requireNonNull(roomClient, "roomClient must not be null");
        Objects.requireNonNull(chatPanel, "chatPanel must not be null");

        Properties uiProps = loadUiProperties();
        String title = uiProps.getProperty("app.title", "StudyLink Desktop Client");
        String subtitle = uiProps.getProperty(
                "app.subtitle",
                "Classroom LAN Study Collaboration — Room Control & Live Room Chat Workspace (Java 21 + FlatLaf Light)"
        );
        String statusBanner = uiProps.getProperty(
                "app.status.banner",
                "TCP Room Control (M1) and Java NIO Live Room Chat (M5) are active. Closing this window disconnects any active session."
        );
        int width = parseDimension(uiProps.getProperty("window.width"), 980);
        int height = parseDimension(uiProps.getProperty("window.height"), 740);

        setTitle(title);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setPreferredSize(new Dimension(width, height));

        this.chatPanel = chatPanel;
        this.roomLifecyclePanel = new RoomLifecyclePanel(config, roomClient, chatPanel);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                cleanupRoomResources();
            }

            @Override
            public void windowClosed(WindowEvent e) {
                cleanupRoomResources();
            }
        });

        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        // Header panel
        JPanel headerPanel = new JPanel(new BorderLayout(4, 4));
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 18f));
        JLabel subtitleLabel = new JLabel(subtitle);
        subtitleLabel.setForeground(new Color(80, 90, 105));
        headerPanel.add(titleLabel, BorderLayout.NORTH);
        headerPanel.add(subtitleLabel, BorderLayout.SOUTH);
        root.add(headerPanel, BorderLayout.NORTH);

        // Tab 1: Unified Room Session (M1) + Live Room Chat (M5) workspace
        JPanel chatContainer = new JPanel(new BorderLayout());
        chatContainer.setBorder(BorderFactory.createTitledBorder("4. Live Room Chat (Java NIO — M5)"));
        chatContainer.add(chatPanel, BorderLayout.CENTER);

        JSplitPane workspaceSplit = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                roomLifecyclePanel,
                chatContainer
        );
        workspaceSplit.setResizeWeight(0.48);
        workspaceSplit.setContinuousLayout(true);
        workspaceSplit.setBorder(BorderFactory.createEmptyBorder());

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Room Session & Live Chat (M1 + M5)", workspaceSplit);
        tabs.addTab("Configuration & Service Endpoints", buildOverviewTab(config, configSource));
        root.add(tabs, BorderLayout.CENTER);

        // Footer panel with status and close button
        JPanel footerPanel = new JPanel(new BorderLayout(8, 8));
        JLabel statusLabel = new JLabel(statusBanner, SwingConstants.LEFT);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        footerPanel.add(statusLabel, BorderLayout.CENTER);

        JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        JButton closeButton = new JButton("Close Window");
        closeButton.addActionListener(e -> dispose());
        buttonBar.add(closeButton);
        footerPanel.add(buttonBar, BorderLayout.EAST);

        root.add(footerPanel, BorderLayout.SOUTH);

        setContentPane(root);
        pack();
        setLocationRelativeTo(null);
    }

    /**
     * Returns the embedded {@link RoomLifecyclePanel} managing room connection and membership state.
     */
    public RoomLifecyclePanel roomLifecyclePanel() {
        return roomLifecyclePanel;
    }

    /**
     * Returns the embedded {@link ChatPanel} managing Java NIO live room chat.
     */
    public ChatPanel chatPanel() {
        return chatPanel;
    }

    @Override
    public void dispose() {
        cleanupRoomResources();
        super.dispose();
    }

    private void cleanupRoomResources() {
        if (cleanedUp.compareAndSet(false, true)) {
            try {
                roomLifecyclePanel.dispose();
            } finally {
                chatPanel.dispose();
            }
        }
    }

    private static JPanel buildOverviewTab(StudyLinkConfig config, String configSource) {
        JPanel centerPanel = new JPanel(new BorderLayout(10, 10));
        centerPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel summaryPanel = buildSummaryPanel(config, configSource);
        centerPanel.add(summaryPanel, BorderLayout.NORTH);

        JTable featureTable = buildFeatureTable(config);
        JScrollPane tableScroll = new JScrollPane(featureTable);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Feature Modules & Server Endpoints (SRS Baseline)"));
        centerPanel.add(tableScroll, BorderLayout.CENTER);
        return centerPanel;
    }

    private static JPanel buildSummaryPanel(StudyLinkConfig config, String configSource) {
        JPanel panel = new JPanel(new GridLayout(0, 2, 12, 6));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Effective Non-Secret Configuration"),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)
        ));

        String roomsText = config.rooms().stream()
                .map(r -> r.displayName() + " (" + r.id() + ")")
                .collect(Collectors.joining(", "));

        panel.add(new JLabel("Configuration Source:"));
        panel.add(new JLabel(configSource));

        panel.add(new JLabel("Target Host & Server ID:"));
        panel.add(new JLabel(config.serverHost() + " [" + config.serverId() + "]"));

        panel.add(new JLabel("Predefined Study Rooms:"));
        panel.add(new JLabel(roomsText));

        panel.add(new JLabel("Client TLS TrustStore:"));
        panel.add(new JLabel(config.tlsClientTrustStore() + " (" + config.tlsProtocol() + ")"));

        panel.add(new JLabel("Host File Data Directory:"));
        panel.add(new JLabel(config.dataDir() + " (max file: " + (config.maxFileSizeBytes() / (1024 * 1024)) + " MiB)"));

        return panel;
    }

    private static JTable buildFeatureTable(StudyLinkConfig config) {
        String[] columns = {"Owner", "Package", "Port", "Transport / API", "Responsibility", "Status"};
        List<FeatureServiceSpec> services = FeatureServiceSpec.plannedServices(config);
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        for (FeatureServiceSpec spec : services) {
            String status = switch (spec.ownerCode()) {
                case "M1" -> "Active (RoomClient + Swing Room Workspace)";
                case "M5" -> "Active (ChatClient + Swing Live Room Chat)";
                default -> spec.status();
            };
            model.addRow(new Object[]{
                    spec.ownerCode(),
                    spec.featureName(),
                    spec.port(),
                    spec.transport(),
                    spec.summary(),
                    status
            });
        }

        JTable table = new JTable(model);
        table.setRowHeight(26);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(55);
        table.getColumnModel().getColumn(1).setPreferredWidth(85);
        table.getColumnModel().getColumn(2).setPreferredWidth(65);
        table.getColumnModel().getColumn(3).setPreferredWidth(210);
        table.getColumnModel().getColumn(4).setPreferredWidth(280);
        table.getColumnModel().getColumn(5).setPreferredWidth(185);
        return table;
    }

    private static Properties loadUiProperties() {
        Properties props = new Properties();
        try (InputStream in = StarterFrame.class.getResourceAsStream(UI_RESOURCE_PATH)) {
            if (in != null) {
                try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    props.load(reader);
                }
            }
        } catch (IOException ignored) {
            // Fall back to default window text and dimensions
        }
        return props;
    }

    private static int parseDimension(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed >= 320 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
