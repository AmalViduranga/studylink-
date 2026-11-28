package lk.studylink.client.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import lk.studylink.client.chat.ChatClient;
import lk.studylink.client.chat.ChatException;
import lk.studylink.client.chat.ChatListener;
import lk.studylink.shared.config.StudyLinkConfig;
import lk.studylink.shared.model.ChatEvent;
import lk.studylink.shared.model.RoomBinding;
import lk.studylink.shared.model.RoomDefinition;

/**
 * Swing Chat tab panel for Member 5 integrated with Member 1's room lifecycle
 * (SRS Section 2 Figure 1, Section 5.5, F06, F07, Q02, Q03, Q09, T04).
 *
 * <p>All network operations run off the Swing Event Dispatch Thread via {@link ChatClient} on a
 * background executor, while every Swing model and widget update is marshaled onto the EDT with
 * {@link SwingUtilities#invokeLater(Runnable)}.
 */
public final class ChatPanel extends JPanel {

    private static final Color SUBTLE_TEXT_COLOR = new Color(90, 100, 115);
    private static final Color OK_STATUS_COLOR = new Color(25, 118, 60);
    private static final Color ERROR_STATUS_COLOR = new Color(180, 40, 40);

    private final ChatClient chatClient;
    private final int maxChatTextBytes;
    private final ExecutorService workerExecutor;
    private final AtomicLong activeUiGeneration = new AtomicLong(0L);
    private final AtomicBoolean disposed = new AtomicBoolean(false);

    private final JLabel roomTitleLabel = new JLabel("No room joined");
    private final JLabel statusLabel = new JLabel("Disconnected");
    private final JTextArea transcriptArea = new JTextArea();
    private final JTextField messageField = new JTextField();
    private final JLabel byteCounterLabel = new JLabel("0 / 2048 B");
    private final JButton sendButton = new JButton("Send");

    private volatile RoomBinding activeBinding;
    private volatile boolean subscribed = false;

    /**
     * Creates a {@link ChatPanel} backed by a new {@link ChatClient} configured from {@code config}.
     */
    public ChatPanel(StudyLinkConfig config) {
        this(
                new ChatClient(Objects.requireNonNull(config, "config must not be null")),
                config.maxChatTextBytes()
        );
    }

