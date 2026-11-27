package lk.studylink.client.chat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lk.studylink.shared.config.StudyLinkConfig;
import lk.studylink.shared.model.ChatEvent;
import lk.studylink.shared.model.RoomBinding;
import lk.studylink.shared.protocol.ChatProtocol;
import lk.studylink.shared.protocol.FrameCodec;
import lk.studylink.shared.protocol.FrameFormatException;
import lk.studylink.shared.protocol.NioFrameAccumulator;
import lk.studylink.shared.protocol.ProtocolConstants;

/**
 * Non-blocking Java NIO client for Member 5's Live Room Chat service (TCP port {@code 45402},
 * SRS Sections 5.5, F06, F07, Q03, Q06, T04, T05, T12).
 *
 * <p>Manages a single active room subscription at a time using {@link SocketChannel},
 * {@link Selector}, and {@link ByteBuffer} on a background I/O thread (never blocking the Swing
 * EDT). Calling {@link #subscribe(RoomBinding, ChatListener)} closes any previous subscription first
 * and binds all callbacks to the captured {@link RoomBinding} revision so late events from old
 * rooms are discarded.
 */
public final class ChatClient implements AutoCloseable {

    private static final int READ_BUFFER_CAPACITY_BYTES = 8_192;
    private static final long DEFAULT_OP_TIMEOUT_MS = 5_000L;
    private static final long CONNECT_TIMEOUT_MS = 5_000L;

    private final String host;
    private final int port;
    private final int maxFrameBytes;
    private final int maxChatTextBytes;
    private final AtomicLong requestCounter = new AtomicLong(0L);
    private final Object lifecycleLock = new Object();

    private volatile ActiveSubscription activeSubscription;
    private volatile boolean closed = false;

    /**
     * Creates a chat client targeting {@code config.serverHost()} and {@code config.chatPort()}
     * with configured frame and text limits.
     */
    public ChatClient(StudyLinkConfig config) {
        this(
                Objects.requireNonNull(config, "config must not be null").serverHost(),
                config.chatPort(),
                config.maxFrameBytes(),
                config.maxChatTextBytes()
        );
    }

    /**
     * Creates a chat client targeting {@code host:port} with SRS default limits.
     */
    public ChatClient(String host, int port) {
        this(
                host,
                port,
                ProtocolConstants.MAX_FRAME_PAYLOAD_BYTES,
                ProtocolConstants.MAX_TEXT_MESSAGE_BYTES
        );
    }

