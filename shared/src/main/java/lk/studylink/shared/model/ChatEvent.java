package lk.studylink.shared.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Immutable live room chat event broadcast by the NIO chat server (M5) to all current subscribers
 * of a single study room (SRS Sections 5.5, 6.3, 6.4, F06, and F07).
 *
 * @param messageId       server-assigned unique chat message identifier (e.g. {@code "m-1"})
 * @param roomId          authoritative room ID determined from the sender's verified server binding
 * @param senderSessionId sender's active session ID
 * @param senderName      authoritative sender display name resolved by the server
 * @param text            validated UTF-8 chat message body (1..2,048 UTF-8 bytes)
 * @param sentAtEpochMs   server UTC timestamp in milliseconds since the Unix epoch
 * @param displayTime     human-readable server display time (e.g. {@code "14:25:09"})
 */
public record ChatEvent(
        String messageId,
        String roomId,
        String senderSessionId,
        String senderName,
        String text,
        long sentAtEpochMs,
        String displayTime
) {

    private static final DateTimeFormatter DISPLAY_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    public ChatEvent {
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(roomId, "roomId must not be null");
        Objects.requireNonNull(senderSessionId, "senderSessionId must not be null");
        Objects.requireNonNull(senderName, "senderName must not be null");
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(displayTime, "displayTime must not be null");

        messageId = messageId.trim();
        roomId = roomId.trim();
        senderSessionId = senderSessionId.trim();
        senderName = senderName.trim();
        displayTime = displayTime.trim();

        if (messageId.isEmpty()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        if (roomId.isEmpty()) {
            throw new IllegalArgumentException("roomId must not be blank");
        }
        if (senderSessionId.isEmpty()) {
            throw new IllegalArgumentException("senderSessionId must not be blank");
        }
        if (senderName.isEmpty()) {
            throw new IllegalArgumentException("senderName must not be blank");
        }
        if (text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        if (sentAtEpochMs < 0L) {
            throw new IllegalArgumentException("sentAtEpochMs must be >= 0, found " + sentAtEpochMs);
        }
        if (displayTime.isEmpty()) {
            throw new IllegalArgumentException("displayTime must not be blank");
        }
    }

    /**
     * Formats {@code epochMs} into {@code HH:mm:ss} in the local system timezone for display.
     */
    public static String formatDisplayTime(long epochMs) {
        return Instant.ofEpochMilli(epochMs)
                .atZone(ZoneId.systemDefault())
                .toLocalTime()
                .format(DISPLAY_TIME_FORMATTER);
    }
}