    /**
     * Creates a {@link ChatPanel} with an injected {@link ChatClient} and UTF-8 message byte limit.
     */
    public ChatPanel(ChatClient chatClient, int maxChatTextBytes) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
        if (maxChatTextBytes < 1) {
            throw new IllegalArgumentException("maxChatTextBytes must be >= 1");
        }
        this.maxChatTextBytes = maxChatTextBytes;
        this.workerExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread t = new Thread(runnable, "studylink-chat-ui-worker");
            t.setDaemon(true);
            return t;
        });

        initializeLayout();
        bindInteractions();
        updateControlsEnabled(false);
    }

    /**
     * Updates the target chat server host and NIO port (from the active room-control connection
     * host and the {@code chatNioTcp} port advertised by {@code HELLO}).
     */
    public void setChatEndpoint(String host, int port) {
        chatClient.setEndpoint(host, port);
    }

    /**
     * Switches the Chat tab to {@code room} using {@code binding}, clearing any prior room
     * transcript and subscribing asynchronously off the EDT.
     */
    public void joinRoomChat(RoomDefinition room, RoomBinding binding) {
        Objects.requireNonNull(room, "room must not be null");
        Objects.requireNonNull(binding, "binding must not be null");
        if (disposed.get()) {
            return;
        }

        long generation = activeUiGeneration.incrementAndGet();
        this.activeBinding = binding;
        this.subscribed = false;

        runOnEdt(() -> {
            if (disposed.get() || activeUiGeneration.get() != generation) {
                return;
            }
            roomTitleLabel.setText(room.displayName() + " (" + room.id() + ")");
            transcriptArea.setText("");
            setStatus("Connecting to " + room.displayName() + "...", SUBTLE_TEXT_COLOR);
            updateControlsEnabled(false);
        });

        try {
            workerExecutor.execute(() -> {
                if (disposed.get() || activeUiGeneration.get() != generation) {
                    return;
                }
                try {
                    chatClient.subscribe(binding, new ChatListener() {
                        @Override
                        public void onChatEvent(ChatEvent event) {
                            runOnEdt(() -> {
                                if (!disposed.get()
                                        && activeUiGeneration.get() == generation
                                        && binding.equals(activeBinding)
                                        && binding.roomId().equals(event.roomId())) {
                                    appendChatEventOnEdt(event);
                                }
                            });
                        }

                        @Override
                        public void onDisconnected(String reason) {
                            runOnEdt(() -> {
                                if (!disposed.get()
                                        && activeUiGeneration.get() == generation
                                        && binding.equals(activeBinding)) {
                                    subscribed = false;
                                    updateControlsEnabled(false);
                                    setStatus("Disconnected: " + reason, ERROR_STATUS_COLOR);
                                }
                            });
                        }

                        @Override
                        public void onError(String errorCode, String message) {
                            runOnEdt(() -> {
                                if (!disposed.get()
                                        && activeUiGeneration.get() == generation
                                        && binding.equals(activeBinding)) {
                                    setStatus("Chat error (" + errorCode + "): " + message, ERROR_STATUS_COLOR);
                                }
                            });
                        }
                    });

                    if (disposed.get() || activeUiGeneration.get() != generation) {
                        chatClient.unsubscribe();
                        return;
                    }

                    runOnEdt(() -> {
                        if (!disposed.get()
                                && activeUiGeneration.get() == generation
                                && binding.equals(activeBinding)) {
                            subscribed = true;
                            setStatus("Connected to " + room.displayName(), OK_STATUS_COLOR);
                            updateControlsEnabled(true);
                            messageField.requestFocusInWindow();
                        }
                    });
                } catch (ChatException e) {
                    runOnEdt(() -> {
                        if (!disposed.get()
                                && activeUiGeneration.get() == generation
                                && binding.equals(activeBinding)) {
                            subscribed = false;
                            updateControlsEnabled(false);
                            setStatus("Failed to subscribe (" + e.errorCode() + "): " + e.getMessage(),
                                    ERROR_STATUS_COLOR);
                        }
                    });
                }
            });
        } catch (RuntimeException ignoredAfterShutdown) {
            // Worker executor already shut down during disposal
        }
    }

    /**
     * Leaves the current chat room, clears the transcript, and closes the active subscription.
     */
    public void leaveRoomChat() {
        long generation = activeUiGeneration.incrementAndGet();
        this.activeBinding = null;
        this.subscribed = false;

        runOnEdt(() -> {
            if (activeUiGeneration.get() != generation) {
                return;
            }
            roomTitleLabel.setText("No room joined");
            transcriptArea.setText("");
            messageField.setText("");
            setStatus("Disconnected (outside room)", SUBTLE_TEXT_COLOR);
            updateControlsEnabled(false);
        });

        if (disposed.get()) {
            return;
        }
        try {
            workerExecutor.execute(() -> {
                if (!disposed.get() && activeUiGeneration.get() == generation) {
                    chatClient.unsubscribe();
                }
            });
        } catch (RuntimeException ignoredAfterShutdown) {
            // Worker executor already shut down during disposal
        }
    }

    /**
     * Sends the text currently entered in the compose field on a background worker thread.
     */
    public void sendCurrentInput() {
        if (disposed.get()) {
            return;
        }
        String text = messageField.getText();
        if (text == null || text.isBlank()) {
            setStatus("Enter a non-empty message before sending.", ERROR_STATUS_COLOR);
            return;
        }
        int utf8Bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (utf8Bytes > maxChatTextBytes) {
            setStatus("Message is " + utf8Bytes + " UTF-8 bytes (limit is " + maxChatTextBytes + " bytes).",
                    ERROR_STATUS_COLOR);
            return;
        }

        RoomBinding current = this.activeBinding;
        long generation = activeUiGeneration.get();
        if (!subscribed || current == null) {
            setStatus("Join a room before sending chat messages.", ERROR_STATUS_COLOR);
            return;
        }

        sendButton.setEnabled(false);
        try {
            workerExecutor.execute(() -> {
                if (disposed.get() || activeUiGeneration.get() != generation) {
                    return;
                }
                try {
                    chatClient.send(text);
                    runOnEdt(() -> {
                        if (!disposed.get()
                                && activeUiGeneration.get() == generation
                                && current.equals(activeBinding)) {
                            messageField.setText("");
                            setStatus("Connected", OK_STATUS_COLOR);
                            updateControlsEnabled(subscribed);
                            messageField.requestFocusInWindow();
                        }
                    });
                } catch (ChatException e) {
                    runOnEdt(() -> {
                        if (!disposed.get()
                                && activeUiGeneration.get() == generation
                                && current.equals(activeBinding)) {
                            setStatus("Send failed (" + e.errorCode() + "): " + e.getMessage(),
                                    ERROR_STATUS_COLOR);
                            updateControlsEnabled(subscribed);
                        }
                    });
                }
            });
        } catch (RuntimeException ignoredAfterShutdown) {
            // Worker executor already shut down during disposal
        }
    }

    /**
     * Returns the current transcript text (for UI inspection and testing).
     */
    public String transcriptText() {
        return transcriptArea.getText();
    }

    /**
     * Returns the current status label text.
     */
    public String statusText() {
        return statusLabel.getText();
    }

    /**
     * Returns the current room title label text.
     */
    public String roomTitleText() {
        return roomTitleLabel.getText();
    }

    /**
     * Returns the current UTF-8 byte counter label text.
     */
    public String byteCounterText() {
        return byteCounterLabel.getText();
    }

    /**
     * Returns {@code true} if the panel currently holds an active room chat subscription.
     */
    public boolean isSubscribed() {
        return subscribed && !disposed.get() && chatClient.isConnected();
    }

    /**
     * Returns the active {@link RoomBinding} currently associated with this chat panel, or {@code null}.
     */
    public RoomBinding activeBinding() {
        return activeBinding;
    }

    /**
     * Returns the underlying {@link ChatClient}.
     */
    public ChatClient chatClient() {
        return chatClient;
    }

    /**
     * Returns the chat message compose text field.
     */
    public JTextField messageField() {
        return messageField;
    }

    /**
     * Returns the chat Send button.
     */
    public JButton sendButton() {
        return sendButton;
    }

    /**
     * Closes the underlying {@link ChatClient} and background worker executor off the Swing EDT.
     * Safe and idempotent to call multiple times.
     */
    public void dispose() {
        if (!disposed.compareAndSet(false, true)) {
            return;
        }
        activeUiGeneration.incrementAndGet();
        subscribed = false;
        activeBinding = null;
        runOnEdt(() -> {
            roomTitleLabel.setText("No room joined");
            transcriptArea.setText("");
            messageField.setText("");
            setStatus("Disconnected", SUBTLE_TEXT_COLOR);
            updateControlsEnabled(false);
        });

        Thread cleanupThread = new Thread(() -> {
            try {
                chatClient.close();
            } finally {
                workerExecutor.shutdown();
            }
        }, "studylink-chat-ui-cleanup");
        cleanupThread.setDaemon(false);
        cleanupThread.start();
    }

    private void initializeLayout() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Top header: room title + live status indicator
        JPanel headerPanel = new JPanel(new BorderLayout(8, 4));
        roomTitleLabel.setFont(roomTitleLabel.getFont().deriveFont(Font.BOLD, 15f));
        statusLabel.setForeground(SUBTLE_TEXT_COLOR);
        headerPanel.add(roomTitleLabel, BorderLayout.WEST);
        headerPanel.add(statusLabel, BorderLayout.EAST);
        add(headerPanel, BorderLayout.NORTH);

        // Center: scrollable read-only chat transcript
        transcriptArea.setEditable(false);
        transcriptArea.setLineWrap(true);
        transcriptArea.setWrapStyleWord(true);
        transcriptArea.getAccessibleContext().setAccessibleName("Live room chat transcript");
        JScrollPane scrollPane = new JScrollPane(transcriptArea);
        scrollPane.setPreferredSize(new Dimension(520, 240));
        add(scrollPane, BorderLayout.CENTER);

        // Bottom: message input, UTF-8 byte counter, and Send button
        JPanel composePanel = new JPanel(new BorderLayout(8, 0));
        messageField.getAccessibleContext().setAccessibleName("Chat message input");
        messageField.putClientProperty("JTextField.placeholderText", "Message this room (up to 2,048 UTF-8 bytes)");
        composePanel.add(messageField, BorderLayout.CENTER);

        JPanel rightControls = new JPanel(new BorderLayout(8, 0));
        byteCounterLabel.setText("0 / " + maxChatTextBytes + " B");
        byteCounterLabel.setForeground(SUBTLE_TEXT_COLOR);
        sendButton.getAccessibleContext().setAccessibleName("Send chat message");
        rightControls.add(byteCounterLabel, BorderLayout.WEST);
        rightControls.add(sendButton, BorderLayout.EAST);
        composePanel.add(rightControls, BorderLayout.EAST);

        add(composePanel, BorderLayout.SOUTH);
    }

    private void bindInteractions() {
        sendButton.addActionListener(e -> sendCurrentInput());
        messageField.addActionListener(e -> sendCurrentInput());
        messageField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refreshByteCounter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refreshByteCounter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refreshByteCounter();
            }
        });
    }

    private void refreshByteCounter() {
        String text = messageField.getText();
        int bytes = (text == null) ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
        byteCounterLabel.setText(bytes + " / " + maxChatTextBytes + " B");
        byteCounterLabel.setForeground(bytes > maxChatTextBytes ? ERROR_STATUS_COLOR : SUBTLE_TEXT_COLOR);
        updateControlsEnabled(subscribed && !disposed.get());
    }

    private void updateControlsEnabled(boolean isSubscribed) {
        boolean canUse = isSubscribed && !disposed.get();
        String text = messageField.getText();
        int bytes = (text == null) ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
        boolean hasValidText = text != null && !text.isBlank() && bytes <= maxChatTextBytes;
        messageField.setEnabled(canUse);
        sendButton.setEnabled(canUse && hasValidText);
    }

    private void appendChatEventOnEdt(ChatEvent event) {
        String line = "[" + event.displayTime() + "] " + event.senderName() + ": " + event.text() + "\n";
        transcriptArea.append(line);
        transcriptArea.setCaretPosition(transcriptArea.getDocument().getLength());
    }

    private void setStatus(String text, Color color) {
        statusLabel.setText(text);
        statusLabel.setForeground(color);
    }

    private static void runOnEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }
}
