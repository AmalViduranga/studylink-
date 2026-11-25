package lk.studylink.server.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lk.studylink.shared.config.StudyLinkConfig;
import lk.studylink.shared.model.RoomBinding;
import lk.studylink.shared.protocol.FrameCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Real-socket integration tests for {@link RoomControlServer} over loopback ephemeral ports (Issue M1-03).
 */
class RoomControlServerTest {

    private static final int SOCKET_TIMEOUT_MS = 3_000;

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void fullSingleClientLifecycleHelloListRoomsJoinSwitchLeavePingAndQuit() throws Exception {
        StudyLinkConfig config = StudyLinkConfig.srsDefaults();
        RoomSessionRegistry registry = new RoomSessionRegistry(config);

        try (RoomControlServer server = new RoomControlServer("127.0.0.1", 0, config, registry)) {
            server.start();
            assertTrue(server.isRunning());
            assertTrue(server.boundPort() > 0);

            try (TestControlClient client = TestControlClient.connect(server.boundPort())) {
                // 1. HELLO
                JsonObject helloData = new JsonObject();
                helloData.addProperty("displayName", "  Amal  ");
                JsonObject helloResp = client.request("r01", RoomControlServer.OP_HELLO, helloData);
                assertTrue(helloResp.get("ok").getAsBoolean());
                assertEquals("r01", helloResp.get("id").getAsString());

                JsonObject helloBody = helloResp.getAsJsonObject("data");
                String sessionId = helloBody.get("sessionId").getAsString();
                assertTrue(sessionId.startsWith("s-"));
                assertEquals("Amal", helloBody.get("displayName").getAsString());

                JsonObject ports = helloBody.getAsJsonObject("ports");
                assertEquals(config.discoveryPort(), ports.get("discoveryUdp").getAsInt());
                assertEquals(server.boundPort(), ports.get("roomsTcp").getAsInt());
                assertEquals(config.chatPort(), ports.get("chatNioTcp").getAsInt());
                assertEquals(config.filesPort(), ports.get("filesTcp").getAsInt());
                assertEquals(config.notesPort(), ports.get("notesTlsTcp").getAsInt());

                // 2. LIST_ROOMS
                JsonObject listResp = client.request("r02", RoomControlServer.OP_LIST_ROOMS, new JsonObject());
                assertTrue(listResp.get("ok").getAsBoolean());
                assertEquals("r02", listResp.get("id").getAsString());
                JsonArray rooms = listResp.getAsJsonObject("data").getAsJsonArray("rooms");
                assertEquals(3, rooms.size());
                assertEquals("netprog", rooms.get(0).getAsJsonObject().get("id").getAsString());
                assertEquals("Network Programming", rooms.get(0).getAsJsonObject().get("name").getAsString());
                assertEquals("revision", rooms.get(1).getAsJsonObject().get("id").getAsString());
                assertEquals("workshop", rooms.get(2).getAsJsonObject().get("id").getAsString());

                // 3. JOIN netprog -> revision 1
                JsonObject join1Data = new JsonObject();
                join1Data.addProperty("roomId", "netprog");
                JsonObject join1Resp = client.request("r03", RoomControlServer.OP_JOIN, join1Data);
                assertTrue(join1Resp.get("ok").getAsBoolean());
                assertEquals("r03", join1Resp.get("id").getAsString());
                JsonObject join1Body = join1Resp.getAsJsonObject("data");
                assertEquals(sessionId, join1Body.get("sessionId").getAsString());
                assertEquals("netprog", join1Body.get("roomId").getAsString());
                assertEquals(1L, join1Body.get("revision").getAsLong());
                RoomBinding binding1 = new RoomBinding(sessionId, "netprog", 1L);
                assertTrue(registry.isCurrent(binding1));

                // 4. Switch to revision -> revision 2
                JsonObject join2Data = new JsonObject();
                join2Data.addProperty("roomId", "revision");
                JsonObject join2Resp = client.request("r04", RoomControlServer.OP_JOIN, join2Data);
                assertTrue(join2Resp.get("ok").getAsBoolean());
                assertEquals("r04", join2Resp.get("id").getAsString());
                JsonObject join2Body = join2Resp.getAsJsonObject("data");
                assertEquals(sessionId, join2Body.get("sessionId").getAsString());
                assertEquals("revision", join2Body.get("roomId").getAsString());
                assertEquals(2L, join2Body.get("revision").getAsLong());
                RoomBinding binding2 = new RoomBinding(sessionId, "revision", 2L);
                assertFalse(registry.isCurrent(binding1));
                assertTrue(registry.isCurrent(binding2));

                // 5. LEAVE
                JsonObject leaveResp = client.request("r05", RoomControlServer.OP_LEAVE, new JsonObject());
                assertTrue(leaveResp.get("ok").getAsBoolean());
                assertEquals("r05", leaveResp.get("id").getAsString());
                JsonObject leaveBody = leaveResp.getAsJsonObject("data");
                assertEquals(sessionId, leaveBody.get("sessionId").getAsString());
                assertEquals("revision", leaveBody.get("leftRoomId").getAsString());
                assertFalse(registry.isCurrent(binding2));
                assertEquals(Optional.empty(), registry.currentBinding(sessionId));
                assertTrue(registry.findSession(sessionId).isPresent());

                // 6. PING
                JsonObject pingResp = client.request("r06", RoomControlServer.OP_PING, new JsonObject());
                assertTrue(pingResp.get("ok").getAsBoolean());
                assertEquals("r06", pingResp.get("id").getAsString());
                assertEquals(sessionId, pingResp.getAsJsonObject("data").get("sessionId").getAsString());
                assertTrue(pingResp.getAsJsonObject("data").get("pong").getAsBoolean());

                // 7. QUIT
                JsonObject quitResp = client.request("r07", RoomControlServer.OP_QUIT, new JsonObject());
                assertTrue(quitResp.get("ok").getAsBoolean());
                assertEquals("r07", quitResp.get("id").getAsString());
                assertTrue(quitResp.getAsJsonObject("data").get("closed").getAsBoolean());

                // Server closes connection after QUIT and releases display name
                assertNull(FrameCodec.readFrame(client.in));
                assertTrue(server.awaitSessionClosed(sessionId, 3, TimeUnit.SECONDS));
                assertEquals(Optional.empty(), registry.findSession(sessionId));
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void twoClientsHandleDuplicateNamesIndependentRoomsAndDisconnectIsolation() throws Exception {
        StudyLinkConfig config = StudyLinkConfig.srsDefaults();
        RoomSessionRegistry registry = new RoomSessionRegistry(config);
        List<String> transcript = new ArrayList<>();

        try (RoomControlServer server = new RoomControlServer("127.0.0.1", 0, config, registry)) {
            server.start();

            try (TestControlClient client1 = TestControlClient.connect(server.boundPort(), "Client-1", transcript);
                 TestControlClient client2 = TestControlClient.connect(server.boundPort(), "Client-2", transcript)) {

                // Client 1 registers "Amal"
                JsonObject c1Hello = new JsonObject();
                c1Hello.addProperty("displayName", "Amal");
                JsonObject c1HelloResp = client1.request("c1-01", RoomControlServer.OP_HELLO, c1Hello);
                assertTrue(c1HelloResp.get("ok").getAsBoolean());
                String s1 = c1HelloResp.getAsJsonObject("data").get("sessionId").getAsString();

                // Client 2 tries case-insensitive duplicate "amal" -> NAME_IN_USE
                JsonObject c2DupHello = new JsonObject();
                c2DupHello.addProperty("displayName", "amal");
                JsonObject c2DupResp = client2.request("c2-01", RoomControlServer.OP_HELLO, c2DupHello);
                assertFalse(c2DupResp.get("ok").getAsBoolean());
                assertEquals("c2-01", c2DupResp.get("id").getAsString());
                assertEquals(
                        RoomRegistryErrorCode.NAME_IN_USE.name(),
                        c2DupResp.getAsJsonObject("error").get("code").getAsString()
                );

                // Client 2 retries with "Kasun" on the same connection -> succeeds
                JsonObject c2Hello = new JsonObject();
                c2Hello.addProperty("displayName", "Kasun");
                JsonObject c2HelloResp = client2.request("c2-02", RoomControlServer.OP_HELLO, c2Hello);
                assertTrue(c2HelloResp.get("ok").getAsBoolean());
                String s2 = c2HelloResp.getAsJsonObject("data").get("sessionId").getAsString();
                assertNotEquals(s1, s2);

                // Both join rooms
                JsonObject joinNetprog = new JsonObject();
                joinNetprog.addProperty("roomId", "netprog");
                JsonObject c1JoinResp = client1.request("c1-02", RoomControlServer.OP_JOIN, joinNetprog);
                assertTrue(c1JoinResp.get("ok").getAsBoolean());
                RoomBinding b1 = new RoomBinding(s1, "netprog", 1L);

                JsonObject joinRevision = new JsonObject();
                joinRevision.addProperty("roomId", "revision");
                JsonObject c2JoinResp = client2.request("c2-03", RoomControlServer.OP_JOIN, joinRevision);
                assertTrue(c2JoinResp.get("ok").getAsBoolean());
                RoomBinding b2 = new RoomBinding(s2, "revision", 1L);

                assertTrue(registry.isCurrent(b1));
                assertTrue(registry.isCurrent(b2));

                // Client 1 leaves and disconnects via QUIT
                JsonObject c1LeaveResp = client1.request("c1-03", RoomControlServer.OP_LEAVE, new JsonObject());
                assertTrue(c1LeaveResp.get("ok").getAsBoolean());
                JsonObject c1QuitResp = client1.request("c1-04", RoomControlServer.OP_QUIT, new JsonObject());
                assertTrue(c1QuitResp.get("ok").getAsBoolean());
                assertTrue(server.awaitSessionClosed(s1, 3, TimeUnit.SECONDS));

                // Client 2's session and binding remain completely intact
                assertTrue(registry.isCurrent(b2));
                JsonObject c2PingResp = client2.request("c2-04", RoomControlServer.OP_PING, new JsonObject());
                assertTrue(c2PingResp.get("ok").getAsBoolean());

                JsonObject c2SwitchResp = client2.request("c2-05", RoomControlServer.OP_JOIN, joinNetprog);
                assertTrue(c2SwitchResp.get("ok").getAsBoolean());
                assertEquals(2L, c2SwitchResp.getAsJsonObject("data").get("revision").getAsLong());

                JsonObject c2QuitResp = client2.request("c2-06", RoomControlServer.OP_QUIT, new JsonObject());
                assertTrue(c2QuitResp.get("ok").getAsBoolean());
            }
        }

        System.out.println("=== TWO-CLIENT WIRE TRANSCRIPT (twoClientsHandleDuplicateNamesIndependentRoomsAndDisconnectIsolation) ===");
        for (String line : transcript) {
            System.out.println(line);
        }
        System.out.println("=== END TWO-CLIENT WIRE TRANSCRIPT ===");
        assertFalse(transcript.isEmpty());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void stalledPartialFrameAndMalformedFrameDoNotBlockOrAffectOtherClients() throws Exception {
        StudyLinkConfig config = StudyLinkConfig.srsDefaults();
        RoomSessionRegistry registry = new RoomSessionRegistry(config);

        try (RoomControlServer server = new RoomControlServer("127.0.0.1", 0, config, registry)) {
            server.start();

            try (Socket slowRawSocket = new Socket()) {
                slowRawSocket.connect(new InetSocketAddress("127.0.0.1", server.boundPort()), SOCKET_TIMEOUT_MS);
                slowRawSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
                OutputStream slowOut = slowRawSocket.getOutputStream();
                InputStream slowIn = slowRawSocket.getInputStream();

                JsonObject slowHelloReq = new JsonObject();
                slowHelloReq.addProperty("v", 1);
                slowHelloReq.addProperty("id", "slow-1");
                slowHelloReq.addProperty("op", RoomControlServer.OP_HELLO);
                JsonObject slowData = new JsonObject();
                slowData.addProperty("displayName", "Maya");
                slowHelloReq.add("data", slowData);
                byte[] fullSlowFrame = FrameCodec.encodeFrameBytes(slowHelloReq);

                // 1. Slow client writes only 2 of the 4 length-prefix bytes and pauses
                slowOut.write(fullSlowFrame, 0, 2);
                slowOut.flush();

                // 2. Healthy client connects and completes HELLO, LIST_ROOMS, and JOIN without blocking
                try (TestControlClient healthyClient = TestControlClient.connect(server.boundPort())) {
                    JsonObject healthyHello = new JsonObject();
                    healthyHello.addProperty("displayName", "Nimal");
                    JsonObject hResp = healthyClient.request("h-1", RoomControlServer.OP_HELLO, healthyHello);
                    assertTrue(hResp.get("ok").getAsBoolean());
                    String healthySessionId = hResp.getAsJsonObject("data").get("sessionId").getAsString();

                    JsonObject listResp = healthyClient.request("h-2", RoomControlServer.OP_LIST_ROOMS, new JsonObject());
                    assertTrue(listResp.get("ok").getAsBoolean());

                    // 3. Slow client writes next 5 bytes (completing prefix + partial payload) and pauses again
                    slowOut.write(fullSlowFrame, 2, 5);
                    slowOut.flush();

                    // Healthy client joins netprog while slow client is paused mid-payload
                    JsonObject joinData = new JsonObject();
                    joinData.addProperty("roomId", "netprog");
                    JsonObject joinResp = healthyClient.request("h-3", RoomControlServer.OP_JOIN, joinData);
                    assertTrue(joinResp.get("ok").getAsBoolean());
                    RoomBinding healthyBinding = new RoomBinding(healthySessionId, "netprog", 1L);
                    assertTrue(registry.isCurrent(healthyBinding));

                    // 4. Slow client finishes its first frame and gets its HELLO response
                    slowOut.write(fullSlowFrame, 7, fullSlowFrame.length - 7);
                    slowOut.flush();
                    JsonObject slowHelloResp = FrameCodec.readFrame(slowIn);
                    assertNotNull(slowHelloResp);
                    assertTrue(slowHelloResp.get("ok").getAsBoolean());
                    String slowSessionId = slowHelloResp.getAsJsonObject("data").get("sessionId").getAsString();

                    // 5. Slow client now sends a malformed frame (oversized length prefix 0x00010000 = 65536 bytes)
                    slowOut.write(new byte[]{0x00, 0x01, 0x00, 0x00});
                    slowOut.flush();

                    // Server closes only the slow client's connection and releases "Maya"
                    assertNull(FrameCodec.readFrame(slowIn));
                    assertTrue(server.awaitSessionClosed(slowSessionId, 3, TimeUnit.SECONDS));
                    assertEquals(Optional.empty(), registry.findSession(slowSessionId));

                    // Healthy client remains connected and unaffected
                    assertTrue(registry.isCurrent(healthyBinding));
                    JsonObject pingResp = healthyClient.request("h-4", RoomControlServer.OP_PING, new JsonObject());
                    assertTrue(pingResp.get("ok").getAsBoolean());
                }
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void enforcesOperationOrderRejectsRepeatedHelloUnknownRoomAndCrossSessionAttempts() throws Exception {
        StudyLinkConfig config = StudyLinkConfig.srsDefaults();
        RoomSessionRegistry registry = new RoomSessionRegistry(config);

        try (RoomControlServer server = new RoomControlServer("127.0.0.1", 0, config, registry)) {
            server.start();

            try (TestControlClient client1 = TestControlClient.connect(server.boundPort());
                 TestControlClient client2 = TestControlClient.connect(server.boundPort())) {

                // 1. Operations before HELLO must be rejected with NOT_REGISTERED
                for (String op : List.of(
                        RoomControlServer.OP_LIST_ROOMS,
                        RoomControlServer.OP_JOIN,
                        RoomControlServer.OP_LEAVE,
                        RoomControlServer.OP_PING
                )) {
                    JsonObject data = new JsonObject();
                    if (RoomControlServer.OP_JOIN.equals(op)) {
                        data.addProperty("roomId", "netprog");
                    }
                    JsonObject resp = client1.request("pre-" + op, op, data);
                    assertFalse(resp.get("ok").getAsBoolean());
                    assertEquals(
                            RoomControlServer.ERR_NOT_REGISTERED,
                            resp.getAsJsonObject("error").get("code").getAsString()
                    );
                }

                // 2. Failed HELLO (invalid display name) must not reserve a name or bind a session
                JsonObject badHello = new JsonObject();
                badHello.addProperty("displayName", "A");
                JsonObject badHelloResp = client1.request("bad-hello", RoomControlServer.OP_HELLO, badHello);
                assertFalse(badHelloResp.get("ok").getAsBoolean());
                assertEquals(
                        RoomRegistryErrorCode.INVALID_NAME.name(),
                        badHelloResp.getAsJsonObject("error").get("code").getAsString()
                );

                // 3. Valid HELLO on Client 1 ("Amal") and JOIN "netprog"
                JsonObject c1Hello = new JsonObject();
                c1Hello.addProperty("displayName", "Amal");
                JsonObject c1HelloResp = client1.request("c1-hello", RoomControlServer.OP_HELLO, c1Hello);
                assertTrue(c1HelloResp.get("ok").getAsBoolean());
                String s1 = c1HelloResp.getAsJsonObject("data").get("sessionId").getAsString();

                JsonObject joinNetprog = new JsonObject();
                joinNetprog.addProperty("roomId", "netprog");
                JsonObject c1JoinResp = client1.request("c1-join", RoomControlServer.OP_JOIN, joinNetprog);
                assertTrue(c1JoinResp.get("ok").getAsBoolean());
                RoomBinding c1Binding = new RoomBinding(s1, "netprog", 1L);
                assertTrue(registry.isCurrent(c1Binding));

                // 4. Repeated HELLO on Client 1 must return ALREADY_REGISTERED without leaking a second session
                JsonObject secondHello = new JsonObject();
                secondHello.addProperty("displayName", "Ravi");
                JsonObject secondHelloResp = client1.request("c1-hello-2", RoomControlServer.OP_HELLO, secondHello);
                assertFalse(secondHelloResp.get("ok").getAsBoolean());
                assertEquals(
                        RoomControlServer.ERR_ALREADY_REGISTERED,
                        secondHelloResp.getAsJsonObject("error").get("code").getAsString()
                );
                assertTrue(registry.isCurrent(c1Binding), "Repeated HELLO must not disturb existing room binding");

                // 5. Client 2 can register "Ravi" (proving Client 1's rejected second HELLO did not reserve "Ravi")
                JsonObject c2HelloResp = client2.request("c2-hello", RoomControlServer.OP_HELLO, secondHello);
                assertTrue(c2HelloResp.get("ok").getAsBoolean());
                String s2 = c2HelloResp.getAsJsonObject("data").get("sessionId").getAsString();

                // 6. Unknown room on Client 1 must return UNKNOWN_ROOM and preserve c1Binding
                JsonObject unknownJoin = new JsonObject();
                unknownJoin.addProperty("roomId", "nonexistent-room");
                JsonObject unknownJoinResp = client1.request("c1-bad-room", RoomControlServer.OP_JOIN, unknownJoin);
                assertFalse(unknownJoinResp.get("ok").getAsBoolean());
                assertEquals(
                        RoomRegistryErrorCode.UNKNOWN_ROOM.name(),
                        unknownJoinResp.getAsJsonObject("error").get("code").getAsString()
                );
                assertTrue(registry.isCurrent(c1Binding), "Unknown room join must preserve existing valid binding");

                // 7. Attempted cross-session operations from Client 2 supplying Client 1's sessionId (s1)
                JsonObject spoofJoinData = new JsonObject();
                spoofJoinData.addProperty("sessionId", s1);
                spoofJoinData.addProperty("roomId", "workshop");
                JsonObject spoofJoinResp = client2.request("c2-spoof-join", RoomControlServer.OP_JOIN, spoofJoinData);
                assertFalse(spoofJoinResp.get("ok").getAsBoolean());
                assertEquals(
                        RoomRegistryErrorCode.STALE_BINDING.name(),
                        spoofJoinResp.getAsJsonObject("error").get("code").getAsString()
                );

                JsonObject spoofLeaveData = new JsonObject();
                spoofLeaveData.addProperty("sessionId", s1);
                JsonObject spoofLeaveResp = client2.request("c2-spoof-leave", RoomControlServer.OP_LEAVE, spoofLeaveData);
                assertFalse(spoofLeaveResp.get("ok").getAsBoolean());
                assertEquals(
                        RoomRegistryErrorCode.STALE_BINDING.name(),
                        spoofLeaveResp.getAsJsonObject("error").get("code").getAsString()
                );

                JsonObject spoofQuitData = new JsonObject();
                spoofQuitData.addProperty("sessionId", s1);
                JsonObject spoofQuitResp = client2.request("c2-spoof-quit", RoomControlServer.OP_QUIT, spoofQuitData);
                assertFalse(spoofQuitResp.get("ok").getAsBoolean());
                assertEquals(
                        RoomRegistryErrorCode.STALE_BINDING.name(),
                        spoofQuitResp.getAsJsonObject("error").get("code").getAsString()
                );

                // Client 1's session and binding are completely unaffected by Client 2's cross-session attempts
                assertTrue(registry.findSession(s1).isPresent());
                assertTrue(registry.isCurrent(c1Binding));
                assertEquals(Optional.empty(), registry.currentBinding(s2));
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void abruptSocketCloseReleasesNameForReconnectAndServerShutdownCompletesBoundedly() throws Exception {
        StudyLinkConfig config = StudyLinkConfig.srsDefaults();
        RoomSessionRegistry registry = new RoomSessionRegistry(config);

        try (RoomControlServer server = new RoomControlServer("127.0.0.1", 0, config, registry)) {
            server.start();

            final String firstSessionId;
            try (TestControlClient abruptClient = TestControlClient.connect(server.boundPort())) {
                JsonObject helloData = new JsonObject();
                helloData.addProperty("displayName", "Amal");
                JsonObject helloResp = abruptClient.request("a-1", RoomControlServer.OP_HELLO, helloData);
                assertTrue(helloResp.get("ok").getAsBoolean());
                firstSessionId = helloResp.getAsJsonObject("data").get("sessionId").getAsString();

                JsonObject joinData = new JsonObject();
                joinData.addProperty("roomId", "netprog");
                JsonObject joinResp = abruptClient.request("a-2", RoomControlServer.OP_JOIN, joinData);
                assertTrue(joinResp.get("ok").getAsBoolean());
                assertTrue(registry.isCurrent(new RoomBinding(firstSessionId, "netprog", 1L)));
                // Exiting try-with-resources closes the raw TCP socket without sending QUIT
            }

            assertTrue(server.awaitSessionClosed(firstSessionId, 3, TimeUnit.SECONDS));
            assertEquals(Optional.empty(), registry.findSession(firstSessionId));
            assertFalse(registry.isCurrent(new RoomBinding(firstSessionId, "netprog", 1L)));

            // Reconnect with the same name "Amal" and also connect a second client "Kasun"
            try (TestControlClient reconnectedClient = TestControlClient.connect(server.boundPort());
                 TestControlClient secondClient = TestControlClient.connect(server.boundPort())) {

                JsonObject amalHello = new JsonObject();
                amalHello.addProperty("displayName", "Amal");
                JsonObject amalResp = reconnectedClient.request("r-1", RoomControlServer.OP_HELLO, amalHello);
                assertTrue(amalResp.get("ok").getAsBoolean());
                String reconnectedSessionId = amalResp.getAsJsonObject("data").get("sessionId").getAsString();
                assertNotEquals(firstSessionId, reconnectedSessionId);

                JsonObject kasunHello = new JsonObject();
                kasunHello.addProperty("displayName", "Kasun");
                JsonObject kasunResp = secondClient.request("k-1", RoomControlServer.OP_HELLO, kasunHello);
                assertTrue(kasunResp.get("ok").getAsBoolean());
                String kasunSessionId = kasunResp.getAsJsonObject("data").get("sessionId").getAsString();

                // Shut down server while both clients are actively connected
                server.close();
                // Calling close() a second time must be a safe no-op
                server.close();

                assertFalse(server.isRunning());
                assertEquals(0, server.activeConnectionCount());
                assertEquals(Optional.empty(), registry.findSession(reconnectedSessionId));
                assertEquals(Optional.empty(), registry.findSession(kasunSessionId));
            }
        }
    }

    private static final class TestControlClient implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final String label;
        private final List<String> transcript;

        private TestControlClient(
                Socket socket,
                InputStream in,
                OutputStream out,
                String label,
                List<String> transcript
        ) {
            this.socket = socket;
            this.in = in;
            this.out = out;
            this.label = label;
            this.transcript = transcript;
        }

        private static TestControlClient connect(int port) throws IOException {
            return connect(port, "Client", null);
        }

        private static TestControlClient connect(int port, String label, List<String> transcript) throws IOException {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), SOCKET_TIMEOUT_MS);
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            return new TestControlClient(socket, socket.getInputStream(), socket.getOutputStream(), label, transcript);
        }

        private JsonObject request(String id, String op, JsonObject data) throws IOException {
            JsonObject req = new JsonObject();
            req.addProperty("v", 1);
            req.addProperty("id", id);
            req.addProperty("op", op);
            if (data != null) {
                req.add("data", data);
            }
            if (transcript != null) {
                transcript.add("[" + label + " -> Server] "
                        + new String(FrameCodec.encodePayloadBytes(req), StandardCharsets.UTF_8));
            }
            FrameCodec.writeFrame(out, req);
            JsonObject resp = FrameCodec.readFrame(in);
            assertNotNull(resp, "Expected response frame for request " + id);
            if (transcript != null) {
                transcript.add("[Server -> " + label + "] "
                        + new String(FrameCodec.encodePayloadBytes(resp), StandardCharsets.UTF_8));
            }
            return resp;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
