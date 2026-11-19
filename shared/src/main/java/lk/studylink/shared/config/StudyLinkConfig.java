package lk.studylink.shared.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lk.studylink.shared.model.RoomDefinition;
import lk.studylink.shared.protocol.ProtocolConstants;

/**
 * Validated, immutable StudyLink runtime configuration (SRS Section 4.1 & Section 6).
 * Holds only non-secret settings; keystore and truststore passwords are read from
 * environment variables at TLS initialization time by Member 4 (M4).
 */
public record StudyLinkConfig(
        String serverId,
        String serverHost,
        String networkInterface,
        int discoveryPort,
        int roomsPort,
        int chatPort,
        int filesPort,
        int notesPort,
        String dataDir,
        String tlsProtocol,
        String tlsServerStore,
        String tlsClientTrustStore,
        List<RoomDefinition> rooms,
        int maxFrameBytes,
        int maxUdpBytes,
        int maxChatTextBytes,
        int maxNoteTextBytes,
        int maxFileSizeBytes,
        int fileWorkerThreads,
        int fileQueueCapacity,
        int maxSlowClientQueueBytes,
        int maxInboxNotesPerRoom,
        int maxFilesPerRoom,
        int discoveryTimeoutMs,
        int heartbeatIntervalMs,
        int presenceExpiryMs,
        int presenceHideMs,
        int controlPingIntervalMs,
        int controlSessionExpiryMs
) {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public StudyLinkConfig {
        Objects.requireNonNull(serverId, "serverId must not be null");
        Objects.requireNonNull(serverHost, "serverHost must not be null");
        Objects.requireNonNull(networkInterface, "networkInterface must not be null");
        Objects.requireNonNull(dataDir, "dataDir must not be null");
        Objects.requireNonNull(tlsProtocol, "tlsProtocol must not be null");
        Objects.requireNonNull(tlsServerStore, "tlsServerStore must not be null");
        Objects.requireNonNull(tlsClientTrustStore, "tlsClientTrustStore must not be null");
        Objects.requireNonNull(rooms, "rooms must not be null");
        rooms = List.copyOf(rooms);
    }

    /**
     * Returns the SRS baseline default configuration matching {@code config/studylink.example.properties}.
     */
    public static StudyLinkConfig srsDefaults() {
        return new StudyLinkConfig(
                "studylink-local",
                "127.0.0.1",
                "loopback",
                ProtocolConstants.DEFAULT_DISCOVERY_UDP_PORT,
                ProtocolConstants.DEFAULT_ROOMS_TCP_PORT,
                ProtocolConstants.DEFAULT_CHAT_NIO_PORT,
                ProtocolConstants.DEFAULT_FILES_TCP_PORT,
                ProtocolConstants.DEFAULT_NOTES_TLS_PORT,
                "var/room-files",
                "TLSv1.3",
                "local/host.p12",
                "local/host-trust.p12",
                RoomDefinition.defaultRooms(),
                ProtocolConstants.MAX_FRAME_PAYLOAD_BYTES,
                ProtocolConstants.MAX_UDP_PAYLOAD_BYTES,
                ProtocolConstants.MAX_TEXT_MESSAGE_BYTES,
                ProtocolConstants.MAX_TEXT_MESSAGE_BYTES,
                ProtocolConstants.MAX_FILE_UPLOAD_BYTES,
                ProtocolConstants.DEFAULT_FILE_WORKER_THREADS,
                ProtocolConstants.DEFAULT_FILE_QUEUE_CAPACITY,
                ProtocolConstants.MAX_SLOW_CLIENT_QUEUE_BYTES,
                100,
                25,
                3_000,
                3_000,
                10_000,
                12_000,
                5_000,
                20_000
        );
    }

    /**
     * Serializes a concise, deterministic JSON summary of effective non-secret configuration values using Gson.
     *
     * @param componentName component running the check (e.g. "studylink-server" or "studylink-client")
     * @param configSource  description or path of the loaded configuration file
     * @return JSON string suitable for {@code --check-config} output
     */
    public String toSummaryJson(String componentName, String configSource) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", "OK");
        summary.put("component", componentName);
        summary.put("configSource", configSource);
        summary.put("protocolVersion", ProtocolConstants.PROTOCOL_VERSION);
        summary.put("serverId", serverId);
        summary.put("serverHost", serverHost);
        summary.put("networkInterface", networkInterface);

        Map<String, Integer> ports = new LinkedHashMap<>();
        ports.put("discoveryUdp", discoveryPort);
        ports.put("roomsTcp", roomsPort);
        ports.put("chatNioTcp", chatPort);
        ports.put("filesTcp", filesPort);
        ports.put("notesTlsTcp", notesPort);
        summary.put("ports", ports);

        summary.put("dataDir", dataDir);

        Map<String, Object> tls = new LinkedHashMap<>();
        tls.put("protocol", tlsProtocol);
        tls.put("serverStore", tlsServerStore);
        tls.put("clientTrustStore", tlsClientTrustStore);
        summary.put("tls", tls);

        List<Map<String, String>> roomList = rooms.stream()
                .map(r -> {
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("id", r.id());
                    entry.put("name", r.displayName());
                    return entry;
                })
                .toList();
        summary.put("rooms", roomList);

        Map<String, Integer> limits = new LinkedHashMap<>();
        limits.put("maxFrameBytes", maxFrameBytes);
        limits.put("maxUdpBytes", maxUdpBytes);
        limits.put("maxChatTextBytes", maxChatTextBytes);
        limits.put("maxNoteTextBytes", maxNoteTextBytes);
        limits.put("maxFileSizeBytes", maxFileSizeBytes);
        limits.put("fileWorkerThreads", fileWorkerThreads);
        limits.put("fileQueueCapacity", fileQueueCapacity);
        limits.put("maxSlowClientQueueBytes", maxSlowClientQueueBytes);
        summary.put("limits", limits);

        return GSON.toJson(summary);
    }
}
