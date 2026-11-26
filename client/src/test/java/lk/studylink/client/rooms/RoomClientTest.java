package lk.studylink.client.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Unit and real-socket loopback integration tests for {@link RoomClient}, automatic control
 * {@code PING} heartbeats, and server-side inactive-session expiry (Issue M1-04).
 */
class RoomClientTest {

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void fullClientLifecycleConnectListRoomsJoinSwitchRejectedSwitchLeavePingAndClose() throws Exception {
        StudyLinkConfig config = StudyLinkConfig.srsDefaults();
        RoomSessionRegistry registry = new RoomSessionRegistry(config);

        try (RoomControlServer server = new RoomControlServer("127.0.0.1", 0, config, registry);
                RoomClient client = new RoomClient(config, 3_000, false)) {
            server.start();
            InetSocketAddress endpoint = new InetSocketAddress("127.0.0.1", server.boundPort());

            // 1. connect / HELLO
            SessionInfo session = client.connect(endpoint, "  Amal  ");
            assertTrue(client.isConnected());
            assertTrue(session.sessionId().startsWith("s-"));
            assertEquals("Amal", session.displayName());
            assertEquals(Optional.of(session), client.sessionInfo());
            assertEquals(Optional.empty(), client.currentBinding());

            AdvertisedServicePorts ports = client.servicePorts().orElseThrow();
            assertEquals(config.discoveryPort(), ports.discoveryUdp());
            assertEquals(server.boundPort(), ports.roomsTcp());
            assertEquals(config.chatPort(), ports.chatNioTcp());
            assertEquals(config.filesPort(), ports.filesTcp());
            assertEquals(config.notesPort(), ports.notesTlsTcp());

            // 2. listRooms
            List<RoomDefinition> rooms = client.listRooms();
            assertEquals(3, rooms.size());
            assertEquals(new RoomDefinition("netprog", "Network Programming"), rooms.get(0));
            assertEquals(new RoomDefinition("revision", "Exam Revision"), rooms.get(1));
            assertEquals(new RoomDefinition("workshop", "Project Workshop"), rooms.get(2));
            assertEquals(config.rooms(), rooms);

            // 3. join("netprog") -> revision 1
            RoomBinding binding1 = client.joinRoom("netprog");
            assertEquals(new RoomBinding(session.sessionId(), "netprog", 1L), binding1);
            assertEquals(Optional.of(binding1), client.currentBinding());
            assertTrue(registry.isCurrent(binding1));

            // 4. switch join("revision") -> revision 2
            RoomBinding binding2 = client.joinRoom("revision");
            assertEquals(new RoomBinding(session.sessionId(), "revision", 2L), binding2);
            assertEquals(Optional.of(binding2), client.currentBinding());
            assertFalse(registry.isCurrent(binding1));
            assertTrue(registry.isCurrent(binding2));

            // 5. rejected room switch preserves previous RoomBinding
            RoomClientException unknownRoomEx =
                    assertThrows(RoomClientException.class, () -> client.joinRoom("nonexistent-room"));
            assertTrue(unknownRoomEx.hasErrorCode("UNKNOWN_ROOM"));
            assertFalse(unknownRoomEx.isConnectionInvalidated());
            assertTrue(client.isConnected());
            assertEquals(Optional.of(binding2), client.currentBinding());
            assertTrue(registry.isCurrent(binding2));

            // 6. leave() clears RoomBinding while keeping SessionInfo
            Optional<String> leftRoom = client.leaveRoom();
            assertEquals(Optional.of("revision"), leftRoom);
            assertEquals(Optional.empty(), client.currentBinding());
            assertEquals(Optional.of(session), client.sessionInfo());
            assertFalse(registry.isCurrent(binding2));

            // Calling leaveRoom() again while not in a room returns Optional.empty()
            assertEquals(Optional.empty(), client.leaveRoom());

            // 7. explicit ping()
            assertTrue(client.ping());

            // 8. close() sends QUIT and clears client state
            client.close();
            assertFalse(client.isConnected());
            assertEquals(Optional.empty(), client.sessionInfo());
            assertEquals(Optional.empty(), client.servicePorts());
            assertEquals(Optional.empty(), client.currentBinding());
            assertTrue(server.awaitSessionClosed(session.sessionId(), 2, TimeUnit.SECONDS));
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void duplicateDisplayNameRejectedWithNameInUseAndLeavesClientDisconnected() throws Exception {
        StudyLinkConfig config = StudyLinkConfig.srsDefaults();
        RoomSessionRegistry registry = new RoomSessionRegistry(config);

        try (RoomControlServer server = new RoomControlServer("127.0.0.1", 0, config, registry);
                RoomClient first = new RoomClient(config, 3_000, false);
                RoomClient duplicate = new RoomClient(config, 3_000, true)) {
            server.start();

            SessionInfo s1 = first.connect("127.0.0.1", server.boundPort(), "Amal");
            assertNotNull(s1);

            RoomClientException ex = assertThrows(
                    RoomClientException.class,
                    () -> duplicate.connect("127.0.0.1", server.boundPort(), "  amal  "));
            assertTrue(ex.hasErrorCode("NAME_IN_USE"));
            assertFalse(duplicate.isConnected());
            assertEquals(Optional.empty(), duplicate.sessionInfo());
            assertEquals(Optional.empty(), duplicate.currentBinding());

            // Original client still works normally
            assertTrue(first.ping());
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void mismatchedReplyIdInvalidatesClientAndClearsRoomBinding() throws Exception {
        try (ServerSocket peer = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
                RoomClient client = new RoomClient(StudyLinkConfig.srsDefaults(), 2_000, false)) {
            Thread serverThread = Thread.ofVirtual().start(() -> {
                try (Socket socket = peer.accept();
                        InputStream in = socket.getInputStream();
                        OutputStream out = socket.getOutputStream()) {
                    JsonObject helloReq = FrameCodec.readFrame(in);
                    FrameCodec.writeFrame(out, buildHelloSuccess(helloReq.get("id").getAsString(), "s-0001", "Amal"));

                    JsonObject joinReq = FrameCodec.readFrame(in);
                    FrameCodec.writeFrame(out, buildJoinSuccess(joinReq.get("id").getAsString(), "s-0001", "netprog", 1L));

                    JsonObject pingReq = FrameCodec.readFrame(in);
                    assertNotNull(pingReq);
                    // Reply with a mismatched request ID
                    JsonObject badResp = buildPongSuccess("wrong-id-999");
                    FrameCodec.writeFrame(out, badResp);
                } catch (IOException ignored) {
                }
            });

            client.connect("127.0.0.1", peer.getLocalPort(), "Amal");
            RoomBinding binding = client.joinRoom("netprog");
            assertEquals(Optional.of(binding), client.currentBinding());

            RoomClientException ex = assertThrows(RoomClientException.class, client::ping);
            assertTrue(ex.isConnectionInvalidated());
            assertFalse(client.isConnected());
            assertEquals(Optional.empty(), client.sessionInfo());
            assertEquals(Optional.empty(), client.currentBinding());
            serverThread.join(2_000);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void invalidProtocolVersionAndMalformedResponseDataInvalidateClient() throws Exception {
        // Part 1: non-exact protocol version 1.0000000000000001 in response
        try (ServerSocket peer = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
                RoomClient client = new RoomClient(StudyLinkConfig.srsDefaults(), 2_000, false)) {
            Thread serverThread = Thread.ofVirtual().start(() -> {
                try (Socket socket = peer.accept();
                        InputStream in = socket.getInputStream();
                        OutputStream out = socket.getOutputStream()) {
                    JsonObject helloReq = FrameCodec.readFrame(in);
                    FrameCodec.writeFrame(out, buildHelloSuccess(helloReq.get("id").getAsString(), "s-0001", "Amal"));

                    JsonObject joinReq = FrameCodec.readFrame(in);
                    FrameCodec.writeFrame(out, buildJoinSuccess(joinReq.get("id").getAsString(), "s-0001", "netprog", 1L));

                    JsonObject pingReq = FrameCodec.readFrame(in);
                    String id = pingReq.get("id").getAsString();
                    String rawJson = "{\"v\":1.0000000000000001,\"id\":\"" + id
                            + "\",\"op\":\"PONG\",\"ok\":true,\"data\":{\"status\":\"OK\"}}";
                    writeRawFrame(out, rawJson);
                } catch (IOException ignored) {
                }
            });

            client.connect("127.0.0.1", peer.getLocalPort(), "Amal");
            client.joinRoom("netprog");

            RoomClientException ex = assertThrows(RoomClientException.class, client::ping);
            assertTrue(ex.isConnectionInvalidated());
            assertFalse(client.isConnected());
            assertEquals(Optional.empty(), client.currentBinding());
            serverThread.join(2_000);
        }

        // Part 2: malformed JOIN response data (sessionId mismatch)
        try (ServerSocket peer = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
                RoomClient client = new RoomClient(StudyLinkConfig.srsDefaults(), 2_000, false)) {
            Thread serverThread = Thread.ofVirtual().start(() -> {
                try (Socket socket = peer.accept();
                        InputStream in = socket.getInputStream();
                        OutputStream out = socket.getOutputStream()) {
                    JsonObject helloReq = FrameCodec.readFrame(in);
                    FrameCodec.writeFrame(out, buildHelloSuccess(helloReq.get("id").getAsString(), "s-0001", "Amal"));

                    JsonObject joinReq = FrameCodec.readFrame(in);
                    // Wrong sessionId in JOIN_OK response
                    FrameCodec.writeFrame(out, buildJoinSuccess(joinReq.get("id").getAsString(), "s-9999", "netprog", 1L));
                } catch (IOException ignored) {
                }
            });

            client.connect("127.0.0.1", peer.getLocalPort(), "Amal");
            RoomClientException ex = assertThrows(RoomClientException.class, () -> client.joinRoom("netprog"));
            assertTrue(ex.isConnectionInvalidated());
            assertFalse(client.isConnected());
            assertEquals(Optional.empty(), client.currentBinding());
            serverThread.join(2_000);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void serverEofAndSocketReadTimeoutInvalidateClientCleanly() throws Exception {
        // Part 1: Server closes socket (EOF) on next request
        try (ServerSocket peer = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
                RoomClient client = new RoomClient(StudyLinkConfig.srsDefaults(), 2_000, false)) {
            Thread serverThread = Thread.ofVirtual().start(() -> {
                try (Socket socket = peer.accept();
                        InputStream in = socket.getInputStream();
                        OutputStream out = socket.getOutputStream()) {
                    JsonObject helloReq = FrameCodec.readFrame(in);
                    FrameCodec.writeFrame(out, buildHelloSuccess(helloReq.get("id").getAsString(), "s-0001", "Amal"));

                    JsonObject joinReq = FrameCodec.readFrame(in);
                    FrameCodec.writeFrame(out, buildJoinSuccess(joinReq.get("id").getAsString(), "s-0001", "netprog", 1L));

                    // Read PING and immediately close socket without replying
                    FrameCodec.readFrame(in);
                } catch (IOException ignored) {
                }
            });

            client.connect("127.0.0.1", peer.getLocalPort(), "Amal");
            client.joinRoom("netprog");

            RoomClientException eofEx = assertThrows(RoomClientException.class, client::ping);
            assertTrue(eofEx.isConnectionInvalidated());
            assertFalse(client.isConnected());
            assertEquals(Optional.empty(), client.currentBinding());
            serverThread.join(2_000);
        }

        // Part 2: Server stalls response beyond socketTimeoutMs
        try (ServerSocket peer = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
                RoomClient client = new RoomClient(StudyLinkConfig.srsDefaults(), 200, false)) {
            CountDownLatch releasePeer = new CountDownLatch(1);
            Thread serverThread = Thread.ofVirtual().start(() -> {
                try (Socket socket = peer.accept();
                        InputStream in = socket.getInputStream();
                        OutputStream out = socket.getOutputStream()) {
                    JsonObject helloReq = FrameCodec.readFrame(in);
                    FrameCodec.writeFrame(out, buildHelloSuccess(helloReq.get("id").getAsString(), "s-0001", "Amal"));

                    FrameCodec.readFrame(in);
                    releasePeer.await(3, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
            });

            client.connect("127.0.0.1", peer.getLocalPort(), "Amal");
            RoomClientException timeoutEx = assertThrows(RoomClientException.class, client::listRooms);
            releasePeer.countDown();
            assertTrue(timeoutEx.isConnectionInvalidated());
            assertFalse(client.isConnected());
            assertEquals(Optional.empty(), client.sessionInfo());
            serverThread.join(2_000);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void concurrentCloseUnblocksBlockedClientReadImmediately() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch releasePeer = new CountDownLatch(1);

        try (ServerSocket peer = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
                RoomClient client = new RoomClient(StudyLinkConfig.srsDefaults(), 10_000, false)) {
            Thread serverThread = Thread.ofVirtual().start(() -> {
                try (Socket socket = peer.accept();
                        InputStream in = socket.getInputStream();
                        OutputStream out = socket.getOutputStream()) {
                    JsonObject helloReq = FrameCodec.readFrame(in);
                    FrameCodec.writeFrame(out, buildHelloSuccess(helloReq.get("id").getAsString(), "s-0001", "Amal"));

                    // Read next request and hold without replying
                    JsonObject blockedReq = FrameCodec.readFrame(in);
                    assertNotNull(blockedReq);
                    requestReceived.countDown();
                    releasePeer.await(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
            });

            client.connect("127.0.0.1", peer.getLocalPort(), "Amal");

            ExecutorService callerExec = Executors.newSingleThreadExecutor();
            try {
                Future<List<RoomDefinition>> blockedFuture = callerExec.submit(client::listRooms);
                assertTrue(requestReceived.await(2, TimeUnit.SECONDS));

                long startNanos = System.nanoTime();
                client.close();
                assertThrows(Exception.class, () -> blockedFuture.get(1, TimeUnit.SECONDS));
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

                assertTrue(elapsedMs < 1_000L, "close() must unblock waiting reader promptly, took " + elapsedMs + " ms");
                assertFalse(client.isConnected());
            } finally {
                releasePeer.countDown();
                callerExec.shutdownNow();
                serverThread.join(2_000);
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void heartbeatOverlappingUserOperationSerializesCleanlyAndStaleGenerationCannotTouchNewConnection()
            throws Exception {
        StudyLinkConfig fastPingConfig = configWithTimers(35, 1_000);
        CountDownLatch joinHolding = new CountDownLatch(1);
        CountDownLatch releaseJoin = new CountDownLatch(1);
        AtomicInteger pingCountGen1 = new AtomicInteger();

        try (ServerSocket peer1 = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
                RoomClient client = new RoomClient(fastPingConfig, 3_000, true)) {
            Thread peer1Thread = Thread.ofVirtual().start(() -> {
                try (Socket socket = peer1.accept();
                        InputStream in = socket.getInputStream();
                        OutputStream out = socket.getOutputStream()) {
                    JsonObject req;
                    while ((req = FrameCodec.readFrame(in)) != null) {
                        String id = req.get("id").getAsString();
                        String op = req.get("op").getAsString();
                        switch (op) {
                            case RoomControlServer.OP_HELLO ->
                                FrameCodec.writeFrame(out, buildHelloSuccess(id, "s-0001", "Amal"));
                            case RoomControlServer.OP_JOIN -> {
                                // Hold JOIN long enough for automatic PING timer to fire and wait on exchangeLock
                                joinHolding.countDown();
                                releaseJoin.await(2, TimeUnit.SECONDS);
                                String roomId = req.getAsJsonObject("data").get("roomId").getAsString();
                                FrameCodec.writeFrame(out, buildJoinSuccess(id, "s-0001", roomId, 1L));
                            }
                            case RoomControlServer.OP_PING -> {
                                pingCountGen1.incrementAndGet();
                                FrameCodec.writeFrame(out, buildPongSuccess(id));
                            }
                            case RoomControlServer.OP_QUIT -> {
                                FrameCodec.writeFrame(out, buildByeSuccess(id));
                                return;
                            }
                            default -> throw new AssertionError("Unexpected op: " + op);
                        }
                    }
                } catch (Exception ignored) {
                }
            });

            client.connect("127.0.0.1", peer1.getLocalPort(), "Amal");
            long gen1 = client.activeGeneration();
            assertEquals(1L, gen1);

            ExecutorService joinExec = Executors.newSingleThreadExecutor();
            try {
                Future<RoomBinding> joinFuture = joinExec.submit(() -> client.joinRoom("netprog"));
                assertTrue(joinHolding.await(2, TimeUnit.SECONDS));

                // Wait longer than controlPingIntervalMs (35 ms) while JOIN holds exchangeLock
                Thread.sleep(90);
                releaseJoin.countDown();

                RoomBinding joined = joinFuture.get(2, TimeUnit.SECONDS);
                assertEquals(new RoomBinding("s-0001", "netprog", 1L), joined);

                // Allow queued heartbeat PING to complete and verify connection remains healthy
                Thread.sleep(70);
                assertTrue(pingCountGen1.get() >= 1, "Expected at least one background PING on generation 1");
                assertTrue(client.isConnected());
                assertEquals(Optional.of(joined), client.currentBinding());
            } finally {
                joinExec.shutdownNow();
            }

            // Now reconnect the same RoomClient instance to a real RoomControlServer (generation 2)
            // and verify generation 1 closure or stale tasks never invalidate generation 2.
            RoomSessionRegistry registry = new RoomSessionRegistry(fastPingConfig);
            try (RoomControlServer server2 = new RoomControlServer("127.0.0.1", 0, fastPingConfig, registry)) {
                server2.start();
                SessionInfo session2 = client.connect("127.0.0.1", server2.boundPort(), "Amal");
                long gen2 = client.activeGeneration();
                assertEquals(2L, gen2);
                assertEquals("Amal", session2.displayName());

                RoomBinding gen2Binding = client.joinRoom("revision");
                Thread.sleep(100);
                assertTrue(client.isConnected());
                assertEquals(Optional.of(gen2Binding), client.currentBinding());
                assertTrue(client.ping());
            }
            peer1Thread.join(2_000);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void fastConfiguredExpiryExpiresSilentSessionReleasesNameAndKeepsPingingClientAlive() throws Exception {
        StudyLinkConfig fastConfig = configWithTimers(40, 180);
        RoomSessionRegistry registry = new RoomSessionRegistry(fastConfig);

        try (RoomControlServer server = new RoomControlServer("127.0.0.1", 0, fastConfig, registry);
                RoomClient activeClient = new RoomClient(fastConfig, 2_000, true);
                RoomClient silentClient = new RoomClient(fastConfig, 2_000, false)) {
            server.start();

            SessionInfo activeSession = activeClient.connect("127.0.0.1", server.boundPort(), "Amal");
            RoomBinding activeBinding = activeClient.joinRoom("netprog");

            SessionInfo silentSession = silentClient.connect("127.0.0.1", server.boundPort(), "Kasun");
            RoomBinding silentBinding = silentClient.joinRoom("revision");

            assertTrue(registry.isCurrent(activeBinding));
            assertTrue(registry.isCurrent(silentBinding));

            // Wait for silent session to expire (~180 ms) while activeClient automatically pings every 40 ms
            assertTrue(
                    server.awaitSessionClosed(silentSession.sessionId(), 2, TimeUnit.SECONDS),
                    "Silent session must be expired by server reaper");

            assertFalse(registry.isCurrent(silentBinding), "Expired session RoomBinding must be invalidated");
            assertTrue(registry.findSession(silentSession.sessionId()).isEmpty());

            // Stay past 2x the expiry window to prove activeClient's automatic PINGs keep it alive
            Thread.sleep(220);
            assertTrue(activeClient.isConnected());
            assertTrue(registry.isCurrent(activeBinding));
            assertEquals(3, activeClient.listRooms().size());

            // Expired display name "Kasun" is released for reuse
            try (RoomClient reclaimedClient = new RoomClient(fastConfig, 2_000, false)) {
                SessionInfo reclaimedSession = reclaimedClient.connect("127.0.0.1", server.boundPort(), "Kasun");
                assertNotEquals(silentSession.sessionId(), reclaimedSession.sessionId());
                assertEquals("Kasun", reclaimedSession.displayName());
            }
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void defaultTimingFiveSecondPingAndTwentySecondExpiryLoopbackObservation() throws Exception {
        StudyLinkConfig defaultConfig = StudyLinkConfig.srsDefaults();
        assertEquals(5_000, defaultConfig.controlPingIntervalMs());
        assertEquals(20_000, defaultConfig.controlSessionExpiryMs());

        RoomSessionRegistry registry = new RoomSessionRegistry(defaultConfig);

        try (RoomControlServer server = new RoomControlServer("127.0.0.1", 0, defaultConfig, registry);
                RoomClient healthyClient = new RoomClient(defaultConfig, 3_000, true);
                RoomClient silentClient = new RoomClient(defaultConfig, 3_000, false)) {
            server.start();

            SessionInfo healthySession = healthyClient.connect("127.0.0.1", server.boundPort(), "Amal");
            RoomBinding healthyBinding = healthyClient.joinRoom("netprog");

            SessionInfo silentSession = silentClient.connect("127.0.0.1", server.boundPort(), "SilentPeer");
            RoomBinding silentOldBinding = silentClient.joinRoom("revision");
            long silentLastActivityNanos = System.nanoTime();

            assertTrue(registry.isCurrent(healthyBinding));
            assertTrue(registry.isCurrent(silentOldBinding));

            // Wait for server reaper to expire the silent session after the 20,000 ms default window
            boolean closed = server.awaitSessionClosed(silentSession.sessionId(), 22, TimeUnit.SECONDS);
            long elapsedNanos = System.nanoTime() - silentLastActivityNanos;
            double elapsedSeconds = elapsedNanos / 1_000_000_000.0;

            assertTrue(closed, "SilentPeer session must expire within 22s wait");
            assertTrue(
                    elapsedSeconds >= 20.0 && elapsedSeconds < 21.0,
                    String.format(
                            Locale.ROOT,
                            "Expected expiry between 20.0s and 21.0s (SRS Q07/T10), measured %.3fs",
                            elapsedSeconds));

            boolean oldBindingCurrent = registry.isCurrent(silentOldBinding);
            assertFalse(oldBindingCurrent, "SilentPeer old RoomBinding must be invalidated after expiry");

            // Verify display name "SilentPeer" can now be reused by a new client connection
            String reusedSessionId;
            try (RoomClient reuseClient = new RoomClient(defaultConfig, 3_000, false)) {
                SessionInfo reusedSession = reuseClient.connect("127.0.0.1", server.boundPort(), "SilentPeer");
                reusedSessionId = reusedSession.sessionId();
                assertNotEquals(silentSession.sessionId(), reusedSessionId);
            }

            // Verify the healthy client (which sent automatic PINGs every 5,000 ms) is still alive and responsive
            assertTrue(healthyClient.isConnected());
            assertTrue(registry.isCurrent(healthyBinding));
            assertTrue(healthyClient.ping());
            List<RoomDefinition> healthyRooms = healthyClient.listRooms();
            assertEquals(3, healthyRooms.size());

            System.out.printf(
                    Locale.ROOT,
                    "[M1-04 Default-Timing Observation] configuredPingMs=%d, configuredExpiryMs=%d, "
                            + "measuredExpirySeconds=%.3f, oldBindingCurrent=%s, oldSessionId=%s, "
                            + "reusedNameNewSessionId=%s, healthySessionId=%s, healthyPostExpiryRooms=%d%n",
                    defaultConfig.controlPingIntervalMs(),
                    defaultConfig.controlSessionExpiryMs(),
                    elapsedSeconds,
                    oldBindingCurrent,
                    silentSession.sessionId(),
                    reusedSessionId,
                    healthySession.sessionId(),
                    healthyRooms.size());
        }
    }

    private static StudyLinkConfig configWithTimers(int pingIntervalMs, int expiryMs) {
        StudyLinkConfig d = StudyLinkConfig.srsDefaults();
        return new StudyLinkConfig(
                d.serverId(),
                d.serverHost(),
                d.networkInterface(),
                d.discoveryPort(),
                d.roomsPort(),
                d.chatPort(),
                d.filesPort(),
                d.notesPort(),
                d.dataDir(),
                d.tlsProtocol(),
                d.tlsServerStore(),
                d.tlsClientTrustStore(),
                d.rooms(),
                d.maxFrameBytes(),
                d.maxUdpBytes(),
                d.maxChatTextBytes(),
                d.maxNoteTextBytes(),
                d.maxFileSizeBytes(),
                d.fileWorkerThreads(),
                d.fileQueueCapacity(),
                d.maxSlowClientQueueBytes(),
                d.maxInboxNotesPerRoom(),
                d.maxFilesPerRoom(),
                d.discoveryTimeoutMs(),
                d.heartbeatIntervalMs(),
                d.presenceExpiryMs(),
                d.presenceHideMs(),
                pingIntervalMs,
                expiryMs);
    }

    private static JsonObject buildHelloSuccess(String id, String sessionId, String displayName) {
        JsonObject ports = new JsonObject();
        ports.addProperty("discoveryUdp", 45400);
        ports.addProperty("roomsTcp", 45401);
        ports.addProperty("chatNioTcp", 45402);
        ports.addProperty("filesTcp", 45403);
        ports.addProperty("notesTlsTcp", 45404);

        JsonObject data = new JsonObject();
        data.addProperty("sessionId", sessionId);
        data.addProperty("displayName", displayName);
        data.add("ports", ports);
        return buildSuccess(id, data);
    }

    private static JsonObject buildJoinSuccess(String id, String sessionId, String roomId, long revision) {
        JsonObject data = new JsonObject();
        data.addProperty("sessionId", sessionId);
        data.addProperty("roomId", roomId);
        data.addProperty("revision", revision);
        return buildSuccess(id, data);
    }

    private static JsonObject buildPongSuccess(String id) {
        JsonObject data = new JsonObject();
        data.addProperty("sessionId", "s-0001");
        data.addProperty("pong", true);
        return buildSuccess(id, data);
    }

    private static JsonObject buildByeSuccess(String id) {
        JsonObject data = new JsonObject();
        data.addProperty("closed", true);
        return buildSuccess(id, data);
    }

    private static JsonObject buildSuccess(String id, JsonObject data) {
        JsonObject resp = new JsonObject();
        resp.addProperty("v", 1);
        resp.addProperty("id", id);
        resp.addProperty("ok", true);
        resp.add("data", data);
        return resp;
    }

    private static void writeRawFrame(OutputStream out, String rawUtf8Json) throws IOException {
        byte[] payload = rawUtf8Json.getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[4 + payload.length];
        ByteBuffer.wrap(frame, 0, 4).putInt(payload.length);
        System.arraycopy(payload, 0, frame, 4, payload.length);
        out.write(frame);
        out.flush();
    }
}
