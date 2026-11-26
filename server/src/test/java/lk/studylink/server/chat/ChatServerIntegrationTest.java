package lk.studylink.server.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import lk.studylink.server.rooms.RoomSessionRegistry;
import lk.studylink.shared.config.StudyLinkConfig;
import lk.studylink.shared.model.ChatEvent;
import lk.studylink.shared.model.RoomBinding;
import lk.studylink.shared.model.SessionInfo;
import lk.studylink.shared.protocol.ChatProtocol;
import lk.studylink.shared.protocol.FrameCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Real-socket Java NIO integration tests for {@link ChatServer} backed by Member 1's
 * {@link RoomSessionRegistry} (covering SRS T04, T05, T06, T12 and all 12 M5 test criteria).
 */
class ChatServerIntegrationTest {

    private static final int SOCKET_TIMEOUT_MS = 3_000;

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bindsToConfiguredDefaultPort45402AndServesClient() throws Exception {
        StudyLinkConfig config = StudyLinkConfig.srsDefaults();
        RoomSessionRegistry registry = new RoomSessionRegistry(config);
        SessionInfo student = registry.registerSession("DefaultPortUser");
        RoomBinding binding = registry.joinRoom(student.sessionId(), "netprog");

        try (ChatServer server = new ChatServer(config, registry)) {
            server.start();
            assertEquals(45402, server.boundPort());

            try (RawChatPeer peer = RawChatPeer.connect(45402)) {
                assertTrue(peer.subscribe("sub-45402", binding).get("ok").getAsBoolean());
                assertTrue(peer.sendChat("msg-45402", "Bound on 45402").get("ok").getAsBoolean());
                ChatEvent ev = ChatProtocol.parseChatEventFrame(peer.nextEvent(2, TimeUnit.SECONDS));
                assertEquals("Bound on 45402", ev.text());
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void sameRoomClientsReceiveEachOthersMessagesAndDifferentRoomsAreIsolated() throws Exception {
        StudyLinkConfig config = StudyLinkConfig.srsDefaults();
        RoomSessionRegistry registry = new RoomSessionRegistry(config);

        SessionInfo alice = registry.registerSession("Alice");
        SessionInfo bob = registry.registerSession("Bob");
        SessionInfo charlie = registry.registerSession("Charlie");

        RoomBinding aliceBinding = registry.joinRoom(alice.sessionId(), "netprog");
        RoomBinding bobBinding = registry.joinRoom(bob.sessionId(), "netprog");
        RoomBinding charlieBinding = registry.joinRoom(charlie.sessionId(), "revision");

        try (ChatServer server = new ChatServer("127.0.0.1", 0, config, registry)) {
            server.start();
            assertTrue(server.isRunning());
            assertTrue(server.boundPort() > 0);

            try (RawChatPeer alicePeer = RawChatPeer.connect(server.boundPort());
                 RawChatPeer bobPeer = RawChatPeer.connect(server.boundPort());
                 RawChatPeer charliePeer = RawChatPeer.connect(server.boundPort())) {

                assertTrue(alicePeer.subscribe("s1", aliceBinding).get("ok").getAsBoolean());
                assertTrue(bobPeer.subscribe("s2", bobBinding).get("ok").getAsBoolean());
                assertTrue(charliePeer.subscribe("s3", charlieBinding).get("ok").getAsBoolean());

                JsonObject sendAck = alicePeer.sendChat("m1", "Hello Network Programming!");
                assertTrue(sendAck.get("ok").getAsBoolean());

                // Both Alice (echo) and Bob receive the event; Charlie in 'revision' must not.
                ChatEvent aliceEvent = ChatProtocol.parseChatEventFrame(alicePeer.nextEvent(2, TimeUnit.SECONDS));
                ChatEvent bobEvent = ChatProtocol.parseChatEventFrame(bobPeer.nextEvent(2, TimeUnit.SECONDS));

                assertEquals("netprog", aliceEvent.roomId());
                assertEquals("Alice", aliceEvent.senderName());
                assertEquals("Hello Network Programming!", aliceEvent.text());
                assertEquals(aliceEvent, bobEvent);

                // Bob replies and Alice receives it
                assertTrue(bobPeer.sendChat("m2", "Hi Alice!").get("ok").getAsBoolean());
                ChatEvent bobReplyAtAlice = ChatProtocol.parseChatEventFrame(
                        alicePeer.nextEvent(2, TimeUnit.SECONDS));
                ChatEvent bobReplyAtBob = ChatProtocol.parseChatEventFrame(
                        bobPeer.nextEvent(2, TimeUnit.SECONDS));
                assertEquals("Bob", bobReplyAtAlice.senderName());
                assertEquals("Hi Alice!", bobReplyAtAlice.text());
                assertEquals(bobReplyAtAlice, bobReplyAtBob);

                // Spoofing an untrusted roomId inside CHAT_SEND must be rejected and never reach 'revision'
                JsonObject spoofedData = new JsonObject();
                spoofedData.addProperty("roomId", "revision");
                spoofedData.addProperty("text", "Cross-room injection attempt");
                JsonObject spoofedReq = new JsonObject();
                spoofedReq.addProperty("v", 1);
                spoofedReq.addProperty("id", "m-spoof");
                spoofedReq.addProperty("op", ChatProtocol.OP_CHAT_SEND);
                spoofedReq.add("data", spoofedData);
                JsonObject spoofResp = alicePeer.request(spoofedReq);
                assertFalse(spoofResp.get("ok").getAsBoolean());
                assertEquals(ChatProtocol.ERR_STALE_BINDING,
                        spoofResp.getAsJsonObject("error").get("code").getAsString());

                // Charlie sends in 'revision' and receives his own echo, confirming Charlie's channel works
                assertTrue(charliePeer.sendChat("m3", "Revision message").get("ok").getAsBoolean());
                ChatEvent charlieEvent = ChatProtocol.parseChatEventFrame(
                        charliePeer.nextEvent(2, TimeUnit.SECONDS));
                assertEquals("revision", charlieEvent.roomId());
                assertEquals("Charlie", charlieEvent.senderName());
                assertEquals("Revision message", charlieEvent.text());

                // Neither Alice nor Bob received Charlie's 'revision' event; Charlie never received 'netprog' events
                assertFalse(alicePeer.hasQueuedEvent(150, TimeUnit.MILLISECONDS));
                assertFalse(bobPeer.hasQueuedEvent(150, TimeUnit.MILLISECONDS));
                assertFalse(charliePeer.hasQueuedEvent(150, TimeUnit.MILLISECONDS));
            }
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void tenConcurrentClientsAcrossTwoRoomsExchangeMessagesSimultaneously() throws Exception {
        StudyLinkConfig config = StudyLinkConfig.srsDefaults();
        RoomSessionRegistry registry = new RoomSessionRegistry(config);

        int clientCount = 10;
        int messagesPerClient = 10; // 100 total messages across two rooms (SRS T06 / Q01)
        List<SessionInfo> sessions = new ArrayList<>();
        List<RoomBinding> bindings = new ArrayList<>();
        for (int i = 0; i < clientCount; i++) {
            SessionInfo session = registry.registerSession("Student " + i);
            String roomId = (i < 5) ? "netprog" : "workshop";
            RoomBinding binding = registry.joinRoom(session.sessionId(), roomId);
            sessions.add(session);
            bindings.add(binding);
        }

        try (ChatServer server = new ChatServer("127.0.0.1", 0, config, registry)) {
            server.start();

            List<RawChatPeer> peers = new ArrayList<>();
            try {
                for (int i = 0; i < clientCount; i++) {
                    RawChatPeer peer = RawChatPeer.connect(server.boundPort());
                    peers.add(peer);
                    assertTrue(peer.subscribe("sub-" + i, bindings.get(i)).get("ok").getAsBoolean());
                }

                CountDownLatch startGate = new CountDownLatch(1);
                CountDownLatch sendDone = new CountDownLatch(clientCount);
                Map<Integer, Throwable> errors = new ConcurrentHashMap<>();
                ExecutorService senders = Executors.newFixedThreadPool(clientCount);
                try {
                    for (int i = 0; i < clientCount; i++) {
                        final int clientIdx = i;
                        senders.execute(() -> {
                            try {
                                startGate.await(2, TimeUnit.SECONDS);
                                RawChatPeer peer = peers.get(clientIdx);
                                for (int m = 0; m < messagesPerClient; m++) {
                                    JsonObject ack = peer.sendChat(
                                            "msg-" + clientIdx + "-" + m,
                                            "Concurrent text #" + clientIdx + "-" + m
                                    );
                                    assertTrue(ack.get("ok").getAsBoolean());
                                }
                            } catch (Throwable t) {
                                errors.put(clientIdx, t);
                            } finally {
                                sendDone.countDown();
                            }
                        });
                    }

                    startGate.countDown();
                    assertTrue(sendDone.await(10, TimeUnit.SECONDS));
                    assertTrue(errors.isEmpty(), "Unexpected sender errors: " + errors);
                } finally {
                    senders.shutdownNow();
                }

                // Each of the 5 clients in 'netprog' must receive 5 * 10 = 50 events (none from 'workshop'),
                // and each of the 5 clients in 'workshop' must receive 50 events (none from 'netprog').
                for (int i = 0; i < clientCount; i++) {
                    String expectedRoom = bindings.get(i).roomId();
                    RawChatPeer peer = peers.get(i);
                    for (int k = 0; k < 50; k++) {
                        ChatEvent ev = ChatProtocol.parseChatEventFrame(peer.nextEvent(3, TimeUnit.SECONDS));
                        assertEquals(expectedRoom, ev.roomId());
                    }
                    assertFalse(peer.hasQueuedEvent(100, TimeUnit.MILLISECONDS));
                }
            } finally {
                for (RawChatPeer peer : peers) {
                    peer.close();
                }
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void handlesSplitPartialFramesAndMultipleCombinedFramesInSingleRead() throws Exception {
        StudyLinkConfig config = StudyLinkConfig.srsDefaults();
        RoomSessionRegistry registry = new RoomSessionRegistry(config);

        SessionInfo sender = registry.registerSession("Sender");
        SessionInfo receiver = registry.registerSession("Receiver");
        RoomBinding senderBinding = registry.joinRoom(sender.sessionId(), "netprog");
        RoomBinding receiverBinding = registry.joinRoom(receiver.sessionId(), "netprog");

        try (ChatServer server = new ChatServer("127.0.0.1", 0, config, registry)) {
            server.start();

            try (RawChatPeer receiverPeer = RawChatPeer.connect(server.boundPort());
                 Socket rawSender = new Socket()) {
                assertTrue(receiverPeer.subscribe("r-sub", receiverBinding).get("ok").getAsBoolean());

                rawSender.connect(new InetSocketAddress("127.0.0.1", server.boundPort()), SOCKET_TIMEOUT_MS);
                rawSender.setSoTimeout(SOCKET_TIMEOUT_MS);
                rawSender.setTcpNoDelay(true);
                OutputStream out = rawSender.getOutputStream();
                InputStream in = rawSender.getInputStream();

                // 1. Split SUBSCRIBE frame byte-by-byte across multiple writes
                byte[] subBytes = FrameCodec.encodeFrameBytes(
                        ChatProtocol.buildSubscribeRequest("s-split", senderBinding));
                for (int i = 0; i < subBytes.length; i++) {
                    out.write(subBytes, i, 1);
                    out.flush();
                }
                JsonObject subAck = FrameCodec.readFrame(in);
                assertNotNull(subAck);
                assertTrue(subAck.get("ok").getAsBoolean());

                // 2. Send 3 CHAT_SEND frames concatenated into a single write buffer
                byte[] m1 = FrameCodec.encodeFrameBytes(ChatProtocol.buildChatSendRequest("m1", "Alpha"));
                byte[] m2 = FrameCodec.encodeFrameBytes(ChatProtocol.buildChatSendRequest("m2", "Beta"));
                byte[] m3 = FrameCodec.encodeFrameBytes(ChatProtocol.buildChatSendRequest("m3", "Gamma"));
                byte[] combined = new byte[m1.length + m2.length + m3.length];
                System.arraycopy(m1, 0, combined, 0, m1.length);
                System.arraycopy(m2, 0, combined, m1.length, m2.length);
                System.arraycopy(m3, 0, combined, m1.length + m2.length, m3.length);
                out.write(combined);
                out.flush();

                List<String> receivedTexts = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    ChatEvent ev = ChatProtocol.parseChatEventFrame(
                            receiverPeer.nextEvent(2, TimeUnit.SECONDS));
                    receivedTexts.add(ev.text());
                }
                assertEquals(List.of("Alpha", "Beta", "Gamma"), receivedTexts);
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void rejectsOversizedEmptyMalformedFramesAndRecoversFromAbruptDisconnect() throws Exception {
        StudyLinkConfig config = StudyLinkConfig.srsDefaults();
        RoomSessionRegistry registry = new RoomSessionRegistry(config);

        SessionInfo healthyUser = registry.registerSession("Healthy");
        RoomBinding healthyBinding = registry.joinRoom(healthyUser.sessionId(), "workshop");

        try (ChatServer server = new ChatServer("127.0.0.1", 0, config, registry)) {
            server.start();

            try (RawChatPeer healthyPeer = RawChatPeer.connect(server.boundPort())) {
                assertTrue(healthyPeer.subscribe("h-sub", healthyBinding).get("ok").getAsBoolean());

                // 1. Empty message rejected with BAD_REQUEST
                JsonObject emptyResp = healthyPeer.sendChat("bad-empty", "   ");
                assertFalse(emptyResp.get("ok").getAsBoolean());
                assertEquals(ChatProtocol.ERR_BAD_REQUEST,
                        emptyResp.getAsJsonObject("error").get("code").getAsString());

                // 2. Message > 2,048 UTF-8 bytes rejected with TOO_LARGE (no broadcast)
                JsonObject tooBigResp = healthyPeer.sendChat("bad-big", "X".repeat(2049));
                assertFalse(tooBigResp.get("ok").getAsBoolean());
                assertEquals(ChatProtocol.ERR_TOO_LARGE,
                        tooBigResp.getAsJsonObject("error").get("code").getAsString());
                assertFalse(healthyPeer.hasQueuedEvent(100, TimeUnit.MILLISECONDS));

                // 3. Invalid oversized frame length (> 16,384 bytes) disconnects rogue client safely
                try (Socket rogue = new Socket()) {
                    rogue.connect(new InetSocketAddress("127.0.0.1", server.boundPort()), SOCKET_TIMEOUT_MS);
                    rogue.setSoTimeout(SOCKET_TIMEOUT_MS);
                    byte[] badPrefix = ByteBuffer.allocate(4).putInt(20_000).array();
                    rogue.getOutputStream().write(badPrefix);
                    rogue.getOutputStream().flush();
                    assertEquals(-1, rogue.getInputStream().read());
                }

                // 4. Mid-frame disconnect does not crash the server
                try (Socket abrupt = new Socket()) {
                    abrupt.connect(new InetSocketAddress("127.0.0.1", server.boundPort()), SOCKET_TIMEOUT_MS);
                    byte[] partialFrame = new byte[]{0x00, 0x00, 0x00, 0x20, '{', '"'};
                    abrupt.getOutputStream().write(partialFrame);
                    abrupt.getOutputStream().flush();
                }

                // Healthy peer still works normally
                assertTrue(healthyPeer.sendChat("ok-1", "Still alive").get("ok").getAsBoolean());
                ChatEvent okEvent = ChatProtocol.parseChatEventFrame(
                        healthyPeer.nextEvent(2, TimeUnit.SECONDS));
                assertEquals("Still alive", okEvent.text());
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void roomSwitchAndLeaveInvalidateOldBindingImmediately() throws Exception {
        StudyLinkConfig config = StudyLinkConfig.srsDefaults();
        RoomSessionRegistry registry = new RoomSessionRegistry(config);

        SessionInfo alice = registry.registerSession("Alice");
        SessionInfo bob = registry.registerSession("Bob");

        RoomBinding aliceNetprog = registry.joinRoom(alice.sessionId(), "netprog");
        RoomBinding bobNetprog = registry.joinRoom(bob.sessionId(), "netprog");

        try (ChatServer server = new ChatServer("127.0.0.1", 0, config, registry)) {
            server.start();

            try (RawChatPeer alicePeer = RawChatPeer.connect(server.boundPort());
                 RawChatPeer bobPeerOld = RawChatPeer.connect(server.boundPort())) {

                assertTrue(alicePeer.subscribe("a-1", aliceNetprog).get("ok").getAsBoolean());
                assertTrue(bobPeerOld.subscribe("b-1", bobNetprog).get("ok").getAsBoolean());
                assertEquals(2, server.activeRoomSubscriptionCount("netprog"));

                // Bob switches from netprog -> revision in M1's registry
                RoomBinding bobRevision = registry.joinRoom(bob.sessionId(), "revision");
                assertEquals(2L, bobRevision.revision());

                // Wait briefly for selector wakeup invalidation to drop Bob's old subscription
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (server.activeRoomSubscriptionCount("netprog") > 1 && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertEquals(1, server.activeRoomSubscriptionCount("netprog"));

                // Alice sends in netprog -> Bob's old socket must NOT receive it
                assertTrue(alicePeer.sendChat("a-msg", "After Bob switched").get("ok").getAsBoolean());
                assertEquals("After Bob switched",
                        ChatProtocol.parseChatEventFrame(alicePeer.nextEvent(2, TimeUnit.SECONDS)).text());
                assertFalse(bobPeerOld.hasQueuedEvent(150, TimeUnit.MILLISECONDS));

                // Attempting to send on Bob's stale channel fails with STALE_BINDING
                JsonObject staleSendResp = bobPeerOld.sendChat("b-stale", "Should fail");
                assertFalse(staleSendResp.get("ok").getAsBoolean());
                assertEquals(ChatProtocol.ERR_STALE_BINDING,
                        staleSendResp.getAsJsonObject("error").get("code").getAsString());

                // Re-subscribing with old binding also fails with STALE_BINDING
                JsonObject staleSubResp = bobPeerOld.subscribe("b-resub-old", bobNetprog);
                assertFalse(staleSubResp.get("ok").getAsBoolean());
                assertEquals(ChatProtocol.ERR_STALE_BINDING,
                        staleSubResp.getAsJsonObject("error").get("code").getAsString());

                // Bob leaves revision completely; subscribing with bobRevision fails
                registry.leaveRoom(bob.sessionId());
                JsonObject leftSubResp = bobPeerOld.subscribe("b-resub-left", bobRevision);
                assertFalse(leftSubResp.get("ok").getAsBoolean());
                assertEquals(ChatProtocol.ERR_STALE_BINDING,
                        leftSubResp.getAsJsonObject("error").get("code").getAsString());
            }
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void slowClientExceeding65536ByteOutboundLimitIsDisconnectedWhileFastPeerContinues() throws Exception {
        StudyLinkConfig config = StudyLinkConfig.srsDefaults();
        assertEquals(65_536, config.maxSlowClientQueueBytes());
        RoomSessionRegistry registry = new RoomSessionRegistry(config);

        SessionInfo sender = registry.registerSession("FastSender");
        SessionInfo fastReader = registry.registerSession("FastReader");
        SessionInfo slowReader = registry.registerSession("SlowReader");

        RoomBinding senderBinding = registry.joinRoom(sender.sessionId(), "netprog");
        RoomBinding fastBinding = registry.joinRoom(fastReader.sessionId(), "netprog");
        RoomBinding slowBinding = registry.joinRoom(slowReader.sessionId(), "netprog");

        try (ChatServer server = new ChatServer("127.0.0.1", 0, config, registry)) {
            server.start();

            try (RawChatPeer senderPeer = RawChatPeer.connect(server.boundPort());
                 RawChatPeer fastPeer = RawChatPeer.connect(server.boundPort());
                 Socket slowSocket = new Socket()) {

                assertTrue(senderPeer.subscribe("s-1", senderBinding).get("ok").getAsBoolean());
                assertTrue(fastPeer.subscribe("f-1", fastBinding).get("ok").getAsBoolean());

                // Subscribe slow peer with a small receive buffer, then stop reading from it
                slowSocket.setReceiveBufferSize(4_096);
                slowSocket.connect(new InetSocketAddress("127.0.0.1", server.boundPort()), SOCKET_TIMEOUT_MS);
                slowSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
                FrameCodec.writeFrame(
                        slowSocket.getOutputStream(),
                        ChatProtocol.buildSubscribeRequest("slow-1", slowBinding)
                );
                JsonObject slowSubAck = FrameCodec.readFrame(slowSocket.getInputStream());
                assertNotNull(slowSubAck);
                assertTrue(slowSubAck.get("ok").getAsBoolean());

                assertEquals(3, server.activeConnectionCount());

                // Flood ~1.8 KiB messages; OS TCP receive window fills and server outbound queue exceeds 65,536 B
                String largePayload = "M".repeat(1_800);
                int sent = 0;
                int maxMessages = 600;
                while (sent < maxMessages && server.activeConnectionCount() == 3) {
                    JsonObject ack = senderPeer.sendChat("flood-" + sent, largePayload);
                    assertTrue(ack.get("ok").getAsBoolean());
                    // Drain sender & fast reader queues so neither becomes slow
                    assertNotNull(senderPeer.nextEvent(2, TimeUnit.SECONDS));
                    assertNotNull(fastPeer.nextEvent(2, TimeUnit.SECONDS));
                    sent++;
                }

                // Wait briefly if final disconnect is in flight on selector thread
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (server.activeConnectionCount() > 2 && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }

                assertEquals(2, server.activeConnectionCount(),
                        "Slow peer exceeding 65,536-byte queue must be disconnected after " + sent + " messages");
                assertEquals(2, server.activeRoomSubscriptionCount("netprog"));

                // Fast sender and fast reader still communicate cleanly after slow client eviction
                assertTrue(senderPeer.sendChat("after-evict", "Healthy check").get("ok").getAsBoolean());
                assertEquals("Healthy check",
                        ChatProtocol.parseChatEventFrame(fastPeer.nextEvent(2, TimeUnit.SECONDS)).text());
                assertEquals("Healthy check",
                        ChatProtocol.parseChatEventFrame(senderPeer.nextEvent(2, TimeUnit.SECONDS)).text());
            }
        }
    }

    /**
     * Minimal test helper that keeps a background reader splitting correlated RPC replies from
     * asynchronous {@code CHAT_EVENT} frames.
     */
    private static final class RawChatPeer implements AutoCloseable {

        private final Socket socket;
        private final OutputStream out;
        private final BlockingQueue<JsonObject> responses = new LinkedBlockingQueue<>();
        private final BlockingQueue<JsonObject> events = new LinkedBlockingQueue<>();
        private final Thread readerThread;

        private RawChatPeer(Socket socket) throws IOException {
            this.socket = socket;
            this.out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            this.readerThread = new Thread(() -> {
                try {
                    while (!socket.isClosed()) {
                        JsonObject frame = FrameCodec.readFrame(in);
                        if (frame == null) {
                            break;
                        }
                        if (frame.has("id")) {
                            responses.add(frame);
                        } else {
                            events.add(frame);
                        }
                    }
                } catch (IOException ignored) {
                }
            }, "test-raw-chat-reader");
            this.readerThread.setDaemon(true);
            this.readerThread.start();
        }

        static RawChatPeer connect(int port) throws IOException {
            Socket sock = new Socket();
            sock.connect(new InetSocketAddress("127.0.0.1", port), SOCKET_TIMEOUT_MS);
            sock.setSoTimeout(SOCKET_TIMEOUT_MS);
            sock.setTcpNoDelay(true);
            return new RawChatPeer(sock);
        }

        JsonObject subscribe(String reqId, RoomBinding binding) throws IOException, InterruptedException {
            return request(ChatProtocol.buildSubscribeRequest(reqId, binding));
        }

        JsonObject sendChat(String reqId, String text) throws IOException, InterruptedException {
            return request(ChatProtocol.buildChatSendRequest(reqId, text));
        }

        synchronized JsonObject request(JsonObject frame) throws IOException, InterruptedException {
            FrameCodec.writeFrame(out, frame);
            JsonObject response = responses.poll(3, TimeUnit.SECONDS);
            assertNotNull(response, "Timed out waiting for response to " + frame);
            return response;
        }

        JsonObject nextEvent(long timeout, TimeUnit unit) throws InterruptedException {
            JsonObject ev = events.poll(timeout, unit);
            assertNotNull(ev, "Expected CHAT_EVENT within " + timeout + " " + unit);
            return ev;
        }

        boolean hasQueuedEvent(long timeout, TimeUnit unit) throws InterruptedException {
            JsonObject ev = events.poll(timeout, unit);
            return ev != null;
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            readerThread.interrupt();
        }
    }
}
