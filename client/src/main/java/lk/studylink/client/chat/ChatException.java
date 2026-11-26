package lk.studylink.client.chat;

import java.util.Objects;

/**
 * Checked exception raised by {@link ChatClient} when subscribing to a room or sending a chat
 * message fails validation, authorization, or network transport.
 */
public final class ChatException extends Exception {

    private final String errorCode;

    public ChatException(String errorCode, String message) {
        super(Objects.requireNonNull(message, "message must not be null"));
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    public ChatException(String errorCode, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message must not be null"), cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    /**
     * Returns the stable protocol or client error code (e.g. {@code STALE_BINDING},
     * {@code TOO_LARGE}, {@code BAD_REQUEST}, {@code NOT_SUBSCRIBED}, {@code CONNECTION_FAILED}).
     */
    public String errorCode() {
        return errorCode;
    }
}
