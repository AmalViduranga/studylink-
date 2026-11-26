package lk.studylink.server.rooms;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import lk.studylink.shared.config.StudyLinkConfig;
import lk.studylink.shared.model.RoomBinding;
import lk.studylink.shared.model.RoomDefinition;
import lk.studylink.shared.model.SessionInfo;
import lk.studylink.shared.protocol.FrameCodec;
import lk.studylink.shared.protocol.FrameFormatException;
import lk.studylink.shared.protocol.ProtocolConstants;

/**
 * Blocking TCP room-control listener for Member 1 (SRS Sections 3, 5.1, 6.3, F03, F04, F13, Q03, T02).
 *
 * <h2>Architecture & Concurrency</h2>
 * <ul>
 *   <li>Binds a {@link ServerSocket} on the configured host and control port (default {@code 45401}),
 *       or ephemeral port {@code 0} on loopback for integration tests.</li>
 *   <li>A dedicated acceptor thread calls {@link ServerSocket#accept()} and immediately hands each
 *       accepted {@link Socket} to its own worker thread so an idle client or partially transmitted
 *       frame never blocks other clients from connecting or executing requests.</li>
 *   <li>Requests on a single socket are read and written sequentially via {@link FrameCodec}. All
 *       socket I/O occurs strictly outside {@link RoomSessionRegistry}'s locks.</li>
 *   <li>Each connection owns at most one registered {@link SessionInfo} established by {@code HELLO}.
 *       Subsequent operations ({@code LIST_ROOMS}, {@code JOIN}, {@code LEAVE}, {@code PING},
 *       {@code QUIT}) operate exclusively on that socket's bound session; any request carrying a
 *       mismatched {@code sessionId} is rejected with {@code STALE_BINDING} without touching another
 *       client's session.</li>
 *   <li>When a connection ends (via {@code QUIT}, clean EOF, framing error, I/O exception, or server
 *       {@link #close()}), its bound session and display name are released idempotently in a
 *       {@code finally} block.</li>
 * </ul>
 */
public final class RoomControlServer implements AutoCloseable {

    public static final String OP_HELLO = "HELLO";
    public static final String OP_LIST_ROOMS = "LIST_ROOMS";
    public static final String OP_JOIN = "JOIN";
    public static final String OP_LEAVE = "LEAVE";
    public static final String OP_PING = "PING";
    public static final String OP_QUIT = "QUIT";

    public static final String ERR_BAD_REQUEST = "BAD_REQUEST";
    public static final String ERR_UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION";
    public static final String ERR_UNSUPPORTED_OP = "UNSUPPORTED_OP";
    public static final String ERR_NOT_REGISTERED = "NOT_REGISTERED";
    public static final String ERR_ALREADY_REGISTERED = "ALREADY_REGISTERED";

    private static final System.Logger LOGGER = System.getLogger(RoomControlServer.class.getName());
    private static final int SHUTDOWN_WAIT_MILLIS = 3_000;
    private static final BigDecimal EXPECTED_PROTOCOL_VERSION =
            BigDecimal.valueOf(ProtocolConstants.PROTOCOL_VERSION);

    private final String bindHost;
    private final int requestedPort;
    private final StudyLinkConfig config;
    private final RoomSessionRegistry registry;

    private final ReentrantLock stateLock = new ReentrantLock();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final CountDownLatch shutdownComplete = new CountDownLatch(1);
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    private final Set<String> activeSessionIds = ConcurrentHashMap.newKeySet();
    private final ReentrantLock cleanupMonitor = new ReentrantLock();
    private final Condition cleanupCondition = cleanupMonitor.newCondition();

    private volatile ServerSocket serverSocket;
    private volatile InetSocketAddress boundAddress;
    private volatile Thread acceptorThread;
    private volatile ExecutorService clientExecutor;
    private volatile Runnable afterSocketBoundTestHook;

