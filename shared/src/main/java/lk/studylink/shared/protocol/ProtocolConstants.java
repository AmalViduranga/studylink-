package lk.studylink.shared.protocol;

/**
 * Shared protocol constants and SRS baseline limits for StudyLink Protocol v1.
 * Stream framing and feature codecs are reserved for Member 1 (M1) and the feature owners.
 */
public final class ProtocolConstants {

    /** Protocol version carried in the "v" field of JSON envelopes. */
    public static final int PROTOCOL_VERSION = 1;

    /** Four-byte unsigned big-endian payload length prefix for stream control messages. */
    public static final int FRAME_LENGTH_PREFIX_BYTES = 4;

    /** Minimum valid stream frame payload size in bytes (SRS Q06). */
    public static final int MIN_FRAME_PAYLOAD_BYTES = 1;

    /** Maximum valid stream frame payload size in bytes (16 KiB, SRS Q06). */
    public static final int MAX_FRAME_PAYLOAD_BYTES = 16_384;

    /** Maximum accepted UDP datagram payload size in bytes (SRS Q06). */
    public static final int MAX_UDP_PAYLOAD_BYTES = 1_200;

    /** Maximum UTF-8 byte length for a chat message or private note body (2 KiB, SRS F06/F11). */
    public static final int MAX_TEXT_MESSAGE_BYTES = 2_048;

    /** Maximum file upload size in bytes for PDF or TXT transfers (5 MiB, SRS F09/Q06). */
    public static final int MAX_FILE_UPLOAD_BYTES = 5 * 1024 * 1024;

    /** Maximum pending outbound queue per NIO chat client before slow-reader disconnect (64 KiB, SRS Q06). */
    public static final int MAX_SLOW_CLIENT_QUEUE_BYTES = 65_536;

    /** Bounded file transfer worker thread count (SRS Section 5.3 & Q06). */
    public static final int DEFAULT_FILE_WORKER_THREADS = 4;

    /** Bounded file transfer waiting queue capacity (SRS Section 5.3 & Q06). */
    public static final int DEFAULT_FILE_QUEUE_CAPACITY = 8;

    /** Default UDP port for M2 discovery and unicast heartbeat/presence. */
    public static final int DEFAULT_DISCOVERY_UDP_PORT = 45400;

    /** Default TCP port for M1 room sessions and membership control. */
    public static final int DEFAULT_ROOMS_TCP_PORT = 45401;

    /** Default NIO over TCP port for M5 live room chat. */
    public static final int DEFAULT_CHAT_NIO_PORT = 45402;

    /** Default TCP port for M3 concurrent PDF/TXT file transfers. */
    public static final int DEFAULT_FILES_TCP_PORT = 45403;

    /** Default TLS 1.3 over TCP port for M4 private notes to host inbox. */
    public static final int DEFAULT_NOTES_TLS_PORT = 45404;

    private ProtocolConstants() {
    }
}
