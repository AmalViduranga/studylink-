package lk.studylink.shared.model;

import java.util.List;
import java.util.Objects;
import lk.studylink.shared.config.StudyLinkConfig;

/**
 * Describes one of the five individually owned StudyLink network services (SRS Section 3 & Section 5).
 *
 * @param ownerCode   member identifier (M1..M5)
 * @param featureName short package/feature identifier
 * @param transport   network transport and JDK API family
 * @param port        configured port number
 * @param summary     feature responsibility summary
 * @param status      honest implementation status in the current repository build
 */
public record FeatureServiceSpec(
        String ownerCode,
        String featureName,
        String transport,
        int port,
        String summary,
        String status
) {

    public FeatureServiceSpec {
        Objects.requireNonNull(ownerCode, "ownerCode must not be null");
        Objects.requireNonNull(featureName, "featureName must not be null");
        Objects.requireNonNull(transport, "transport must not be null");
        Objects.requireNonNull(summary, "summary must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }

    /**
     * Builds the five planned feature service descriptors using the active configuration's ports.
     */
    public static List<FeatureServiceSpec> plannedServices(StudyLinkConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        String plannedStatus = "Planned (reserved for feature owner)";
        return List.of(
                new FeatureServiceSpec(
                        "M1",
                        "rooms",
                        "TCP (java.net.ServerSocket / Socket)",
                        config.roomsPort(),
                        "Room sessions, membership registry and 4-byte big-endian JSON framing",
                        plannedStatus
                ),
                new FeatureServiceSpec(
                        "M2",
                        "discovery",
                        "UDP (java.net.DatagramSocket / DatagramPacket)",
                        config.discoveryPort(),
                        "Broadcast host discovery, manual fallback and unicast heartbeat/presence",
                        plannedStatus
                ),
                new FeatureServiceSpec(
                        "M3",
                        "files",
                        "TCP (java.util.concurrent pool + java.nio.file)",
                        config.filesPort(),
                        "Concurrent PDF/TXT transfers (<= 5 MiB), bounded 4-worker pool and SHA-256 verification",
                        plannedStatus
                ),
                new FeatureServiceSpec(
                        "M4",
                        "notes",
                        "TLS 1.3 over TCP (javax.net.ssl / JSSE)",
                        config.notesPort(),
                        "Certificate-validated private notes to verified host and local console inbox",
                        plannedStatus
                ),
                new FeatureServiceSpec(
                        "M5",
                        "chat",
                        "NIO over TCP (java.nio.channels.Selector + ByteBuffer)",
                        config.chatPort(),
                        "Live room chat with non-blocking channels, selector loop and room isolation",
                        plannedStatus
                )
        );
    }
}