    /**
     * Creates a room-control server configured to bind {@code config.serverHost()} and
     * {@code config.roomsPort()}.
     *
     * @param config   validated StudyLink configuration (must not be {@code null})
     * @param registry shared session and room membership registry (must not be {@code null})
     */
    public RoomControlServer(StudyLinkConfig config, RoomSessionRegistry registry) {
        this(
                Objects.requireNonNull(config, "config must not be null").serverHost(),
                config.roomsPort(),
                config,
                registry
        );
    }

    /**
     * Creates a room-control server with an explicit bind host and port (such as {@code "127.0.0.1"}
     * and {@code 0} for ephemeral-port tests) while advertising service ports from {@code config}.
     *
     * @param bindHost      host address or IP to bind (must not be {@code null} or blank)
     * @param requestedPort TCP port to bind ({@code 0} for an ephemeral OS-assigned port, or {@code 1..65535})
     * @param config        validated StudyLink configuration (must not be {@code null})
     * @param registry      shared session and room membership registry (must not be {@code null})
     */
    public RoomControlServer(
            String bindHost,
            int requestedPort,
            StudyLinkConfig config,
            RoomSessionRegistry registry
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
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /**
     * Binds the TCP {@link ServerSocket} and starts the non-blocking accept loop thread.
     *
     * <p>Coordinates with {@link #close()} via {@code stateLock} so an in-progress {@code start()}
     * and a concurrent {@code close()} are serialized: either {@code close()} runs first and
     * {@code start()} throws {@link IllegalStateException} without binding a socket, or
     * {@code start()} finishes publishing its listener under {@code stateLock} and {@code close()}
     * immediately closes that listener before returning.
     *
     * @throws IOException           if binding the socket fails
     * @throws IllegalStateException if the server was already started or already closed
     */
    public void start() throws IOException {
        stateLock.lock();
        try {
            if (closed.get()) {
                throw new IllegalStateException("RoomControlServer has already been closed");
            }
            if (started.get()) {
                throw new IllegalStateException("RoomControlServer is already started");
            }

            ServerSocket socket = new ServerSocket();
            try {
                socket.setReuseAddress(true);
                InetAddress address = InetAddress.getByName(bindHost);
                socket.bind(new InetSocketAddress(address, requestedPort));
            } catch (IOException e) {
                closeQuietly(socket);
                throw e;
            }

            this.serverSocket = socket;
            this.boundAddress = (InetSocketAddress) socket.getLocalSocketAddress();
            int actualPort = socket.getLocalPort();

            Runnable hook = this.afterSocketBoundTestHook;
            if (hook != null) {
                hook.run();
            }

            AtomicInteger workerCounter = new AtomicInteger(1);
            ThreadFactory workerFactory = runnable -> {
                Thread t = new Thread(
                        runnable,
                        "studylink-rooms-worker-" + actualPort + "-" + workerCounter.getAndIncrement()
                );
                t.setDaemon(true);
                return t;
            };
            this.clientExecutor = Executors.newCachedThreadPool(workerFactory);

            Thread acceptor = new Thread(this::acceptLoop, "studylink-rooms-acceptor-" + actualPort);
            acceptor.setDaemon(true);
            this.acceptorThread = acceptor;
            this.started.set(true);
            acceptor.start();
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Returns {@code true} if the server has been started and has not yet been closed.
     */
    public boolean isRunning() {
        ServerSocket socket = this.serverSocket;
        return started.get() && !closed.get() && socket != null && !socket.isClosed();
    }

    /**
     * Returns the actual bound TCP port (resolving ephemeral port {@code 0} after {@link #start()}).
     *
     * @throws IllegalStateException if called before {@link #start()}
     */
    public int boundPort() {
        InetSocketAddress addr = this.boundAddress;
        if (addr == null) {
            throw new IllegalStateException("RoomControlServer has not been started");
        }
        return addr.getPort();
    }

    /**
     * Returns the actual bound {@link InetSocketAddress} after {@link #start()}.
     *
     * @throws IllegalStateException if called before {@link #start()}
     */
    public InetSocketAddress boundAddress() {
        InetSocketAddress addr = this.boundAddress;
        if (addr == null) {
            throw new IllegalStateException("RoomControlServer has not been started");
        }
        return addr;
    }

    /**
     * Returns the shared {@link RoomSessionRegistry} backing this control server.
     */
    public RoomSessionRegistry registry() {
        return registry;
    }

    /**
     * Returns the number of currently open client connections tracked by this listener.
     */
    public int activeConnectionCount() {
        return activeSockets.size();
    }

    /**
     * Waits up to the specified timeout for {@code sessionId} to be removed from the backing
     * {@link RoomSessionRegistry} (for example, after an abrupt client socket disconnect).
     *
     * @param sessionId session ID expected to close
     * @param timeout   maximum time to wait
     * @param unit      time unit of {@code timeout}
     * @return {@code true} if {@code sessionId} is no longer active in the registry;
     *         {@code false} if the timeout elapsed while the session remained active
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public boolean awaitSessionClosed(String sessionId, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(unit, "unit must not be null");
        long remainingNanos = unit.toNanos(timeout);
        cleanupMonitor.lock();
        try {
            while (registry.findSession(sessionId).isPresent()) {
                if (remainingNanos <= 0L) {
                    return false;
                }
                remainingNanos = cleanupCondition.awaitNanos(remainingNanos);
            }
            return true;
        } finally {
            cleanupMonitor.unlock();
        }
    }

    /**
     * Stops accepting new connections, closes the listener and all active client sockets, releases
     * all sessions owned by connections of this listener, and waits boundedly for worker threads
     * to exit. Safe and idempotent to call multiple times.
     */
    @Override
    public void close() {
        final boolean isFirstCloser;
        final ServerSocket listenerToClose;
        final ExecutorService executorToShutdown;
        final Thread acceptorToJoin;

        stateLock.lock();
        try {
            isFirstCloser = closed.compareAndSet(false, true);
            listenerToClose = this.serverSocket;
            executorToShutdown = this.clientExecutor;
            acceptorToJoin = this.acceptorThread;
            closeQuietly(listenerToClose);
        } finally {
            stateLock.unlock();
        }

        if (!isFirstCloser) {
            try {
                shutdownComplete.await(SHUTDOWN_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return;
        }

        try {
            for (Socket clientSocket : activeSockets) {
                closeQuietly(clientSocket);
            }
            activeSockets.clear();

            for (String sessionId : activeSessionIds) {
                registry.closeSession(sessionId);
            }
            activeSessionIds.clear();
            signalCleanupMonitor();

            if (executorToShutdown != null) {
                executorToShutdown.shutdownNow();
                try {
                    executorToShutdown.awaitTermination(SHUTDOWN_WAIT_MILLIS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            if (acceptorToJoin != null && acceptorToJoin != Thread.currentThread()) {
                try {
                    acceptorToJoin.join(SHUTDOWN_WAIT_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            shutdownComplete.countDown();
        }
    }

    /**
     * Package-private lifecycle test hook invoked inside {@link #start()} after the
     * {@link ServerSocket} is bound and while {@code stateLock} is still held.
     */
    void setAfterSocketBoundTestHook(Runnable hook) {
        this.afterSocketBoundTestHook = hook;
    }

    /**
     * Package-private inspection helper for tests verifying that a concurrent {@link #close()}
     * is queued on {@code stateLock} while {@link #start()} is in progress.
     */
    boolean hasQueuedLifecycleThreads() {
        return stateLock.hasQueuedThreads();
    }

    private void acceptLoop() {
        ServerSocket listener = this.serverSocket;
        while (!closed.get() && listener != null && !listener.isClosed()) {
            final Socket clientSocket;
            try {
                clientSocket = listener.accept();
            } catch (SocketException e) {
                if (closed.get() || listener.isClosed()) {
                    break;
                }
                LOGGER.log(System.Logger.Level.WARNING, "SocketException in RoomControlServer accept loop", e);
                continue;
            } catch (IOException e) {
                if (closed.get() || listener.isClosed()) {
                    break;
                }
                LOGGER.log(System.Logger.Level.WARNING, "I/O error in RoomControlServer accept loop", e);
                continue;
            }

            configureAcceptedSocket(clientSocket);
            activeSockets.add(clientSocket);
            if (closed.get()) {
                activeSockets.remove(clientSocket);
                closeQuietly(clientSocket);
                break;
            }

            ExecutorService executor = this.clientExecutor;
            if (executor == null || executor.isShutdown()) {
                activeSockets.remove(clientSocket);
                closeQuietly(clientSocket);
                break;
            }

            try {
                executor.execute(() -> handleClientConnection(clientSocket));
            } catch (RuntimeException e) {
                activeSockets.remove(clientSocket);
                closeQuietly(clientSocket);
            }
        }
    }

    private static void configureAcceptedSocket(Socket clientSocket) {
        try {
            clientSocket.setTcpNoDelay(true);
        } catch (SocketException ignored) {
            // Best-effort low-latency control frame flag
        }
    }

    private void handleClientConnection(Socket clientSocket) {
        SessionInfo boundSession = null;
        try (Socket socket = clientSocket;
             InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {

            while (!closed.get() && !socket.isClosed()) {
                final JsonObject request;
                try {
                    request = FrameCodec.readFrame(in);
                } catch (EOFException | FrameFormatException e) {
                    // Unreadable or truncated frame where no trustworthy request ID exists: close connection
                    break;
                }

                if (request == null) {
                    // Clean EOF at frame boundary
                    break;
                }

                String requestId = extractValidRequestId(request);
                if (requestId == null) {
                    // No valid request ID exists to correlate a response: close connection predictably
                    break;
                }

                ConnectionDispatchOutcome outcome = dispatchRequest(request, requestId, boundSession);
                boundSession = outcome.updatedSession();

                FrameCodec.writeFrame(out, outcome.response());
                if (outcome.closeConnectionAfterResponse()) {
                    break;
                }
            }
        } catch (IOException ignored) {
            // Connection dropped or closed during shutdown; cleanup runs in finally
        } finally {
            activeSockets.remove(clientSocket);
            if (boundSession != null) {
                activeSessionIds.remove(boundSession.sessionId());
                registry.closeSession(boundSession.sessionId());
            }
            closeQuietly(clientSocket);
            signalCleanupMonitor();
        }
    }

    private ConnectionDispatchOutcome dispatchRequest(
            JsonObject request,
            String requestId,
            SessionInfo boundSession
    ) {
        if (!hasSupportedProtocolVersion(request)) {
            JsonObject err = buildErrorResponse(
                    requestId,
                    ERR_UNSUPPORTED_VERSION,
                    "Unsupported or missing protocol version 'v' (expected " + ProtocolConstants.PROTOCOL_VERSION + ")"
            );
            return new ConnectionDispatchOutcome(boundSession, err, false);
        }

        String op = extractOptionalStringField(request, "op");
        if (op == null || op.isBlank()) {
            JsonObject err = buildErrorResponse(
                    requestId,
                    ERR_BAD_REQUEST,
                    "Request field 'op' must be a non-blank string"
            );
            return new ConnectionDispatchOutcome(boundSession, err, false);
        }

        final JsonObject data;
        try {
            data = extractOptionalDataObject(request);
        } catch (IllegalArgumentException e) {
            JsonObject err = buildErrorResponse(requestId, ERR_BAD_REQUEST, e.getMessage());
            return new ConnectionDispatchOutcome(boundSession, err, false);
        }

        return switch (op) {
            case OP_HELLO -> handleHello(requestId, data, boundSession);
            case OP_LIST_ROOMS -> handleListRooms(requestId, request, data, boundSession);
            case OP_JOIN -> handleJoin(requestId, request, data, boundSession);
            case OP_LEAVE -> handleLeave(requestId, request, data, boundSession);
            case OP_PING -> handlePing(requestId, request, data, boundSession);
            case OP_QUIT -> handleQuit(requestId, request, data, boundSession);
            default -> new ConnectionDispatchOutcome(
                    boundSession,
                    buildErrorResponse(requestId, ERR_UNSUPPORTED_OP, "Unsupported control operation: '" + op + "'"),
                    false
            );
        };
    }

    private ConnectionDispatchOutcome handleHello(
            String requestId,
            JsonObject data,
            SessionInfo boundSession
    ) {
        if (boundSession != null) {
            JsonObject err = buildErrorResponse(
                    requestId,
                    ERR_ALREADY_REGISTERED,
                    "Session is already registered on this connection as '" + boundSession.displayName() + "'"
            );
            return new ConnectionDispatchOutcome(boundSession, err, false);
        }

        String rawDisplayName = (data == null) ? null : extractStrictStringField(data, "displayName");
        if (rawDisplayName == null) {
            JsonObject err = buildErrorResponse(
                    requestId,
                    RoomRegistryErrorCode.INVALID_NAME.name(),
                    "HELLO requires a string 'displayName' inside 'data'"
            );
            return new ConnectionDispatchOutcome(null, err, false);
        }

        final SessionInfo newSession;
        try {
            newSession = registry.registerSession(rawDisplayName);
        } catch (RoomRegistryException e) {
            JsonObject err = buildErrorResponse(requestId, e.errorCode().name(), e.getMessage());
            return new ConnectionDispatchOutcome(null, err, false);
        }

        activeSessionIds.add(newSession.sessionId());
        if (closed.get()) {
            activeSessionIds.remove(newSession.sessionId());
            registry.closeSession(newSession.sessionId());
            JsonObject err = buildErrorResponse(
                    requestId,
                    RoomRegistryErrorCode.STALE_BINDING.name(),
                    "Server is shutting down"
            );
            return new ConnectionDispatchOutcome(null, err, true);
        }

        JsonObject ports = new JsonObject();
        ports.addProperty("discoveryUdp", config.discoveryPort());
        ports.addProperty("roomsTcp", boundPort());
        ports.addProperty("chatNioTcp", config.chatPort());
        ports.addProperty("filesTcp", config.filesPort());
        ports.addProperty("notesTlsTcp", config.notesPort());

        JsonObject responseData = new JsonObject();
        responseData.addProperty("sessionId", newSession.sessionId());
        responseData.addProperty("displayName", newSession.displayName());
        responseData.add("ports", ports);

        return new ConnectionDispatchOutcome(newSession, buildSuccessResponse(requestId, responseData), false);
    }

    private ConnectionDispatchOutcome handleListRooms(
            String requestId,
            JsonObject request,
            JsonObject data,
            SessionInfo boundSession
    ) {
        JsonObject precheckError = checkRegisteredAndSameSession(requestId, request, data, boundSession, OP_LIST_ROOMS);
        if (precheckError != null) {
            return new ConnectionDispatchOutcome(boundSession, precheckError, false);
        }

        JsonArray roomsArray = new JsonArray();
        for (RoomDefinition room : registry.listRooms()) {
            JsonObject roomObj = new JsonObject();
            roomObj.addProperty("id", room.id());
            roomObj.addProperty("name", room.displayName());
            roomsArray.add(roomObj);
        }

        JsonObject responseData = new JsonObject();
        responseData.add("rooms", roomsArray);
        return new ConnectionDispatchOutcome(boundSession, buildSuccessResponse(requestId, responseData), false);
    }

    private ConnectionDispatchOutcome handleJoin(
            String requestId,
            JsonObject request,
            JsonObject data,
            SessionInfo boundSession
    ) {
        JsonObject precheckError = checkRegisteredAndSameSession(requestId, request, data, boundSession, OP_JOIN);
        if (precheckError != null) {
            return new ConnectionDispatchOutcome(boundSession, precheckError, false);
        }

        String roomId = (data == null) ? null : extractStrictStringField(data, "roomId");
        if (roomId == null) {
            JsonObject err = buildErrorResponse(
                    requestId,
                    RoomRegistryErrorCode.UNKNOWN_ROOM.name(),
                    "JOIN requires a string 'roomId' inside 'data'"
            );
            return new ConnectionDispatchOutcome(boundSession, err, false);
        }

        final RoomBinding binding;
        try {
            binding = registry.joinRoom(boundSession.sessionId(), roomId);
        } catch (RoomRegistryException e) {
            JsonObject err = buildErrorResponse(requestId, e.errorCode().name(), e.getMessage());
            return new ConnectionDispatchOutcome(boundSession, err, false);
        }

        JsonObject responseData = new JsonObject();
        responseData.addProperty("sessionId", binding.sessionId());
        responseData.addProperty("roomId", binding.roomId());
        responseData.addProperty("revision", binding.revision());
        return new ConnectionDispatchOutcome(boundSession, buildSuccessResponse(requestId, responseData), false);
    }

    private ConnectionDispatchOutcome handleLeave(
            String requestId,
            JsonObject request,
            JsonObject data,
            SessionInfo boundSession
    ) {
        JsonObject precheckError = checkRegisteredAndSameSession(requestId, request, data, boundSession, OP_LEAVE);
        if (precheckError != null) {
            return new ConnectionDispatchOutcome(boundSession, precheckError, false);
        }

        final Optional<RoomBinding> invalidated;
        try {
            invalidated = registry.leaveRoom(boundSession.sessionId());
        } catch (RoomRegistryException e) {
            JsonObject err = buildErrorResponse(requestId, e.errorCode().name(), e.getMessage());
            return new ConnectionDispatchOutcome(boundSession, err, false);
        }

        JsonObject responseData = new JsonObject();
        responseData.addProperty("sessionId", boundSession.sessionId());
        if (invalidated.isPresent()) {
            responseData.addProperty("leftRoomId", invalidated.get().roomId());
        } else {
            responseData.add("leftRoomId", JsonNull.INSTANCE);
        }
        return new ConnectionDispatchOutcome(boundSession, buildSuccessResponse(requestId, responseData), false);
    }

    private ConnectionDispatchOutcome handlePing(
            String requestId,
            JsonObject request,
            JsonObject data,
            SessionInfo boundSession
    ) {
        JsonObject precheckError = checkRegisteredAndSameSession(requestId, request, data, boundSession, OP_PING);
        if (precheckError != null) {
            return new ConnectionDispatchOutcome(boundSession, precheckError, false);
        }

        if (registry.findSession(boundSession.sessionId()).isEmpty()) {
            JsonObject err = buildErrorResponse(
                    requestId,
                    RoomRegistryErrorCode.STALE_BINDING.name(),
                    "Session is no longer active: " + boundSession.sessionId()
            );
            return new ConnectionDispatchOutcome(boundSession, err, false);
        }

        JsonObject responseData = new JsonObject();
        responseData.addProperty("sessionId", boundSession.sessionId());
        responseData.addProperty("pong", true);
        return new ConnectionDispatchOutcome(boundSession, buildSuccessResponse(requestId, responseData), false);
    }

    private ConnectionDispatchOutcome handleQuit(
            String requestId,
            JsonObject request,
            JsonObject data,
            SessionInfo boundSession
    ) {
        if (boundSession != null && hasMismatchedSessionId(request, data, boundSession.sessionId())) {
            JsonObject err = buildErrorResponse(
                    requestId,
                    RoomRegistryErrorCode.STALE_BINDING.name(),
                    "Request sessionId does not match this connection's bound session"
            );
            return new ConnectionDispatchOutcome(boundSession, err, false);
        }

        if (boundSession != null) {
            activeSessionIds.remove(boundSession.sessionId());
            registry.closeSession(boundSession.sessionId());
            signalCleanupMonitor();
        }

        JsonObject responseData = new JsonObject();
        responseData.addProperty("closed", true);
        return new ConnectionDispatchOutcome(null, buildSuccessResponse(requestId, responseData), true);
    }

    private static JsonObject checkRegisteredAndSameSession(
            String requestId,
            JsonObject request,
            JsonObject data,
            SessionInfo boundSession,
            String operationName
    ) {
        if (boundSession == null) {
            return buildErrorResponse(
                    requestId,
                    ERR_NOT_REGISTERED,
                    "Complete HELLO before sending " + operationName
            );
        }
        if (hasMismatchedSessionId(request, data, boundSession.sessionId())) {
            return buildErrorResponse(
                    requestId,
                    RoomRegistryErrorCode.STALE_BINDING.name(),
                    "Request sessionId does not match this connection's bound session"
            );
        }
        return null;
    }

    private static boolean hasMismatchedSessionId(
            JsonObject request,
            JsonObject data,
            String boundSessionId
    ) {
        if (hasMismatchedField(request, "sessionId", boundSessionId)) {
            return true;
        }
        return data != null && hasMismatchedField(data, "sessionId", boundSessionId);
    }

    private static boolean hasMismatchedField(JsonObject obj, String fieldName, String expectedSessionId) {
        if (!obj.has(fieldName)) {
            return false;
        }
        JsonElement element = obj.get(fieldName);
        if (element == null || element.isJsonNull()) {
            return false;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return true;
        }
        return !expectedSessionId.equals(element.getAsString().trim());
    }

    private static boolean hasSupportedProtocolVersion(JsonObject request) {
        if (!request.has("v")) {
            return false;
        }
        JsonElement vElem = request.get("v");
        if (vElem == null || !vElem.isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive prim = vElem.getAsJsonPrimitive();
        if (!prim.isNumber()) {
            return false;
        }
        try {
            return prim.getAsBigDecimal().compareTo(EXPECTED_PROTOCOL_VERSION) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String extractValidRequestId(JsonObject request) {
        String id = extractStrictStringField(request, "id");
        if (id == null || id.isBlank()) {
            return null;
        }
        return id;
    }

    private static String extractOptionalStringField(JsonObject obj, String key) {
        return extractStrictStringField(obj, key);
    }

    private static String extractStrictStringField(JsonObject obj, String key) {
        if (!obj.has(key)) {
            return null;
        }
        JsonElement elem = obj.get(key);
        if (elem == null || !elem.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive prim = elem.getAsJsonPrimitive();
        return prim.isString() ? prim.getAsString() : null;
    }

    private static JsonObject extractOptionalDataObject(JsonObject request) {
        if (!request.has("data")) {
            return null;
        }
        JsonElement dataElem = request.get("data");
        if (dataElem == null || dataElem.isJsonNull()) {
            return null;
        }
        if (!dataElem.isJsonObject()) {
            throw new IllegalArgumentException("Request field 'data' must be a JSON object");
        }
        return dataElem.getAsJsonObject();
    }

    private static JsonObject buildSuccessResponse(String requestId, JsonObject data) {
        JsonObject response = new JsonObject();
        response.addProperty("v", ProtocolConstants.PROTOCOL_VERSION);
        response.addProperty("id", requestId);
        response.addProperty("ok", true);
        response.add("data", data);
        return response;
    }

    private static JsonObject buildErrorResponse(String requestId, String code, String message) {
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

    private void signalCleanupMonitor() {
        cleanupMonitor.lock();
        try {
            cleanupCondition.signalAll();
        } finally {
            cleanupMonitor.unlock();
        }
    }

    private static void closeQuietly( ServerSocket serverSocket) {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private record ConnectionDispatchOutcome(
            SessionInfo updatedSession,
            JsonObject response,
            boolean closeConnectionAfterResponse
    ) {
    }
}
