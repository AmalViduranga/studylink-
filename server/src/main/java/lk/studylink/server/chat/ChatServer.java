package lk.studylink.server.chat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import lk.studylink.server.rooms.RoomSessionRegistry;
import lk.studylink.shared.config.StudyLinkConfig;
import lk.studylink.shared.model.BindingInvalidationListener;
import lk.studylink.shared.model.ChatEvent;
import lk.studylink.shared.model.MembershipView;
import lk.studylink.shared.model.RoomBinding;
import lk.studylink.shared.model.SessionInfo;
import lk.studylink.shared.protocol.ChatProtocol;
import lk.studylink.shared.protocol.FrameCodec;
import lk.studylink.shared.protocol.FrameFormatException;

/**
 * Non-blocking Java NIO Live Room Chat server for Member 5 (SRS Sections 3, 5.5, 6.2, 6.3,
 * F06, F07, Q01, Q03, Q06, T04, T05, T06, T12).
 *
 * <h2>Architecture</h2>
 * <ul>
 *   <li>Uses a single {@link Selector}, one non-blocking {@link ServerSocketChannel} on port
 *       {@code 45402} (or ephemeral port {@code 0} in tests), and non-blocking {@link SocketChannel}s.</li>
 *   <li>All channel registrations, reads, frame decoding, room broadcasts, outbound queues,
 *       membership invalidations, and partial writes run exclusively on the selector thread.</li>
 *   <li>Each channel maintains its own {@link lk.studylink.shared.protocol.NioFrameAccumulator}
 *       supporting complete, split, and multi-frame reads with immediate rejection of invalid or
 *       oversized frame prefixes ({@code > 16,384} bytes) before buffer allocation.</li>
 *   <li>Room isolation is enforced using Member 1's authoritative {@link MembershipView}:
 *       {@code CHAT_SEND} ignores any untrusted client room field, verifies the connection's
 *       bound {@link RoomBinding} is still current, resolves the sender's display name from the
 *       session lookup, and delivers {@code CHAT_EVENT}s only to current subscribers in the same room.</li>
 *   <li>Slow-client protection disconnects any peer whose unsent outbound queue exceeds
 *       {@code maxSlowClientQueueBytes} ({@code 65,536} bytes) without stalling other clients.</li>
 * </ul>
 */
