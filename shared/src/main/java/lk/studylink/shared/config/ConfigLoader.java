package lk.studylink.shared.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import lk.studylink.shared.model.RoomDefinition;
import lk.studylink.shared.protocol.ProtocolConstants;

/**
 * Loads and validates {@link StudyLinkConfig} from {@code .properties} files or {@link Properties} instances.
 * Validates required properties, port ranges (1..65535), distinct service ports, path syntax,
 * room catalog definitions and protocol limits. Does not require TLS store files to exist on disk
 * during starter configuration checks.
 */
public final class ConfigLoader {

    public static final int MIN_PORT = 1;
    public static final int MAX_PORT = 65_535;
    public static final Path DEFAULT_LOCAL_CONFIG_PATH = Paths.get("config", "studylink.properties");
    public static final Path EXAMPLE_CONFIG_PATH = Paths.get("config", "studylink.example.properties");

    private ConfigLoader() {
    }

    /**
     * Loads and validates configuration from the given filesystem path.
     *
     * @param path path to the properties file
     * @return validated {@link StudyLinkConfig}
     * @throws ConfigException if the file does not exist, cannot be read, or contains invalid values
     */
    public static StudyLinkConfig load(Path path) throws ConfigException {
        Objects.requireNonNull(path, "Configuration path must not be null");
        if (!Files.exists(path)) {
            throw new ConfigException("Configuration file not found: " + path);
        }
        if (!Files.isRegularFile(path)) {
            throw new ConfigException("Configuration path is not a regular file: " + path);
        }

        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(path);
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new ConfigException("Failed to read configuration file '" + path + "': " + e.getMessage(), e);
        }

