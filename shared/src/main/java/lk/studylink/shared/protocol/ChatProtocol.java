package lk.studylink.shared.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import lk.studylink.shared.model.ChatEvent;
import lk.studylink.shared.model.RoomBinding;

/**
 * Shared Protocol v1 wire constants, envelope builders, and validation helpers for Member 5's
 * Live Room Chat service over Java NIO (TCP port {@code 45402}).
 */
public final class ChatProtocol {

    public static final String OP_SUBSCRIBE = "SUBSCRIBE";
    public static final String OP_CHAT_SEND = "CHAT_SEND";
    public static final String OP_CHAT_EVENT = "CHAT_EVENT";

    public static final String ERR_BAD_REQUEST = "BAD_REQUEST";
    public static final String ERR_UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION";
    public static final String ERR_UNSUPPORTED_OP = "UNSUPPORTED_OP";
    public static final String ERR_NOT_SUBSCRIBED = "NOT_SUBSCRIBED";
    public static final String ERR_STALE_BINDING = "STALE_BINDING";
    public static final String ERR_TOO_LARGE = "TOO_LARGE";

    private ChatProtocol() {
    }

    /**
     * Builds a {@code SUBSCRIBE} request JSON frame payload for {@code binding}.
     */
    public static JsonObject buildSubscribeRequest(String requestId, RoomBinding binding) {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(binding, "binding must not be null");

        JsonObject data = new JsonObject();
        data.addProperty("sessionId", binding.sessionId());
        data.addProperty("roomId", binding.roomId());
        data.addProperty("revision", binding.revision());

        JsonObject frame = new JsonObject();
        frame.addProperty("v", ProtocolConstants.PROTOCOL_VERSION);
        frame.addProperty("id", requestId);
        frame.addProperty("op", OP_SUBSCRIBE);
        frame.add("data", data);
        return frame;
    }

    /**
     * Builds a {@code CHAT_SEND} request JSON frame payload carrying {@code text}.
     */
    public static JsonObject buildChatSendRequest(String requestId, String text) {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(text, "text must not be null");

        JsonObject data = new JsonObject();
        data.addProperty("text", text);

        JsonObject frame = new JsonObject();
        frame.addProperty("v", ProtocolConstants.PROTOCOL_VERSION);
        frame.addProperty("id", requestId);
        frame.addProperty("op", OP_CHAT_SEND);
        frame.add("data", data);
        return frame;
    }

    /**
     * Builds a server-pushed {@code CHAT_EVENT} JSON frame payload from a {@link ChatEvent}.
     */
    public static JsonObject buildChatEventFrame(ChatEvent event) {
        Objects.requireNonNull(event, "event must not be null");

        JsonObject data = new JsonObject();
        data.addProperty("messageId", event.messageId());
        data.addProperty("roomId", event.roomId());
        data.addProperty("senderSessionId", event.senderSessionId());
        data.addProperty("senderName", event.senderName());
        data.addProperty("text", event.text());
        data.addProperty("sentAtEpochMs", event.sentAtEpochMs());
        data.addProperty("displayTime", event.displayTime());

        JsonObject frame = new JsonObject();
        frame.addProperty("v", ProtocolConstants.PROTOCOL_VERSION);
        frame.addProperty("op", OP_CHAT_EVENT);
        frame.add("data", data);
        return frame;
    }

    /**
     * Parses a server-pushed {@code CHAT_EVENT} frame into a {@link ChatEvent}.
     *
     * @throws FrameFormatException if any required field is missing or invalid
     */
    public static ChatEvent parseChatEventFrame(JsonObject frame) throws FrameFormatException {
        Objects.requireNonNull(frame, "frame must not be null");
        if (!hasSupportedProtocolVersion(frame)) {
            throw new FrameFormatException("Unsupported or missing protocol version in CHAT_EVENT");
        }
        String op = extractStrictString(frame, "op");
        if (!OP_CHAT_EVENT.equals(op)) {
            throw new FrameFormatException("Expected op='" + OP_CHAT_EVENT + "', found '" + op + "'");
        }
        JsonObject data = extractDataObject(frame);
        if (data == null) {
            throw new FrameFormatException("CHAT_EVENT missing 'data' object");
        }

        String messageId = extractStrictString(data, "messageId");
        String roomId = extractStrictString(data, "roomId");
        String senderSessionId = extractStrictString(data, "senderSessionId");
        String senderName = extractStrictString(data, "senderName");
        String text = extractStrictString(data, "text");
        Long sentAtEpochMs = extractStrictLong(data, "sentAtEpochMs");
        String displayTime = extractStrictString(data, "displayTime");

        if (messageId == null || roomId == null || senderSessionId == null
                || senderName == null || text == null || sentAtEpochMs == null || displayTime == null) {
            throw new FrameFormatException("CHAT_EVENT missing one or more required fields");
        }
        try {
            return new ChatEvent(messageId, roomId, senderSessionId, senderName, text, sentAtEpochMs, displayTime);
        } catch (IllegalArgumentException e) {
            throw new FrameFormatException("Invalid CHAT_EVENT data: " + e.getMessage(), e);
        }
    }

