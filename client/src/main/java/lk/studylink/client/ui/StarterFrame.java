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
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableModel;
import lk.studylink.client.rooms.RoomClient;
import lk.studylink.shared.config.StudyLinkConfig;
import lk.studylink.shared.model.FeatureServiceSpec;

/**
 * Main Swing desktop window for the StudyLink student client rendered with FlatLaf Light on the
 * Swing Event Dispatch Thread (SRS Section 2, F02–F04, F13, T02).
 *
 * <p>Hosts the interactive {@link RoomLifecyclePanel} for connecting to the TCP room-control server,
 * listing server-configured rooms, joining/switching/leaving one active study room at a time, and
 * disconnecting cleanly on user request or window close.
 */
public final class StarterFrame extends JFrame {

    private static final String UI_RESOURCE_PATH = "/studylink-ui.properties";

    private final RoomLifecyclePanel roomLifecyclePanel;
    private final AtomicBoolean cleanedUp = new AtomicBoolean(false);

    public StarterFrame(StudyLinkConfig config, String configSource) {
        this(config, configSource, new RoomClient(Objects.requireNonNull(config, "config must not be null")));
    }

    public StarterFrame(StudyLinkConfig config, String configSource, RoomClient roomClient) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(configSource, "configSource must not be null");
        Objects.requireNonNull(roomClient, "roomClient must not be null");

        Properties uiProps = loadUiProperties();
        String title = uiProps.getProperty("app.title", "StudyLink Desktop Client");
        String subtitle = uiProps.getProperty(
                "app.subtitle",
                "Classroom LAN Study Collaboration — Room Control & Session Workspace (Java 21 + FlatLaf Light)"
        );
        String statusBanner = uiProps.getProperty(
                "app.status.banner",
                "TCP Room Control (M1) is active. Closing this window automatically disconnects any active session."
        );
        int width = parseDimension(uiProps.getProperty("window.width"), 940);
        int height = parseDimension(uiProps.getProperty("window.height"), 620);

        setTitle(title);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setPreferredSize(new Dimension(width, height));

        this.roomLifecyclePanel = new RoomLifecyclePanel(config, roomClient);

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

        // Center content: Tab 1 = Interactive Room Session & Membership (M1), Tab 2 = Config & Endpoints
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Room Session & Membership (M1)", roomLifecyclePanel);
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

    @Override
    public void dispose() {
        cleanupRoomResources();
        super.dispose();
    }

    private void cleanupRoomResources() {
        if (cleanedUp.compareAndSet(false, true)) {
            roomLifecyclePanel.dispose();
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
            String status = "M1".equals(spec.ownerCode())
                    ? "Active (RoomClient + Swing Room Workspace)"
                    : spec.status();
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
