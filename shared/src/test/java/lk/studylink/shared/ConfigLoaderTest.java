package lk.studylink.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import lk.studylink.shared.config.CliOptions;
import lk.studylink.shared.config.ConfigException;
import lk.studylink.shared.config.ConfigLoader;
import lk.studylink.shared.config.StudyLinkConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless JUnit tests for {@link ConfigLoader}, {@link StudyLinkConfig} and {@link CliOptions}.
 */
class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsExamplePropertiesAndValidatesBeforeTlsFilesExist() throws ConfigException {
        Path repoExample = resolveExampleConfig();
        StudyLinkConfig config = ConfigLoader.load(repoExample);

        assertEquals("studylink-local", config.serverId());
        assertEquals("127.0.0.1", config.serverHost());
        assertEquals(45400, config.discoveryPort());
        assertEquals(45401, config.roomsPort());
        assertEquals(45402, config.chatPort());
        assertEquals(45403, config.filesPort());
        assertEquals(45404, config.notesPort());
        assertEquals("var/room-files", config.dataDir());
        assertEquals("TLSv1.3", config.tlsProtocol());
        assertEquals("local/host.p12", config.tlsServerStore());
        assertEquals("local/host-trust.p12", config.tlsClientTrustStore());
        assertEquals(3, config.rooms().size());
        assertEquals("netprog", config.rooms().get(0).id());
        assertEquals("Network Programming", config.rooms().get(0).displayName());
        assertEquals("revision", config.rooms().get(1).id());
        assertEquals("Exam Revision", config.rooms().get(1).displayName());
        assertEquals("workshop", config.rooms().get(2).id());
        assertEquals("Project Workshop", config.rooms().get(2).displayName());
    }

    @Test
    void summaryJsonContainsEffectiveNonSecretSettings() throws ConfigException {
        Properties props = validProperties();
        props.setProperty("tls.keystorePassword", "should-never-be-serialized");

        StudyLinkConfig config = ConfigLoader.fromProperties(props, "unit-test");
        String json = config.toSummaryJson("studylink-server", "config/studylink.example.properties");

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("OK", root.get("status").getAsString());
        assertEquals("studylink-server", root.get("component").getAsString());
        assertEquals("127.0.0.1", root.get("serverHost").getAsString());
        assertEquals(45401, root.getAsJsonObject("ports").get("roomsTcp").getAsInt());
        assertEquals("var/room-files", root.get("dataDir").getAsString());
        assertEquals("local/host.p12", root.getAsJsonObject("tls").get("serverStore").getAsString());
        assertFalse(json.contains("should-never-be-serialized"), "JSON summary must not leak secret values");
    }

    @Test
    void rejectsMissingConfigurationFile() {
        Path missing = tempDir.resolve("does-not-exist.properties");
        ConfigException ex = assertThrows(ConfigException.class, () -> ConfigLoader.load(missing));
        assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    void rejectsMissingRequiredProperties() {
        Properties missingHost = validProperties();
        missingHost.remove("server.host");
        ConfigException hostEx = assertThrows(
                ConfigException.class,
                () -> ConfigLoader.fromProperties(missingHost, "missing-host.properties")
        );
        assertTrue(hostEx.getMessage().contains("server.host"));

        Properties missingDataDir = validProperties();
        missingDataDir.remove("data.dir");
        ConfigException dataDirEx = assertThrows(
                ConfigException.class,
                () -> ConfigLoader.fromProperties(missingDataDir, "missing-datadir.properties")
        );
        assertTrue(dataDirEx.getMessage().contains("data.dir"));
    }

    @Test
    void rejectsOutOfRangeAndNonNumericPorts() {
        Properties zeroPort = validProperties();
        zeroPort.setProperty("port.rooms", "0");
        ConfigException zeroEx = assertThrows(
                ConfigException.class,
                () -> ConfigLoader.fromProperties(zeroPort, "zero-port.properties")
        );
        assertTrue(zeroEx.getMessage().contains("port.rooms"));

        Properties highPort = validProperties();
        highPort.setProperty("port.notes", "65536");
        ConfigException highEx = assertThrows(
                ConfigException.class,
                () -> ConfigLoader.fromProperties(highPort, "high-port.properties")
        );
        assertTrue(highEx.getMessage().contains("port.notes"));

        Properties textPort = validProperties();
        textPort.setProperty("port.chat", "forty-five-thousand");
        ConfigException textEx = assertThrows(
                ConfigException.class,
                () -> ConfigLoader.fromProperties(textPort, "text-port.properties")
        );
        assertTrue(textEx.getMessage().contains("port.chat"));
    }

    @Test
    void rejectsDuplicateServicePorts() {
        Properties duplicatePorts = validProperties();
        duplicatePorts.setProperty("port.files", "45401");
        ConfigException ex = assertThrows(
                ConfigException.class,
                () -> ConfigLoader.fromProperties(duplicatePorts, "duplicate-ports.properties")
        );
        assertTrue(ex.getMessage().contains("45401"));
    }

    @Test
    void rejectsUnsupportedTlsProtocolAndInvalidRoomCatalog() {
        Properties badTls = validProperties();
        badTls.setProperty("tls.protocol", "TLSv1.2");
        assertThrows(ConfigException.class, () -> ConfigLoader.fromProperties(badTls, "bad-tls.properties"));

        Properties badRooms = validProperties();
        badRooms.setProperty("rooms.catalog", "netprog-without-colon");
        assertThrows(ConfigException.class, () -> ConfigLoader.fromProperties(badRooms, "bad-rooms.properties"));
    }

    @Test
    void parsesCliOptionsAndRejectsMalformedFlags() throws ConfigException, IOException {
        Path cfg = tempDir.resolve("sample.properties");
        Files.writeString(cfg, "server.host=127.0.0.1\n", StandardCharsets.UTF_8);

        CliOptions helpOpts = CliOptions.parse(new String[]{"--help"});
        assertTrue(helpOpts.helpRequested());
        assertFalse(helpOpts.checkConfigRequested());

        CliOptions checkOpts = CliOptions.parse(new String[]{"--config", cfg.toString(), "--check-config"});
        assertFalse(checkOpts.helpRequested());
        assertTrue(checkOpts.checkConfigRequested());
        assertNotNull(checkOpts.configPath());
        assertEquals(cfg, checkOpts.effectiveCheckConfigPath());

        assertThrows(ConfigException.class, () -> CliOptions.parse(new String[]{"--config"}));
        assertThrows(ConfigException.class, () -> CliOptions.parse(new String[]{"--unknown-flag"}));
    }

    private static Properties validProperties() {
        Properties props = new Properties();
        props.setProperty("server.id", "studylink-local");
        props.setProperty("server.host", "127.0.0.1");
        props.setProperty("network.interface", "loopback");
        props.setProperty("port.discovery", "45400");
        props.setProperty("port.rooms", "45401");
        props.setProperty("port.chat", "45402");
        props.setProperty("port.files", "45403");
        props.setProperty("port.notes", "45404");
        props.setProperty("data.dir", "var/room-files");
        props.setProperty("tls.protocol", "TLSv1.3");
        props.setProperty("tls.serverStore", "local/host.p12");
        props.setProperty("tls.clientTrustStore", "local/host-trust.p12");
        return props;
    }

    private static Path resolveExampleConfig() {
        Path direct = Paths.get("config", "studylink.example.properties");
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        Path parent = Paths.get("..", "config", "studylink.example.properties");
        if (Files.isRegularFile(parent)) {
            return parent;
        }
        throw new IllegalStateException("Could not locate config/studylink.example.properties from test working directory");
    }
}