    /**
     * Creates a chat client with explicit host, port, and size limits.
     */
    public ChatClient(String host, int port, int maxFrameBytes, int maxChatTextBytes) {
        Objects.requireNonNull(host, "host must not be null");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be 1..65535, found " + port);
        }
        if (maxFrameBytes < ProtocolConstants.MIN_FRAME_PAYLOAD_BYTES
                || maxFrameBytes > ProtocolConstants.MAX_FRAME_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("maxFrameBytes out of range: " + maxFrameBytes);
        }
        if (maxChatTextBytes < 1 || maxChatTextBytes > maxFrameBytes) {
            throw new IllegalArgumentException("maxChatTextBytes out of range: " + maxChatTextBytes);
        }
        this.host = host.trim();
        this.port = port;
        this.maxFrameBytes = maxFrameBytes;
        this.maxChatTextBytes = maxChatTextBytes;
    }

    /**
     * Closes any previous room subscription, opens a fresh NIO TCP connection to the chat server,
     * and subscribes using {@code binding}.
     *
     * @param binding  authoritative {@link RoomBinding} issued by Member 1's room control service
     * @param listener listener receiving {@link ChatEvent}s for this subscription
     * @throws ChatException if connection fails, times out, or the server rejects the binding
     */
    public void subscribe(RoomBinding binding, ChatListener listener) throws ChatException {
        Objects.requireNonNull(binding, "binding must not be null");
        Objects.requireNonNull(listener, "listener must not be null");

        ActiveSubscription newSub;
        synchronized (lifecycleLock) {
            if (closed) {
                throw new ChatException("CLIENT_CLOSED", "ChatClient is closed");
            }
            closeSubscriptionInternal(activeSubscription, false);
            newSub = openSubscription(binding, listener);
            activeSubscription = newSub;
        }

        try {
            String reqId = nextRequestId();
            JsonObject request = ChatProtocol.buildSubscribeRequest(reqId, binding);
            JsonObject response = newSub.sendAndAwait(reqId, request, DEFAULT_OP_TIMEOUT_MS);
            assertSuccessResponse(response);
        } catch (ChatException e) {
            synchronized (lifecycleLock) {
                if (activeSubscription == newSub) {
                    closeSubscriptionInternal(newSub, false);
                    activeSubscription = null;
                }
            }
            throw e;
        }
    }

    /**
     * Validates and submits a chat message for the currently subscribed room.
     *
     * @param text UTF-8 chat text (1..2,048 bytes, non-blank)
     * @return server-assigned message ID once accepted
     * @throws ChatException if not subscribed, validation fails, or the server rejects the message
     */
    public String send(String text) throws ChatException {
        String validationError = ChatProtocol.validateChatTextErrorCode(text, maxChatTextBytes);
        if (validationError != null) {
            String message = ChatProtocol.ERR_TOO_LARGE.equals(validationError)
                    ? "Chat message exceeds " + maxChatTextBytes + " UTF-8 bytes"
                    : "Chat message must not be empty or contain NUL bytes";
            throw new ChatException(validationError, message);
        }

        ActiveSubscription sub = this.activeSubscription;
        if (sub == null || !sub.isOpen()) {
            throw new ChatException(ChatProtocol.ERR_NOT_SUBSCRIBED,
                    "Not subscribed to an active chat room");
        }

        String reqId = nextRequestId();
        JsonObject request = ChatProtocol.buildChatSendRequest(reqId, text);
        JsonObject response = sub.sendAndAwait(reqId, request, DEFAULT_OP_TIMEOUT_MS);
        JsonObject data = assertSuccessResponse(response);
        String messageId = ChatProtocol.extractStrictString(data, "messageId");
        return messageId != null ? messageId : "";
    }

    /**
     * Returns the currently subscribed {@link RoomBinding}, or {@code null} if not subscribed.
     */
    public RoomBinding currentBinding() {
        ActiveSubscription sub = this.activeSubscription;
        return (sub != null && sub.isOpen()) ? sub.binding : null;
    }

    /**
     * Returns {@code true} if there is an active, open chat subscription.
     */
    public boolean isConnected() {
        ActiveSubscription sub = this.activeSubscription;
        return sub != null && sub.isOpen();
    }

    /**
     * Unsubscribes from the current room (if any) without closing this {@link ChatClient} instance
     * permanently, allowing a subsequent {@link #subscribe(RoomBinding, ChatListener)} call.
     */
    public void unsubscribe() {
        synchronized (lifecycleLock) {
            closeSubscriptionInternal(activeSubscription, false);
            activeSubscription = null;
        }
    }

    /**
     * Permanently closes this client and any active NIO channel, selector, and background thread.
     */
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            closeSubscriptionInternal(activeSubscription, false);
            activeSubscription = null;
        }
    }

    private ActiveSubscription openSubscription(RoomBinding binding, ChatListener listener)
            throws ChatException {
        Selector selector = null;
        SocketChannel channel = null;
        try {
            selector = Selector.open();
            channel = SocketChannel.open();
            channel.configureBlocking(false);
            try {
                channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            } catch (IOException ignored) {
            }

            boolean connectedImmediately = channel.connect(new InetSocketAddress(host, port));
            if (!connectedImmediately) {
                SelectionKey connectKey = channel.register(selector, SelectionKey.OP_CONNECT);
                long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONNECT_TIMEOUT_MS);
                while (!channel.finishConnect()) {
                    long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
                    if (remainingMs <= 0L) {
                        throw new ChatException("CONNECTION_TIMEOUT",
                                "Timed out connecting to chat server at " + host + ":" + port);
                    }
                    selector.select(remainingMs);
                    if (connectKey.isValid() && connectKey.isConnectable()) {
                        if (channel.finishConnect()) {
                            break;
                        }
                    }
                }
            }

            SelectionKey ioKey = channel.register(selector, SelectionKey.OP_READ);
            ActiveSubscription sub = new ActiveSubscription(
                    binding,
                    listener,
                    channel,
                    selector,
                    ioKey,
                    maxFrameBytes
            );
            sub.start();
            return sub;
        } catch (ChatException e) {
            closeQuietly(channel);
            closeQuietly(selector);
            throw e;
        } catch (IOException e) {
            closeQuietly(channel);
            closeQuietly(selector);
            throw new ChatException("CONNECTION_FAILED",
                    "Failed to connect to chat server at " + host + ":" + port + ": " + e.getMessage(), e);
        }
    }

    private void closeSubscriptionInternal(ActiveSubscription sub, boolean notifyDisconnect) {
        if (sub != null) {
            sub.shutdown(notifyDisconnect, "Subscription closed");
        }
    }

    private String nextRequestId() {
        return "c-" + requestCounter.incrementAndGet();
    }

    private static JsonObject assertSuccessResponse(JsonObject response) throws ChatException {
        JsonElement okElem = response.get("ok");
        boolean ok = okElem != null && okElem.isJsonPrimitive()
                && okElem.getAsJsonPrimitive().isBoolean() && okElem.getAsBoolean();
        if (ok) {
            JsonElement dataElem = response.get("data");
            return (dataElem != null && dataElem.isJsonObject()) ? dataElem.getAsJsonObject() : new JsonObject();
        }
        String code = "SERVER_ERROR";
        String message = "Chat server rejected request";
        JsonElement errElem = response.get("error");
        if (errElem != null && errElem.isJsonObject()) {
            JsonObject errObj = errElem.getAsJsonObject();
            String parsedCode = ChatProtocol.extractStrictString(errObj, "code");
            String parsedMessage = ChatProtocol.extractStrictString(errObj, "message");
            if (parsedCode != null && !parsedCode.isBlank()) {
                code = parsedCode;
            }
            if (parsedMessage != null && !parsedMessage.isBlank()) {
                message = parsedMessage;
            }
        }
        throw new ChatException(code, message);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }

    private final class ActiveSubscription {

        private final RoomBinding binding;
        private final ChatListener listener;
        private final SocketChannel channel;
        private final Selector selector;
        private final SelectionKey key;
        private final NioFrameAccumulator accumulator;
        private final ArrayDeque<ByteBuffer> outboundQueue = new ArrayDeque<>();
        private final Object outboundLock = new Object();
        private final Map<String, CompletableFuture<JsonObject>> pendingResponses = new ConcurrentHashMap<>();
        private final AtomicBoolean closedFlag = new AtomicBoolean(false);
        private final Thread ioThread;

        private ActiveSubscription(
                RoomBinding binding,
                ChatListener listener,
                SocketChannel channel,
                Selector selector,
                SelectionKey key,
                int maxFrameBytes
        ) {
            this.binding = binding;
            this.listener = listener;
            this.channel = channel;
            this.selector = selector;
            this.key = key;
            this.accumulator = new NioFrameAccumulator(maxFrameBytes);
            this.ioThread = new Thread(
                    this::runIoLoop,
                    "studylink-chat-client-rev-" + binding.revision()
            );
            this.ioThread.setDaemon(true);
        }

        private void start() {
            ioThread.start();
        }

        private boolean isOpen() {
            return !closedFlag.get() && channel.isOpen() && selector.isOpen();
        }

        private JsonObject sendAndAwait(String requestId, JsonObject frame, long timeoutMs)
                throws ChatException {
            if (!isOpen()) {
                throw new ChatException("DISCONNECTED", "Chat connection is closed");
            }

            final byte[] encoded;
            try {
                encoded = FrameCodec.encodeFrameBytes(frame);
            } catch (FrameFormatException e) {
                throw new ChatException(ChatProtocol.ERR_TOO_LARGE, e.getMessage(), e);
            }

            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            pendingResponses.put(requestId, future);

            synchronized (outboundLock) {
                if (!isOpen()) {
                    pendingResponses.remove(requestId);
                    throw new ChatException("DISCONNECTED", "Chat connection closed before send");
                }
                outboundQueue.addLast(ByteBuffer.wrap(encoded));
            }
            selector.wakeup();

            try {
                return future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                pendingResponses.remove(requestId);
                Thread.currentThread().interrupt();
                throw new ChatException("INTERRUPTED", "Interrupted waiting for chat server reply", e);
            } catch (TimeoutException e) {
                pendingResponses.remove(requestId);
                throw new ChatException("TIMEOUT", "Timed out waiting for chat server reply", e);
            } catch (ExecutionException e) {
                pendingResponses.remove(requestId);
                Throwable cause = e.getCause();
                if (cause instanceof ChatException ce) {
                    throw ce;
                }
                throw new ChatException("CONNECTION_FAILED",
                        "Chat request failed: " + (cause != null ? cause.getMessage() : e.getMessage()),
                        cause);
            }
        }

        private void runIoLoop() {
            ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER_CAPACITY_BYTES);
            String disconnectReason = "Connection closed by server";
            boolean notifyDisconnect = false;

            try {
                while (!closedFlag.get() && selector.isOpen() && channel.isOpen()) {
                    syncWriteInterest();
                    selector.select();
                    if (closedFlag.get() || !selector.isOpen()) {
                        break;
                    }

                    Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
                    while (iter.hasNext()) {
                        SelectionKey selected = iter.next();
                        iter.remove();
                        if (!selected.isValid()) {
                            continue;
                        }
                        if (selected.isReadable()) {
                            if (!readIncoming(readBuffer)) {
                                notifyDisconnect = true;
                                return;
                            }
                        }
                        if (selected.isValid() && selected.isWritable()) {
                            flushOutbound(selected);
                        }
                    }
                }
            } catch (FrameFormatException e) {
                disconnectReason = "Invalid frame from server: " + e.getMessage();
                notifyErrorIfActive("FRAME_ERROR", disconnectReason);
                notifyDisconnect = true;
            } catch (IOException e) {
                if (!closedFlag.get()) {
                    disconnectReason = "Chat connection lost: " + e.getMessage();
                    notifyDisconnect = true;
                }
            } finally {
                shutdown(notifyDisconnect, disconnectReason);
            }
        }

        private boolean readIncoming(ByteBuffer readBuffer) throws IOException {
            readBuffer.clear();
            int read = channel.read(readBuffer);
            if (read < 0) {
                return false;
            }
            if (read == 0) {
                return true;
            }
            readBuffer.flip();
            List<JsonObject> frames = accumulator.drainFrames(readBuffer);
            for (JsonObject frame : frames) {
                dispatchServerFrame(frame);
            }
            return true;
        }

        private void syncWriteInterest() {
            synchronized (outboundLock) {
                if (!key.isValid()) {
                    return;
                }
                int desiredOps = outboundQueue.isEmpty()
                        ? SelectionKey.OP_READ
                        : (SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                if (key.interestOps() != desiredOps) {
                    key.interestOps(desiredOps);
                }
            }
        }

        private void flushOutbound(SelectionKey selectedKey) throws IOException {
            synchronized (outboundLock) {
                while (!outboundQueue.isEmpty()) {
                    ByteBuffer head = outboundQueue.peekFirst();
                    channel.write(head);
                    if (head.hasRemaining()) {
                        return;
                    }
                    outboundQueue.pollFirst();
                }
                if (outboundQueue.isEmpty() && selectedKey.isValid()) {
                    selectedKey.interestOps(selectedKey.interestOps() & ~SelectionKey.OP_WRITE);
                }
            }
        }

        private void dispatchServerFrame(JsonObject frame) {
            String requestId = ChatProtocol.extractValidRequestId(frame);
            if (requestId != null) {
                CompletableFuture<JsonObject> future = pendingResponses.remove(requestId);
                if (future != null) {
                    future.complete(frame);
                }
                return;
            }

            String op = ChatProtocol.extractStrictString(frame, "op");
            if (!ChatProtocol.OP_CHAT_EVENT.equals(op)) {
                return;
            }

            final ChatEvent event;
            try {
                event = ChatProtocol.parseChatEventFrame(frame);
            } catch (FrameFormatException e) {
                notifyErrorIfActive("INVALID_EVENT", e.getMessage());
                return;
            }

            // Discard stale or cross-room events before forwarding to the UI/listener
            if (!isCurrentActiveSubscription()) {
                return;
            }
            if (!binding.roomId().equals(event.roomId())) {
                return;
            }
            listener.onChatEvent(event);
        }

        private boolean isCurrentActiveSubscription() {
            return !closedFlag.get() && ChatClient.this.activeSubscription == this;
        }

        private void notifyErrorIfActive(String code, String message) {
            if (isCurrentActiveSubscription()) {
                listener.onError(code, message);
            }
        }

        private void shutdown(boolean notifyDisconnect, String reason) {
            boolean wasCurrent = (ChatClient.this.activeSubscription == this);
            if (!closedFlag.compareAndSet(false, true)) {
                return;
            }

            ChatException disconnectEx = new ChatException("DISCONNECTED", reason);
            for (var entry : pendingResponses.entrySet()) {
                CompletableFuture<JsonObject> future = pendingResponses.remove(entry.getKey());
                if (future != null) {
                    future.completeExceptionally(disconnectEx);
                }
            }

            synchronized (outboundLock) {
                outboundQueue.clear();
            }

            selector.wakeup();
            try {
                key.cancel();
            } catch (RuntimeException ignored) {
            }
            closeQuietly(channel);
            closeQuietly(selector);

            if (notifyDisconnect && wasCurrent) {
                listener.onDisconnected(reason);
            }
        }
    }
}
