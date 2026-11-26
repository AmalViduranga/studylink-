package lk.studylink.client.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import lk.studylink.client.ui.ChatPanel;
import lk.studylink.shared.model.ChatEvent;
import lk.studylink.shared.model.RoomBinding;
import lk.studylink.shared.model.RoomDefinition;
import lk.studylink.shared.protocol.ChatProtocol;
import lk.studylink.shared.protocol.FrameCodec;
import lk.studylink.shared.protocol.ProtocolConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Headless tests for {@link ChatClient} and {@link ChatPanel} against a lightweight mock NIO/TCP
 * peer server (keeping client module decoupled from server module).
 */
class ChatClientAndPanelTest {

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void chatClientSubscribesSendsReceivesAndSwitchesRoomsDiscardingOldEvents() throws Exception {
        try (MockChatServer mockServer = MockChatServer.start()) {
            try (ChatClient client = new ChatClient("127.0.0.1", mockServer.port())) {
                BlockingQueue<ChatEvent> room1Events = new LinkedBlockingQueue<>();
                RoomBinding binding1 = new RoomBinding("s-1", "netprog", 1L);

                client.subscribe(binding1, room1Events::add);
                assertTrue(client.isConnected());
                assertEquals(binding1, client.currentBinding());

                String msgId = client.send("Hello from ChatClient");
                assertEquals("m-mock-1", msgId);

                ChatEvent received = room1Events.poll(2, TimeUnit.SECONDS);
                assertNotNull(received);
                assertEquals("netprog", received.roomId());
                assertEquals("Hello from ChatClient", received.text());

                // Client-side validation rejects empty and > 2,048 UTF-8 byte messages before network send
                ChatException emptyEx = assertThrows(ChatException.class, () -> client.send("   "));
                assertEquals(ChatProtocol.ERR_BAD_REQUEST, emptyEx.errorCode());

                String over2048 = "z".repeat(ProtocolConstants.MAX_TEXT_MESSAGE_BYTES + 1);
                ChatException tooBigEx = assertThrows(ChatException.class, () -> client.send(over2048));
                assertEquals(ChatProtocol.ERR_TOO_LARGE, tooBigEx.errorCode());

                // Switch room -> old subscription is closed and any event from old room is discarded
                BlockingQueue<ChatEvent> room2Events = new LinkedBlockingQueue<>();
                RoomBinding binding2 = new RoomBinding("s-1", "revision", 2L);
                client.subscribe(binding2, room2Events::add);
                assertEquals(binding2, client.currentBinding());

                // Server pushes a stale 'netprog' event on the new socket -> ChatClient must discard it
                mockServer.pushEventToLatestClient(new ChatEvent(
                        "m-stale",
                        "netprog",
                        "s-other",
                        "Other",
                        "Leaked from old room",
                        System.currentTimeMillis(),
                        "12:00:00"
                ));
                mockServer.pushEventToLatestClient(new ChatEvent(
                        "m-valid",
                        "revision",
                        "s-other",
                        "Other",
                        "Valid in revision",
                        System.currentTimeMillis(),
                        "12:00:01"
                ));

                ChatEvent onlyValid = room2Events.poll(2, TimeUnit.SECONDS);
                assertNotNull(onlyValid);
                assertEquals("revision", onlyValid.roomId());
                assertEquals("Valid in revision", onlyValid.text());
                assertTrue(room1Events.isEmpty(), "Old room listener must not receive events after switch");
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void chatClientReportsConnectionFailureAndDisconnectCleanly() throws Exception {
        int unusedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            unusedPort = probe.getLocalPort();
        }

        try (ChatClient failingClient = new ChatClient("127.0.0.1", unusedPort)) {
            ChatException ex = assertThrows(ChatException.class,
                    () -> failingClient.subscribe(new RoomBinding("s-1", "netprog", 1L), ev -> {}));
            assertEquals("CONNECTION_FAILED", ex.errorCode());
            assertFalse(failingClient.isConnected());
        }

        try (MockChatServer mockServer = MockChatServer.start();
             ChatClient client = new ChatClient("127.0.0.1", mockServer.port())) {
            CountDownLatch disconnectedLatch = new CountDownLatch(1);
            client.subscribe(new RoomBinding("s-2", "workshop", 1L), new ChatListener() {
                @Override
                public void onChatEvent(ChatEvent event) {
                }

                @Override
                public void onDisconnected(String reason) {
                    disconnectedLatch.countDown();
                }
            });

            mockServer.dropLatestClient();
            assertTrue(disconnectedLatch.await(3, TimeUnit.SECONDS));
            assertFalse(client.isConnected());
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void chatPanelUpdatesTranscriptAndStatusHeadlesslyOnSwingEdt() throws Exception {
        try (MockChatServer mockServer = MockChatServer.start()) {
            ChatClient client = new ChatClient("127.0.0.1", mockServer.port());
            ChatPanel[] holder = new ChatPanel[1];
            SwingUtilities.invokeAndWait(() -> holder[0] = new ChatPanel(client, 2048));
            ChatPanel panel = holder[0];

            try {
                RoomDefinition netprog = new RoomDefinition("netprog", "Network Programming");
                RoomBinding binding = new RoomBinding("s-ui", "netprog", 1L);
                panel.joinRoomChat(netprog, binding);

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!client.isConnected() && System.nanoTime() < deadline) {
                    Thread.sleep(15);
                }
                assertTrue(client.isConnected());

                // Simulate receiving a server chat event and verify transcript renders on EDT
                mockServer.pushEventToLatestClient(new ChatEvent(
                        "m-ui-1",
                        "netprog",
                        "s-peer",
                        "Nimali",
                        "See lecture 07 selector notes",
                        System.currentTimeMillis(),
                        "10:15:00"
                ));

                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                String[] transcript = new String[1];
                while (System.nanoTime() < deadline) {
                    SwingUtilities.invokeAndWait(() -> transcript[0] = panel.transcriptText());
                    if (transcript[0].contains("Nimali: See lecture 07 selector notes")) {
                        break;
                    }
                    Thread.sleep(15);
                }
                assertTrue(transcript[0].contains("[10:15:00] Nimali: See lecture 07 selector notes"));

                // Leaving room clears transcript and disconnects cleanly
                panel.leaveRoomChat();
                SwingUtilities.invokeAndWait(() -> transcript[0] = panel.transcriptText());
                assertEquals("", transcript[0]);
            } finally {
                SwingUtilities.invokeAndWait(panel::dispose);
            }
        }
    }

    /**
     * Simple loopback TCP mock server speaking StudyLink's 4-byte framed chat protocol.
     */
    private static final class MockChatServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final List<java.net.Socket> acceptedSockets = new CopyOnWriteArrayList<>();
        private final Thread acceptThread;
        private volatile java.net.Socket latestSocket;
        private volatile RoomBinding latestBinding;
        private int msgSeq = 0;

        private MockChatServer(ServerSocket serverSocket) {
            this.serverSocket = serverSocket;
            this.acceptThread = new Thread(this::acceptLoop, "mock-chat-accept");
            this.acceptThread.setDaemon(true);
            this.acceptThread.start();
        }

        static MockChatServer start() throws Exception {
            ServerSocket ss = new ServerSocket();
            ss.bind(new InetSocketAddress("127.0.0.1", 0));
            return new MockChatServer(ss);
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        void pushEventToLatestClient(ChatEvent event) throws Exception {
            java.net.Socket s = latestSocket;
            assertNotNull(s);
            synchronized (s) {
                FrameCodec.writeFrame(s.getOutputStream(), ChatProtocol.buildChatEventFrame(event));
            }
        }

        void dropLatestClient() throws Exception {
            java.net.Socket s = latestSocket;
            if (s != null) {
                s.close();
            }
        }

        private void acceptLoop() {
            while (!closed.get() && !serverSocket.isClosed()) {
                try {
                    java.net.Socket client = serverSocket.accept();
                    acceptedSockets.add(client);
                    latestSocket = client;
                    Thread handler = new Thread(() -> handleClient(client), "mock-chat-handler");
                    handler.setDaemon(true);
                    handler.start();
                } catch (Exception e) {
                    if (closed.get()) {
                        break;
                    }
                }
            }
        }

        private void handleClient(java.net.Socket client) {
            try (client) {
                var in = client.getInputStream();
                while (!closed.get() && !client.isClosed()) {
                    var req = FrameCodec.readFrame(in);
                    if (req == null) {
                        break;
                    }
                    String reqId = req.get("id").getAsString();
                    String op = req.get("op").getAsString();
                    var data = req.getAsJsonObject("data");
                    if (ChatProtocol.OP_SUBSCRIBE.equals(op)) {
                        latestBinding = new RoomBinding(
                                data.get("sessionId").getAsString(),
                                data.get("roomId").getAsString(),
                                data.get("revision").getAsLong()
                        );
                        var okData = new com.google.gson.JsonObject();
                        okData.addProperty("subscribed", true);
                        synchronized (client) {
                            FrameCodec.writeFrame(client.getOutputStream(),
                                    ChatProtocol.buildSuccessResponse(reqId, okData));
                        }
                    } else if (ChatProtocol.OP_CHAT_SEND.equals(op)) {
                        String id = "m-mock-" + (++msgSeq);
                        long now = System.currentTimeMillis();
                        var okData = new com.google.gson.JsonObject();
                        okData.addProperty("messageId", id);
                        synchronized (client) {
                            FrameCodec.writeFrame(client.getOutputStream(),
                                    ChatProtocol.buildSuccessResponse(reqId, okData));
                            RoomBinding b = Optional.ofNullable(latestBinding)
                                    .orElse(new RoomBinding("s-1", "netprog", 1L));
                            ChatEvent ev = new ChatEvent(
                                    id,
                                    b.roomId(),
                                    b.sessionId(),
                                    "Student",
                                    data.get("text").getAsString(),
                                    now,
                                    ChatEvent.formatDisplayTime(now)
                            );
                            FrameCodec.writeFrame(client.getOutputStream(),
                                    ChatProtocol.buildChatEventFrame(ev));
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        @Override
        public void close() {
            closed.set(true);
            try {
                serverSocket.close();
            } catch (Exception ignored) {
            }
            for (var s : acceptedSockets) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
