package lk.studylink.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lk.studylink.shared.model.ChatEvent;
import lk.studylink.shared.model.RoomBinding;
import lk.studylink.shared.protocol.ChatProtocol;
import lk.studylink.shared.protocol.FrameCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for {@link ServerMain} CLI diagnostics, live TCP room-control launcher lifecycle
 * (startup, {@code HELLO}/{@code LIST_ROOMS}, occupied-port failure, shutdown, and port reuse),
 * and bounded loopback socket smoke checks on JDK 21.
 */
class ServerStarterAndSocketSmokeTest {

    private static final int SOCKET_TIMEOUT_MS = 3_000;

    @TempDir
    Path tempDir;

    @Test
    void serverMainHelpAndCheckConfigWorkHeadlessly() throws IOException {
        Path configFile = writeValidTempConfig(45401);

        ByteArrayOutputStream helpOut = new ByteArrayOutputStream();
        ByteArrayOutputStream helpErr = new ByteArrayOutputStream();
        int helpCode = ServerMain.run(
                new String[]{"--help"},
                new PrintStream(helpOut, true, StandardCharsets.UTF_8),
                new PrintStream(helpErr, true, StandardCharsets.UTF_8)
        );
        assertEquals(0, helpCode);
        assertTrue(helpOut.toString(StandardCharsets.UTF_8).contains("--check-config"));

        ByteArrayOutputStream checkOut = new ByteArrayOutputStream();
        ByteArrayOutputStream checkErr = new ByteArrayOutputStream();
        int checkCode = ServerMain.run(
                new String[]{"--config", configFile.toString(), "--check-config"},
                new PrintStream(checkOut, true, StandardCharsets.UTF_8),
                new PrintStream(checkErr, true, StandardCharsets.UTF_8)
        );
        assertEquals(0, checkCode);
        JsonObject summary = JsonParser.parseString(checkOut.toString(StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("OK", summary.get("status").getAsString());
        assertEquals("studylink-server", summary.get("component").getAsString());
    }

    @Test
    void serverMainRejectsMissingOrInvalidConfig() throws IOException {
        Path missing = tempDir.resolve("missing.properties");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int missingCode = ServerMain.run(
                new String[]{"--config", missing.toString(), "--check-config"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)
        );
        assertNotEquals(0, missingCode);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Configuration check failed"));

        Path invalid = tempDir.resolve("invalid.properties");
        Files.writeString(
                invalid,
                "server.host=127.0.0.1\nport.discovery=45400\nport.rooms=99999\n",
                StandardCharsets.UTF_8
        );
        ByteArrayOutputStream invalidErr = new ByteArrayOutputStream();
        int invalidCode = ServerMain.run(
                new String[]{"--config", invalid.toString(), "--check-config"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(invalidErr, true, StandardCharsets.UTF_8)
        );
        assertNotEquals(0, invalidCode);
        assertTrue(invalidErr.toString(StandardCharsets.UTF_8).contains("port.rooms"));
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.SECONDS)
    void serverMainNormalLaunchServesHelloAndListRoomsRejectsOccupiedPortAndReleasesPortOnShutdown()
            throws Exception {
        int[] ports = reserveDistinctLoopbackPorts(5);
        int roomsPort = ports[1];
        int chatPort = ports[2];
        Path configFile = writeTempConfigWithPorts("launcher-live.properties", ports);
        String expectedRoomsEndpoint = "127.0.0.1:" + roomsPort;
        String expectedChatEndpoint = "127.0.0.1:" + chatPort;

        ManagedServerProcess firstServer = startServerSubprocess(configFile);
        try {
            assertTrue(
                    firstServer.awaitListening(expectedRoomsEndpoint, expectedChatEndpoint, 8, TimeUnit.SECONDS),
                    () -> "Expected server subprocess to report listening on " + expectedRoomsEndpoint
                            + " and " + expectedChatEndpoint
                            + ", output was:\n" + firstServer.capturedOutput()
            );
            assertTrue(firstServer.process().isAlive(), "Server process must stay alive after startup");

            // 1. Verify HELLO, LIST_ROOMS, JOIN, and live NIO chat over real loopback TCP sockets
            verifyHelloListRoomsAndLiveChat(roomsPort, chatPort, "Amal");

            // 2. While firstServer is still holding roomsPort, a second launch on the same port must fail clearly
            ManagedServerProcess occupiedServer = startServerSubprocess(configFile);
            try {
                assertTrue(
                        occupiedServer.process().waitFor(8, TimeUnit.SECONDS),
                        "Occupied-port launch must exit promptly without hanging"
                );
                int bindExitCode = occupiedServer.process().exitValue();
                assertEquals(1, bindExitCode, "Occupied-port bind failure must return exit code 1");
                assertTrue(
                        occupiedServer.awaitOutputComplete(3, TimeUnit.SECONDS),
                        "Timed out waiting for occupied-port child output reader to finish"
                );
                String conflictOutput = occupiedServer.capturedOutput();
                assertTrue(
                        conflictOutput.contains("Failed to bind TCP room-control service on " + expectedRoomsEndpoint),
                        () -> "Expected bind failure message mentioning " + expectedRoomsEndpoint
                                + ", got:\n" + conflictOutput
                );
            } finally {
                occupiedServer.close();
            }

            // Original server must still be alive and serving requests after the failed second launch
            assertTrue(firstServer.process().isAlive());
            verifyHelloListRoomsAndLiveChat(roomsPort, chatPort, "Kasun");

            // 3. Stop firstServer and verify it terminates and releases both roomsPort and chatPort for reuse
            firstServer.stopAndAwait(5, TimeUnit.SECONDS);
            assertFalse(firstServer.process().isAlive(), "First server process must have terminated");
        } finally {
            firstServer.close();
        }

        // 4. Port reuse: launch a fresh server subprocess on the exact same config, roomsPort, and chatPort
        ManagedServerProcess reusedServer = startServerSubprocess(configFile);
        try {
            assertTrue(
                    reusedServer.awaitListening(expectedRoomsEndpoint, expectedChatEndpoint, 8, TimeUnit.SECONDS),
                    () -> "Expected restarted server to bind reusable ports " + expectedRoomsEndpoint
                            + " and " + expectedChatEndpoint
                            + ", output was:\n" + reusedServer.capturedOutput()
            );
            assertTrue(reusedServer.process().isAlive());
            verifyHelloListRoomsAndLiveChat(roomsPort, chatPort, "Nimal");
            reusedServer.stopAndAwait(5, TimeUnit.SECONDS);
            assertFalse(reusedServer.process().isAlive());
        } finally {
            reusedServer.close();
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void serverMainInProcessRunServesControlRequestsRollsBackOccupiedChatPortAndReleasesPortsOnStopSignal()
            throws Exception {
        int[] ports = reserveDistinctLoopbackPorts(5);
        int roomsPort = ports[1];
        int chatPort = ports[2];
        Path configFile = writeTempConfigWithPorts("inprocess-live.properties", ports);

        // 1. Pre-occupy chatPort while leaving roomsPort free: ServerMain must close RoomControlServer and return 1
        try (ServerSocket occupiedChatSocket = new ServerSocket(
                chatPort,
                10,
                InetAddress.getByName("127.0.0.1")
        )) {
            assertTrue(occupiedChatSocket.isBound());
            ByteArrayOutputStream failOut = new ByteArrayOutputStream();
            ByteArrayOutputStream failErr = new ByteArrayOutputStream();
            int failCode = ServerMain.run(
                    new String[]{"--config", configFile.toString()},
                    new PrintStream(failOut, true, StandardCharsets.UTF_8),
                    new PrintStream(failErr, true, StandardCharsets.UTF_8)
            );
            assertEquals(1, failCode, "Partial startup failure when chatPort is occupied must return exit code 1");
            String errText = failErr.toString(StandardCharsets.UTF_8);
            assertTrue(
                    errText.contains("Failed to bind NIO live room-chat service on 127.0.0.1:" + chatPort),
                    () -> "Expected occupied chat port error message, got:\n" + errText
            );

            // Verify roomsPort was rolled back and released immediately after chatPort bind failure
            try (ServerSocket probeRoomsPort = new ServerSocket(
                    roomsPort,
                    10,
                    InetAddress.getByName("127.0.0.1")
            )) {
                assertTrue(probeRoomsPort.isBound(), "roomsPort must be released when chatPort fails to bind");
            }
        }

        // 2. Now both ports are free: run ServerMain in-process and verify both listeners start and stop cleanly
        CountDownLatch listeningLatch = new CountDownLatch(1);
        CountDownLatch stopSignal = new CountDownLatch(1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        ExecutorService runner = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> exitCodeFuture = runner.submit(() -> ServerMain.run(
                    new String[]{"--config", configFile.toString()},
                    new PrintStream(out, true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8),
                    stopSignal,
                    addr -> listeningLatch.countDown()
            ));

            assertTrue(listeningLatch.await(3, TimeUnit.SECONDS));
            verifyHelloListRoomsAndLiveChat(roomsPort, chatPort, "Amal");

            stopSignal.countDown();
            assertEquals(0, exitCodeFuture.get(3, TimeUnit.SECONDS));
            String stdout = out.toString(StandardCharsets.UTF_8);
            assertTrue(stdout.contains("TCP room-control service listening on 127.0.0.1:" + roomsPort));
            assertTrue(stdout.contains("NIO live room-chat service listening on 127.0.0.1:" + chatPort));
            assertTrue(stdout.contains("TCP room-control service stopped (127.0.0.1:" + roomsPort + ")."));
            assertTrue(stdout.contains("NIO live room-chat service stopped (127.0.0.1:" + chatPort + ")."));
        } finally {
            stopSignal.countDown();
            runner.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void boundedLoopbackTcpAndUdpSocketSmokeTestOnEphemeralPort() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        byte[] payload = "studylink-loopback-smoke".getBytes(StandardCharsets.UTF_8);

        // 1. Bounded TCP loopback exchange on ephemeral port (0)
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.bind(new InetSocketAddress(loopback, 0));
            serverSocket.setSoTimeout(2_000);
            int ephemeralTcpPort = serverSocket.getLocalPort();
            assertTrue(ephemeralTcpPort > 0);

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<byte[]> serverRead = executor.submit(() -> {
                    try (Socket accepted = serverSocket.accept()) {
                        accepted.setSoTimeout(2_000);
                        DataInputStream in = new DataInputStream(accepted.getInputStream());
                        DataOutputStream out = new DataOutputStream(accepted.getOutputStream());
                        int length = in.readInt();
                        byte[] received = in.readNBytes(length);
                        out.writeInt(received.length);
                        out.write(received);
                        out.flush();
                        return received;
                    }
                });

                try (Socket clientSocket = new Socket()) {
                    clientSocket.connect(new InetSocketAddress(loopback, ephemeralTcpPort), 2_000);
                    clientSocket.setSoTimeout(2_000);
                    DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream());
                    DataInputStream in = new DataInputStream(clientSocket.getInputStream());

                    out.writeInt(payload.length);
                    out.write(payload);
                    out.flush();

                    int echoLength = in.readInt();
                    byte[] echoed = in.readNBytes(echoLength);
                    assertArrayEquals(payload, echoed);
                }

                assertArrayEquals(payload, serverRead.get(2, TimeUnit.SECONDS));
            } finally {
                executor.shutdownNow();
            }
        }

        // 2. Bounded UDP loopback exchange on ephemeral port (0)
        try (DatagramSocket receiver = new DatagramSocket(new InetSocketAddress(loopback, 0));
             DatagramSocket sender = new DatagramSocket(new InetSocketAddress(loopback, 0))) {
            receiver.setSoTimeout(2_000);
            DatagramPacket outbound = new DatagramPacket(
                    payload,
                    payload.length,
                    loopback,
                    receiver.getLocalPort()
            );
            sender.send(outbound);

            byte[] buffer = new byte[128];
            DatagramPacket inbound = new DatagramPacket(buffer, buffer.length);
            receiver.receive(inbound);
            byte[] receivedBytes = new byte[inbound.getLength()];
            System.arraycopy(inbound.getData(), inbound.getOffset(), receivedBytes, 0, inbound.getLength());
            assertArrayEquals(payload, receivedBytes);
        }
    }

    private static void verifyHelloListRoomsAndLiveChat(int roomsPort, int chatPort, String displayName)
            throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", roomsPort), SOCKET_TIMEOUT_MS);
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            socket.setTcpNoDelay(true);

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            JsonObject helloReq = new JsonObject();
            helloReq.addProperty("v", 1);
            helloReq.addProperty("id", "hello-" + displayName);
            helloReq.addProperty("op", "HELLO");
            JsonObject helloData = new JsonObject();
            helloData.addProperty("displayName", displayName);
            helloReq.add("data", helloData);

            FrameCodec.writeFrame(out, helloReq);
            JsonObject helloResp = FrameCodec.readFrame(in);
            assertNotNull(helloResp);
            assertTrue(helloResp.get("ok").getAsBoolean());
            assertEquals("hello-" + displayName, helloResp.get("id").getAsString());

            JsonObject helloBody = helloResp.getAsJsonObject("data");
            String sessionId = helloBody.get("sessionId").getAsString();
            assertTrue(sessionId.startsWith("s-"));
            assertEquals(displayName, helloBody.get("displayName").getAsString());
            assertEquals(roomsPort, helloBody.getAsJsonObject("ports").get("roomsTcp").getAsInt());
            assertEquals(chatPort, helloBody.getAsJsonObject("ports").get("chatNioTcp").getAsInt());

            JsonObject listReq = new JsonObject();
            listReq.addProperty("v", 1);
            listReq.addProperty("id", "list-" + displayName);
            listReq.addProperty("op", "LIST_ROOMS");
            listReq.add("data", new JsonObject());

            FrameCodec.writeFrame(out, listReq);
            JsonObject listResp = FrameCodec.readFrame(in);
            assertNotNull(listResp);
            assertTrue(listResp.get("ok").getAsBoolean());
            assertEquals("list-" + displayName, listResp.get("id").getAsString());

            JsonArray rooms = listResp.getAsJsonObject("data").getAsJsonArray("rooms");
            assertEquals(3, rooms.size());
            assertEquals("netprog", rooms.get(0).getAsJsonObject().get("id").getAsString());
            assertEquals("revision", rooms.get(1).getAsJsonObject().get("id").getAsString());
            assertEquals("workshop", rooms.get(2).getAsJsonObject().get("id").getAsString());

            // Join "netprog" so the shared RoomSessionRegistry authorizes CHAT_SUBSCRIBE on chatPort
            JsonObject joinReq = new JsonObject();
            joinReq.addProperty("v", 1);
            joinReq.addProperty("id", "join-" + displayName);
            joinReq.addProperty("op", "JOIN");
            JsonObject joinData = new JsonObject();
            joinData.addProperty("sessionId", sessionId);
            joinData.addProperty("roomId", "netprog");
            joinReq.add("data", joinData);

            FrameCodec.writeFrame(out, joinReq);
            JsonObject joinResp = FrameCodec.readFrame(in);
            assertNotNull(joinResp);
            assertTrue(joinResp.get("ok").getAsBoolean());
            long revision = joinResp.getAsJsonObject("data").get("revision").getAsLong();
            assertEquals(1L, revision);

            try (Socket chatSocket = new Socket()) {
                chatSocket.connect(new InetSocketAddress("127.0.0.1", chatPort), SOCKET_TIMEOUT_MS);
                chatSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
                chatSocket.setTcpNoDelay(true);

                InputStream chatIn = chatSocket.getInputStream();
                OutputStream chatOut = chatSocket.getOutputStream();

                RoomBinding binding = new RoomBinding(sessionId, "netprog", revision);
                JsonObject subReq = ChatProtocol.buildSubscribeRequest("sub-" + displayName, binding);
                FrameCodec.writeFrame(chatOut, subReq);
                JsonObject subResp = FrameCodec.readFrame(chatIn);
                assertNotNull(subResp);
                assertTrue(subResp.get("ok").getAsBoolean(), () -> "SUBSCRIBE failed: " + subResp);

                String expectedText = "smoke-chat-" + displayName;
                JsonObject sendReq = ChatProtocol.buildChatSendRequest("send-" + displayName, expectedText);
                FrameCodec.writeFrame(chatOut, sendReq);

                // Read frames until both the CHAT_SEND acknowledgment and CHAT_EVENT push arrive
                boolean sawSendAck = false;
                boolean sawChatEvent = false;
                for (int i = 0; i < 2; i++) {
                    JsonObject frame = FrameCodec.readFrame(chatIn);
                    assertNotNull(frame);
                    if (frame.has("ok") && ("send-" + displayName).equals(frame.get("id").getAsString())) {
                        assertTrue(frame.get("ok").getAsBoolean(), () -> "CHAT_SEND failed: " + frame);
                        sawSendAck = true;
                    } else if (ChatProtocol.OP_CHAT_EVENT.equals(frame.get("op").getAsString())) {
                        ChatEvent event = ChatProtocol.parseChatEventFrame(frame);
                        assertEquals("netprog", event.roomId());
                        assertEquals(displayName, event.senderName());
                        assertEquals(expectedText, event.text());
                        sawChatEvent = true;
                    }
                }
                assertTrue(sawSendAck, "Expected CHAT_SEND ack from ChatServer");
                assertTrue(sawChatEvent, "Expected CHAT_EVENT broadcast from ChatServer");
            }

            JsonObject quitReq = new JsonObject();
            quitReq.addProperty("v", 1);
            quitReq.addProperty("id", "quit-" + displayName);
            quitReq.addProperty("op", "QUIT");
            quitReq.add("data", new JsonObject());
            FrameCodec.writeFrame(out, quitReq);
            JsonObject quitResp = FrameCodec.readFrame(in);
            assertNotNull(quitResp);
            assertTrue(quitResp.get("ok").getAsBoolean());
        }
    }

    private static int[] reserveDistinctLoopbackPorts(int count) throws IOException {
        List<ServerSocket> sockets = new ArrayList<>(count);
        Set<Integer> chosen = new HashSet<>();
        int[] ports = new int[count];
        try {
            for (int i = 0; i < count; i++) {
                ServerSocket ss = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
                sockets.add(ss);
                int port = ss.getLocalPort();
                if (!chosen.add(port)) {
                    throw new IOException("Duplicate ephemeral port allocated: " + port);
                }
                ports[i] = port;
            }
        } finally {
            for (ServerSocket ss : sockets) {
                try {
                    ss.close();
                } catch (IOException ignored) {
                }
            }
        }
        return ports;
    }

    private Path writeValidTempConfig(int roomsPort) throws IOException {
        return writeTempConfigWithPorts(
                "studylink.test.properties",
                new int[]{45400, roomsPort, 45402, 45403, 45404}
        );
    }

    private Path writeTempConfigWithPorts(String fileName, int[] ports) throws IOException {
        Path file = tempDir.resolve(fileName);
        String content = """
                server.id=studylink-test
                server.host=127.0.0.1
                network.interface=loopback
                port.discovery=%d
                port.rooms=%d
                port.chat=%d
                port.files=%d
                port.notes=%d
                data.dir=var/room-files
                tls.protocol=TLSv1.3
                tls.serverStore=local/host.p12
                tls.clientTrustStore=local/host-trust.p12
                """.formatted(ports[0], ports[1], ports[2], ports[3], ports[4]);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static ManagedServerProcess startServerSubprocess(Path configFile) throws IOException {
        String javaBin = ProcessHandle.current()
                .info()
                .command()
                .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin", "java").toString());
        String classpath = System.getProperty("java.class.path");

        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-cp",
                classpath,
                ServerMain.class.getName(),
                "--config",
                configFile.toString()
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();
        return new ManagedServerProcess(process);
    }

    private static final class ManagedServerProcess implements AutoCloseable {
        private final Process process;
        private final StringBuffer outputBuffer = new StringBuffer();
        private final CountDownLatch listeningLatch = new CountDownLatch(1);
        private final CountDownLatch outputCompleteLatch = new CountDownLatch(1);
        private final Thread outputReaderThread;
        private volatile String expectedRoomsEndpoint;
        private volatile String expectedChatEndpoint;

        private ManagedServerProcess(Process process) {
            this.process = process;
            this.outputReaderThread = Thread.ofVirtual().start(this::consumeOutput);
        }

        private void consumeOutput() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputBuffer.append(line).append(System.lineSeparator());
                    if (hasBothListeningLines(expectedRoomsEndpoint, expectedChatEndpoint)) {
                        listeningLatch.countDown();
                    }
                }
            } catch (IOException ignored) {
            } finally {
                outputCompleteLatch.countDown();
            }
        }

