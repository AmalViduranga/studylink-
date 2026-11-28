package lk.studylink.client.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import lk.studylink.client.rooms.AdvertisedServicePorts;
import lk.studylink.client.rooms.RoomClient;
import lk.studylink.client.rooms.RoomClientException;
import lk.studylink.shared.config.StudyLinkConfig;
import lk.studylink.shared.model.RoomBinding;
import lk.studylink.shared.model.RoomDefinition;
import lk.studylink.shared.model.SessionInfo;

/**
 * Swing room-control and membership lifecycle panel for Member 1 (SRS Sections 2, 5.1, 6.3, F02–F04, F13, T02).
 *
 * <h2>EDT &amp; Networking Discipline</h2>
 * <ul>
 *   <li>All TCP room-control operations ({@code HELLO}, {@code LIST_ROOMS}, {@code JOIN}, {@code LEAVE},
 *       {@code QUIT}) execute off the Swing Event Dispatch Thread on a single-thread background
 *       worker using {@link RoomClient} and its automatic {@code PING} heartbeat.</li>
 *   <li>Every Swing widget and model mutation runs strictly on the Swing EDT via
 *       {@link SwingUtilities#invokeLater(Runnable)}.</li>
 *   <li>Visible session, room catalogue, and active {@link RoomBinding} states are derived strictly
 *       from successful server responses. Failed room switches preserve the previous valid binding,
 *       duplicate display names ({@code NAME_IN_USE}) or unreachable servers are reported clearly
 *       while keeping inputs ready for retry, and disconnecting or closing the window releases the
 *       session cleanly.</li>
 * </ul>
 */
public final class RoomLifecyclePanel extends JPanel {

    private static final Color SUBTLE_COLOR = new Color(85, 95, 110);
    private static final Color CONNECTED_COLOR = new Color(22, 115, 58);
    private static final Color ERROR_COLOR = new Color(180, 38, 38);
    private static final int LIVENESS_POLL_MS = 400;

    private final StudyLinkConfig config;
    private final RoomClient roomClient;
    private final ExecutorService workerExecutor;
    private final AtomicLong uiGeneration = new AtomicLong(1L);
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final AtomicBoolean lastNetworkIoOffEdt = new AtomicBoolean(true);
    private final AtomicBoolean lastUiUpdateOnEdt = new AtomicBoolean(true);

    private final JTextField hostField = new JTextField(16);
    private final JTextField portField = new JTextField(6);
    private final JTextField displayNameField = new JTextField(16);
    private final JButton connectButton = new JButton("Connect");
    private final JButton disconnectButton = new JButton("Disconnect");

    private final DefaultListModel<RoomDefinition> roomListModel = new DefaultListModel<>();
    private final JList<RoomDefinition> roomList = new JList<>(roomListModel);
    private final JButton joinSwitchButton = new JButton("Join / Switch Room");
    private final JButton leaveButton = new JButton("Leave Room");
    private final JButton refreshRoomsButton = new JButton("Refresh Rooms");

    private final JLabel connectionStateLabel = new JLabel("Disconnected");
    private final JLabel sessionSummaryLabel = new JLabel("None");
    private final JLabel activeRoomLabel = new JLabel("None (outside all rooms)");
    private final JLabel servicePortsLabel = new JLabel("Not advertised");
    private final JLabel statusBannerLabel =
            new JLabel("Enter server host, control port, and display name, then click Connect.");

    private final Timer livenessTimer;

    private boolean connectedInUi = false;
    private boolean operationInFlight = false;
    private SessionInfo visibleSession = null;
    private AdvertisedServicePorts visiblePorts = null;
    private RoomBinding visibleBinding = null;
    private List<RoomDefinition> visibleRooms = List.of();

    /**
     * Creates a room lifecycle panel backed by a new {@link RoomClient} configured from {@code config}.
     */
    public RoomLifecyclePanel(StudyLinkConfig config) {
        this(
                Objects.requireNonNull(config, "config must not be null"),
                new RoomClient(config)
        );
    }

