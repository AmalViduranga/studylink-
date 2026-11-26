package lk.studylink.client.rooms;

/**
 * Immutable snapshot of the five StudyLink listener ports advertised by the host server
 * in a successful {@code HELLO} response (SRS Section 6.3).
 *
 * @param discoveryUdp UDP discovery and unicast heartbeat/presence port ({@code M2}, default {@code 45400})
 * @param roomsTcp     TCP room session and membership control port ({@code M1}, default {@code 45401})
 * @param chatNioTcp   Java NIO over TCP live room chat port ({@code M5}, default {@code 45402})
 * @param filesTcp     TCP concurrent file transfer port ({@code M3}, default {@code 45403})
 * @param notesTlsTcp  TLS 1.3 over TCP private host note port ({@code M4}, default {@code 45404})
 */
public record AdvertisedServicePorts(
        int discoveryUdp,
        int roomsTcp,
        int chatNioTcp,
        int filesTcp,
        int notesTlsTcp
) {

    public AdvertisedServicePorts {
        validatePort("discoveryUdp", discoveryUdp);
        validatePort("roomsTcp", roomsTcp);
        validatePort("chatNioTcp", chatNioTcp);
        validatePort("filesTcp", filesTcp);
        validatePort("notesTlsTcp", notesTlsTcp);
    }

    private static void validatePort(String name, int port) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException(
                    "Invalid advertised port for '" + name + "': " + port + " (expected 1..65535)"
            );
        }
    }
}
