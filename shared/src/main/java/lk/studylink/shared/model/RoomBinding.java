package lk.studylink.shared.model;

import java.util.Objects;

/**
 * Immutable proof of a student's active membership in a specific classroom study room
 * at a specific per-session monotonic revision (SRS Sections 3, 5.1, 6.1, and 6.4).
 *
 * <p>Joining or switching rooms increments {@code revision} and invalidates any previous
 * {@code RoomBinding} for that session. Equality compares all three components
 * ({@code sessionId}, {@code roomId}, and {@code revision}).
 *
 * @param sessionId server-issued session identifier (non-blank)
 * @param roomId    configured room identifier such as {@code "netprog"} (non-blank)
 * @param revision  per-session monotonically increasing positive revision ({@code >= 1})
 */
public record RoomBinding(String sessionId, String roomId, long revision) {

    public RoomBinding {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(roomId, "roomId must not be null");
        sessionId = sessionId.trim();
        roomId = roomId.trim();
        if (sessionId.isEmpty()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (roomId.isEmpty()) {
            throw new IllegalArgumentException("roomId must not be blank");
        }
        if (revision < 1L) {
            throw new IllegalArgumentException("revision must be >= 1, but was " + revision);
        }
    }
}
