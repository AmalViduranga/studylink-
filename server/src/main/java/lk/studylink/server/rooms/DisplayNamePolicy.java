package lk.studylink.server.rooms;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/**
 * Enforces the SRS Section 6.4 display name validation and normalization rules:
 * <ul>
 *   <li>Trim leading and trailing whitespace while preserving the student's chosen internal spelling.</li>
 *   <li>Require {@code 2..24} Unicode code points.</li>
 *   <li>Require at most {@code 48} UTF-8 bytes.</li>
 *   <li>Allow only Unicode letters, Unicode digits, and the ASCII space character ({@code ' '}).</li>
 *   <li>Compare active names case-insensitively using {@link Locale#ROOT}.</li>
 * </ul>
 */
public final class DisplayNamePolicy {

    /** Minimum display name length in Unicode code points after trimming (SRS Section 6.4). */
    public static final int MIN_CODE_POINTS = 2;

    /** Maximum display name length in Unicode code points after trimming (SRS Section 6.4). */
    public static final int MAX_CODE_POINTS = 24;

    /** Maximum display name length in UTF-8 bytes after trimming (SRS Section 6.4). */
    public static final int MAX_UTF8_BYTES = 48;

    private DisplayNamePolicy() {
    }

    /**
     * Trims and validates a student's raw display name according to SRS Section 6.4.
     *
     * @param rawDisplayName raw display name submitted by the client
     * @return trimmed display name preserving the caller's original letter casing
     * @throws RoomRegistryException with {@link RoomRegistryErrorCode#INVALID_NAME} if the name is
     *                               {@code null}, blank, outside length limits, or contains invalid characters
     */
    public static String validateAndTrim(String rawDisplayName) throws RoomRegistryException {
        if (rawDisplayName == null) {
            throw new RoomRegistryException(
                    RoomRegistryErrorCode.INVALID_NAME,
                    "Display name must not be null"
            );
        }

        String trimmed = rawDisplayName.trim();
        if (trimmed.isEmpty()) {
            throw new RoomRegistryException(
                    RoomRegistryErrorCode.INVALID_NAME,
                    "Display name must not be empty or blank"
            );
        }

        int codePoints = trimmed.codePointCount(0, trimmed.length());
        if (codePoints < MIN_CODE_POINTS || codePoints > MAX_CODE_POINTS) {
            throw new RoomRegistryException(
                    RoomRegistryErrorCode.INVALID_NAME,
                    "Display name must be " + MIN_CODE_POINTS + ".." + MAX_CODE_POINTS
                            + " characters (code points), found " + codePoints
            );
        }

        int utf8Bytes = trimmed.getBytes(StandardCharsets.UTF_8).length;
        if (utf8Bytes > MAX_UTF8_BYTES) {
            throw new RoomRegistryException(
                    RoomRegistryErrorCode.INVALID_NAME,
                    "Display name must be at most " + MAX_UTF8_BYTES
                            + " UTF-8 bytes, found " + utf8Bytes
            );
        }

        for (int offset = 0; offset < trimmed.length(); ) {
            int cp = trimmed.codePointAt(offset);
            if (cp != ' ' && !Character.isLetterOrDigit(cp)) {
                throw new RoomRegistryException(
                        RoomRegistryErrorCode.INVALID_NAME,
                        "Display name may contain only letters, digits, or spaces"
                );
            }
            offset += Character.charCount(cp);
        }

        return trimmed;
    }

    /**
     * Produces a locale-independent, case-folded canonical key for active display name uniqueness checks.
     *
     * @param trimmedDisplayName validated, trimmed display name
     * @return canonical key folded with {@link Locale#ROOT}
     */
    public static String canonicalKey(String trimmedDisplayName) {
        Objects.requireNonNull(trimmedDisplayName, "trimmedDisplayName must not be null");
        return trimmedDisplayName.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }
}