    /**
     * Builds a standard Protocol v1 success response envelope.
     */
    public static JsonObject buildSuccessResponse(String requestId, JsonObject data) {
        JsonObject response = new JsonObject();
        response.addProperty("v", ProtocolConstants.PROTOCOL_VERSION);
        response.addProperty("id", requestId);
        response.addProperty("ok", true);
        response.add("data", data);
        return response;
    }

    /**
     * Builds a standard Protocol v1 error response envelope.
     */
    public static JsonObject buildErrorResponse(String requestId, String code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);

        JsonObject response = new JsonObject();
        response.addProperty("v", ProtocolConstants.PROTOCOL_VERSION);
        response.addProperty("id", requestId);
        response.addProperty("ok", false);
        response.add("error", error);
        return response;
    }

    /**
     * Validates a chat message body against empty/blank text, NUL bytes, and UTF-8 byte ceiling.
     *
     * @param text             candidate chat text
     * @param maxChatTextBytes maximum allowed UTF-8 byte count (default {@code 2,048})
     * @return error code ({@link #ERR_BAD_REQUEST} or {@link #ERR_TOO_LARGE}) if invalid, or {@code null} if valid
     */
    public static String validateChatTextErrorCode(String text, int maxChatTextBytes) {
        if (text == null || text.isBlank()) {
            return ERR_BAD_REQUEST;
        }
        if (text.indexOf('\0') >= 0) {
            return ERR_BAD_REQUEST;
        }
        int utf8Length = text.getBytes(StandardCharsets.UTF_8).length;
        if (utf8Length > maxChatTextBytes) {
            return ERR_TOO_LARGE;
        }
        return null;
    }

    public static boolean hasSupportedProtocolVersion(JsonObject frame) {
        if (frame == null || !frame.has("v")) {
            return false;
        }
        JsonElement vElem = frame.get("v");
        if (vElem == null || !vElem.isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive prim = vElem.getAsJsonPrimitive();
        if (!prim.isNumber()) {
            return false;
        }
        try {
            return prim.getAsInt() == ProtocolConstants.PROTOCOL_VERSION
                    && prim.getAsDouble() == ProtocolConstants.PROTOCOL_VERSION;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static String extractValidRequestId(JsonObject obj) {
        String id = extractStrictString(obj, "id");
        if (id == null) {
            return null;
        }
        String trimmed = id.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static String extractStrictString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) {
            return null;
        }
        JsonElement elem = obj.get(key);
        if (elem == null || !elem.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive prim = elem.getAsJsonPrimitive();
        return prim.isString() ? prim.getAsString() : null;
    }

    public static Long extractStrictLong(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) {
            return null;
        }
        JsonElement elem = obj.get(key);
        if (elem == null || !elem.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive prim = elem.getAsJsonPrimitive();
        if (!prim.isNumber()) {
            return null;
        }
        try {
            long asLong = prim.getAsLong();
            double asDouble = prim.getAsDouble();
            if (!Double.isFinite(asDouble) || Math.floor(asDouble) != asDouble) {
                return null;
            }
            return asLong;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static JsonObject extractDataObject(JsonObject obj) throws FrameFormatException {
        if (obj == null || !obj.has("data")) {
            return null;
        }
        JsonElement elem = obj.get("data");
        if (elem == null || elem.isJsonNull()) {
            return null;
        }
        if (!elem.isJsonObject()) {
            throw new FrameFormatException("Field 'data' must be a JSON object");
        }
        return elem.getAsJsonObject();
    }
}
