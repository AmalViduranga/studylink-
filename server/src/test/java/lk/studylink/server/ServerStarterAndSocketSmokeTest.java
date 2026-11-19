package lk.studylink.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for {@link ServerMain} CLI diagnostics and a bounded loopback socket smoke test
 * using ephemeral ports to verify the local Java 21 networking test environment.
 */
class ServerStarterAndSocketSmokeTest {

    @TempDir
    Path tempDir;

    @Test
    void serverMainHelpAndCheckConfigWorkHeadlessly() throws IOException {
        Path configFile = writeValidTempConfig();

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
    void serverMainNormalStarterLaunchExplainsPlannedListenersAndExitsCleanly() throws IOException {
        Path configFile = writeValidTempConfig();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = ServerMain.run(
                new String[]{"--config", configFile.toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)
        );
        assertEquals(0, code);
        String output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Planned Network Service Listeners"));
        assertTrue(output.contains("45401"));
        assertTrue(output.contains("Exiting cleanly"));
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

    private Path writeValidTempConfig() throws IOException {
        Path file = tempDir.resolve("studylink.test.properties");
        String content = """
                server.id=studylink-test
                server.host=127.0.0.1
                network.interface=loopback
                port.discovery=45400
                port.rooms=45401
                port.chat=45402
                port.files=45403
                port.notes=45404
                data.dir=var/room-files
                tls.protocol=TLSv1.3
                tls.serverStore=local/host.p12
                tls.clientTrustStore=local/host-trust.p12
                """;
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
