package lk.studylink.shared.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Deterministic unit tests for {@link FrameCodec} and {@link FrameFormatException} (Issue M1-01).
 */
class FrameCodecTest {

    @Test
    void encodesKnownWireBytesIndependentlyOfDecoder() throws IOException {
        JsonObject joinRequest = new JsonObject();
        joinRequest.addProperty("v", 1);
        joinRequest.addProperty("id", "r17");
        joinRequest.addProperty("op", "JOIN");
        JsonObject data = new JsonObject();
        data.addProperty("roomId", "netprog");
        joinRequest.add("data", data);

        String expectedJson = "{\"v\":1,\"id\":\"r17\",\"op\":\"JOIN\",\"data\":{\"roomId\":\"netprog\"}}";
        byte[] expectedPayload = expectedJson.getBytes(StandardCharsets.UTF_8);
        assertEquals(58, expectedPayload.length); // 58 == 0x3A

        byte[] expectedWire = new byte[4 + expectedPayload.length];
        expectedWire[0] = 0x00;
        expectedWire[1] = 0x00;
        expectedWire[2] = 0x00;
        expectedWire[3] = 0x3A;
        System.arraycopy(expectedPayload, 0, expectedWire, 4, expectedPayload.length);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameCodec.writeFrame(out, joinRequest);
        assertArrayEquals(expectedWire, out.toByteArray());

        JsonObject decoded = FrameCodec.readFrame(new ByteArrayInputStream(expectedWire));
        assertEquals(joinRequest, decoded);
    }

    @Test
    void roundTripsSinhalaAndUnicodeUsingUtf8ByteLength() throws IOException {
        String sinhalaText = "ආයුබෝවන් StudyLink — ජාල ක්‍රමලේඛනය 📚";
        JsonObject message = new JsonObject();
        message.addProperty("v", 1);
        message.addProperty("op", "CHAT_SEND");
        message.addProperty("text", sinhalaText);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameCodec.writeFrame(out, message);
        byte[] wire = out.toByteArray();

        int declaredByteLength = ByteBuffer.wrap(wire, 0, 4).getInt();
        int actualPayloadByteLength = wire.length - 4;
        assertEquals(actualPayloadByteLength, declaredByteLength);

        String payloadAsString = new String(wire, 4, declaredByteLength, StandardCharsets.UTF_8);
        assertTrue(declaredByteLength > payloadAsString.length(),
                "Multi-byte Sinhala UTF-8 payload byte count must exceed UTF-16 char count");

        JsonObject decoded = FrameCodec.readFrame(new ByteArrayInputStream(wire));
        assertEquals(sinhalaText, decoded.get("text").getAsString());
    }

    @Test
    void reassemblesFrameAcrossShortReadsInPrefixAndPayload() throws IOException {
        JsonObject original = new JsonObject();
        original.addProperty("v", 1);
        original.addProperty("id", "r42");
        original.addProperty("op", "HELLO");
        original.addProperty("displayName", "Kasun Perera");

        byte[] wire = FrameCodec.encodeFrameBytes(original);

        for (int maxChunkSize : new int[]{1, 2, 3, 5}) {
            InputStream dripStream = new DripInputStream(wire, maxChunkSize);
            JsonObject decoded = FrameCodec.readFrame(dripStream);
            assertEquals(original, decoded, "Failed to reassemble frame with chunk size " + maxChunkSize);
            assertNull(FrameCodec.readFrame(dripStream));
        }
    }

    @Test
    void readsTwoConcatenatedFramesInOrderFromPersistentStream() throws IOException {
        JsonObject first = new JsonObject();
        first.addProperty("v", 1);
        first.addProperty("id", "r1");
        first.addProperty("op", "LIST_ROOMS");

        JsonObject second = new JsonObject();
        second.addProperty("v", 1);
        second.addProperty("id", "r2");
        second.addProperty("op", "JOIN");
        second.addProperty("roomId", "revision");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameCodec.writeFrame(out, first);
        FrameCodec.writeFrame(out, second);

        InputStream in = new DripInputStream(out.toByteArray(), 2);
        assertEquals(first, FrameCodec.readFrame(in));
        assertEquals(second, FrameCodec.readFrame(in));
        assertNull(FrameCodec.readFrame(in));
    }

