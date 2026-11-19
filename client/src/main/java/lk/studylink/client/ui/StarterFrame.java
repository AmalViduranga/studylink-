package lk.studylink.client.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableModel;
import lk.studylink.shared.config.StudyLinkConfig;
import lk.studylink.shared.model.FeatureServiceSpec;

/**
 * Simple Swing starter window rendered with FlatLaf Light on the Swing Event Dispatch Thread.
 * Displays an honest phase-0 setup summary and lists the five planned feature areas reserved for M1-M5.
 */
public final class StarterFrame extends JFrame {

    private static final String UI_RESOURCE_PATH = "/studylink-ui.properties";

    public StarterFrame(StudyLinkConfig config, String configSource) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(configSource, "configSource must not be null");

        Properties uiProps = loadUiProperties();
        String title = uiProps.getProperty("app.title", "StudyLink Desktop Client — Starter Baseline");
        String subtitle = uiProps.getProperty("app.subtitle",
                "Phase-0 local setup verified with FlatLaf Light on Java 21");
        String statusBanner = uiProps.getProperty("app.status.banner",
                "Starter foundation ready. Working room, discovery, file transfer, private note and chat features are reserved for M1-M5.");
        int width = parseDimension(uiProps.getProperty("window.width"), 860);
        int height = parseDimension(uiProps.getProperty("window.height"), 560);

        setTitle(title);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setPreferredSize(new Dimension(width, height));

        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        // Header panel
        JPanel headerPanel = new JPanel(new BorderLayout(4, 4));
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 18f));
        JLabel subtitleLabel = new JLabel(subtitle);
        subtitleLabel.setForeground(new Color(80, 90, 105));
        headerPanel.add(titleLabel, BorderLayout.NORTH);
        headerPanel.add(subtitleLabel, BorderLayout.SOUTH);
        root.add(headerPanel, BorderLayout.NORTH);

        // Center content: Configuration summary + Feature ownership table
        JPanel centerPanel = new JPanel(new BorderLayout(10, 10));

        JPanel summaryPanel = buildSummaryPanel(config, configSource);
        centerPanel.add(summaryPanel, BorderLayout.NORTH);

        JTable featureTable = buildFeatureTable(config);
        JScrollPane tableScroll = new JScrollPane(featureTable);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Planned Feature Modules & Server Endpoints (SRS Baseline)"));
        centerPanel.add(tableScroll, BorderLayout.CENTER);

        root.add(centerPanel, BorderLayout.CENTER);

        // Footer panel with honest setup status and close button
        JPanel footerPanel = new JPanel(new BorderLayout(8, 8));
        JLabel statusLabel = new JLabel(statusBanner, SwingConstants.LEFT);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        footerPanel.add(statusLabel, BorderLayout.CENTER);

        JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        JButton closeButton = new JButton("Close Starter Window");
        closeButton.addActionListener(e -> dispose());
        buttonBar.add(closeButton);
        footerPanel.add(buttonBar, BorderLayout.EAST);

        root.add(footerPanel, BorderLayout.SOUTH);

        setContentPane(root);
        pack();
        setLocationRelativeTo(null);
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
        String[] columns = {"Owner", "Package", "Port", "Transport / API", "Responsibility", "Starter Status"};
        List<FeatureServiceSpec> services = FeatureServiceSpec.plannedServices(config);
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        for (FeatureServiceSpec spec : services) {
            model.addRow(new Object[]{
                    spec.ownerCode(),
                    spec.featureName(),
                    spec.port(),
                    spec.transport(),
                    spec.summary(),
                    spec.status()
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
        table.getColumnModel().getColumn(5).setPreferredWidth(165);
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