    /**
     * Creates a room lifecycle panel with an explicit {@link StudyLinkConfig} and {@link RoomClient}.
     */
    public RoomLifecyclePanel(StudyLinkConfig config, RoomClient roomClient) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.roomClient = Objects.requireNonNull(roomClient, "roomClient must not be null");
        this.workerExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread t = new Thread(runnable, "studylink-rooms-ui-worker");
            t.setDaemon(true);
            return t;
        });

        initializeLayout();
        bindActions();
        updateControlStatesOnEdt();

        this.livenessTimer = new Timer(LIVENESS_POLL_MS, e -> pollConnectionLivenessOnEdt());
        this.livenessTimer.start();
    }

    /**
     * Initiates an asynchronous {@code HELLO} + {@code LIST_ROOMS} connection using the current
     * host, port, and display-name fields. Network I/O runs off the EDT and all UI state updates
     * run on the EDT before the returned future completes.
     */
    public CompletableFuture<Optional<SessionInfo>> connectAsync() {
        CompletableFuture<Optional<SessionInfo>> result = new CompletableFuture<>();
        runOnEdt(() -> {
            recordUiUpdateThread();
            if (disposed.get()) {
                result.complete(Optional.empty());
                return;
            }
            if (operationInFlight) {
                setStatusBannerOnEdt("Another room-control operation is already in progress.", ERROR_COLOR);
                result.complete(Optional.ofNullable(visibleSession));
                return;
            }

            String host = hostField.getText() == null ? "" : hostField.getText().trim();
            String portText = portField.getText() == null ? "" : portField.getText().trim();
            String displayName = displayNameField.getText() == null ? "" : displayNameField.getText();

            if (host.isEmpty()) {
                setStatusBannerOnEdt("Enter a non-blank server hostname or IPv4 address.", ERROR_COLOR);
                result.complete(Optional.empty());
                return;
            }

            final int port;
            try {
                port = Integer.parseInt(portText);
                if (port < 1 || port > 65_535) {
                    throw new NumberFormatException("Port out of range");
                }
            } catch (NumberFormatException ex) {
                setStatusBannerOnEdt("Control port must be an integer between 1 and 65535.", ERROR_COLOR);
                result.complete(Optional.empty());
                return;
            }

            long gen = uiGeneration.incrementAndGet();
            operationInFlight = true;
            connectionStateLabel.setText("Connecting to " + host + ":" + port + "...");
            connectionStateLabel.setForeground(SUBTLE_COLOR);
            setStatusBannerOnEdt(
                    "Connecting to " + host + ":" + port + " as '" + displayName.trim() + "'...",
                    SUBTLE_COLOR
            );
            updateControlStatesOnEdt();

            workerExecutor.execute(() -> {
                recordNetworkThread();
                try {
                    SessionInfo session = roomClient.connect(host, port, displayName);
                    List<RoomDefinition> rooms;
                    try {
                        rooms = roomClient.listRooms();
                    } catch (IOException listEx) {
                        roomClient.close();
                        throw listEx;
                    }
                    AdvertisedServicePorts ports = roomClient.advertisedPorts().orElse(null);

                    runOnEdt(() -> {
                        recordUiUpdateThread();
                        if (disposed.get() || uiGeneration.get() != gen) {
                            result.complete(Optional.empty());
                            return;
                        }
                        operationInFlight = false;
                        applyConnectedStateOnEdt(host, port, session, ports, rooms);
                        result.complete(Optional.of(session));
                    });
                } catch (IOException ex) {
                    runOnEdt(() -> {
                        recordUiUpdateThread();
                        if (disposed.get() || uiGeneration.get() != gen) {
                            result.complete(Optional.empty());
                            return;
                        }
                        operationInFlight = false;
                        applyDisconnectedStateOnEdt(formatErrorBanner("Connection failed", ex), true);
                        result.complete(Optional.empty());
                    });
                }
            });
        });
        return result;
    }

    /**
     * Refreshes the configured room catalogue from the server via {@code LIST_ROOMS} off the EDT.
     */
    public CompletableFuture<List<RoomDefinition>> refreshRoomsAsync() {
        CompletableFuture<List<RoomDefinition>> result = new CompletableFuture<>();
        runOnEdt(() -> {
            recordUiUpdateThread();
            if (disposed.get() || !connectedInUi || operationInFlight) {
                result.complete(visibleRooms);
                return;
            }
            long gen = uiGeneration.get();
            operationInFlight = true;
            setStatusBannerOnEdt("Refreshing room catalogue from server...", SUBTLE_COLOR);
            updateControlStatesOnEdt();

            workerExecutor.execute(() -> {
                recordNetworkThread();
                try {
                    List<RoomDefinition> rooms = roomClient.listRooms();
                    runOnEdt(() -> {
                        recordUiUpdateThread();
                        if (disposed.get() || uiGeneration.get() != gen) {
                            result.complete(List.of());
                            return;
                        }
                        operationInFlight = false;
                        updateRoomListModelOnEdt(rooms);
                        setStatusBannerOnEdt(
                                "Loaded " + rooms.size() + " study rooms from server.",
                                CONNECTED_COLOR
                        );
                        updateControlStatesOnEdt();
                        result.complete(rooms);
                    });
                } catch (IOException ex) {
                    runOnEdt(() -> {
                        recordUiUpdateThread();
                        if (disposed.get() || uiGeneration.get() != gen) {
                            result.complete(List.of());
                            return;
                        }
                        operationInFlight = false;
                        handlePostConnectFailureOnEdt("Failed to refresh rooms", ex);
                        result.complete(visibleRooms);
                    });
                }
            });
        });
        return result;
    }

    /**
     * Joins or switches to the currently selected room in {@link #roomList()}.
     */
    public CompletableFuture<Optional<RoomBinding>> joinSelectedRoomAsync() {
        CompletableFuture<Optional<RoomBinding>> result = new CompletableFuture<>();
        runOnEdt(() -> {
            recordUiUpdateThread();
            RoomDefinition selected = roomList.getSelectedValue();
            if (selected == null) {
                setStatusBannerOnEdt("Select a study room from the list before clicking Join / Switch Room.", ERROR_COLOR);
                result.complete(Optional.ofNullable(visibleBinding));
                return;
            }
            joinRoomAsync(selected.id()).whenComplete((binding, err) -> {
                if (err != null) {
                    result.completeExceptionally(err);
                } else {
                    result.complete(binding);
                }
            });
        });
        return result;
    }

    /**
     * Joins or switches to {@code roomId} via {@code JOIN} off the EDT and updates visible
     * room binding state on the EDT from the server's response.
     */
    public CompletableFuture<Optional<RoomBinding>> joinRoomAsync(String roomId) {
        Objects.requireNonNull(roomId, "roomId must not be null");
        CompletableFuture<Optional<RoomBinding>> result = new CompletableFuture<>();
        runOnEdt(() -> {
            recordUiUpdateThread();
            if (disposed.get() || !connectedInUi) {
                setStatusBannerOnEdt("Connect to a server before joining a room.", ERROR_COLOR);
                result.complete(Optional.empty());
                return;
            }
            if (operationInFlight) {
                setStatusBannerOnEdt("Another room-control operation is already in progress.", ERROR_COLOR);
                result.complete(Optional.ofNullable(visibleBinding));
                return;
            }

            long gen = uiGeneration.get();
            operationInFlight = true;
            setStatusBannerOnEdt("Joining room '" + roomId.trim() + "'...", SUBTLE_COLOR);
            updateControlStatesOnEdt();

            workerExecutor.execute(() -> {
                recordNetworkThread();
                try {
                    RoomBinding newBinding = roomClient.joinRoom(roomId);
                    runOnEdt(() -> {
                        recordUiUpdateThread();
                        if (disposed.get() || uiGeneration.get() != gen) {
                            result.complete(Optional.empty());
                            return;
                        }
                        operationInFlight = false;
                        visibleBinding = newBinding;
                        selectRoomInListOnEdt(newBinding.roomId());
                        activeRoomLabel.setText(formatActiveRoomText(newBinding));
                        activeRoomLabel.setForeground(CONNECTED_COLOR);
                        setStatusBannerOnEdt(
                                "Joined room " + formatActiveRoomText(newBinding) + ".",
                                CONNECTED_COLOR
                        );
                        updateControlStatesOnEdt();
                        result.complete(Optional.of(newBinding));
                    });
                } catch (IOException ex) {
                    runOnEdt(() -> {
                        recordUiUpdateThread();
                        if (disposed.get() || uiGeneration.get() != gen) {
                            result.complete(Optional.empty());
                            return;
                        }
                        operationInFlight = false;
                        handlePostConnectFailureOnEdt("Room join rejected", ex);
                        result.complete(Optional.ofNullable(visibleBinding));
                    });
                }
            });
        });
        return result;
    }

    /**
     * Leaves the currently active room via {@code LEAVE} off the EDT while keeping the session
     * connected.
     */
    public CompletableFuture<Optional<String>> leaveRoomAsync() {
        CompletableFuture<Optional<String>> result = new CompletableFuture<>();
        runOnEdt(() -> {
            recordUiUpdateThread();
            if (disposed.get() || !connectedInUi) {
                setStatusBannerOnEdt("Not connected to a server.", ERROR_COLOR);
                result.complete(Optional.empty());
                return;
            }
            if (operationInFlight) {
                setStatusBannerOnEdt("Another room-control operation is already in progress.", ERROR_COLOR);
                result.complete(Optional.empty());
                return;
            }

            long gen = uiGeneration.get();
            operationInFlight = true;
            setStatusBannerOnEdt("Leaving current room...", SUBTLE_COLOR);
            updateControlStatesOnEdt();

            workerExecutor.execute(() -> {
                recordNetworkThread();
                try {
                    Optional<String> leftRoomId = roomClient.leaveRoom();
                    runOnEdt(() -> {
                        recordUiUpdateThread();
                        if (disposed.get() || uiGeneration.get() != gen) {
                            result.complete(Optional.empty());
                            return;
                        }
                        operationInFlight = false;
                        visibleBinding = roomClient.currentBinding().orElse(null);
                        activeRoomLabel.setText(formatActiveRoomText(visibleBinding));
                        activeRoomLabel.setForeground(SUBTLE_COLOR);
                        String leftDesc = leftRoomId.map(id -> "'" + id + "'").orElse("active room");
                        setStatusBannerOnEdt(
                                "Left " + leftDesc + ". Session remains connected.",
                                CONNECTED_COLOR
                        );
                        updateControlStatesOnEdt();
                        result.complete(leftRoomId);
                    });
                } catch (IOException ex) {
                    runOnEdt(() -> {
                        recordUiUpdateThread();
                        if (disposed.get() || uiGeneration.get() != gen) {
                            result.complete(Optional.empty());
                            return;
                        }
                        operationInFlight = false;
                        handlePostConnectFailureOnEdt("Leave room failed", ex);
                        result.complete(Optional.empty());
                    });
                }
            });
        });
        return result;
    }

    /**
     * Disconnects the active session via {@link RoomClient#close()} off the EDT, releasing the
     * student's display name on the server and resetting the UI for reconnect.
     */
    public CompletableFuture<Void> disconnectAsync() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        runOnEdt(() -> {
            recordUiUpdateThread();
            if (disposed.get()) {
                result.complete(null);
                return;
            }
            long gen = uiGeneration.incrementAndGet();
            operationInFlight = true;
            setStatusBannerOnEdt("Disconnecting from server...", SUBTLE_COLOR);
            updateControlStatesOnEdt();

            workerExecutor.execute(() -> {
                recordNetworkThread();
                roomClient.close();
                runOnEdt(() -> {
                    recordUiUpdateThread();
                    if (!disposed.get() && uiGeneration.get() == gen) {
                        operationInFlight = false;
                        applyDisconnectedStateOnEdt(
                                "Disconnected from server. Display name released; ready to reconnect.",
                                false
                        );
                    }
                    result.complete(null);
                });
            });
        });
        return result;
    }

    /**
     * Stops the UI liveness timer, immediately marks the panel disposed on the EDT, and closes
     * the underlying {@link RoomClient} and worker executor off the EDT on an independent
     * non-daemon cleanup thread so pending or active network operations are cancelled without
     * blocking the Swing EDT. Safe and idempotent to call multiple times across
     * {@code windowClosing}, {@code windowClosed}, and {@code dispose()}.
     */
    public void dispose() {
        if (!disposed.compareAndSet(false, true)) {
            return;
        }
        uiGeneration.incrementAndGet();
        livenessTimer.stop();
        runOnEdt(() -> {
            recordUiUpdateThread();
            operationInFlight = false;
            applyDisconnectedStateOnEdt(
                    "Disconnected from server. Display name released.",
                    false
            );
        });

        Thread cleanupThread = new Thread(() -> {
            try {
                recordNetworkThread();
                roomClient.close();
            } finally {
                workerExecutor.shutdownNow();
            }
        }, "studylink-rooms-ui-cleanup");
        cleanupThread.setDaemon(false);
        cleanupThread.start();
    }

    // --- Component & state accessors for UI composition and tests ---

    public JTextField hostField() {
        return hostField;
    }

    public JTextField portField() {
        return portField;
    }

    public JTextField displayNameField() {
        return displayNameField;
    }

    public JButton connectButton() {
        return connectButton;
    }

    public JButton disconnectButton() {
        return disconnectButton;
    }

    public JList<RoomDefinition> roomList() {
        return roomList;
    }

    public JButton joinSwitchButton() {
        return joinSwitchButton;
    }

    public JButton leaveButton() {
        return leaveButton;
    }

    public JButton refreshRoomsButton() {
        return refreshRoomsButton;
    }

    public String connectionStateText() {
        return connectionStateLabel.getText();
    }

    public String sessionSummaryText() {
        return sessionSummaryLabel.getText();
    }

    public String activeRoomText() {
        return activeRoomLabel.getText();
    }

    public String servicePortsText() {
        return servicePortsLabel.getText();
    }

    public String statusBannerText() {
        return statusBannerLabel.getText();
    }

    public boolean isConnectedInUi() {
        return connectedInUi;
    }

    public Optional<SessionInfo> currentSession() {
        return Optional.ofNullable(visibleSession);
    }

    public Optional<RoomBinding> currentBinding() {
        return Optional.ofNullable(visibleBinding);
    }

    public List<RoomDefinition> listedRooms() {
        return visibleRooms;
    }

    public boolean lastNetworkIoRanOffEdt() {
        return lastNetworkIoOffEdt.get();
    }

    public boolean lastUiUpdateRanOnEdt() {
        return lastUiUpdateOnEdt.get();
    }

    private void initializeLayout() {
        setLayout(new BorderLayout(12, 12));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        // 1. Top Panel: Server Endpoint & Display Name Connection Bar
        JPanel connectionPanel = new JPanel(new GridBagLayout());
        connectionPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("1. Host Connection & Student Display Name (TCP Room Control)"),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)
        ));

        hostField.setText(config.serverHost());
        hostField.getAccessibleContext().setAccessibleName("Server host or IPv4 address");

        portField.setText(Integer.toString(config.roomsPort()));
        portField.getAccessibleContext().setAccessibleName("Server TCP control port");

        displayNameField.putClientProperty(
                "JTextField.placeholderText",
                "2–24 chars (letters, digits, spaces)"
        );
        displayNameField.getAccessibleContext().setAccessibleName("Student display name");

        connectButton.getAccessibleContext().setAccessibleName("Connect to server");
        disconnectButton.getAccessibleContext().setAccessibleName("Disconnect from server");

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0;
        gbc.gridy = 0;
        connectionPanel.add(new JLabel("Server Host:"), gbc);

        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 0.35;
        connectionPanel.add(hostField, gbc);

        gbc.gridx = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0.0;
        connectionPanel.add(new JLabel("Control Port:"), gbc);

        gbc.gridx = 3;
        connectionPanel.add(portField, gbc);

        gbc.gridx = 4;
        connectionPanel.add(new JLabel("Display Name:"), gbc);

        gbc.gridx = 5;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 0.45;
        connectionPanel.add(displayNameField, gbc);

        gbc.gridx = 6;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0.0;
        connectionPanel.add(connectButton, gbc);

        gbc.gridx = 7;
        connectionPanel.add(disconnectButton, gbc);

        add(connectionPanel, BorderLayout.NORTH);

        // 2. Center Split: Left = Server Room Catalogue & Actions, Right = Live Session & Room Binding State
        JPanel centerPanel = new JPanel(new BorderLayout(12, 12));

        JPanel roomsPanel = new JPanel(new BorderLayout(8, 8));
        roomsPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("2. Server Study Rooms (Fetched via LIST_ROOMS)"),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)
        ));

        roomList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        roomList.setVisibleRowCount(5);
        roomList.getAccessibleContext().setAccessibleName("Available study rooms");
        roomList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list,
                    Object value,
                    int index,
                    boolean isSelected,
                    boolean cellHasFocus
            ) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof RoomDefinition room) {
                    setText(room.displayName() + "  [" + room.id() + "]");
                }
                return this;
            }
        });

        JScrollPane roomScroll = new JScrollPane(roomList);
        roomScroll.setPreferredSize(new Dimension(340, 160));
        roomsPanel.add(roomScroll, BorderLayout.CENTER);

        JPanel roomButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        joinSwitchButton.getAccessibleContext().setAccessibleName("Join or switch to selected study room");
        leaveButton.getAccessibleContext().setAccessibleName("Leave current study room");
        refreshRoomsButton.getAccessibleContext().setAccessibleName("Refresh study room list");
        roomButtons.add(joinSwitchButton);
        roomButtons.add(leaveButton);
        roomButtons.add(refreshRoomsButton);
        roomsPanel.add(roomButtons, BorderLayout.SOUTH);

        centerPanel.add(roomsPanel, BorderLayout.CENTER);

        JPanel statePanel = new JPanel(new GridBagLayout());
        statePanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("3. Active Session & Room Membership State"),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)
        ));
        statePanel.setPreferredSize(new Dimension(380, 160));

        connectionStateLabel.setFont(connectionStateLabel.getFont().deriveFont(Font.BOLD));
        connectionStateLabel.setForeground(SUBTLE_COLOR);
        activeRoomLabel.setFont(activeRoomLabel.getFont().deriveFont(Font.BOLD));
        activeRoomLabel.setForeground(SUBTLE_COLOR);

        addStateRow(statePanel, 0, "Connection Status:", connectionStateLabel);
        addStateRow(statePanel, 1, "Registered Session:", sessionSummaryLabel);
        addStateRow(statePanel, 2, "Active Study Room:", activeRoomLabel);
        addStateRow(statePanel, 3, "Advertised Ports:", servicePortsLabel);

        centerPanel.add(statePanel, BorderLayout.EAST);
        add(centerPanel, BorderLayout.CENTER);

        // 3. Bottom Status Banner
        JPanel bannerPanel = new JPanel(new BorderLayout(8, 4));
        bannerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Room Control Status"),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
        ));
        statusBannerLabel.setForeground(SUBTLE_COLOR);
        bannerPanel.add(statusBannerLabel, BorderLayout.CENTER);
        add(bannerPanel, BorderLayout.SOUTH);
    }

    private static void addStateRow(JPanel panel, int row, String title, JLabel valueLabel) {
        GridBagConstraints labelGbc = new GridBagConstraints();
        labelGbc.gridx = 0;
        labelGbc.gridy = row;
        labelGbc.anchor = GridBagConstraints.NORTHWEST;
        labelGbc.insets = new Insets(4, 4, 4, 8);
        panel.add(new JLabel(title), labelGbc);

        GridBagConstraints valGbc = new GridBagConstraints();
        valGbc.gridx = 1;
        valGbc.gridy = row;
        valGbc.weightx = 1.0;
        valGbc.fill = GridBagConstraints.HORIZONTAL;
        valGbc.anchor = GridBagConstraints.NORTHWEST;
        valGbc.insets = new Insets(4, 0, 4, 4);
        panel.add(valueLabel, valGbc);
    }

    private void bindActions() {
        connectButton.addActionListener(e -> connectAsync());
        displayNameField.addActionListener(e -> {
            if (connectButton.isEnabled()) {
                connectAsync();
            }
        });
        disconnectButton.addActionListener(e -> disconnectAsync());
        joinSwitchButton.addActionListener(e -> joinSelectedRoomAsync());
        leaveButton.addActionListener(e -> leaveRoomAsync());
        refreshRoomsButton.addActionListener(e -> refreshRoomsAsync());
        roomList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateControlStatesOnEdt();
            }
        });
    }

    private void applyConnectedStateOnEdt(
            String host,
            int port,
            SessionInfo session,
            AdvertisedServicePorts ports,
            List<RoomDefinition> rooms
    ) {
        this.connectedInUi = true;
        this.visibleSession = session;
        this.visiblePorts = ports;
        this.visibleBinding = roomClient.currentBinding().orElse(null);

        connectionStateLabel.setText("Connected (" + host + ":" + port + ")");
        connectionStateLabel.setForeground(CONNECTED_COLOR);

        sessionSummaryLabel.setText(session.displayName() + " [" + session.sessionId() + "]");
        activeRoomLabel.setText(formatActiveRoomText(visibleBinding));
        activeRoomLabel.setForeground(visibleBinding != null ? CONNECTED_COLOR : SUBTLE_COLOR);

        if (ports != null) {
            servicePortsLabel.setText(
                    "UDP " + ports.discoveryUdp()
                            + " | Rooms " + ports.roomsTcp()
                            + " | Chat " + ports.chatNioTcp()
                            + " | Files " + ports.filesTcp()
                            + " | Notes " + ports.notesTlsTcp()
            );
        } else {
            servicePortsLabel.setText("Not advertised");
        }

        updateRoomListModelOnEdt(rooms);
        setStatusBannerOnEdt(
                "Connected as '" + session.displayName() + "' (" + session.sessionId()
                        + "). Loaded " + rooms.size() + " study rooms.",
                CONNECTED_COLOR
        );
        updateControlStatesOnEdt();
    }

    private void applyDisconnectedStateOnEdt(String bannerMessage, boolean isError) {
        this.connectedInUi = false;
        this.visibleSession = null;
        this.visiblePorts = null;
        this.visibleBinding = null;
        this.visibleRooms = List.of();

        roomListModel.clear();
        connectionStateLabel.setText("Disconnected");
        connectionStateLabel.setForeground(isError ? ERROR_COLOR : SUBTLE_COLOR);
        sessionSummaryLabel.setText("None");
        activeRoomLabel.setText("None (outside all rooms)");
        activeRoomLabel.setForeground(SUBTLE_COLOR);
        servicePortsLabel.setText("Not advertised");

        setStatusBannerOnEdt(bannerMessage, isError ? ERROR_COLOR : SUBTLE_COLOR);
        updateControlStatesOnEdt();
    }

    private void handlePostConnectFailureOnEdt(String prefix, IOException ex) {
        if (!roomClient.isConnected()
                || (ex instanceof RoomClientException rce && rce.isConnectionInvalidated())) {
            applyDisconnectedStateOnEdt(
                    formatErrorBanner(prefix + " (connection lost)", ex),
                    true
            );
            return;
        }
        // Correlated server error (e.g. UNKNOWN_ROOM): preserve connection and previous RoomBinding
        this.visibleBinding = roomClient.currentBinding().orElse(null);
        activeRoomLabel.setText(formatActiveRoomText(visibleBinding));
        activeRoomLabel.setForeground(visibleBinding != null ? CONNECTED_COLOR : SUBTLE_COLOR);
        if (visibleBinding != null) {
            selectRoomInListOnEdt(visibleBinding.roomId());
        }
        setStatusBannerOnEdt(formatErrorBanner(prefix, ex), ERROR_COLOR);
        updateControlStatesOnEdt();
    }

    private void pollConnectionLivenessOnEdt() {
        if (disposed.get() || operationInFlight) {
            return;
        }
        if (connectedInUi && !roomClient.isConnected()) {
            uiGeneration.incrementAndGet();
            applyDisconnectedStateOnEdt(
                    "Disconnected from server (connection closed or session expired). You may reconnect.",
                    true
            );
        }
    }

    private void updateRoomListModelOnEdt(List<RoomDefinition> rooms) {
        String previousSelectionId = Optional.ofNullable(roomList.getSelectedValue())
                .map(RoomDefinition::id)
                .orElseGet(() -> visibleBinding != null ? visibleBinding.roomId() : null);

        this.visibleRooms = List.copyOf(rooms);
        roomListModel.clear();
        for (RoomDefinition room : visibleRooms) {
            roomListModel.addElement(room);
        }

        if (previousSelectionId != null) {
            selectRoomInListOnEdt(previousSelectionId);
        } else if (!visibleRooms.isEmpty()) {
            roomList.setSelectedIndex(0);
        }
    }

    private void selectRoomInListOnEdt(String roomId) {
        for (int i = 0; i < roomListModel.size(); i++) {
            if (roomListModel.get(i).id().equals(roomId)) {
                roomList.setSelectedIndex(i);
                return;
            }
        }
    }

    private void updateControlStatesOnEdt() {
        boolean idle = !operationInFlight && !disposed.get();
        hostField.setEnabled(idle && !connectedInUi);
        portField.setEnabled(idle && !connectedInUi);
        displayNameField.setEnabled(idle && !connectedInUi);
        connectButton.setEnabled(idle && !connectedInUi);

        disconnectButton.setEnabled(idle && connectedInUi);
        roomList.setEnabled(idle && connectedInUi);
        joinSwitchButton.setEnabled(idle && connectedInUi && roomList.getSelectedValue() != null);
        leaveButton.setEnabled(idle && connectedInUi && visibleBinding != null);
        refreshRoomsButton.setEnabled(idle && connectedInUi);
    }

    private String formatActiveRoomText(RoomBinding binding) {
        if (binding == null) {
            return "None (outside all rooms)";
        }
        String displayName = visibleRooms.stream()
                .filter(r -> r.id().equals(binding.roomId()))
                .map(RoomDefinition::displayName)
                .findFirst()
                .orElse(binding.roomId());
        return displayName + " (" + binding.roomId() + ") - Revision " + binding.revision();
    }

    private static String formatErrorBanner(String prefix, IOException ex) {
        if (ex instanceof RoomClientException rce && rce.errorCode().isPresent()) {
            return prefix + " [" + rce.errorCode().get() + "]: " + rce.getMessage();
        }
        String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return prefix + ": " + msg;
    }

    private void setStatusBannerOnEdt(String text, Color color) {
        statusBannerLabel.setText(text);
        statusBannerLabel.setForeground(color);
    }

    private void recordNetworkThread() {
        lastNetworkIoOffEdt.set(!SwingUtilities.isEventDispatchThread());
    }

    private void recordUiUpdateThread() {
        lastUiUpdateOnEdt.set(SwingUtilities.isEventDispatchThread());
    }

    private static void runOnEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }
}
