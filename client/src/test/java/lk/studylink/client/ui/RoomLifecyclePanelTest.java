package lk.studylink.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import lk.studylink.server.rooms.RoomControlServer;
import lk.studylink.server.rooms.RoomSessionRegistry;
import lk.studylink.shared.config.StudyLinkConfig;
import lk.studylink.shared.model.RoomBinding;
import lk.studylink.shared.model.RoomDefinition;
import lk.studylink.shared.model.SessionInfo;
import lk.studylink.shared.protocol.FrameCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Headless and real-socket loopback tests for {@link RoomLifecyclePanel} and {@link StarterFrame}
 * (Issue M1-06: Swing room lifecycle, EDT thread discipline, duplicate display name handling,
 * disconnect/reconnect, and window close cleanup).
 */
class RoomLifecyclePanelTest {

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void connectListRoomsJoinSwitchRejectedSwitchLeaveDisconnectAndReconnectLifecycle() throws Exception {
        StudyLinkConfig config = StudyLinkConfig.srsDefaults();
        RoomSessionRegistry registry = new RoomSessionRegistry(config);

        try (RoomControlServer server = new RoomControlServer("127.0.0.1", 0, config, registry)) {
            server.start();
            RoomLifecyclePanel panel = createPanelOnEdt(config);

            try {
                // 1. Configure endpoint & display name on EDT, then connect
                SwingUtilities.invokeAndWait(() -> {
                    assertFalse(panel.isConnectedInUi());
                    assertEquals("Disconnected", panel.connectionStateText());
                    assertEquals("None", panel.sessionSummaryText());
                    assertEquals("None (outside all rooms)", panel.activeRoomText());
                    panel.hostField().setText("127.0.0.1");
                    panel.portField().setText(Integer.toString(server.boundPort()));
                    panel.displayNameField().setText("  Amal  ");
                });

                SessionInfo session1 = panel.connectAsync().get(5, TimeUnit.SECONDS).orElseThrow();
                assertEquals("Amal", session1.displayName());
                assertTrue(session1.sessionId().startsWith("s-"));
                assertTrue(panel.lastNetworkIoRanOffEdt(), "Socket I/O must run off the Swing EDT");
                assertTrue(panel.lastUiUpdateRanOnEdt(), "Swing UI state updates must run on the Swing EDT");

                SwingUtilities.invokeAndWait(() -> {
                    assertTrue(panel.isConnectedInUi());
                    assertEquals(Optional.of(session1), panel.currentSession());
                    assertEquals(Optional.empty(), panel.currentBinding());
                    assertEquals(config.rooms(), panel.listedRooms());
                    assertEquals("Connected (127.0.0.1:" + server.boundPort() + ")", panel.connectionStateText());
                    assertEquals("Amal [" + session1.sessionId() + "]", panel.sessionSummaryText());
                    assertEquals("None (outside all rooms)", panel.activeRoomText());
                    assertFalse(panel.connectButton().isEnabled());
                    assertTrue(panel.disconnectButton().isEnabled());
                    assertTrue(panel.joinSwitchButton().isEnabled());
                    assertFalse(panel.leaveButton().isEnabled());
                });

                // 2. Select "netprog" in JList and join
                SwingUtilities.invokeAndWait(() -> panel.roomList().setSelectedIndex(0));
                RoomBinding binding1 = panel.joinSelectedRoomAsync().get(5, TimeUnit.SECONDS).orElseThrow();
                assertEquals(new RoomBinding(session1.sessionId(), "netprog", 1L), binding1);
                assertTrue(registry.isCurrent(binding1));
                assertTrue(panel.lastNetworkIoRanOffEdt());
                assertTrue(panel.lastUiUpdateRanOnEdt());

                SwingUtilities.invokeAndWait(() -> {
                    assertEquals(Optional.of(binding1), panel.currentBinding());
                    assertEquals("Network Programming (netprog) - Revision 1", panel.activeRoomText());
                    assertTrue(panel.leaveButton().isEnabled());
                });

                // 3. Switch to "revision" -> revision 2
                RoomBinding binding2 = panel.joinRoomAsync("revision").get(5, TimeUnit.SECONDS).orElseThrow();
                assertEquals(new RoomBinding(session1.sessionId(), "revision", 2L), binding2);
                assertFalse(registry.isCurrent(binding1));
                assertTrue(registry.isCurrent(binding2));

                SwingUtilities.invokeAndWait(() -> {
                    assertEquals(Optional.of(binding2), panel.currentBinding());
                    assertEquals("Exam Revision (revision) - Revision 2", panel.activeRoomText());
                    assertTrue(panel.leaveButton().isEnabled());
                });

                // 4. Rejected switch to unknown room preserves previous RoomBinding in UI
                Optional<RoomBinding> afterRejected =
                        panel.joinRoomAsync("unknown-room").get(5, TimeUnit.SECONDS);
                assertEquals(Optional.of(binding2), afterRejected);
                assertTrue(registry.isCurrent(binding2));

                SwingUtilities.invokeAndWait(() -> {
                    assertTrue(panel.isConnectedInUi());
                    assertEquals(Optional.of(binding2), panel.currentBinding());
                    assertEquals("Exam Revision (revision) - Revision 2", panel.activeRoomText());
                    assertTrue(panel.statusBannerText().contains("UNKNOWN_ROOM"));
                });

                // 5. Leave room -> session remains active, room binding cleared
                Optional<String> leftRoomId = panel.leaveRoomAsync().get(5, TimeUnit.SECONDS);
                assertEquals(Optional.of("revision"), leftRoomId);
                assertFalse(registry.isCurrent(binding2));

                SwingUtilities.invokeAndWait(() -> {
                    assertTrue(panel.isConnectedInUi());
                    assertEquals(Optional.of(session1), panel.currentSession());
                    assertEquals(Optional.empty(), panel.currentBinding());
                    assertEquals("None (outside all rooms)", panel.activeRoomText());
                    assertFalse(panel.leaveButton().isEnabled());
                });

                // 6. Disconnect -> releases session and display name, resets UI for reconnect
                panel.disconnectAsync().get(5, TimeUnit.SECONDS);
                assertTrue(server.awaitSessionClosed(session1.sessionId(), 2, TimeUnit.SECONDS));

                SwingUtilities.invokeAndWait(() -> {
                    assertFalse(panel.isConnectedInUi());
                    assertEquals(Optional.empty(), panel.currentSession());
                    assertEquals(Optional.empty(), panel.currentBinding());
                    assertEquals(List.of(), panel.listedRooms());
                    assertEquals("Disconnected", panel.connectionStateText());
                    assertEquals("None", panel.sessionSummaryText());
                    assertEquals("None (outside all rooms)", panel.activeRoomText());
                    assertTrue(panel.connectButton().isEnabled());
                    assertFalse(panel.disconnectButton().isEnabled());
                });

                // 7. Reconnect on the same panel with the same display name -> fresh sessionId
                SessionInfo session2 = panel.connectAsync().get(5, TimeUnit.SECONDS).orElseThrow();
                assertNotEquals(session1.sessionId(), session2.sessionId());
                assertEquals("Amal", session2.displayName());
                assertTrue(panel.isConnectedInUi());
            } finally {
                panel.dispose();
            }
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void duplicateCaseEquivalentNameRejectedAndRetrySucceedsAfterFirstClientDisconnects() throws Exception {
        StudyLinkConfig config = StudyLinkConfig.srsDefaults();
        RoomSessionRegistry registry = new RoomSessionRegistry(config);

        try (RoomControlServer server = new RoomControlServer("127.0.0.1", 0, config, registry)) {
            server.start();
            RoomLifecyclePanel client1Panel = createPanelOnEdt(config);
            RoomLifecyclePanel client2Panel = createPanelOnEdt(config);

            try {
                SwingUtilities.invokeAndWait(() -> {
                    client1Panel.hostField().setText("127.0.0.1");
                    client1Panel.portField().setText(Integer.toString(server.boundPort()));
                    client1Panel.displayNameField().setText("Amal");

                    client2Panel.hostField().setText("127.0.0.1");
                    client2Panel.portField().setText(Integer.toString(server.boundPort()));
                    client2Panel.displayNameField().setText("amal");
                });

                // Client 1 connects as "Amal" and joins "netprog"
                SessionInfo session1 = client1Panel.connectAsync().get(5, TimeUnit.SECONDS).orElseThrow();
                RoomBinding binding1 = client1Panel.joinRoomAsync("netprog").get(5, TimeUnit.SECONDS).orElseThrow();
                assertEquals("netprog", binding1.roomId());

                // Client 2 attempts case-equivalent duplicate name "amal" -> rejected with NAME_IN_USE
                Optional<SessionInfo> duplicateResult = client2Panel.connectAsync().get(5, TimeUnit.SECONDS);
                assertEquals(Optional.empty(), duplicateResult);

                SwingUtilities.invokeAndWait(() -> {
                    assertFalse(client2Panel.isConnectedInUi());
                    assertEquals(Optional.empty(), client2Panel.currentSession());
                    assertEquals("Disconnected", client2Panel.connectionStateText());
                    assertTrue(
                            client2Panel.statusBannerText().contains("NAME_IN_USE"),
                            "Expected NAME_IN_USE in status banner, got: " + client2Panel.statusBannerText()
                    );
                    assertTrue(client2Panel.connectButton().isEnabled(), "Connect button must stay enabled for retry");
                    assertTrue(client2Panel.displayNameField().isEnabled());
                });

                // Client 1 disconnects, releasing "Amal"
                client1Panel.disconnectAsync().get(5, TimeUnit.SECONDS);
                assertTrue(server.awaitSessionClosed(session1.sessionId(), 2, TimeUnit.SECONDS));

                // Client 2 retries connecting as "amal" without changing fields -> succeeds!
                SessionInfo session2 = client2Panel.connectAsync().get(5, TimeUnit.SECONDS).orElseThrow();
                assertNotEquals(session1.sessionId(), session2.sessionId());
                assertEquals("amal", session2.displayName());

                RoomBinding binding2 = client2Panel.joinRoomAsync("workshop").get(5, TimeUnit.SECONDS).orElseThrow();
                assertEquals(new RoomBinding(session2.sessionId(), "workshop", 1L), binding2);

                SwingUtilities.invokeAndWait(() -> {
                    assertTrue(client2Panel.isConnectedInUi());
                    assertEquals("Project Workshop (workshop) - Revision 1", client2Panel.activeRoomText());
                });
            } finally {
                client1Panel.dispose();
                client2Panel.dispose();
            }
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void invalidInputsUnreachableServerAndUnexpectedDisconnectAreHandledCleanlyOnEdt() throws Exception {
        StudyLinkConfig config = StudyLinkConfig.srsDefaults();
        RoomLifecyclePanel panel = createPanelOnEdt(config);

        try {
            // 1. Invalid port text
            SwingUtilities.invokeAndWait(() -> {
                panel.hostField().setText("127.0.0.1");
                panel.portField().setText("99999");
                panel.displayNameField().setText("Amal");
            });
            assertEquals(Optional.empty(), panel.connectAsync().get(3, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(panel.isConnectedInUi());
                assertTrue(panel.statusBannerText().contains("between 1 and 65535"));
                assertTrue(panel.connectButton().isEnabled());
            });

            // 2. Unreachable server port
            int unusedPort;
            try (ServerSocket probe = new ServerSocket(0)) {
                unusedPort = probe.getLocalPort();
            }
            int targetUnusedPort = unusedPort;
            SwingUtilities.invokeAndWait(() -> panel.portField().setText(Integer.toString(targetUnusedPort)));
            assertEquals(Optional.empty(), panel.connectAsync().get(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(panel.isConnectedInUi());
                assertTrue(panel.statusBannerText().contains("Connection failed"));
                assertTrue(panel.connectButton().isEnabled());
            });

            // 3. Connect to real server, then close server and verify panel transitions to disconnected state
            RoomSessionRegistry registry = new RoomSessionRegistry(config);
            SessionInfo session;
            try (RoomControlServer server = new RoomControlServer("127.0.0.1", 0, config, registry)) {
                server.start();
                SwingUtilities.invokeAndWait(
                        () -> panel.portField().setText(Integer.toString(server.boundPort()))
                );
                session = panel.connectAsync().get(5, TimeUnit.SECONDS).orElseThrow();
                panel.joinRoomAsync("netprog").get(5, TimeUnit.SECONDS);
                assertTrue(panel.isConnectedInUi());
                assertNotEquals("", session.sessionId());
            }

            // Next room operation against closed server transitions UI cleanly back to disconnected state
            List<RoomDefinition> afterServerClose = panel.refreshRoomsAsync().get(5, TimeUnit.SECONDS);
            assertEquals(List.of(), afterServerClose);
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(panel.isConnectedInUi());
                assertEquals(Optional.empty(), panel.currentSession());
                assertEquals(Optional.empty(), panel.currentBinding());
                assertEquals("Disconnected", panel.connectionStateText());
                assertTrue(panel.connectButton().isEnabled());
            });
        } finally {
            panel.dispose();
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void windowCloseOrPanelDisposeReleasesActiveServerSession() throws Exception {
        StudyLinkConfig config = StudyLinkConfig.srsDefaults();
        RoomSessionRegistry registry = new RoomSessionRegistry(config);

        try (RoomControlServer server = new RoomControlServer("127.0.0.1", 0, config, registry)) {
            server.start();

            if (!GraphicsEnvironment.isHeadless()) {
                AtomicReference<StarterFrame> frameRef = new AtomicReference<>();
                SwingUtilities.invokeAndWait(
                        () -> frameRef.set(new StarterFrame(config, "unit-test"))
                );
                StarterFrame frame = frameRef.get();
                try {
                    RoomLifecyclePanel panel = frame.roomLifecyclePanel();
                    SwingUtilities.invokeAndWait(() -> {
                        panel.hostField().setText("127.0.0.1");
                        panel.portField().setText(Integer.toString(server.boundPort()));
                        panel.displayNameField().setText("WindowStudent");
                    });
                    SessionInfo session = panel.connectAsync().get(5, TimeUnit.SECONDS).orElseThrow();
                    panel.joinRoomAsync("netprog").get(5, TimeUnit.SECONDS);
                    assertTrue(registry.findSession(session.sessionId()).isPresent());

                    // Disposing the window must close RoomClient and release the session on the server
                    SwingUtilities.invokeAndWait(frame::dispose);
                    assertTrue(
                            server.awaitSessionClosed(session.sessionId(), 2, TimeUnit.SECONDS),
                            "Closing StarterFrame must release the active session on the server"
                    );
                } finally {
                    SwingUtilities.invokeAndWait(frame::dispose);
                }
            } else {
                RoomLifecyclePanel panel = createPanelOnEdt(config);
                SwingUtilities.invokeAndWait(() -> {
                    panel.hostField().setText("127.0.0.1");
                    panel.portField().setText(Integer.toString(server.boundPort()));
                    panel.displayNameField().setText("HeadlessStudent");
                });
                SessionInfo session = panel.connectAsync().get(5, TimeUnit.SECONDS).orElseThrow();
                panel.dispose();
                assertTrue(server.awaitSessionClosed(session.sessionId(), 2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void disposingWindowDuringPendingHelloReturnsPromptlyOnEdtClosesPeerSocketAndCompletesPendingConnect()
            throws Exception {
        StudyLinkConfig config = StudyLinkConfig.srsDefaults();
        CountDownLatch helloReceivedByPeer = new CountDownLatch(1);
        CountDownLatch peerObservedSocketClosure = new CountDownLatch(1);
        CountDownLatch releasePeerHold = new CountDownLatch(1);

        try (ServerSocket delayedPeer = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            Thread peerThread = Thread.ofVirtual().start(() -> {
                try (Socket accepted = delayedPeer.accept();
                        InputStream in = accepted.getInputStream()) {
                    JsonObject helloReq = FrameCodec.readFrame(in);
                    assertNotNull(helloReq);
                    helloReceivedByPeer.countDown();

                    // Hold the HELLO reply and wait until the client closes the socket during disposal
                    try {
                        JsonObject next = FrameCodec.readFrame(in);
                        if (next == null) {
                            peerObservedSocketClosure.countDown();
                        }
                    } catch (IOException expectedOnSocketClose) {
                        peerObservedSocketClosure.countDown();
                    }
                    releasePeerHold.await(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
            });

            AtomicReference<StarterFrame> frameRef = new AtomicReference<>();
            AtomicReference<RoomLifecyclePanel> panelRef = new AtomicReference<>();
            boolean hasDisplay = !GraphicsEnvironment.isHeadless();

            SwingUtilities.invokeAndWait(() -> {
                if (hasDisplay) {
                    StarterFrame frame = new StarterFrame(config, "pending-hello-dispose-test");
                    frameRef.set(frame);
                    panelRef.set(frame.roomLifecyclePanel());
                } else {
                    panelRef.set(new RoomLifecyclePanel(config));
                }
                RoomLifecyclePanel panel = panelRef.get();
                panel.hostField().setText("127.0.0.1");
                panel.portField().setText(Integer.toString(delayedPeer.getLocalPort()));
                panel.displayNameField().setText("Amal");
            });

            RoomLifecyclePanel panel = panelRef.get();
            try {
                CompletableFuture<Optional<SessionInfo>> pendingConnect = panel.connectAsync();
                assertTrue(helloReceivedByPeer.await(3, TimeUnit.SECONDS), "Peer must receive HELLO frame");

                CountDownLatch followUpEdtRan = new CountDownLatch(1);
                AtomicLong edtDisposeElapsedMs = new AtomicLong(-1L);
                long startNanos = System.nanoTime();

                // Queue disposal on the EDT followed immediately by a second EDT event while HELLO reply is still held
                SwingUtilities.invokeLater(() -> {
                    long t0 = System.nanoTime();
                    if (hasDisplay) {
                        frameRef.get().dispose();
                        frameRef.get().dispose(); // verify idempotence
                    } else {
                        panel.dispose();
                        panel.dispose(); // verify idempotence
                    }
                    edtDisposeElapsedMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
                });
                SwingUtilities.invokeLater(followUpEdtRan::countDown);

                boolean edtUnblocked = followUpEdtRan.await(2_500, TimeUnit.MILLISECONDS);
                long totalEdtTurnaroundMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                System.out.printf(
                        "[M1-06 Pending-HELLO Dispose Observation] edtDisposeElapsedMs=%d, totalEdtTurnaroundMs=%d%n",
                        edtDisposeElapsedMs.get(),
                        totalEdtTurnaroundMs
                );

                assertTrue(
                        edtUnblocked && edtDisposeElapsedMs.get() >= 0L && totalEdtTurnaroundMs < 300L,
                        "Disposing window/panel on EDT during pending HELLO blocked EDT for "
                                + edtDisposeElapsedMs.get() + " ms (turnaround " + totalEdtTurnaroundMs + " ms)"
                );

                // Peer must observe socket closure while HELLO reply is still held
                assertTrue(
                        peerObservedSocketClosure.await(2, TimeUnit.SECONDS),
                        "Peer must observe socket closure triggered off the EDT"
                );

                // Pending connect operation must finish cleanly with Optional.empty()
                assertEquals(Optional.empty(), pendingConnect.get(2, TimeUnit.SECONDS));
                assertTrue(panel.lastNetworkIoRanOffEdt(), "RoomClient.close() must not run on the Swing EDT");

                SwingUtilities.invokeAndWait(() -> {
                    assertFalse(panel.isConnectedInUi(), "Late callbacks must not restore connected UI state");
                    assertEquals(Optional.empty(), panel.currentSession());
                    assertEquals(Optional.empty(), panel.currentBinding());
                    assertFalse(panel.connectButton().isEnabled(), "Disposed panel controls must remain disabled");
                });
            } finally {
                releasePeerHold.countDown();
                if (hasDisplay && frameRef.get() != null) {
                    SwingUtilities.invokeAndWait(frameRef.get()::dispose);
                } else {
                    panel.dispose();
                }
                peerThread.join(2_000);
            }
        }
    }

    private static RoomLifecyclePanel createPanelOnEdt(StudyLinkConfig config) throws Exception {
        AtomicReference<RoomLifecyclePanel> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> ref.set(new RoomLifecyclePanel(config)));
        return ref.get();
    }
}
