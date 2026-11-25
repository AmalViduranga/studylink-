package lk.studylink.server.rooms;

/**
 * Stable protocol error codes produced by room session and membership operations
 * (SRS Sections 5.1, 6.3, and 6.4).
 */
public enum RoomRegistryErrorCode {

    /** Display name is empty, outside 2–24 code points, > 48 UTF-8 bytes, or contains disallowed characters. */
    INVALID_NAME,

    /** Display name is already reserved by another active session (case-insensitive under {@code Locale.ROOT}). */
    NAME_IN_USE,

    /** Requested {@code roomId} is not present in the configured room catalogue. */
    UNKNOWN_ROOM,

    /** Session is unknown/closed or the supplied {@code RoomBinding} is no longer current. */
    STALE_BINDING
}
