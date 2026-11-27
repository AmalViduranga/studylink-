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
        Path configFile = writeTempConfigWithPorts("launcher-live.properties", ports);
        String expectedEndpoint = "127.0.0.1:" + roomsPort;

        ManagedServerProcess firstServer = startServerSubprocess(configFile);
        try {
            assertTrue(
                    firstServer.awaitListening(expectedEndpoint, 8, TimeUnit.SECONDS),
                    () -> "Expected server subprocess to report listening on " + expectedEndpoint
                            + ", output was:\n" + firstServer.capturedOutput()
            );
            assertTrue(firstServer.process().isAlive(), "Server process must stay alive after startup");

            // 1. Verify HELLO and LIST_ROOMS over a real loopback TCP socket
            verifyHelloAndListRooms(roomsPort, "Amal");

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
                        conflictOutput.contains("Failed to bind TCP room-control service on " + expectedEndpoint),
                        () -> "Expected bind failure message mentioning " + expectedEndpoint
                                + ", got:\n" + conflictOutput
                );
            } finally {
                occupiedServer.close();
            }

            // Original server must still be alive and serving requests after the failed second launch
            assertTrue(firstServer.process().isAlive());
            verifyHelloAndListRooms(roomsPort, "Kasun");

            // 3. Stop firstServer and verify it terminates and releases roomsPort for reuse
            firstServer.stopAndAwait(5, TimeUnit.SECONDS);
            assertFalse(firstServer.process().isAlive(), "First server process must have terminated");
        } finally {
            firstServer.close();
        }

        // 4. Port reuse: launch a fresh server subprocess on the exact same config and roomsPort
        ManagedServerProcess reusedServer = startServerSubprocess(configFile);
        try {
            assertTrue(
                    reusedServer.awaitListening(expectedEndpoint, 8, TimeUnit.SECONDS),
                    () -> "Expected restarted server to bind reusable port " + expectedEndpoint
                            + ", output was:\n" + reusedServer.capturedOutput()
            );
            assertTrue(reusedServer.process().isAlive());
            verifyHelloAndListRooms(roomsPort, "Nimal");
            reusedServer.stopAndAwait(5, TimeUnit.SECONDS);
            assertFalse(reusedServer.process().isAlive());
        } finally {
            reusedServer.close();
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void serverMainInProcessRunServesControlRequestsAndReleasesPortOnStopSignal() throws Exception {
        int[] ports = reserveDistinctLoopbackPorts(5);
        int roomsPort = ports[1];
        Path configFile = writeTempConfigWithPorts("inprocess-live.properties", ports);

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
            verifyHelloAndListRooms(roomsPort, "Amal");

            stopSignal.countDown();
            assertEquals(0, exitCodeFuture.get(3, TimeUnit.SECONDS));
            String stdout = out.toString(StandardCharsets.UTF_8);
            assertTrue(stdout.contains("TCP room-control service listening on 127.0.0.1:" + roomsPort));
            assertTrue(stdout.contains("TCP room-control service stopped (127.0.0.1:" + roomsPort + ")."));
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

    private static void verifyHelloAndListRooms(int roomsPort, String displayName) throws IOException {
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
            assertTrue(helloBody.get("sessionId").getAsString().startsWith("s-"));
            assertEquals(displayName, helloBody.get("displayName").getAsString());
            assertEquals(roomsPort, helloBody.getAsJsonObject("ports").get("roomsTcp").getAsInt());

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
        private volatile String expectedEndpoint;

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
                    String endpoint = this.expectedEndpoint;
                    if (line.contains("TCP room-control service listening on ")
                            && (endpoint == null || line.contains(endpoint))) {
                        listeningLatch.countDown();
                    }
                }
            } catch (IOException ignored) {
            } finally {
                outputCompleteLatch.countDown();
            }
        }

        private Process process() {
            return process;
        }

        private boolean awaitListening(String endpoint, long timeout, TimeUnit unit) throws InterruptedException {
            this.expectedEndpoint = endpoint;
            if (outputBuffer.toString().contains("TCP room-control service listening on " + endpoint)) {
                return true;
            }
            return listeningLatch.await(timeout, unit);
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
