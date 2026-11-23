package lk.studylink.shared.protocol;

import java.io.IOException;

/**
 * Signals a StudyLink Protocol v1 stream framing or payload validation error, including:
 * <ul>
 *   <li>declared or encoded payload length outside {@code 1..16,384} bytes,</li>
 *   <li>malformed or unmappable UTF-8 byte sequences,</li>
 *   <li>malformed JSON syntax or trailing non-whitespace tokens, or</li>
 *   <li>a JSON root value that is not a JSON object.</li>
 * </ul>
 */
public final class FrameFormatException extends IOException {

    public FrameFormatException(String message) {
        super(message);
    }

    public FrameFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