public final class ChatServer implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger(ChatServer.class.getName());
    private static final int READ_BUFFER_CAPACITY_BYTES = 8_192;
    private static final int SHUTDOWN_WAIT_MILLIS = 3_000;

    private final String bindHost;
    private final int requestedPort;
    private final StudyLinkConfig config;
    private final MembershipView membershipView;
    private final Function<String, Optional<SessionInfo>> sessionLookup;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong messageSequence = new AtomicLong(0L);

    private final Set<ChatClientState> activeClients = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<ChatClientState>> roomSubscribers = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<RoomBinding> pendingInvalidations = new ConcurrentLinkedQueue<>();
    private final BindingInvalidationListener invalidationListener = this::onBindingInvalidated;

    private volatile ServerSocketChannel serverChannel;
    private volatile Selector selector;
    private volatile InetSocketAddress boundAddress;
    private volatile Thread selectorThread;

    /**
     * Creates a chat server bound to {@code config.serverHost()} and {@code config.chatPort()}
     * backed by Member 1's {@link RoomSessionRegistry}.
     */
    public ChatServer(StudyLinkConfig config, RoomSessionRegistry registry) {
        this(
                Objects.requireNonNull(config, "config must not be null").serverHost(),
                config.chatPort(),
                config,
                registry
        );
    }

    /**
     * Creates a chat server with an explicit bind host and port (such as {@code "127.0.0.1"} and
     * {@code 0} for ephemeral-port tests) backed by {@link RoomSessionRegistry}.
     */
    public ChatServer(
            String bindHost,
            int requestedPort,
            StudyLinkConfig config,
            RoomSessionRegistry registry
    ) {
        this(
                bindHost,
                requestedPort,
                config,
                Objects.requireNonNull(registry, "registry must not be null"),
                registry::findSession
        );
    }

    /**
     * Creates a chat server against any {@link MembershipView} and {@code sessionLookup} function,
     * enabling decoupled unit and integration tests.
     */
    public ChatServer(
            String bindHost,
            int requestedPort,
            StudyLinkConfig config,
            MembershipView membershipView,
            Function<String, Optional<SessionInfo>> sessionLookup
    ) {
        Objects.requireNonNull(bindHost, "bindHost must not be null");
        if (bindHost.isBlank()) {
            throw new IllegalArgumentException("bindHost must not be blank");
        }
        if (requestedPort < 0 || requestedPort > 65_535) {
            throw new IllegalArgumentException("requestedPort must be 0..65535, found " + requestedPort);
        }
        this.bindHost = bindHost.trim();
        this.requestedPort = requestedPort;
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.membershipView = Objects.requireNonNull(membershipView, "membershipView must not be null");
        this.sessionLookup = Objects.requireNonNull(sessionLookup, "sessionLookup must not be null");
    }

    /**
     * Opens the NIO {@link Selector} and non-blocking {@link ServerSocketChannel}, registers the
     * membership invalidation listener, and starts the selector event loop thread.
     */
    public synchronized void start() throws IOException {
        if (closed.get()) {
            throw new IllegalStateException("ChatServer has already been closed");
        }
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("ChatServer is already started");
        }

        Selector openedSelector = null;
        ServerSocketChannel openedServerChannel = null;
        try {
            openedSelector = Selector.open();
            openedServerChannel = ServerSocketChannel.open();
            openedServerChannel.configureBlocking(false);
            openedServerChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            InetAddress address = InetAddress.getByName(bindHost);
            openedServerChannel.bind(new InetSocketAddress(address, requestedPort));
            openedServerChannel.register(openedSelector, SelectionKey.OP_ACCEPT);
        } catch (IOException e) {
            started.set(false);
            closeQuietly(openedServerChannel);
            closeQuietly(openedSelector);
            throw e;
        }

        this.selector = openedSelector;
        this.serverChannel = openedServerChannel;
        this.boundAddress = (InetSocketAddress) openedServerChannel.getLocalAddress();
        int actualPort = this.boundAddress.getPort();

        this.membershipView.addInvalidationListener(invalidationListener);

        Thread loopThread = new Thread(this::runSelectorLoop, "studylink-chat-selector-" + actualPort);
        loopThread.setDaemon(true);
        this.selectorThread = loopThread;
        loopThread.start();
    }

    /**
     * Returns {@code true} if the chat server is currently started and open.
     */
    public boolean isRunning() {
        ServerSocketChannel ch = this.serverChannel;
        Selector sel = this.selector;
        return started.get() && !closed.get() && ch != null && ch.isOpen() && sel != null && sel.isOpen();
    }

    /**
     * Returns the bound TCP port (resolving ephemeral port {@code 0} after {@link #start()}).
     */
    public int boundPort() {
        InetSocketAddress addr = this.boundAddress;
        if (addr == null) {
            throw new IllegalStateException("ChatServer has not been started");
        }
        return addr.getPort();
    }

    /**
     * Returns the bound {@link InetSocketAddress} after {@link #start()}.
     */
    public InetSocketAddress boundAddress() {
        InetSocketAddress addr = this.boundAddress;
        if (addr == null) {
            throw new IllegalStateException("ChatServer has not been started");
        }
        return addr;
    }

    /**
     * Returns the number of currently connected client channels.
     */
    public int activeConnectionCount() {
        return activeClients.size();
    }

    /**
     * Returns the total number of currently subscribed client channels across all rooms.
     */
    public int activeSubscriptionCount() {
        int total = 0;
        for (Set<ChatClientState> subscribers : roomSubscribers.values()) {
            total += subscribers.size();
        }
        return total;
    }

    /**
     * Returns the number of currently subscribed client channels for {@code roomId}.
     */
    public int activeRoomSubscriptionCount(String roomId) {
        if (roomId == null || roomId.isBlank()) {
            return 0;
        }
        Set<ChatClientState> subscribers = roomSubscribers.get(roomId.trim());
        return subscribers != null ? subscribers.size() : 0;
    }

    /**
     * Stops the selector loop, unregisters from {@link MembershipView}, and closes all channels and
     * the selector cleanly. Idempotent.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        membershipView.removeInvalidationListener(invalidationListener);

        Selector sel = this.selector;
        if (sel != null) {
            sel.wakeup();
        }

        Thread loopThread = this.selectorThread;
        if (loopThread != null && loopThread != Thread.currentThread()) {
            try {
                loopThread.join(SHUTDOWN_WAIT_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void onBindingInvalidated(RoomBinding invalidatedBinding) {
        if (invalidatedBinding == null || closed.get()) {
            return;
        }
        pendingInvalidations.add(invalidatedBinding);
        Selector sel = this.selector;
        if (sel != null && sel.isOpen()) {
            sel.wakeup();
        }
    }

    private void runSelectorLoop() {
        ByteBuffer sharedReadBuffer = ByteBuffer.allocate(READ_BUFFER_CAPACITY_BYTES);
        Selector sel = this.selector;

        try {
            while (!closed.get() && sel != null && sel.isOpen()) {
                drainPendingInvalidations();

                sel.select();
                if (closed.get() || !sel.isOpen()) {
                    break;
                }

                drainPendingInvalidations();

                Iterator<SelectionKey> keyIterator = sel.selectedKeys().iterator();
                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    keyIterator.remove();

                    if (!key.isValid()) {
                        continue;
                    }

                    try {
                        if (key.isAcceptable()) {
                            handleAccept(sel);
                        }
                        if (key.isValid() && key.isReadable()) {
                            handleRead(key, sharedReadBuffer);
                        }
                        if (key.isValid() && key.isWritable()) {
                            handleWrite(key);
                        }
                    } catch (CancelledKeyException ignored) {
                        cleanupKeyAttachment(key);
                    } catch (RuntimeException e) {
                        LOGGER.log(System.Logger.Level.WARNING,
                                "Unexpected error handling NIO key; closing client channel", e);
                        cleanupKeyAttachment(key);
                    }
                }
            }
        } catch (IOException e) {
            if (!closed.get()) {
                LOGGER.log(System.Logger.Level.ERROR, "Fatal I/O error in ChatServer selector loop", e);
            }
        } finally {
            shutdownAllChannelsAndSelector();
        }
    }

    private void drainPendingInvalidations() {
        RoomBinding invalidated;
        while ((invalidated = pendingInvalidations.poll()) != null) {
            Set<ChatClientState> subscribers = roomSubscribers.get(invalidated.roomId());
            if (subscribers == null) {
                continue;
            }
            for (ChatClientState client : List.copyOf(subscribers)) {
                if (invalidated.equals(client.subscribedBinding())) {
                    invalidateClientSubscription(client);
                    client.purgeUnstartedEventsFor(invalidated);
                }
            }
        }
    }

    private void handleAccept(Selector sel) {
        ServerSocketChannel listener = this.serverChannel;
        if (listener == null || !listener.isOpen()) {
            return;
        }
        while (!closed.get()) {
            final SocketChannel clientChannel;
            try {
                clientChannel = listener.accept();
            } catch (IOException e) {
                if (!closed.get()) {
                    LOGGER.log(System.Logger.Level.WARNING, "Failed to accept NIO chat client", e);
                }
                break;
            }
            if (clientChannel == null) {
                break;
            }
            try {
                clientChannel.configureBlocking(false);
                try {
                    clientChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
                    clientChannel.setOption(StandardSocketOptions.SO_SNDBUF, 32_768);
                } catch (IOException ignored) {
                    // Best-effort low-latency and bounded kernel send-buffer options
                }
                SelectionKey clientKey = clientChannel.register(sel, SelectionKey.OP_READ);
                ChatClientState state = new ChatClientState(clientChannel, clientKey, config.maxFrameBytes());
                clientKey.attach(state);
                activeClients.add(state);
            } catch (IOException e) {
                closeQuietly(clientChannel);
            }
        }
    }

    private void handleRead(SelectionKey key, ByteBuffer readBuffer) {
        ChatClientState client = (ChatClientState) key.attachment();
        if (client == null) {
            closeKey(key);
            return;
        }

        SocketChannel channel = client.channel();
        readBuffer.clear();

        final int bytesRead;
        try {
            bytesRead = channel.read(readBuffer);
        } catch (IOException e) {
            closeConnection(client);
            return;
        }

        if (bytesRead < 0) {
            closeConnection(client);
            return;
        }
        if (bytesRead == 0) {
            return;
        }

        readBuffer.flip();
        final List<JsonObject> frames;
        try {
            frames = client.frameAccumulator().drainFrames(readBuffer);
        } catch (FrameFormatException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    () -> "Closing NIO chat client due to invalid frame: " + e.getMessage());
            closeConnection(client);
            return;
        }

        for (JsonObject frame : frames) {
            if (!activeClients.contains(client)) {
                break;
            }
            handleClientFrame(client, frame);
        }
    }

    private void handleWrite(SelectionKey key) {
        ChatClientState client = (ChatClientState) key.attachment();
        if (client == null) {
            closeKey(key);
            return;
        }

        SocketChannel channel = client.channel();
        var queue = client.outboundQueue();

        while (!queue.isEmpty()) {
            ChatClientState.OutboundFrame head = queue.peekFirst();
            ByteBuffer buffer = head.buffer();
            try {
                int written = channel.write(buffer);
                client.recordBytesWritten(written);
                if (buffer.hasRemaining()) {
                    // Kernel send buffer is full; wait for next OP_WRITE readiness
                    return;
                }
                queue.pollFirst();
            } catch (IOException e) {
                closeConnection(client);
                return;
            }
        }

        if (queue.isEmpty() && key.isValid()) {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
        }
    }

    private void handleClientFrame(ChatClientState client, JsonObject request) {
        String requestId = ChatProtocol.extractValidRequestId(request);
        if (requestId == null) {
            // Uncorrelated request frame without a valid non-blank string id: close cleanly
            closeConnection(client);
            return;
        }

        if (!ChatProtocol.hasSupportedProtocolVersion(request)) {
            sendError(client, requestId, ChatProtocol.ERR_UNSUPPORTED_VERSION,
                    "Unsupported or missing protocol version 'v' (expected 1)");
            return;
        }

        String op = ChatProtocol.extractStrictString(request, "op");
        if (op == null || op.isBlank()) {
            sendError(client, requestId, ChatProtocol.ERR_BAD_REQUEST,
                    "Request field 'op' must be a non-blank string");
            return;
        }

        final JsonObject data;
        try {
            data = ChatProtocol.extractDataObject(request);
        } catch (FrameFormatException e) {
            sendError(client, requestId, ChatProtocol.ERR_BAD_REQUEST, e.getMessage());
            return;
        }

        switch (op) {
            case ChatProtocol.OP_SUBSCRIBE -> handleSubscribe(client, requestId, data);
            case ChatProtocol.OP_CHAT_SEND -> handleChatSend(client, requestId, data);
            default -> sendError(client, requestId, ChatProtocol.ERR_UNSUPPORTED_OP,
                    "Unsupported chat operation: '" + op + "'");
        }
    }

    private void handleSubscribe(ChatClientState client, String requestId, JsonObject data) {
        if (data == null) {
            unsubscribeClient(client);
            sendError(client, requestId, ChatProtocol.ERR_BAD_REQUEST,
                    "SUBSCRIBE requires a 'data' object with sessionId, roomId, and revision");
            return;
        }

        String sessionId = ChatProtocol.extractStrictString(data, "sessionId");
        String roomId = ChatProtocol.extractStrictString(data, "roomId");
        Long revision = ChatProtocol.extractStrictLong(data, "revision");
        if (sessionId == null || sessionId.isBlank()
                || roomId == null || roomId.isBlank()
                || revision == null || revision < 1L) {
            unsubscribeClient(client);
            sendError(client, requestId, ChatProtocol.ERR_BAD_REQUEST,
                    "SUBSCRIBE requires non-blank sessionId, roomId, and revision >= 1");
            return;
        }

        final RoomBinding candidateBinding;
        try {
            candidateBinding = new RoomBinding(sessionId, roomId, revision);
        } catch (IllegalArgumentException e) {
            unsubscribeClient(client);
            sendError(client, requestId, ChatProtocol.ERR_BAD_REQUEST, e.getMessage());
            return;
        }

        if (!membershipView.isCurrent(candidateBinding)) {
            unsubscribeClient(client);
            sendError(client, requestId, ChatProtocol.ERR_STALE_BINDING,
                    "Room binding is not current for session '" + candidateBinding.sessionId() + "'");
            return;
        }

        Optional<SessionInfo> sessionInfo = sessionLookup.apply(candidateBinding.sessionId());
        if (sessionInfo.isEmpty()) {
            unsubscribeClient(client);
            sendError(client, requestId, ChatProtocol.ERR_STALE_BINDING,
                    "Session '" + candidateBinding.sessionId() + "' is not active");
            return;
        }

        unsubscribeClient(client);
        client.setSubscription(candidateBinding, sessionInfo.get().displayName());
        roomSubscribers
                .computeIfAbsent(candidateBinding.roomId(), ignored -> ConcurrentHashMap.newKeySet())
                .add(client);

        JsonObject responseData = new JsonObject();
        responseData.addProperty("sessionId", candidateBinding.sessionId());
        responseData.addProperty("roomId", candidateBinding.roomId());
        responseData.addProperty("revision", candidateBinding.revision());
        responseData.addProperty("subscribed", true);
        sendResponse(client, requestId, responseData);
    }

    private void handleChatSend(ChatClientState client, String requestId, JsonObject data) {
        RoomBinding activeBinding = client.subscribedBinding();
        if (activeBinding == null) {
            if (client.isSubscriptionInvalidated()) {
                sendError(client, requestId, ChatProtocol.ERR_STALE_BINDING,
                        "Active room binding has expired or been superseded");
            } else {
                sendError(client, requestId, ChatProtocol.ERR_NOT_SUBSCRIBED,
                        "Subscribe with a current RoomBinding before sending chat messages");
            }
            return;
        }

        if (data == null) {
            sendError(client, requestId, ChatProtocol.ERR_BAD_REQUEST,
                    "CHAT_SEND requires a 'data' object containing 'text'");
            return;
        }

        if (hasMismatchedClientBinding(data, activeBinding)) {
            sendError(client, requestId, ChatProtocol.ERR_STALE_BINDING,
                    "Message binding does not match this channel's active room subscription");
            return;
        }

        if (!membershipView.isCurrent(activeBinding)) {
            invalidateClientSubscription(client);
            client.purgeUnstartedEventsFor(activeBinding);
            sendError(client, requestId, ChatProtocol.ERR_STALE_BINDING,
                    "Active room binding has expired or been superseded");
            return;
        }

        Optional<SessionInfo> sessionInfo = sessionLookup.apply(activeBinding.sessionId());
        if (sessionInfo.isEmpty()) {
            invalidateClientSubscription(client);
            client.purgeUnstartedEventsFor(activeBinding);
            sendError(client, requestId, ChatProtocol.ERR_STALE_BINDING,
                    "Sender session is no longer active");
            return;
        }

        String text = ChatProtocol.extractStrictString(data, "text");
        String textValidationError = ChatProtocol.validateChatTextErrorCode(text, config.maxChatTextBytes());
        if (textValidationError != null) {
            String msg = ChatProtocol.ERR_TOO_LARGE.equals(textValidationError)
                    ? "Chat message exceeds maximum of " + config.maxChatTextBytes() + " UTF-8 bytes"
                    : "Chat message text must be a non-empty string without NUL bytes";
            sendError(client, requestId, textValidationError, msg);
            return;
        }

        long nowEpochMs = System.currentTimeMillis();
        String messageId = "m-" + messageSequence.incrementAndGet();
        ChatEvent event = new ChatEvent(
                messageId,
                activeBinding.roomId(),
                activeBinding.sessionId(),
                sessionInfo.get().displayName(),
                text,
                nowEpochMs,
                ChatEvent.formatDisplayTime(nowEpochMs)
        );

        final byte[] encodedEventFrame;
        try {
            encodedEventFrame = FrameCodec.encodeFrameBytes(ChatProtocol.buildChatEventFrame(event));
        } catch (FrameFormatException e) {
            sendError(client, requestId, ChatProtocol.ERR_TOO_LARGE,
                    "Encoded chat event exceeds frame payload limit: " + e.getMessage());
            return;
        }

        JsonObject ackData = new JsonObject();
        ackData.addProperty("messageId", event.messageId());
        ackData.addProperty("roomId", event.roomId());
        ackData.addProperty("sentAtEpochMs", event.sentAtEpochMs());
        ackData.addProperty("displayTime", event.displayTime());
        sendResponse(client, requestId, ackData);

        broadcastToRoom(activeBinding.roomId(), encodedEventFrame);
    }

    private void broadcastToRoom(String roomId, byte[] encodedEventFrame) {
        Set<ChatClientState> subscribers = roomSubscribers.get(roomId);
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }

        for (ChatClientState recipient : List.copyOf(subscribers)) {
            if (!activeClients.contains(recipient)) {
                subscribers.remove(recipient);
                continue;
            }
            RoomBinding recipientBinding = recipient.subscribedBinding();
            if (recipientBinding == null
                    || !roomId.equals(recipientBinding.roomId())
                    || !membershipView.isCurrent(recipientBinding)) {
                invalidateClientSubscription(recipient);
                if (recipientBinding != null) {
                    recipient.purgeUnstartedEventsFor(recipientBinding);
                }
                continue;
            }
            enqueueOutbound(recipient, encodedEventFrame, recipientBinding);
        }
    }

    private void sendResponse(ChatClientState client, String requestId, JsonObject data) {
        sendControlFrame(client, ChatProtocol.buildSuccessResponse(requestId, data));
    }

    private void sendError(ChatClientState client, String requestId, String code, String message) {
        sendControlFrame(client, ChatProtocol.buildErrorResponse(requestId, code, message));
    }

    private void sendControlFrame(ChatClientState client, JsonObject payload) {
        try {
            byte[] encoded = FrameCodec.encodeFrameBytes(payload);
            enqueueOutbound(client, encoded, null);
        } catch (FrameFormatException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    () -> "Failed to encode control response frame; closing client: " + e.getMessage());
            closeConnection(client);
        }
    }

    private void enqueueOutbound(ChatClientState client, byte[] frameBytes, RoomBinding eventBinding) {
        if (!activeClients.contains(client)) {
            return;
        }
        boolean enqueued = client.tryEnqueue(frameBytes, eventBinding, config.maxSlowClientQueueBytes());
        if (!enqueued) {
            LOGGER.log(System.Logger.Level.WARNING, () ->
                    "Disconnecting slow chat client (" + client.subscribedBinding()
                            + "): pending outbound queue would exceed "
                            + config.maxSlowClientQueueBytes() + " bytes");
            closeConnection(client);
            return;
        }

        SelectionKey key = client.selectionKey();
        if (key.isValid()) {
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
        } else {
            closeConnection(client);
        }
    }

    private static boolean hasMismatchedClientBinding(JsonObject data, RoomBinding activeBinding) {
        if (data.has("sessionId")) {
            JsonElement elem = data.get("sessionId");
            if (elem != null && !elem.isJsonNull()) {
                String supplied = ChatProtocol.extractStrictString(data, "sessionId");
                if (supplied == null || !activeBinding.sessionId().equals(supplied.trim())) {
                    return true;
                }
            }
        }
        if (data.has("roomId")) {
            JsonElement elem = data.get("roomId");
            if (elem != null && !elem.isJsonNull()) {
                String supplied = ChatProtocol.extractStrictString(data, "roomId");
                if (supplied == null || !activeBinding.roomId().equals(supplied.trim())) {
                    return true;
                }
            }
        }
        if (data.has("revision")) {
            JsonElement elem = data.get("revision");
            if (elem != null && !elem.isJsonNull()) {
                Long supplied = ChatProtocol.extractStrictLong(data, "revision");
                if (supplied == null || supplied != activeBinding.revision()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void unsubscribeClient(ChatClientState client) {
        RoomBinding previous = client.subscribedBinding();
        client.clearSubscription();
        removeFromRoomSubscribers(client, previous);
    }

    private void invalidateClientSubscription(ChatClientState client) {
        RoomBinding previous = client.subscribedBinding();
        client.markSubscriptionInvalidated();
        removeFromRoomSubscribers(client, previous);
    }

    private void removeFromRoomSubscribers(ChatClientState client, RoomBinding previous) {
        if (previous != null) {
            Set<ChatClientState> subscribers = roomSubscribers.get(previous.roomId());
            if (subscribers != null) {
                subscribers.remove(client);
                if (subscribers.isEmpty()) {
                    roomSubscribers.remove(previous.roomId(), subscribers);
                }
            }
        }
    }

    private void cleanupKeyAttachment(SelectionKey key) {
        Object attachment = key.attachment();
        if (attachment instanceof ChatClientState client) {
            closeConnection(client);
        } else {
            closeKey(key);
        }
    }

    private void closeConnection(ChatClientState client) {
        activeClients.remove(client);
        unsubscribeClient(client);
        client.outboundQueue().clear();
        closeKey(client.selectionKey());
        closeQuietly(client.channel());
    }

    private void shutdownAllChannelsAndSelector() {
        for (ChatClientState client : new ArrayList<>(activeClients)) {
            closeConnection(client);
        }
        activeClients.clear();
        roomSubscribers.clear();

        Selector sel = this.selector;
        if (sel != null && sel.isOpen()) {
            for (SelectionKey key : sel.keys()) {
                closeKey(key);
                if (key.channel() instanceof SocketChannel sc) {
                    closeQuietly(sc);
                }
            }
            closeQuietly(sel);
        }
        closeQuietly(this.serverChannel);
    }

    private static void closeKey(SelectionKey key) {
        if (key != null) {
            try {
                key.cancel();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }
}