        private boolean hasBothListeningLines(String roomsEndpoint, String chatEndpoint) {
            String text = outputBuffer.toString();
            boolean roomsOk = (roomsEndpoint == null)
                    ? text.contains("TCP room-control service listening on ")
                    : text.contains("TCP room-control service listening on " + roomsEndpoint);
            boolean chatOk = (chatEndpoint == null)
                    ? text.contains("NIO live room-chat service listening on ")
                    : text.contains("NIO live room-chat service listening on " + chatEndpoint);
            return roomsOk && chatOk;
        }

        private Process process() {
            return process;
        }

        private boolean awaitListening(
                String roomsEndpoint,
                String chatEndpoint,
                long timeout,
                TimeUnit unit
        ) throws InterruptedException {
            this.expectedRoomsEndpoint = roomsEndpoint;
            this.expectedChatEndpoint = chatEndpoint;
            if (hasBothListeningLines(roomsEndpoint, chatEndpoint)) {
                return true;
            }
            return listeningLatch.await(timeout, unit) && hasBothListeningLines(roomsEndpoint, chatEndpoint);
        }

        private boolean awaitOutputComplete(long timeout, TimeUnit unit) throws InterruptedException {
            return outputCompleteLatch.await(timeout, unit);
        }

        private String capturedOutput() {
            return outputBuffer.toString();
        }

        private void stopAndAwait(long timeout, TimeUnit unit) throws InterruptedException {
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(timeout, unit)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            }
            outputReaderThread.join(1_000);
        }

        @Override
        public void close() {
            try {
                if (process.isAlive()) {
                    process.destroy();
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        process.waitFor(2, TimeUnit.SECONDS);
                    }
                }
                outputReaderThread.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