    @Test
    void preservesTrailingRawBytesAfterFrameWithoutReadAheadLoss() throws IOException {
        JsonObject metadataFrame = new JsonObject();
        metadataFrame.addProperty("v", 1);
        metadataFrame.addProperty("ok", true);
        metadataFrame.addProperty("op", "READY");
        metadataFrame.addProperty("size", 16);

        byte[] rawFileBody = "%PDF-1.7\nRAWBODY".getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream combined = new ByteArrayOutputStream();
        FrameCodec.writeFrame(combined, metadataFrame);
        combined.write(rawFileBody);

        ByteArrayInputStream in = new ByteArrayInputStream(combined.toByteArray());
        JsonObject decodedHeader = FrameCodec.readFrame(in);
        assertEquals(metadataFrame, decodedHeader);

        byte[] remainingBytes = in.readAllBytes();
        assertArrayEquals(rawFileBody, remainingBytes,
                "FrameCodec must leave all trailing raw file bytes unread for the caller");
    }

    @Test
    void acceptsExactMaximumPayloadSizeOf16384Bytes() throws IOException {
        JsonObject payload = buildPayloadWithExactUtf8Size(ProtocolConstants.MAX_FRAME_PAYLOAD_BYTES);
        byte[] utf8Bytes = FrameCodec.encodePayloadBytes(payload);
        assertEquals(ProtocolConstants.MAX_FRAME_PAYLOAD_BYTES, utf8Bytes.length);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameCodec.writeFrame(out, payload);
        byte[] wire = out.toByteArray();

        assertEquals(ProtocolConstants.FRAME_LENGTH_PREFIX_BYTES + ProtocolConstants.MAX_FRAME_PAYLOAD_BYTES, wire.length);
        assertEquals(0x00, wire[0] & 0xFF);
        assertEquals(0x00, wire[1] & 0xFF);
        assertEquals(0x40, wire[2] & 0xFF);
        assertEquals(0x00, wire[3] & 0xFF);

        JsonObject decoded = FrameCodec.readFrame(new ByteArrayInputStream(wire));
        assertEquals(payload, decoded);
    }

    @ParameterizedTest
    @ValueSource(ints = {
            0,
            ProtocolConstants.MAX_FRAME_PAYLOAD_BYTES + 1,
            100_000,
            0x7FFFFFFF,
            0x80000000,
            0xFFFFFFFF
    })
    void rejectsInvalidInboundLengthsBeforeReadingOrAllocatingPayload(int invalidLength) {
        byte[] prefixAndDummyPayload = new byte[8];
        ByteBuffer.wrap(prefixAndDummyPayload, 0, 4).putInt(invalidLength);
        Arrays.fill(prefixAndDummyPayload, 4, 8, (byte) 'x');

        AtomicInteger bytesReadCounter = new AtomicInteger();
        InputStream trackingStream = new InputStream() {
            private int pos = 0;

            @Override
            public int read() {
                if (pos >= prefixAndDummyPayload.length) {
                    return -1;
                }
                bytesReadCounter.incrementAndGet();
                return prefixAndDummyPayload[pos++] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (pos >= prefixAndDummyPayload.length) {
                    return -1;
                }
                int toCopy = Math.min(len, prefixAndDummyPayload.length - pos);
                System.arraycopy(prefixAndDummyPayload, pos, b, off, toCopy);
                pos += toCopy;
                bytesReadCounter.addAndGet(toCopy);
                return toCopy;
            }
        };

        assertThrows(FrameFormatException.class, () -> FrameCodec.readFrame(trackingStream));
        assertEquals(ProtocolConstants.FRAME_LENGTH_PREFIX_BYTES, bytesReadCounter.get(),
                "Codec must reject invalid length immediately after 4-byte prefix without reading payload");
    }

