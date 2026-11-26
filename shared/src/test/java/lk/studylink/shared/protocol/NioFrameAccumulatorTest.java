package lk.studylink.shared.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lk.studylink.shared.model.ChatEvent;
import lk.studylink.shared.model.RoomBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link NioFrameAccumulator}, {@link ChatProtocol}, and {@link ChatEvent}.
 */
class NioFrameAccumulatorTest {

    @Test
    void drainsSingleCompleteFrameAndResetsPartialState() throws FrameFormatException {
        NioFrameAccumulator accumulator = new NioFrameAccumulator();
        JsonObject req = ChatProtocol.buildChatSendRequest("c-1", "Hello StudyLink");
        byte[] wire = FrameCodec.encodeFrameBytes(req);

        List<JsonObject> frames = accumulator.drainFrames(ByteBuffer.wrap(wire));
        assertEquals(1, frames.size());
        assertEquals(req, frames.get(0));
        assertFalse(accumulator.hasPartialFrame());
    }

    @Test
    void reassemblesFrameAcrossSingleByteReads() throws FrameFormatException {
        NioFrameAccumulator accumulator = new NioFrameAccumulator();
        JsonObject req = ChatProtocol.buildSubscribeRequest(
                "c-2",
                new RoomBinding("s-101", "netprog", 2L)
        );
        byte[] wire = FrameCodec.encodeFrameBytes(req);

        List<JsonObject> collected = new ArrayList<>();
        for (int i = 0; i < wire.length; i++) {
            ByteBuffer singleByte = ByteBuffer.wrap(wire, i, 1);
            List<JsonObject> decoded = accumulator.drainFrames(singleByte);
            if (i < wire.length - 1) {
                assertTrue(decoded.isEmpty());
                assertTrue(accumulator.hasPartialFrame());
            } else {
                collected.addAll(decoded);
            }
        }

        assertEquals(1, collected.size());
        assertEquals(req, collected.get(0));
        assertFalse(accumulator.hasPartialFrame());
    }

    @Test
    void drainsMultipleCombinedFramesAndPreservesTrailingPartialFrame() throws FrameFormatException {
        NioFrameAccumulator accumulator = new NioFrameAccumulator();
        JsonObject f1 = ChatProtocol.buildChatSendRequest("c-1", "First");
        JsonObject f2 = ChatProtocol.buildChatSendRequest("c-2", "Second");
        JsonObject f3 = ChatProtocol.buildChatSendRequest("c-3", "Third");

        byte[] b1 = FrameCodec.encodeFrameBytes(f1);
        byte[] b2 = FrameCodec.encodeFrameBytes(f2);
        byte[] b3 = FrameCodec.encodeFrameBytes(f3);

        int splitPoint = 7;
        byte[] chunk1 = new byte[b1.length + b2.length + splitPoint];
        System.arraycopy(b1, 0, chunk1, 0, b1.length);
        System.arraycopy(b2, 0, chunk1, b1.length, b2.length);
        System.arraycopy(b3, 0, chunk1, b1.length + b2.length, splitPoint);

        List<JsonObject> firstBatch = accumulator.drainFrames(ByteBuffer.wrap(chunk1));
        assertEquals(List.of(f1, f2), firstBatch);
        assertTrue(accumulator.hasPartialFrame());

        byte[] chunk2 = new byte[b3.length - splitPoint];
        System.arraycopy(b3, splitPoint, chunk2, 0, chunk2.length);

        List<JsonObject> secondBatch = accumulator.drainFrames(ByteBuffer.wrap(chunk2));
        assertEquals(List.of(f3), secondBatch);
        assertFalse(accumulator.hasPartialFrame());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -128, 16_385, 65_536, Integer.MAX_VALUE, Integer.MIN_VALUE})
    void rejectsInvalidOrOversizedFrameLengthBeforeAllocatingPayload(int badLength) {
        NioFrameAccumulator accumulator = new NioFrameAccumulator();
        ByteBuffer prefix = ByteBuffer.allocate(4).putInt(badLength).flip();

        assertThrows(FrameFormatException.class, () -> accumulator.drainFrames(prefix));
    }

    @Test
    void rejectsInvalidUtf8AndMalformedJsonPayloads() {
        NioFrameAccumulator accumulator = new NioFrameAccumulator();
        byte[] invalidUtf8 = new byte[]{(byte) 0xFF, (byte) 0xFE, (byte) 0xFD};
        ByteBuffer frame = ByteBuffer.allocate(4 + invalidUtf8.length)
                .putInt(invalidUtf8.length)
                .put(invalidUtf8)
                .flip();

        assertThrows(FrameFormatException.class, () -> accumulator.drainFrames(frame));

        accumulator.reset();
        byte[] notJsonObject = "[1,2,3]".getBytes(StandardCharsets.UTF_8);
        ByteBuffer arrayFrame = ByteBuffer.allocate(4 + notJsonObject.length)
                .putInt(notJsonObject.length)
                .put(notJsonObject)
                .flip();

        assertThrows(FrameFormatException.class, () -> accumulator.drainFrames(arrayFrame));
    }

    @Test
    void validatesChatMessageUtf8BoundsAndRoundTripsChatEvent() throws FrameFormatException {
        assertEquals(ChatProtocol.ERR_BAD_REQUEST, ChatProtocol.validateChatTextErrorCode(null, 2048));
        assertEquals(ChatProtocol.ERR_BAD_REQUEST, ChatProtocol.validateChatTextErrorCode("", 2048));
        assertEquals(ChatProtocol.ERR_BAD_REQUEST, ChatProtocol.validateChatTextErrorCode("   \t\n ", 2048));
        assertEquals(ChatProtocol.ERR_BAD_REQUEST, ChatProtocol.validateChatTextErrorCode("hi\0there", 2048));

        String exact2048 = "a".repeat(2048);
        assertNull(ChatProtocol.validateChatTextErrorCode(exact2048, 2048));

        String over2048Ascii = "a".repeat(2049);
        assertEquals(ChatProtocol.ERR_TOO_LARGE, ChatProtocol.validateChatTextErrorCode(over2048Ascii, 2048));

        // Multi-byte UTF-8: 683 * 3 bytes = 2,049 UTF-8 bytes
        String sinhalaOver2048 = "අ".repeat(683);
        assertEquals(2049, sinhalaOver2048.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(ChatProtocol.ERR_TOO_LARGE, ChatProtocol.validateChatTextErrorCode(sinhalaOver2048, 2048));

        ChatEvent event = new ChatEvent(
                "m-42",
                "netprog",
                "s-demo",
                "Kasun",
                "ආයුබෝවන් NIO!",
                1_700_000_000_000L,
                "12:00:00"
        );
        JsonObject encoded = ChatProtocol.buildChatEventFrame(event);
        ChatEvent decoded = ChatProtocol.parseChatEventFrame(encoded);
        assertEquals(event, decoded);
    }
}
