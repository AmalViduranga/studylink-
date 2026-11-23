package lk.studylink.shared.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.MalformedJsonException;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Shared Protocol v1 length-prefixed UTF-8 JSON frame codec (SRS Sections 5.1, 5.5, 6.2 Q06, and 6.3).
 *
 * <h2>Wire Format</h2>
 * Every stream control frame consists of:
 * <ol>
 *   <li>a 4-byte unsigned big-endian payload length prefix {@code L} in network byte order, where
 *       {@link ProtocolConstants#MIN_FRAME_PAYLOAD_BYTES} ({@code 1}) {@code <= L <=}
 *       {@link ProtocolConstants#MAX_FRAME_PAYLOAD_BYTES} ({@code 16,384}), followed by</li>
 *   <li>exactly {@code L} bytes of UTF-8 encoded JSON whose root element is a JSON object
 *       ({@code {...}}).</li>
 * </ol>
 *
 * <h2>Blocking Stream Behavior ({@code M1}, {@code M3}, {@code M4})</h2>
 * <ul>
 *   <li>{@link #writeFrame(OutputStream, JsonObject)} serializes and validates the full frame in memory
 *       before touching the stream. If the UTF-8 payload exceeds {@code 16,384} bytes, it throws
 *       {@link FrameFormatException} without writing any partial prefix or payload bytes.</li>
 *   <li>{@link #readFrame(InputStream)} reads raw bytes directly from the {@link InputStream} without
 *       wrapping it in a buffered reader, ensuring that trailing bytes belonging to the next frame or
 *       a raw file body ({@code M3}) remain unread in the stream.</li>
 *   <li><strong>Clean EOF:</strong> If the stream reaches end-of-file before the first byte of the
 *       4-byte length prefix is read, {@link #readFrame(InputStream)} returns {@code null} (and
 *       {@link #readOptionalFrame(InputStream)} returns {@link Optional#empty()}).</li>
 *   <li><strong>Truncated frame:</strong> If EOF occurs after 1–3 prefix bytes or before all {@code L}
 *       payload bytes arrive, {@link #readFrame(InputStream)} throws {@link EOFException}.</li>
 *   <li><strong>Malformed frame:</strong> If the declared length is outside {@code 1..16,384} bytes,
 *       the payload is not valid UTF-8, the JSON syntax is invalid, trailing non-whitespace tokens exist,
 *       or the root JSON value is not an object, {@link #readFrame(InputStream)} throws
 *       {@link FrameFormatException}.</li>
 * </ul>
 *
 * <h2>Non-Blocking NIO Reuse ({@code M5})</h2>
 * Member 5's {@code Selector} loop manages channel registrations, per-client {@link ByteBuffer}
 * accumulation, and partial non-blocking writes, while reusing the wire rules in this class:
 * <ul>
 *   <li>Outbound NIO frames: call {@link #encodeFrameBytes(JsonObject)} (or
 *       {@link #encodePayloadBytes(JsonObject)} + {@link #encodeLengthPrefix(int)}) and enqueue
 *       {@code ByteBuffer.wrap(frameBytes)} on the channel's pending write queue.</li>
 *   <li>Inbound NIO frames: once 4 prefix bytes are accumulated in a channel's input buffer, validate
 *       the declared length with {@link #validatePayloadLength(int)} (or
 *       {@link #decodeLengthPrefix(byte[], int)}) before allocating or awaiting payload bytes; once
 *       {@code L} payload bytes have arrived, call {@link #decodePayloadBytes(byte[], int, int)} to
 *       validate UTF-8 and parse the {@link JsonObject}.</li>
 * </ul>
 */
public final class FrameCodec {

    private static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .setStrictness(Strictness.STRICT)
            .create();

    private FrameCodec() {
    }

    /**
     * Writes one length-prefixed UTF-8 JSON object frame to {@code out} and flushes the stream.
     * The caller retains ownership of closing {@code out}.
     *
     * @param out     destination output stream (must not be {@code null})
     * @param payload JSON object payload to serialize (must not be {@code null})
     * @throws FrameFormatException if the UTF-8 encoded payload size is outside {@code 1..16,384} bytes
     *                              (no bytes are written to {@code out} when this occurs)
     * @throws IOException          if an underlying stream write or flush fails
     */
    public static void writeFrame(OutputStream out, JsonObject payload) throws IOException {
        Objects.requireNonNull(out, "out must not be null");
        Objects.requireNonNull(payload, "payload must not be null");

        byte[] frameBytes = encodeFrameBytes(payload);
        out.write(frameBytes);
        out.flush();
    }

    /**
     * Reads exactly one length-prefixed UTF-8 JSON object frame from {@code in} without reading ahead
     * past the end of the frame. The caller retains ownership of closing {@code in}.
     *
     * @param in source input stream (must not be {@code null})
     * @return the decoded {@link JsonObject}, or {@code null} on a clean EOF before any byte of the
     *         4-byte length prefix is read
     * @throws EOFException         if EOF occurs mid-prefix (after 1–3 bytes) or mid-payload (before
     *                              all {@code L} declared payload bytes are read)
     * @throws FrameFormatException if the declared length is outside {@code 1..16,384} bytes (checked
     *                              before allocating the payload buffer), or if the payload is not
     *                              valid UTF-8 or not a single strict JSON object
     * @throws IOException          if an underlying stream read fails
     */
    public static JsonObject readFrame(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in must not be null");

        byte[] prefix = new byte[ProtocolConstants.FRAME_LENGTH_PREFIX_BYTES];
        int prefixRead = 0;
        while (prefixRead < ProtocolConstants.FRAME_LENGTH_PREFIX_BYTES) {
            int n = in.read(prefix, prefixRead, ProtocolConstants.FRAME_LENGTH_PREFIX_BYTES - prefixRead);
            if (n < 0) {
                if (prefixRead == 0) {
                    return null;
                }
                throw new EOFException("Truncated frame length prefix: expected "
                        + ProtocolConstants.FRAME_LENGTH_PREFIX_BYTES
                        + " bytes, received " + prefixRead + " before EOF");
            }
            prefixRead += n;
        }

        int payloadLength = decodeLengthPrefix(prefix, 0);

        byte[] payload = new byte[payloadLength];
        int payloadRead = 0;
        while (payloadRead < payloadLength) {
            int n = in.read(payload, payloadRead, payloadLength - payloadRead);
            if (n < 0) {
                throw new EOFException("Truncated frame payload: expected " + payloadLength
                        + " bytes, received " + payloadRead + " before EOF");
            }
            payloadRead += n;
        }

        return decodePayloadBytes(payload, 0, payloadLength);
    }

    /**
     * Convenience wrapper around {@link #readFrame(InputStream)} returning {@link Optional#empty()}
     * on clean EOF at a frame boundary.
     *
     * @param in source input stream (must not be {@code null})
     * @return {@link Optional} containing the decoded {@link JsonObject}, or {@link Optional#empty()} on clean EOF
     * @throws EOFException         on truncated prefix or truncated payload
     * @throws FrameFormatException on invalid frame length, invalid UTF-8, or non-object/malformed JSON
     * @throws IOException          on underlying stream I/O failure
     */
    public static Optional<JsonObject> readOptionalFrame(InputStream in) throws IOException {
        return Optional.ofNullable(readFrame(in));
    }

    /**
     * Encodes a {@link JsonObject} into a complete wire frame ({@code 4-byte big-endian length + UTF-8 JSON bytes}).
     * Suitable for both blocking stream writes and M5 NIO outbound {@link ByteBuffer} queueing.
     *
     * @param payload JSON object payload (must not be {@code null})
     * @return complete wire frame bytes ({@code 4 + L} bytes)
     * @throws FrameFormatException if the UTF-8 payload byte count is outside {@code 1..16,384}
     */
    public static byte[] encodeFrameBytes(JsonObject payload) throws FrameFormatException {
        Objects.requireNonNull(payload, "payload must not be null");
        byte[] payloadBytes = encodePayloadBytes(payload);
        int length = payloadBytes.length;
        byte[] frame = new byte[ProtocolConstants.FRAME_LENGTH_PREFIX_BYTES + length];
        frame[0] = (byte) ((length >>> 24) & 0xFF);
        frame[1] = (byte) ((length >>> 16) & 0xFF);
        frame[2] = (byte) ((length >>> 8) & 0xFF);
        frame[3] = (byte) (length & 0xFF);
        System.arraycopy(payloadBytes, 0, frame, ProtocolConstants.FRAME_LENGTH_PREFIX_BYTES, length);
        return frame;
    }

    /**
     * Serializes a {@link JsonObject} to UTF-8 bytes, preserving explicit JSON {@code null} fields,
     * rejecting non-finite numeric values ({@code NaN}, {@code Infinity}, {@code -Infinity}), and
     * verifying that its UTF-8 byte length is within {@code 1..16,384} bytes.
     *
     * @param payload JSON object payload (must not be {@code null})
     * @return UTF-8 encoded JSON object bytes
     * @throws FrameFormatException if the payload contains non-finite numbers or the encoded byte
     *                              length is outside {@code 1..16,384}
     */
    public static byte[] encodePayloadBytes(JsonObject payload) throws FrameFormatException {
        Objects.requireNonNull(payload, "payload must not be null");
        validateOutboundElement(payload);
        final String json;
        try {
            json = GSON.toJson(payload);
        } catch (IllegalArgumentException | JsonParseException e) {
            throw new FrameFormatException("Frame payload contains invalid JSON value: " + e.getMessage(), e);
        }
        byte[] utf8 = json.getBytes(StandardCharsets.UTF_8);
        validatePayloadLength(utf8.length);
        return utf8;
    }

    private static void validateOutboundElement(JsonElement element) throws FrameFormatException {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                validateOutboundElement(entry.getValue());
            }
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                validateOutboundElement(item);
            }
            return;
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                Number number = primitive.getAsNumber();
                if (number instanceof Double d && (!Double.isFinite(d))) {
                    throw new FrameFormatException("Frame payload contains non-finite numeric value: " + d);
                }
                if (number instanceof Float f && (!Float.isFinite(f))) {
                    throw new FrameFormatException("Frame payload contains non-finite numeric value: " + f);
                }
                double asDouble = number.doubleValue();
                if (Double.isNaN(asDouble)) {
                    throw new FrameFormatException("Frame payload contains NaN numeric value");
                }
            }
        }
    }

    /**
     * Encodes a validated payload byte length into a 4-byte unsigned big-endian prefix.
     *
     * @param payloadLength UTF-8 payload byte length
     * @return 4-byte big-endian length prefix
     * @throws FrameFormatException if {@code payloadLength} is outside {@code 1..16,384}
     */
    public static byte[] encodeLengthPrefix(int payloadLength) throws FrameFormatException {
        validatePayloadLength(payloadLength);
        return new byte[]{
                (byte) ((payloadLength >>> 24) & 0xFF),
                (byte) ((payloadLength >>> 16) & 0xFF),
                (byte) ((payloadLength >>> 8) & 0xFF),
                (byte) (payloadLength & 0xFF)
        };
    }

    /**
     * Decodes and validates a 4-byte big-endian length prefix starting at index 0.
     *
     * @param prefixBytes byte array containing at least 4 bytes
     * @return validated payload length in {@code 1..16,384}
     * @throws FrameFormatException if the decoded length is outside {@code 1..16,384}
     */
    public static int decodeLengthPrefix(byte[] prefixBytes) throws FrameFormatException {
        return decodeLengthPrefix(prefixBytes, 0);
    }

    /**
     * Decodes and validates a 4-byte big-endian length prefix from {@code buffer} at {@code offset}.
     *
     * @param buffer byte array containing the 4-byte prefix
     * @param offset starting index of the 4-byte prefix
     * @return validated payload length in {@code 1..16,384}
     * @throws FrameFormatException if the decoded unsigned 32-bit length is outside {@code 1..16,384}
     */
    public static int decodeLengthPrefix(byte[] buffer, int offset) throws FrameFormatException {
        Objects.requireNonNull(buffer, "buffer must not be null");
        if (offset < 0 || buffer.length - offset < ProtocolConstants.FRAME_LENGTH_PREFIX_BYTES) {
            throw new IllegalArgumentException("Buffer must contain at least 4 bytes from offset " + offset);
        }
        int rawLength = ((buffer[offset] & 0xFF) << 24)
                | ((buffer[offset + 1] & 0xFF) << 16)
                | ((buffer[offset + 2] & 0xFF) << 8)
                | (buffer[offset + 3] & 0xFF);
        return validatePayloadLength(rawLength);
    }

    /**
     * Validates that a declared 32-bit big-endian frame length (interpreted as an unsigned 32-bit integer)
     * lies within {@link ProtocolConstants#MIN_FRAME_PAYLOAD_BYTES} ({@code 1}) and
     * {@link ProtocolConstants#MAX_FRAME_PAYLOAD_BYTES} ({@code 16,384}).
     *
     * @param declaredLength 32-bit integer decoded from the 4-byte big-endian prefix
     * @return {@code declaredLength} if valid
     * @throws FrameFormatException if {@code declaredLength <= 0} or {@code declaredLength > 16,384}
     */
    public static int validatePayloadLength(int declaredLength) throws FrameFormatException {
        long unsignedLength = Integer.toUnsignedLong(declaredLength);
        if (declaredLength < ProtocolConstants.MIN_FRAME_PAYLOAD_BYTES
                || declaredLength > ProtocolConstants.MAX_FRAME_PAYLOAD_BYTES) {
            throw new FrameFormatException("Frame payload length out of bounds: "
                    + unsignedLength + " bytes (expected "
                    + ProtocolConstants.MIN_FRAME_PAYLOAD_BYTES + ".."
                    + ProtocolConstants.MAX_FRAME_PAYLOAD_BYTES + ")");
        }
        return declaredLength;
    }

    /**
     * Strictly decodes and validates a UTF-8 JSON object payload from the entire {@code payloadBytes} array.
     *
     * @param payloadBytes raw payload byte array
     * @return validated {@link JsonObject}
     * @throws FrameFormatException if the length is outside {@code 1..16,384}, UTF-8 decoding fails,
     *                              JSON syntax is malformed, trailing tokens exist, or the root is not a JSON object
     */
    public static JsonObject decodePayloadBytes(byte[] payloadBytes) throws FrameFormatException {
        Objects.requireNonNull(payloadBytes, "payloadBytes must not be null");
        return decodePayloadBytes(payloadBytes, 0, payloadBytes.length);
    }

    /**
     * Strictly decodes and validates a UTF-8 JSON object payload from {@code buffer[offset .. offset + length)}.
     *
     * @param buffer byte array containing the payload
     * @param offset start offset in {@code buffer}
     * @param length number of payload bytes
     * @return validated {@link JsonObject}
     * @throws FrameFormatException if {@code length} is outside {@code 1..16,384}, UTF-8 decoding fails,
     *                              JSON syntax is malformed, trailing tokens exist, or the root is not a JSON object
     */
    public static JsonObject decodePayloadBytes(byte[] buffer, int offset, int length) throws FrameFormatException {
        Objects.requireNonNull(buffer, "buffer must not be null");
        if (offset < 0 || length < 0 || offset > buffer.length - length) {
            throw new IllegalArgumentException(
                    "Invalid slice offset=" + offset + ", length=" + length + " for buffer length=" + buffer.length);
        }
        validatePayloadLength(length);

        CharsetDecoder utf8Decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        final String jsonText;
        try {
            jsonText = utf8Decoder.decode(ByteBuffer.wrap(buffer, offset, length)).toString();
        } catch (CharacterCodingException e) {
            throw new FrameFormatException("Frame payload contains invalid UTF-8 bytes: " + e.getMessage(), e);
        }

        try (JsonReader reader = new JsonReader(new StringReader(jsonText))) {
            reader.setStrictness(Strictness.STRICT);
            JsonToken firstToken = reader.peek();
            if (firstToken == JsonToken.END_DOCUMENT) {
                throw new FrameFormatException("Frame payload contains no JSON element");
            }
            if (firstToken != JsonToken.BEGIN_OBJECT) {
                throw new FrameFormatException(
                        "Frame payload root must be a JSON object, but found " + firstToken);
            }

            JsonElement element = JsonParser.parseReader(reader);
            JsonToken trailingToken = reader.peek();
            if (trailingToken != JsonToken.END_DOCUMENT) {
                throw new FrameFormatException(
                        "Frame payload contains trailing non-whitespace content (" + trailingToken + ") after root JSON object");
            }
            if (!element.isJsonObject()) {
                throw new FrameFormatException("Frame payload did not decode to a JSON object");
            }
            return element.getAsJsonObject();
        } catch (FrameFormatException e) {
            throw e;
        } catch (MalformedJsonException | JsonParseException | IllegalStateException e) {
            throw new FrameFormatException("Frame payload contains malformed JSON: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new FrameFormatException("Failed to read JSON payload: " + e.getMessage(), e);
        }
    }
}
