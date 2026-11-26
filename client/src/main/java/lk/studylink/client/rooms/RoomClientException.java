package lk.studylink.client.rooms;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Checked exception thrown by {@link RoomClient} when a room-control request is rejected by the
 * server with a correlated error envelope ({@code "ok": false}) or when a transport, timeout, EOF,
 * or wire-protocol validation failure invalidates the control connection.
 */
public final class RoomClientException extends IOException {

    private final String errorCode;
    private final boolean connectionInvalidated;

    /**
     * Creates an exception representing a correlated server error response ({@code "ok": false}).
     *
     * @param errorCode             stable protocol error code such as {@code "NAME_IN_USE"} or
     *                              {@code "UNKNOWN_ROOM"} (must not be {@code null} or blank)
     * @param serverMessage         human-readable error message from the server
     * @param connectionInvalidated {@code true} if the control connection was closed/invalidated
     */
    public RoomClientException(String errorCode, String serverMessage, boolean connectionInvalidated) {
        super(formatServerMessage(errorCode, serverMessage));
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        String trimmedCode = errorCode.trim();
        if (trimmedCode.isEmpty()) {
            throw new IllegalArgumentException("errorCode must not be blank");
        }
        this.errorCode = trimmedCode;
        this.connectionInvalidated = connectionInvalidated;
    }

    /**
     * Creates an exception representing a local state, transport, EOF, timeout, or malformed
     * response failure without a server error code or cause.
     *
     * @param message               diagnostic message
     * @param connectionInvalidated {@code true} if the control connection was invalidated
     */
    public RoomClientException(String message, boolean connectionInvalidated) {
        this(message, (Throwable) null, connectionInvalidated);
    }

    /**
     * Creates an exception representing a local state, transport, EOF, timeout, or malformed
     * response failure without a server error code.
     *
     * @param message               diagnostic message
     * @param cause                 optional underlying cause (may be {@code null})
     * @param connectionInvalidated {@code true} if the control connection was invalidated
     */
    public RoomClientException(String message, Throwable cause, boolean connectionInvalidated) {
        super(Objects.requireNonNull(message, "message must not be null"), cause);
        this.errorCode = null;
        this.connectionInvalidated = connectionInvalidated;
    }

    /**
     * Returns the stable server error code (such as {@code "NAME_IN_USE"} or {@code "UNKNOWN_ROOM"}),
     * or {@link Optional#empty()} if the failure was a transport/protocol/state error.
     */
    public Optional<String> errorCode() {
        return Optional.ofNullable(errorCode);
    }

    /**
     * Returns {@code true} if this exception carries the specified server error code.
     */
    public boolean hasErrorCode(String expectedCode) {
        return errorCode != null && errorCode.equals(expectedCode);
    }

    /**
     * Returns {@code true} if the control connection and any active {@code RoomBinding} were
     * invalidated as a result of this failure.
     */
    public boolean isConnectionInvalidated() {
        return connectionInvalidated;
    }

    private static String formatServerMessage(String errorCode, String serverMessage) {
        String code = (errorCode == null) ? "" : errorCode.trim();
        String msg = (serverMessage == null || serverMessage.isBlank()) ? "Server rejected request" : serverMessage;
        return "[" + code + "] " + msg;
    }
}