    @Test
    void returnsNullAndEmptyOptionalOnCleanEofAtFrameBoundary() throws IOException {
        byte[] empty = new byte[0];
        assertNull(FrameCodec.readFrame(new ByteArrayInputStream(empty)));
        assertEquals(Optional.empty(), FrameCodec.readOptionalFrame(new ByteArrayInputStream(empty)));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void throwsEofExceptionOnTruncatedLengthPrefix(int prefixBytesAvailable) {
        byte[] truncatedPrefix = new byte[prefixBytesAvailable];
        assertThrows(EOFException.class, () -> FrameCodec.readFrame(new ByteArrayInputStream(truncatedPrefix)));
    }

    @Test
    void throwsEofExceptionOnTruncatedPayload() {
        byte[] wire = new byte[]{0x00, 0x00, 0x00, 0x10, '{', '"', 'v', '"', ':', '1'};
        assertThrows(EOFException.class, () -> FrameCodec.readFrame(new ByteArrayInputStream(wire)));
    }

    @Test
    void rejectsMalformedUtf8BytesInPayload() {
        byte[] invalidUtf8Payload = new byte[]{'{', '"', 'a', '"', ':', '"', (byte) 0xFF, (byte) 0xFE, '"', '}'};
        byte[] wire = frameRawBytes(invalidUtf8Payload);

        assertThrows(FrameFormatException.class, () -> FrameCodec.readFrame(new ByteArrayInputStream(wire)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{",
            "{\"v\":1,",
            "{unquotedKey:1}",
            "{\"v\":1}{\"v\":2}",
            "{\"v\":1} trailing",
            "[]",
            "[1,2,3]",
            "\"just-a-string\"",
            "12345",
            "true",
            "null",
            "   "
    })
    void rejectsMalformedJsonTrailingTokensAndNonObjectRoots(String badPayloadText) {
        byte[] payloadBytes = badPayloadText.getBytes(StandardCharsets.UTF_8);
        byte[] wire = frameRawBytes(payloadBytes);

        assertThrows(FrameFormatException.class, () -> FrameCodec.readFrame(new ByteArrayInputStream(wire)));
    }

    @Test
    void rejectsOversizedOutboundFrameWithoutWritingPartialBytes() {
        JsonObject oversized = buildPayloadWithExactUtf8Size(ProtocolConstants.MAX_FRAME_PAYLOAD_BYTES + 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertThrows(FrameFormatException.class, () -> FrameCodec.writeFrame(out, oversized));
        assertEquals(0, out.size(), "Oversized outbound frame must not write any partial prefix or payload bytes");
    }

    @Test
    void preservesExplicitNullFieldsAtTopLevelAndNestedObjectsAndDistinguishesMissingProperties() throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("v", 1);
        payload.add("topNull", JsonNull.INSTANCE);
        JsonObject data = new JsonObject();
        data.add("roomId", JsonNull.INSTANCE);
        payload.add("data", data);

        String expectedJson = "{\"v\":1,\"topNull\":null,\"data\":{\"roomId\":null}}";
        assertEquals(expectedJson, new String(FrameCodec.encodePayloadBytes(payload), StandardCharsets.UTF_8));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameCodec.writeFrame(out, payload);
        JsonObject decoded = FrameCodec.readFrame(new ByteArrayInputStream(out.toByteArray()));
        assertNotNull(decoded);

        // Top-level explicit null vs missing key
        assertTrue(decoded.has("topNull"), "Top-level explicit null key must be preserved");
        assertTrue(decoded.get("topNull").isJsonNull(), "Top-level explicit null value must be JsonNull");
        assertEquals(JsonNull.INSTANCE, decoded.get("topNull"));
        assertFalse(decoded.has("missingTop"), "Omitted top-level key must remain absent");
        assertNull(decoded.get("missingTop"));

        // Nested explicit null vs missing key
        JsonObject decodedData = decoded.getAsJsonObject("data");
        assertNotNull(decodedData);
        assertTrue(decodedData.has("roomId"), "Nested explicit null key 'roomId' must be preserved");
        assertTrue(decodedData.get("roomId").isJsonNull(), "Nested 'roomId' value must be JsonNull");
        assertEquals(JsonNull.INSTANCE, decodedData.get("roomId"));
        assertFalse(decodedData.has("missingNested"), "Omitted nested key must remain absent");
        assertNull(decodedData.get("missingNested"));
    }

    @ParameterizedTest
    @ValueSource(doubles = {
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY
    })
    void rejectsNonFiniteNumbersOnOutboundWriteAndEncodeWithZeroBytesWritten(double nonFinite) {
        // 1. Direct top-level numeric property
        JsonObject topLevel = new JsonObject();
        topLevel.addProperty("v", 1);
        topLevel.addProperty("metric", nonFinite);

        ByteArrayOutputStream topOut = new ByteArrayOutputStream();
        assertThrows(FrameFormatException.class, () -> FrameCodec.writeFrame(topOut, topLevel));
        assertEquals(0, topOut.size(), "writeFrame must write 0 bytes when rejecting top-level non-finite number");
        assertThrows(FrameFormatException.class, () -> FrameCodec.encodeFrameBytes(topLevel));
        assertThrows(FrameFormatException.class, () -> FrameCodec.encodePayloadBytes(topLevel));

        // 2. Nested object numeric property
        JsonObject nestedObj = new JsonObject();
        nestedObj.addProperty("v", 1);
        JsonObject child = new JsonObject();
        child.addProperty("latency", nonFinite);
        nestedObj.add("data", child);

        ByteArrayOutputStream nestedOut = new ByteArrayOutputStream();
        assertThrows(FrameFormatException.class, () -> FrameCodec.writeFrame(nestedOut, nestedObj));
        assertEquals(0, nestedOut.size(), "writeFrame must write 0 bytes when rejecting nested non-finite number");
        assertThrows(FrameFormatException.class, () -> FrameCodec.encodeFrameBytes(nestedObj));
        assertThrows(FrameFormatException.class, () -> FrameCodec.encodePayloadBytes(nestedObj));

        // 3. Nested array numeric element
        JsonObject arrayHolder = new JsonObject();
        arrayHolder.addProperty("v", 1);
        JsonArray array = new JsonArray();
        array.add(1.0);
        array.add(nonFinite);
        arrayHolder.add("samples", array);

        ByteArrayOutputStream arrayOut = new ByteArrayOutputStream();
        assertThrows(FrameFormatException.class, () -> FrameCodec.writeFrame(arrayOut, arrayHolder));
        assertEquals(0, arrayOut.size(), "writeFrame must write 0 bytes when rejecting array non-finite number");
        assertThrows(FrameFormatException.class, () -> FrameCodec.encodeFrameBytes(arrayHolder));
        assertThrows(FrameFormatException.class, () -> FrameCodec.encodePayloadBytes(arrayHolder));
    }

    @Test
    void acceptsFiniteNumbersAndLiteralNaNStringOnRoundTrip() throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("v", 1);
        payload.addProperty("zero", 0);
        payload.addProperty("negativeInt", -42);
        payload.addProperty("decimal", 3.14159);
        payload.addProperty("statusText", "NaN");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameCodec.writeFrame(out, payload);

        JsonObject decoded = FrameCodec.readFrame(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(payload, decoded);
        assertEquals(0, decoded.get("zero").getAsInt());
        assertEquals(-42, decoded.get("negativeInt").getAsInt());
        assertEquals(3.14159, decoded.get("decimal").getAsDouble(), 1e-9);
        assertEquals("NaN", decoded.get("statusText").getAsString());
    }

    private static JsonObject buildPayloadWithExactUtf8Size(int targetBytes) {
        JsonObject obj = new JsonObject();
        obj.addProperty("pad", "");
        int baseSize = obj.toString().getBytes(StandardCharsets.UTF_8).length;
        int paddingLength = targetBytes - baseSize;
        assertTrue(paddingLength >= 0, "targetBytes must be at least " + baseSize);
        obj.addProperty("pad", "a".repeat(paddingLength));
        return obj;
    }

    private static byte[] frameRawBytes(byte[] payload) {
        byte[] wire = new byte[4 + payload.length];
        ByteBuffer.wrap(wire, 0, 4).putInt(payload.length);
        System.arraycopy(payload, 0, wire, 4, payload.length);
        return wire;
    }

    /**
     * Test stream that limits each {@code read(byte[], int, int)} to at most {@code maxChunkSize} bytes
     * to simulate fragmented TCP reads across prefix and payload boundaries.
     */
    private static final class DripInputStream extends InputStream {
        private final byte[] data;
        private final int maxChunkSize;
        private int position = 0;

        private DripInputStream(byte[] data, int maxChunkSize) {
            this.data = data;
            this.maxChunkSize = maxChunkSize;
        }

        @Override
        public int read() {
            if (position >= data.length) {
                return -1;
            }
            return data[position++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (position >= data.length) {
                return -1;
            }
            if (len == 0) {
                return 0;
            }
            int chunk = Math.min(Math.min(len, maxChunkSize), data.length - position);
            System.arraycopy(data, position, b, off, chunk);
            position += chunk;
            return chunk;
        }
    }
}
