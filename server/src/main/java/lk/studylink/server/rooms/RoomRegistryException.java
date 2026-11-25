package lk.studylink.server.rooms;

import java.util.Objects;

/**
 * Checked exception thrown when a session registration or room membership transition violates
 * StudyLink validation or lifecycle rules. Carries a stable {@link RoomRegistryErrorCode}.
 */
public final class RoomRegistryException extends Exception {

    private final RoomRegistryErrorCode errorCode;

    public RoomRegistryException(RoomRegistryErrorCode errorCode, String message) {
        super(Objects.requireNonNull(message, "message must not be null"));
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    /**
     * Returns the stable protocol error code (such as {@code NAME_IN_USE} or {@code UNKNOWN_ROOM}).
     */
    public RoomRegistryErrorCode errorCode() {
        return errorCode;
    }
}
