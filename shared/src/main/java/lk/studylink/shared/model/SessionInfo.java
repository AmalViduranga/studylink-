package lk.studylink.shared.model;

import java.util.Objects;

/**
 * Immutable session record issued when a student connects and registers an available
 * display name (SRS Sections 5.1, F03, and 6.4).
 *
 * @param sessionId   fresh server-generated session identifier (non-blank)
 * @param displayName validated, trimmed display name preserving the student's chosen spelling
 */
public record SessionInfo(String sessionId, String displayName) {

    public SessionInfo {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        sessionId = sessionId.trim();
        displayName = displayName.trim();
        if (sessionId.isEmpty()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (displayName.isEmpty()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
    }
}