        return fromProperties(properties, path.toString());
    }

    /**
     * Resolves a configuration for normal starter launch when {@code --config} is optional.
     * If {@code explicitPath} is provided, loads strictly from that path.
     * Otherwise checks {@code config/studylink.properties}, then {@code config/studylink.example.properties},
     * and finally falls back to embedded SRS defaults.
     */
    public static LoadedConfig resolveForNormalLaunch(Path explicitPath) throws ConfigException {
        if (explicitPath != null) {
            return new LoadedConfig(load(explicitPath), explicitPath.toString());
        }
        if (Files.isRegularFile(DEFAULT_LOCAL_CONFIG_PATH)) {
            return new LoadedConfig(load(DEFAULT_LOCAL_CONFIG_PATH), DEFAULT_LOCAL_CONFIG_PATH.toString());
        }
        if (Files.isRegularFile(EXAMPLE_CONFIG_PATH)) {
            return new LoadedConfig(load(EXAMPLE_CONFIG_PATH), EXAMPLE_CONFIG_PATH.toString());
        }
        return new LoadedConfig(StudyLinkConfig.srsDefaults(), "embedded-srs-defaults");
    }

    /**
     * Parses and validates a {@link Properties} object into a {@link StudyLinkConfig}.
     *
     * @param props      loaded properties
     * @param sourceName human-readable source name for error messages
     * @return validated {@link StudyLinkConfig}
     * @throws ConfigException if any required property is missing or any value violates constraints
     */
    public static StudyLinkConfig fromProperties(Properties props, String sourceName) throws ConfigException {
        Objects.requireNonNull(props, "props must not be null");
        String src = (sourceName == null || sourceName.isBlank()) ? "<properties>" : sourceName;

        StudyLinkConfig defaults = StudyLinkConfig.srsDefaults();

        String serverHost = requireNonBlank(props, src, "server.host", "host.address", "host");
        if (serverHost.chars().anyMatch(Character::isWhitespace)) {
            throw new ConfigException("Invalid server.host in " + src + ": must not contain whitespace ('" + serverHost + "')");
        }

        int discoveryPort = requirePort(props, src, "port.discovery", "discovery.port", "udp.discovery.port");
        int roomsPort = requirePort(props, src, "port.rooms", "rooms.port", "tcp.rooms.port");
        int chatPort = requirePort(props, src, "port.chat", "chat.port", "nio.chat.port");
        int filesPort = requirePort(props, src, "port.files", "files.port", "tcp.files.port");
        int notesPort = requirePort(props, src, "port.notes", "notes.port", "tls.notes.port");

        validateDistinctPorts(src, Map.of(
                "port.discovery", discoveryPort,
                "port.rooms", roomsPort,
                "port.chat", chatPort,
                "port.files", filesPort,
                "port.notes", notesPort
        ));

        String dataDir = requireValidPathString(props, src, "data.dir");
        String tlsServerStore = requireValidPathString(props, src, "tls.serverStore");
        String tlsClientTrustStore = requireValidPathString(props, src, "tls.clientTrustStore");

        String serverId = optionalNonBlank(props, src, "server.id", defaults.serverId());
        String networkInterface = optionalNonBlank(props, src, "network.interface", defaults.networkInterface());
        String tlsProtocol = optionalNonBlank(props, src, "tls.protocol", defaults.tlsProtocol());
        if (!"TLSv1.3".equals(tlsProtocol)) {
            throw new ConfigException("Invalid tls.protocol in " + src + ": expected 'TLSv1.3' per SRS Section 5.4/F11, found '" + tlsProtocol + "'");
        }

        List<RoomDefinition> rooms = parseRoomsCatalog(props, src, defaults.rooms());

        int maxFrameBytes = optionalPositiveInt(props, src, "limits.maxFrameBytes",
                defaults.maxFrameBytes(), ProtocolConstants.MIN_FRAME_PAYLOAD_BYTES, 1_048_576);
        int maxUdpBytes = optionalPositiveInt(props, src, "limits.maxUdpBytes",
                defaults.maxUdpBytes(), 64, 65_507);
        int maxChatTextBytes = optionalPositiveInt(props, src, "limits.maxChatTextBytes",
                defaults.maxChatTextBytes(), 1, maxFrameBytes);
        int maxNoteTextBytes = optionalPositiveInt(props, src, "limits.maxNoteTextBytes",
                defaults.maxNoteTextBytes(), 1, maxFrameBytes);
        int maxFileSizeBytes = optionalPositiveInt(props, src, "limits.maxFileSizeBytes",
                defaults.maxFileSizeBytes(), 1, 100 * 1024 * 1024);
        int fileWorkerThreads = optionalPositiveInt(props, src, "limits.fileWorkerThreads",
                defaults.fileWorkerThreads(), 1, 64);
        int fileQueueCapacity = optionalPositiveInt(props, src, "limits.fileQueueCapacity",
                defaults.fileQueueCapacity(), 1, 256);
        int maxSlowClientQueueBytes = optionalPositiveInt(props, src, "limits.maxSlowClientQueueBytes",
                defaults.maxSlowClientQueueBytes(), 1024, 16 * 1024 * 1024);
        int maxInboxNotesPerRoom = optionalPositiveInt(props, src, "limits.maxInboxNotesPerRoom",
                defaults.maxInboxNotesPerRoom(), 1, 10_000);
        int maxFilesPerRoom = optionalPositiveInt(props, src, "limits.maxFilesPerRoom",
                defaults.maxFilesPerRoom(), 1, 10_000);

        int discoveryTimeoutMs = optionalPositiveInt(props, src, "timeouts.discoveryMs",
                defaults.discoveryTimeoutMs(), 100, 60_000);
        int heartbeatIntervalMs = optionalPositiveInt(props, src, "timeouts.heartbeatIntervalMs",
                defaults.heartbeatIntervalMs(), 100, 60_000);
        int presenceExpiryMs = optionalPositiveInt(props, src, "timeouts.presenceExpiryMs",
                defaults.presenceExpiryMs(), 500, 300_000);
        int presenceHideMs = optionalPositiveInt(props, src, "timeouts.presenceHideMs",
                defaults.presenceHideMs(), 500, 300_000);
        int controlPingIntervalMs = optionalPositiveInt(props, src, "timeouts.controlPingIntervalMs",
                defaults.controlPingIntervalMs(), 100, 60_000);
        int controlSessionExpiryMs = optionalPositiveInt(props, src, "timeouts.controlSessionExpiryMs",
                defaults.controlSessionExpiryMs(), 500, 300_000);

        return new StudyLinkConfig(
                serverId,
                serverHost,
                networkInterface,
                discoveryPort,
                roomsPort,
                chatPort,
                filesPort,
                notesPort,
                dataDir,
                tlsProtocol,
                tlsServerStore,
                tlsClientTrustStore,
                rooms,
                maxFrameBytes,
                maxUdpBytes,
                maxChatTextBytes,
                maxNoteTextBytes,
                maxFileSizeBytes,
                fileWorkerThreads,
                fileQueueCapacity,
                maxSlowClientQueueBytes,
                maxInboxNotesPerRoom,
                maxFilesPerRoom,
                discoveryTimeoutMs,
                heartbeatIntervalMs,
                presenceExpiryMs,
                presenceHideMs,
                controlPingIntervalMs,
                controlSessionExpiryMs
        );
    }

    private static String requireNonBlank(Properties props, String src, String primaryKey, String... aliasKeys)
            throws ConfigException {
        String raw = findProperty(props, primaryKey, aliasKeys);
        if (raw == null || raw.trim().isEmpty()) {
            throw new ConfigException("Missing required property '" + primaryKey + "' in " + src);
        }
        return raw.trim();
    }

    private static String optionalNonBlank(Properties props, String src, String key, String defaultValue)
            throws ConfigException {
        if (!props.containsKey(key)) {
            return defaultValue;
        }
        String raw = props.getProperty(key);
        if (raw == null || raw.trim().isEmpty()) {
            throw new ConfigException("Property '" + key + "' must not be blank in " + src);
        }
        return raw.trim();
    }

    private static int requirePort(Properties props, String src, String primaryKey, String... aliasKeys)
            throws ConfigException {
        String raw = requireNonBlank(props, src, primaryKey, aliasKeys);
        final int port;
        try {
            port = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new ConfigException("Invalid integer for '" + primaryKey + "' in " + src + ": '" + raw + "'", e);
        }
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new ConfigException(
                    "Port out of range for '" + primaryKey + "' in " + src
                            + ": " + port + " (expected " + MIN_PORT + ".." + MAX_PORT + ")");
        }
        return port;
    }

    private static void validateDistinctPorts(String src, Map<String, Integer> portsByKey) throws ConfigException {
        Map<Integer, String> seen = new LinkedHashMap<>();
        List<String> keys = List.of("port.discovery", "port.rooms", "port.chat", "port.files", "port.notes");
        for (String key : keys) {
            Integer port = portsByKey.get(key);
            if (port == null) {
                continue;
            }
            String existingKey = seen.putIfAbsent(port, key);
            if (existingKey != null) {
                throw new ConfigException(
                        "Duplicate port " + port + " configured for both '" + existingKey
                                + "' and '" + key + "' in " + src);
            }
        }
    }

    private static String requireValidPathString(Properties props, String src, String key) throws ConfigException {
        String value = requireNonBlank(props, src, key);
        if (value.indexOf('\0') >= 0) {
            throw new ConfigException("Invalid path for '" + key + "' in " + src + ": contains NUL character");
        }
        try {
            Path parsed = Paths.get(value);
            if (parsed.toString().trim().isEmpty()) {
                throw new ConfigException("Invalid empty path for '" + key + "' in " + src);
            }
        } catch (InvalidPathException e) {
            throw new ConfigException("Invalid filesystem path for '" + key + "' in " + src + ": '" + value + "'", e);
        }
        return value;
    }

    private static int optionalPositiveInt(
            Properties props,
            String src,
            String key,
            int defaultValue,
            int minValue,
            int maxValue
    ) throws ConfigException {
        if (!props.containsKey(key)) {
            return defaultValue;
        }
        String raw = props.getProperty(key);
        if (raw == null || raw.trim().isEmpty()) {
            throw new ConfigException("Property '" + key + "' must not be blank in " + src);
        }
        final int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException("Invalid integer for '" + key + "' in " + src + ": '" + raw + "'", e);
        }
        if (parsed < minValue || parsed > maxValue) {
            throw new ConfigException(
                    "Value out of range for '" + key + "' in " + src
                            + ": " + parsed + " (expected " + minValue + ".." + maxValue + ")");
        }
        return parsed;
    }

    private static List<RoomDefinition> parseRoomsCatalog(
            Properties props,
            String src,
            List<RoomDefinition> defaultRooms
    ) throws ConfigException {
        if (!props.containsKey("rooms.catalog")) {
            return defaultRooms;
        }
        String raw = props.getProperty("rooms.catalog");
        if (raw == null || raw.trim().isEmpty()) {
            throw new ConfigException("Property 'rooms.catalog' must not be blank in " + src);
        }
        String[] entries = raw.split(",");
        List<RoomDefinition> result = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (String entry : entries) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0 || colon == trimmed.length() - 1) {
                throw new ConfigException(
                        "Invalid rooms.catalog entry '" + trimmed + "' in " + src
                                + " (expected 'roomId:Display Name')");
            }
            String id = trimmed.substring(0, colon).trim();
            String name = trimmed.substring(colon + 1).trim();
            if (id.isEmpty() || name.isEmpty()) {
                throw new ConfigException(
                        "Invalid rooms.catalog entry '" + trimmed + "' in " + src
                                + " (roomId and display name must be non-empty)");
            }
            if (!seenIds.add(id)) {
                throw new ConfigException("Duplicate room id '" + id + "' in rooms.catalog in " + src);
            }
            result.add(new RoomDefinition(id, name));
        }
        if (result.isEmpty()) {
            throw new ConfigException("Property 'rooms.catalog' in " + src + " must define at least one room");
        }
        return List.copyOf(result);
    }

    private static String findProperty(Properties props, String primaryKey, String... aliasKeys) {
        String value = props.getProperty(primaryKey);
        if (value != null) {
            return value;
        }
        for (String alias : aliasKeys) {
            value = props.getProperty(alias);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Pairs a validated {@link StudyLinkConfig} with the path or label of its source.
     */
    public record LoadedConfig(StudyLinkConfig config, String sourceDescription) {
    }
}
