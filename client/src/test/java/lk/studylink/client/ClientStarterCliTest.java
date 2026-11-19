package lk.studylink.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for {@link DesktopMain} command-line handling ({@code --help}, {@code --check-config}
 * and invalid configuration rejection).
 */
class ClientStarterCliTest {

    @TempDir
    Path tempDir;

    @Test
    void desktopMainHelpRunsHeadlessly() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = DesktopMain.run(
                new String[]{"--help"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                false
        );

        assertEquals(0, exitCode);
        String output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("lk.studylink.client.DesktopMain"));
        assertTrue(output.contains("--check-config"));
    }

    @Test
    void desktopMainCheckConfigPrintsValidGsonSummaryHeadlessly() throws IOException {
        Path configFile = tempDir.resolve("client-valid.properties");
        Files.writeString(configFile, """
                server.id=studylink-local
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
                """, StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = DesktopMain.run(
                new String[]{"--config", configFile.toString(), "--check-config"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                false
        );

        assertEquals(0, exitCode);
        JsonObject summary = JsonParser.parseString(out.toString(StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("OK", summary.get("status").getAsString());
        assertEquals("studylink-client", summary.get("component").getAsString());
        assertEquals(45404, summary.getAsJsonObject("ports").get("notesTlsTcp").getAsInt());
    }

    @Test
    void desktopMainExitsNonZeroOnMissingOrInvalidConfig() throws IOException {
        Path missing = tempDir.resolve("missing-client.properties");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int missingCode = DesktopMain.run(
                new String[]{"--config", missing.toString(), "--check-config"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                false
        );
        assertNotEquals(0, missingCode);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Configuration check failed"));

        Path invalid = tempDir.resolve("invalid-client.properties");
        Files.writeString(invalid, "server.host=127.0.0.1\nport.discovery=0\n", StandardCharsets.UTF_8);
        ByteArrayOutputStream invalidErr = new ByteArrayOutputStream();
        int invalidCode = DesktopMain.run(
                new String[]{"--config", invalid.toString(), "--check-config"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(invalidErr, true, StandardCharsets.UTF_8),
                false
        );
        assertNotEquals(0, invalidCode);
        assertTrue(invalidErr.toString(StandardCharsets.UTF_8).contains("port.discovery"));
    }
}
