package lk.studylink.shared.protocol;

import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Stateful, non-blocking per-channel frame accumulator for StudyLink's 4-byte length-prefixed
 * UTF-8 JSON stream protocol (SRS Sections 5.5, 6.2 Q06, and 6.3).
 *
 * <p>Designed for single-thread use per {@link java.nio.channels.SocketChannel} inside a Java NIO
 * {@link java.nio.channels.Selector} loop (and client NIO channel reader). Handles:
 * <ul>
 *   <li>A complete frame arriving in a single {@code read()},</li>
 *   <li>One frame split across arbitrarily many partial {@code read()} calls (even 1 byte at a time),</li>
 *   <li>Multiple frames (plus trailing partial bytes) arriving in a single {@code read()}, and</li>
 *   <li>Immediate rejection of invalid or oversized declared frame lengths ({@code <= 0} or
 *       {@code > maxFrameBytes}) before allocating payload byte arrays.</li>
 * </ul>
 */
public final class NioFrameAccumulator {

    private final int maxFrameBytes;
    private final byte[] prefixBuffer = new byte[ProtocolConstants.FRAME_LENGTH_PREFIX_BYTES];
    private int prefixBytesRead = 0;

    private byte[] payloadBuffer = null;
    private int payloadBytesRead = 0;

    /**
     * Creates an accumulator enforcing the SRS default frame cap
     * ({@link ProtocolConstants#MAX_FRAME_PAYLOAD_BYTES}, {@code 16,384} bytes).
     */
    public NioFrameAccumulator() {
        this(ProtocolConstants.MAX_FRAME_PAYLOAD_BYTES);
    }

    /**
     * Creates an accumulator enforcing a configured maximum payload size in {@code 1..16,384} bytes.
     *
     * @param maxFrameBytes maximum allowed payload length in bytes
     */
    public NioFrameAccumulator(int maxFrameBytes) {
        if (maxFrameBytes < ProtocolConstants.MIN_FRAME_PAYLOAD_BYTES
                || maxFrameBytes > ProtocolConstants.MAX_FRAME_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "maxFrameBytes must be in "
                            + ProtocolConstants.MIN_FRAME_PAYLOAD_BYTES
                            + ".."
                            + ProtocolConstants.MAX_FRAME_PAYLOAD_BYTES
                            + ", found " + maxFrameBytes
            );
        }
        this.maxFrameBytes = maxFrameBytes;
    }

    /**
     * Consumes all available bytes from {@code readBuffer} (between {@code position()} and
     * {@code limit()}) and returns every complete decoded {@link JsonObject} frame in arrival order.
     *
     * <p>Any incomplete prefix or payload bytes at the end of {@code readBuffer} remain buffered
     * inside this accumulator until subsequent reads supply the rest of the frame.
     *
     * @param readBuffer flipped {@link ByteBuffer} ready for reading
     * @return immutable-order list of decoded JSON frames (empty if no complete frame has arrived yet)
     * @throws FrameFormatException if the 4-byte length prefix is outside {@code 1..maxFrameBytes},
     *                              the payload contains invalid UTF-8, or the payload is not a single
     *                              strict JSON object
     */
    public List<JsonObject> drainFrames(ByteBuffer readBuffer) throws FrameFormatException {
        Objects.requireNonNull(readBuffer, "readBuffer must not be null");
        List<JsonObject> frames = new ArrayList<>();

        while (readBuffer.hasRemaining()) {
            if (payloadBuffer == null) {
                int neededPrefix = ProtocolConstants.FRAME_LENGTH_PREFIX_BYTES - prefixBytesRead;
                int toCopy = Math.min(neededPrefix, readBuffer.remaining());
                readBuffer.get(prefixBuffer, prefixBytesRead, toCopy);
                prefixBytesRead += toCopy;

                if (prefixBytesRead < ProtocolConstants.FRAME_LENGTH_PREFIX_BYTES) {
                    break;
                }

                int declaredLength = FrameCodec.decodeLengthPrefix(prefixBuffer, 0);
                if (declaredLength > maxFrameBytes) {
                    throw new FrameFormatException(
                            "Frame payload length " + declaredLength
                                    + " exceeds configured limit of " + maxFrameBytes + " bytes"
                    );
                }

                payloadBuffer = new byte[declaredLength];
                payloadBytesRead = 0;
            }

            if (payloadBuffer != null && readBuffer.hasRemaining()) {
                int neededPayload = payloadBuffer.length - payloadBytesRead;
                int toCopy = Math.min(neededPayload, readBuffer.remaining());
                readBuffer.get(payloadBuffer, payloadBytesRead, toCopy);
                payloadBytesRead += toCopy;

                if (payloadBytesRead == payloadBuffer.length) {
                    JsonObject decoded = FrameCodec.decodePayloadBytes(payloadBuffer, 0, payloadBuffer.length);
                    frames.add(decoded);
                    resetFrameState();
                }
            }
        }

        return frames;
    }

    /**
     * Returns {@code true} if bytes of an incomplete frame (either partial length prefix or partial
     * payload) are currently buffered awaiting more data.
     */
    public boolean hasPartialFrame() {
        return prefixBytesRead > 0 || payloadBuffer != null;
    }

    /**
     * Resets any buffered partial frame state.
     */
    public void reset() {
        resetFrameState();
    }

    private void resetFrameState() {
        prefixBytesRead = 0;
        payloadBuffer = null;
        payloadBytesRead = 0;
    }
}
