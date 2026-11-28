package lk.studylink.client.rooms;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import lk.studylink.shared.config.StudyLinkConfig;
import lk.studylink.shared.model.RoomBinding;
import lk.studylink.shared.model.RoomDefinition;
import lk.studylink.shared.model.SessionInfo;
import lk.studylink.shared.protocol.FrameCodec;
import lk.studylink.shared.protocol.ProtocolConstants;

/**
 * Production blocking TCP room-control client for Member 1 (SRS Sections 5.1, 6.3, F02–F04, F13, Q07, T02, T10).
 *
 * <h2>Concurrency, Serialization, and Failure Isolation</h2>
 * <ul>
 *   <li>Each connection attempt creates an isolated generation object with its own fair
 *       {@link ReentrantLock} ({@code exchangeLock}), request counter, and background heartbeat
 *       scheduler, and registers ownership in {@code activeConnection} before performing any
 *       blocking {@link Socket#connect} or {@code HELLO} I/O.</li>
 *   <li>Every control exchange ({@code HELLO}, {@code LIST_ROOMS}, {@code JOIN}, {@code LEAVE},
 *       {@code PING}, {@code QUIT}), including background heartbeats, acquires {@code exchangeLock}
 *       around the full {@code [writeFrame -> readFrame -> validate -> commit local state]} sequence
 *       so a background {@code PING} and a user action can never interleave frames, consume each
 *       other's reply, or apply {@code JOIN}/{@code LEAVE} binding mutations out of order.</li>
 *   <li>Automatic {@code PING} heartbeats start only after {@code HELLO} succeeds on the latest
 *       still-current attempt and are cancelled immediately on {@link #close()}, transport failure,
 *       or reconnect. Because each attempt is pinned to its specific connection generation, a
 *       delayed reply, failure, or timer callback from an older generation can never publish a
 *       session, replace, or close a newer connection.</li>
 *   <li>A correlated server rejection on {@code JOIN} (such as {@code UNKNOWN_ROOM}) throws
 *       {@link RoomClientException} with the server error code while preserving the previous valid
 *       {@link RoomBinding}.</li>
 *   <li>On EOF, read timeout, malformed frame, mismatched response {@code id}, invalid protocol
 *       version {@code v}, or missing required payload fields, the affected connection and its local
 *       {@link RoomBinding} are immediately invalidated and the underlying {@link Socket} is closed.</li>
 *   <li>Calling {@link #close()} cancels any pending or established connection attempt and closes
 *       its {@link Socket} immediately without holding a lifecycle lock across blocking I/O,
 *       unblocking any thread waiting in {@code connect}/{@code HELLO} or a control exchange.</li>
 * </ul>
 *
 * <h2>M2 Discovery Handoff</h2>
 * Until Member 2's shared {@code Endpoint} model is introduced, {@link #connect(InetSocketAddress, String)}
 * and {@link #connect(String, int, String)} accept a standard JDK {@link InetSocketAddress} or
 * {@code (host, controlPort)} pair from either UDP discovery or manual user entry.
 */
public final class RoomClient implements AutoCloseable {

    public static final String OP_HELLO = "HELLO";
    public static final String OP_LIST_ROOMS = "LIST_ROOMS";
    public static final String OP_JOIN = "JOIN";
    public static final String OP_LEAVE = "LEAVE";
    public static final String OP_PING = "PING";
    public static final String OP_QUIT = "QUIT";

    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 3_000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 5_000;
    private static final int QUIT_LOCK_WAIT_MS = 150;
    private static final int QUIT_READ_TIMEOUT_MS = 500;
    private static final BigDecimal EXPECTED_PROTOCOL_VERSION =
            BigDecimal.valueOf(ProtocolConstants.PROTOCOL_VERSION);
    private static final HostResolver DEFAULT_HOST_RESOLVER = InetAddress::getByName;
    private static final BooleanSupplier NEVER_CANCELLED = () -> false;

    /**
     * Resolves a server hostname or IPv4 literal to an {@link InetAddress}.
     */
    @FunctionalInterface
    public interface HostResolver {
        InetAddress resolve(String host) throws IOException;
    }

    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int pingIntervalMs;
    private final boolean autoPingEnabled;

    private final AtomicLong generationSequence = new AtomicLong(1L);
    private final AtomicReference<ActiveConnection> activeConnection = new AtomicReference<>();
    private final Set<ActiveConnection> trackedConnections = ConcurrentHashMap.newKeySet();
    private volatile HostResolver hostResolver = DEFAULT_HOST_RESOLVER;
    private volatile Consumer<String> afterExchangeEnvelopeTestHook;

    /**
     * Creates a client using SRS baseline defaults ({@code 3,000 ms} connect timeout,
     * {@code 5,000 ms} read timeout, and {@code 5,000 ms} automatic {@code PING} interval).
     */
    public RoomClient() {
        this(StudyLinkConfig.srsDefaults());
    }

    /**
     * Creates a client using the timeout and ping intervals from {@code config}.
     *
     * @param config validated StudyLink configuration (must not be {@code null})
     */
    public RoomClient(StudyLinkConfig config) {
        this(
                Objects.requireNonNull(config, "config must not be null").discoveryTimeoutMs(),
                Math.max(DEFAULT_READ_TIMEOUT_MS, config.controlPingIntervalMs()),
                config.controlPingIntervalMs(),
                true
        );
    }

    /**
     * Creates a client using {@code config} with an explicit per-request read timeout and
     * automatic heartbeat toggle.
     *
     * @param config          validated StudyLink configuration (must not be {@code null})
     * @param readTimeoutMs   bounded per-request socket read timeout in milliseconds ({@code >= 1})
     * @param autoPingEnabled whether to schedule automatic periodic {@code PING} frames after {@code HELLO}
     */
    public RoomClient(StudyLinkConfig config, int readTimeoutMs, boolean autoPingEnabled) {
        this(
                Objects.requireNonNull(config, "config must not be null").discoveryTimeoutMs(),
                readTimeoutMs,
                config.controlPingIntervalMs(),
                autoPingEnabled
        );
    }

    /**
     * Creates a client with explicit timeouts and heartbeat settings.
     *
     * @param connectTimeoutMs bounded TCP connect timeout in milliseconds ({@code >= 1})
     * @param readTimeoutMs    bounded per-request socket read timeout in milliseconds ({@code >= 1})
     * @param pingIntervalMs   periodic control ping interval in milliseconds ({@code >= 1} when enabled)
     * @param autoPingEnabled  whether to schedule automatic periodic {@code PING} frames after {@code HELLO}
     */
    public RoomClient(
            int connectTimeoutMs,
            int readTimeoutMs,
            int pingIntervalMs,
            boolean autoPingEnabled
    ) {
        if (connectTimeoutMs < 1) {
            throw new IllegalArgumentException("connectTimeoutMs must be >= 1, found " + connectTimeoutMs);
        }
        if (readTimeoutMs < 1) {
            throw new IllegalArgumentException("readTimeoutMs must be >= 1, found " + readTimeoutMs);
        }
        if (autoPingEnabled && pingIntervalMs < 1) {
            throw new IllegalArgumentException("pingIntervalMs must be >= 1 when autoPingEnabled is true, found " + pingIntervalMs);
        }
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.pingIntervalMs = pingIntervalMs;
        this.autoPingEnabled = autoPingEnabled;
    }

    /**
     * Connects to {@code host:controlPort}, performs the {@code HELLO} handshake for
     * {@code displayName}, records the returned {@link SessionInfo} and {@link AdvertisedServicePorts},
     * and starts automatic {@code PING} heartbeats (if enabled).
     *
     * @param host        server hostname or IPv4 address (must not be {@code null} or blank)
     * @param controlPort server TCP control port ({@code 1..65535})
     * @param displayName desired student display name
     * @return established {@link SessionInfo}
     * @throws RoomClientException if {@code HELLO} is rejected (e.g. {@code NAME_IN_USE},
     *                             {@code INVALID_NAME}), cancelled, superseded, or invalid
     * @throws IOException         if connecting or socket I/O fails
     */
    public SessionInfo connect(String host, int controlPort, String displayName) throws IOException {
        return connect(host, controlPort, displayName, NEVER_CANCELLED);
    }

    /**
     * Connects to {@code host:controlPort} with an external cancellation check (such as UI disposal
     * or generation supersession) that is evaluated before and after hostname resolution, socket
     * connection, and the {@code HELLO} handshake.
     *
     * <p>Unlike constructing a resolved {@link InetSocketAddress} upfront, this method registers
     * the connection attempt before resolving {@code host} so a concurrent {@link #close()} or
     * UI disposal during DNS lookup cancels the attempt before any socket connection or
     * {@code HELLO} frame is sent.
     */
    public SessionInfo connect(
            String host,
            int controlPort,
            String displayName,
            BooleanSupplier externalCancelCheck
    ) throws IOException {
        Objects.requireNonNull(host, "host must not be null");
        Objects.requireNonNull(externalCancelCheck, "externalCancelCheck must not be null");
        String trimmedHost = host.trim();
        if (trimmedHost.isEmpty()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (controlPort < 1 || controlPort > 65_535) {
            throw new IllegalArgumentException("controlPort must be 1..65535, found " + controlPort);
        }
        return connect(
                InetSocketAddress.createUnresolved(trimmedHost, controlPort),
                displayName,
                externalCancelCheck
        );
    }

    /**
     * Connects to {@code endpoint}, performs the {@code HELLO} handshake for {@code displayName},
     * records the returned {@link SessionInfo} and {@link AdvertisedServicePorts}, and starts
     * automatic {@code PING} heartbeats (if enabled).
     *
     * <p>Ownership of the new attempt is recorded before any blocking hostname resolution,
     * {@link Socket#connect}, or {@code HELLO} exchange begins. If this {@code RoomClient} already
     * holds an established connection, that previous connection and its heartbeat timer are closed
     * without affecting the new attempt. Any older still-pending attempt is superseded and can
     * never overwrite or close the newer session when its delayed reply arrives.
     *
     * @param endpoint    target server control address (must not be {@code null})
     * @param displayName desired student display name (must not be {@code null})
     * @return established {@link SessionInfo}
     * @throws RoomClientException if {@code HELLO} is rejected (e.g. {@code NAME_IN_USE},
     *                             {@code INVALID_NAME}), cancelled by {@link #close()}, superseded
     *                             by a newer {@code connect(...)}, or the response violates Protocol v1
     * @throws IOException         if opening the socket fails
     */
    public SessionInfo connect(InetSocketAddress endpoint, String displayName) throws IOException {
        return connect(endpoint, displayName, NEVER_CANCELLED);
    }

    /**
     * Connects to {@code endpoint} with an external cancellation check evaluated across hostname
     * resolution, socket connect, and {@code HELLO}.
     */
    public SessionInfo connect(
            InetSocketAddress endpoint,
            String displayName,
            BooleanSupplier externalCancelCheck
    ) throws IOException {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(externalCancelCheck, "externalCancelCheck must not be null");

        long generation = generationSequence.getAndIncrement();
        Socket socket = new Socket();
        ActiveConnection candidate = new ActiveConnection(generation, endpoint, socket, externalCancelCheck);
        trackedConnections.add(candidate);

        ActiveConnection previous = activeConnection.getAndSet(candidate);
        if (previous != null && previous != candidate && previous.established.get()) {
            closeSpecificConnection(previous, true);
        }

        try {
            if (!isCurrentAttempt(candidate)) {
                throw new RoomClientException("Control connection attempt was cancelled or superseded", true);
            }
            InetSocketAddress resolvedEndpoint = resolveEndpoint(endpoint);
            candidate.endpoint = resolvedEndpoint;
            if (!isCurrentAttempt(candidate)) {
                throw new RoomClientException(
                        "Control connection attempt was cancelled or superseded during hostname resolution",
                        true
                );
            }
            socket.connect(resolvedEndpoint, connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            socket.setTcpNoDelay(true);
            candidate.initStreams(socket.getInputStream(), socket.getOutputStream());
            if (!isCurrentAttempt(candidate)) {
                throw new RoomClientException("Control connection attempt was cancelled or superseded", true);
            }
        } catch (RoomClientException e) {
            cleanupAttempt(candidate);
            throw e;
        } catch (IOException e) {
            cleanupAttempt(candidate);
            throw new RoomClientException("Failed to connect to " + endpoint + ": " + e.getMessage(), e, true);
        }

        JsonObject helloData = new JsonObject();
        helloData.addProperty("displayName", displayName);

        final SessionInfo establishedSession;
        try {
            establishedSession = executeExchange(candidate, OP_HELLO, helloData, false, responseData -> {
                SessionInfo parsedSession = parseHelloSessionInfo(responseData);
                AdvertisedServicePorts parsedPorts = parseHelloPorts(responseData);
                if (!isCurrentAttempt(candidate)) {
                    throw new RoomClientException(
                            "Control connection attempt was cancelled or superseded during HELLO",
                            true
                    );
                }
                candidate.sessionInfo = parsedSession;
                candidate.advertisedPorts = parsedPorts;
                candidate.established.set(true);
                if (!isCurrentAttempt(candidate)) {
                    candidate.established.set(false);
                    throw new RoomClientException(
                            "Control connection attempt was cancelled or superseded during HELLO",
                            true
                    );
                }
                return parsedSession;
            });
        } catch (RoomClientException e) {
            cleanupAttempt(candidate);
            throw e;
        }

        if (autoPingEnabled && pingIntervalMs > 0) {
            candidate.startAutoPing(this, pingIntervalMs);
        }
        if (!isCurrentAttempt(candidate)) {
            cleanupAttempt(candidate);
            throw new RoomClientException(
                    "Control connection attempt was cancelled or superseded after HELLO",
                    true
            );
        }

        return establishedSession;
    }

    /**
     * Queries the server's configured room catalogue via {@code LIST_ROOMS}.
     *
     * @return immutable list of configured {@link RoomDefinition}s in display order
     * @throws RoomClientException if not connected, the server returns an error, or the response is malformed
     */
    public List<RoomDefinition> listRooms() throws IOException {
        ActiveConnection conn = requireActiveConnection();
        return executeExchange(conn, OP_LIST_ROOMS, new JsonObject(), true, data -> {
            if (!data.has("rooms") || !data.get("rooms").isJsonArray()) {
                invalidateConnection(conn);
                throw new RoomClientException("LIST_ROOMS response missing 'rooms' array", true);
            }

            JsonArray roomsArray = data.getAsJsonArray("rooms");
            List<RoomDefinition> rooms = new ArrayList<>(roomsArray.size());
            for (JsonElement element : roomsArray) {
                if (element == null || !element.isJsonObject()) {
                    invalidateConnection(conn);
                    throw new RoomClientException("LIST_ROOMS entry is not a JSON object", true);
                }
                JsonObject roomObj = element.getAsJsonObject();
                String id = extractNonBlankString(roomObj, "id");
                String name = extractNonBlankString(roomObj, "name");
                if (id == null || name == null) {
                    invalidateConnection(conn);
                    throw new RoomClientException("LIST_ROOMS entry missing non-blank 'id' or 'name'", true);
                }
                rooms.add(new RoomDefinition(id, name));
            }

            return List.copyOf(rooms);
        });
    }

    /**
     * Joins or switches to {@code roomId} via {@code JOIN}, replacing the client's current
     * {@link RoomBinding} on success while still holding the connection's serialized exchange lock.
     *
     * <p>If the server rejects the request (for example with {@code UNKNOWN_ROOM}), throws
     * {@link RoomClientException} carrying that error code and preserves the previous valid
     * {@link RoomBinding}.
     *
     * @param roomId target room ID such as {@code "netprog"} (must not be {@code null})
     * @return newly issued {@link RoomBinding}
     * @throws RoomClientException if not connected, the server rejects the join, or the response is invalid
     */
    public RoomBinding join(String roomId) throws IOException {
        Objects.requireNonNull(roomId, "roomId must not be null");
        ActiveConnection conn = requireActiveConnection();

        JsonObject reqData = new JsonObject();
        reqData.addProperty("roomId", roomId);

        return executeExchange(conn, OP_JOIN, reqData, true, respData -> {
            String respSessionId = extractNonBlankString(respData, "sessionId");
            String respRoomId = extractNonBlankString(respData, "roomId");
            Long revision = extractPositiveLong(respData, "revision");

            SessionInfo session = conn.sessionInfo;
            if (respSessionId == null
                    || respRoomId == null
                    || revision == null
                    || session == null
                    || !session.sessionId().equals(respSessionId)
                    || !roomId.trim().equals(respRoomId)) {
                invalidateConnection(conn);
                throw new RoomClientException("Malformed JOIN response data from server", true);
            }

            RoomBinding newBinding = new RoomBinding(respSessionId, respRoomId, revision);
            conn.currentBinding = newBinding;
            return newBinding;
        });
    }

    /**
     * Alias for {@link #join(String)}.
     */
    public RoomBinding joinRoom(String roomId) throws IOException {
        return join(roomId);
    }

    /**
     * Leaves the current room via {@code LEAVE}, clearing the local {@link RoomBinding} under
     * the connection's serialized exchange lock while keeping the session and display name active.
     *
     * @return the room ID that was left, or {@link Optional#empty()} if the session was already
     *         outside all rooms
     * @throws RoomClientException if not connected, the server rejects the request, or the response is invalid
     */
    public Optional<String> leave() throws IOException {
        ActiveConnection conn = requireActiveConnection();
        return executeExchange(conn, OP_LEAVE, new JsonObject(), true, respData -> {
            String respSessionId = extractNonBlankString(respData, "sessionId");
            SessionInfo session = conn.sessionInfo;
            if (respSessionId == null || session == null || !session.sessionId().equals(respSessionId)) {
                invalidateConnection(conn);
                throw new RoomClientException("Malformed LEAVE response: missing or mismatched sessionId", true);
            }

            String leftRoomId = null;
            if (respData.has("leftRoomId") && !respData.get("leftRoomId").isJsonNull()) {
                leftRoomId = extractNonBlankString(respData, "leftRoomId");
                if (leftRoomId == null) {
                    invalidateConnection(conn);
                    throw new RoomClientException("Malformed LEAVE response: invalid leftRoomId", true);
                }
            }

            conn.currentBinding = null;
            return Optional.ofNullable(leftRoomId);
        });
    }

    /**
     * Alias for {@link #leave()}.
     */
    public Optional<String> leaveRoom() throws IOException {
        return leave();
    }

    /**
     * Sends an explicit {@code PING} control request and verifies the server's correlated
     * {@code pong} response.
     *
     * @return {@code true} when the server replies with a valid correlated {@code pong: true}
     * @throws RoomClientException if not connected or the ping exchange fails
     */
    public boolean ping() throws IOException {
        ActiveConnection conn = requireActiveConnection();
        pingOnConnection(conn);
        return true;
    }

    /**
     * Cancels any pending or established connection attempts, stops automatic heartbeats, sends a
     * best-effort {@code QUIT} frame if an established connection is idle and healthy, and closes
     * the underlying TCP socket (unblocking any thread waiting in {@code connect}/{@code HELLO} or
     * a server reply). Safe and idempotent to call multiple times.
     */
    @Override
    public void close() {
        ActiveConnection current = activeConnection.getAndSet(null);
        if (current != null) {
            closeSpecificConnection(current, true);
        }
        for (ActiveConnection tracked : trackedConnections) {
            if (tracked != current) {
                closeSpecificConnection(tracked, false);
            }
        }
    }

    /**
     * Returns {@code true} if this client currently holds a live, established, non-invalidated
     * control connection.
     */
    public boolean isConnected() {
        ActiveConnection conn = activeConnection.get();
        return conn != null && conn.isEstablishedAndOpen() && activeConnection.get() == conn;
    }

    /**
     * Returns the immutable {@link SessionInfo} established by {@code HELLO} on the current
     * active connection, or {@link Optional#empty()} if disconnected or invalidated.
     */
    public Optional<SessionInfo> sessionInfo() {
        ActiveConnection conn = activeConnection.get();
        return (conn != null && conn.isEstablishedAndOpen() && activeConnection.get() == conn)
                ? Optional.ofNullable(conn.sessionInfo)
                : Optional.empty();
    }

    /**
     * Returns the immutable {@link AdvertisedServicePorts} returned by {@code HELLO} on the
     * current active connection, or {@link Optional#empty()} if disconnected or invalidated.
     */
    public Optional<AdvertisedServicePorts> advertisedPorts() {
        ActiveConnection conn = activeConnection.get();
        return (conn != null && conn.isEstablishedAndOpen() && activeConnection.get() == conn)
                ? Optional.ofNullable(conn.advertisedPorts)
                : Optional.empty();
    }

    /**
     * Alias for {@link #advertisedPorts()}.
     */
    public Optional<AdvertisedServicePorts> servicePorts() {
        return advertisedPorts();
    }

    /**
     * Returns the current active {@link RoomBinding}, or {@link Optional#empty()} if the student
     * has not joined a room, has left their room, or the connection was closed/invalidated.
     */
    public Optional<RoomBinding> currentBinding() {
        ActiveConnection conn = activeConnection.get();
        return (conn != null && conn.isEstablishedAndOpen() && activeConnection.get() == conn)
                ? Optional.ofNullable(conn.currentBinding)
                : Optional.empty();
    }

    /**
     * Returns the remote server control endpoint for the current active connection, or
     * {@link Optional#empty()} if disconnected.
     */
    public Optional<InetSocketAddress> remoteEndpoint() {
        ActiveConnection conn = activeConnection.get();
        return (conn != null && conn.isEstablishedAndOpen() && activeConnection.get() == conn)
                ? Optional.of(conn.endpoint)
                : Optional.empty();
    }

    /**
     * Package-private inspection helper for tests verifying that a concurrent heartbeat or caller
     * is queued on the current connection's serialized exchange lock.
     */
    boolean hasQueuedExchangeThreads() {
        ActiveConnection conn = activeConnection.get();
        return conn != null && conn.exchangeLock.hasQueuedThreads();
    }

    /**
     * Package-private helper returning the current connection generation number ({@code 0} if none).
     */
    long currentConnectionGeneration() {
        ActiveConnection conn = activeConnection.get();
        return conn != null ? conn.generation : 0L;
    }

    /**
     * Alias for {@link #currentConnectionGeneration()}.
     */
    long activeGeneration() {
        return currentConnectionGeneration();
    }

    /**
     * Installs a custom {@link HostResolver} (used by tests to coordinate hostname resolution
     * timing deterministically; defaults to {@link InetAddress#getByName(String)}).
     */
    public void setHostResolver(HostResolver hostResolver) {
        this.hostResolver = Objects.requireNonNull(hostResolver, "hostResolver must not be null");
    }

    /**
     * Package-private test hook invoked inside {@code executeExchange} after the response envelope
     * has been read and validated and before the operation's state change is committed or
     * {@code exchangeLock} is released.
     */
    void setAfterExchangeEnvelopeTestHook(Consumer<String> hook) {
        this.afterExchangeEnvelopeTestHook = hook;
    }

    private InetSocketAddress resolveEndpoint(InetSocketAddress endpoint) throws IOException {
        HostResolver resolver = this.hostResolver;
        if (endpoint.isUnresolved() || resolver != DEFAULT_HOST_RESOLVER) {
            InetAddress resolvedAddress = resolver.resolve(endpoint.getHostString());
            return new InetSocketAddress(resolvedAddress, endpoint.getPort());
        }
        return endpoint;
    }

    private void pingOnConnection(ActiveConnection conn) throws RoomClientException {
        executeExchange(conn, OP_PING, new JsonObject(), true, respData -> {
            String respSessionId = extractNonBlankString(respData, "sessionId");
            Boolean pong = extractBoolean(respData, "pong");
            SessionInfo session = conn.sessionInfo;
            if (respSessionId == null
                    || pong == null
                    || !pong
                    || session == null
                    || !session.sessionId().equals(respSessionId)) {
                invalidateConnection(conn);
                throw new RoomClientException("Malformed PING response from server", true);
            }
            return Boolean.TRUE;
        });
    }

    private void runScheduledPing(ActiveConnection conn) {
        if (!isCurrentAttempt(conn) || !conn.established.get()) {
            conn.cancelAutoPing();
            return;
        }
        try {
            pingOnConnection(conn);
        } catch (RoomClientException ignored) {
            // Connection invalidation is already handled inside executeExchange / pingOnConnection
        }
    }

    private ActiveConnection requireActiveConnection() throws RoomClientException {
        ActiveConnection conn = activeConnection.get();
        if (conn == null || !conn.isEstablishedAndOpen() || activeConnection.get() != conn) {
            throw new RoomClientException("RoomClient is not connected", true);
        }
        return conn;
    }

    private boolean isCurrentAttempt(ActiveConnection conn) {
        return conn != null
                && !conn.invalidated.get()
                && !conn.isExternallyCancelled()
                && !conn.socket.isClosed()
                && activeConnection.get() == conn;
    }

    private <T> T executeExchange(
            ActiveConnection conn,
            String op,
            JsonObject requestData,
            boolean requireEstablished,
            ResponseProcessor<T> processor
    ) throws RoomClientException {
        conn.exchangeLock.lock();
        try {
            if (conn.invalidated.get() || conn.socket.isClosed()) {
                invalidateConnection(conn);
                throw new RoomClientException("Control connection is closed or invalidated", true);
            }
            if (activeConnection.get() != conn) {
                cleanupAttempt(conn);
                throw new RoomClientException("Control connection has been superseded", true);
            }
            if (requireEstablished && !conn.established.get()) {
                invalidateConnection(conn);
                throw new RoomClientException("Control connection has not completed HELLO", true);
            }

            InputStream in = conn.in;
            OutputStream out = conn.out;
            if (in == null || out == null) {
                invalidateConnection(conn);
                throw new RoomClientException("Control connection streams are not initialized", true);
            }

            String requestId = conn.nextRequestId();
            JsonObject requestEnvelope = buildRequestEnvelope(requestId, op, requestData);

            final JsonObject responseEnvelope;
            try {
                conn.socket.setSoTimeout(readTimeoutMs);
                FrameCodec.writeFrame(out, requestEnvelope);
                responseEnvelope = FrameCodec.readFrame(in);
            } catch (IOException e) {
                invalidateConnection(conn);
                throw new RoomClientException(
                        "Control transport failure during " + op + ": " + e.getMessage(),
                        e,
                        true
                );
            }

            if (!isCurrentAttempt(conn)) {
                cleanupAttempt(conn);
                throw new RoomClientException(
                        "Control connection was closed or superseded during " + op,
                        true
                );
            }

            if (responseEnvelope == null) {
                invalidateConnection(conn);
                throw new RoomClientException(
                        "Server closed control connection (EOF) during " + op,
                        true
                );
            }

            if (!hasExactProtocolVersion(responseEnvelope)) {
                invalidateConnection(conn);
                throw new RoomClientException(
                        "Invalid or unsupported protocol version in " + op + " response",
                        true
                );
            }

            String responseId = extractStrictString(responseEnvelope, "id");
            if (!requestId.equals(responseId)) {
                invalidateConnection(conn);
                throw new RoomClientException(
                        "Mismatched response id for " + op + ": expected '" + requestId + "', got '" + responseId + "'",
                        true
                );
            }

            Boolean ok = extractBoolean(responseEnvelope, "ok");
            if (ok == null) {
                invalidateConnection(conn);
                throw new RoomClientException(
                        "Missing boolean 'ok' field in " + op + " response",
                        true
                );
            }

            if (!ok) {
                if (!responseEnvelope.has("error") || !responseEnvelope.get("error").isJsonObject()) {
                    invalidateConnection(conn);
                    throw new RoomClientException(
                            "Malformed error response for " + op + ": missing 'error' object",
                            true
                    );
                }
                JsonObject errorObj = responseEnvelope.getAsJsonObject("error");
                String code = extractNonBlankString(errorObj, "code");
                String message = extractStrictString(errorObj, "message");
                if (code == null) {
                    invalidateConnection(conn);
                    throw new RoomClientException(
                            "Malformed error response for " + op + ": missing error 'code'",
                            true
                    );
                }
                if ("STALE_BINDING".equals(code) || "NOT_REGISTERED".equals(code)) {
                    conn.currentBinding = null;
                }
                throw new RoomClientException(code, message, false);
            }

            if (!responseEnvelope.has("data") || !responseEnvelope.get("data").isJsonObject()) {
                invalidateConnection(conn);
                throw new RoomClientException(
                        "Malformed success response for " + op + ": missing 'data' object",
                        true
                );
            }

            Consumer<String> hook = this.afterExchangeEnvelopeTestHook;
            if (hook != null) {
                hook.accept(op);
            }

            return processor.process(responseEnvelope.getAsJsonObject("data"));
        } finally {
            conn.exchangeLock.unlock();
        }
    }

    private void closeSpecificConnection(ActiveConnection conn, boolean sendQuitIfEstablished) {
        if (conn == null) {
            return;
        }
        conn.cancelAutoPing();
        try {
            if (sendQuitIfEstablished
                    && conn.established.get()
                    && !conn.invalidated.get()
                    && !conn.socket.isClosed()) {
                boolean locked = false;
                try {
                    locked = conn.exchangeLock.tryLock(QUIT_LOCK_WAIT_MS, TimeUnit.MILLISECONDS);
                    if (locked && conn.established.get() && !conn.invalidated.get() && !conn.socket.isClosed()) {
                        InputStream in = conn.in;
                        OutputStream out = conn.out;
                        if (in != null && out != null) {
                            conn.socket.setSoTimeout(Math.min(readTimeoutMs, QUIT_READ_TIMEOUT_MS));
                            String reqId = conn.nextRequestId();
                            JsonObject quitReq = buildRequestEnvelope(reqId, OP_QUIT, new JsonObject());
                            FrameCodec.writeFrame(out, quitReq);
                            FrameCodec.readFrame(in);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                    // Best-effort QUIT handshake before closing socket
                } finally {
                    conn.invalidateAndCloseSocket();
                    if (locked) {
                        conn.exchangeLock.unlock();
                    }
                }
            } else {
                conn.invalidateAndCloseSocket();
            }
        } finally {
            trackedConnections.remove(conn);
            activeConnection.compareAndSet(conn, null);
        }
    }

    private void cleanupAttempt(ActiveConnection conn) {
        if (conn == null) {
            return;
        }
        conn.invalidateAndCloseSocket();
        trackedConnections.remove(conn);
        activeConnection.compareAndSet(conn, null);
    }

    private void invalidateConnection(ActiveConnection conn) {
        cleanupAttempt(conn);
    }

    private static SessionInfo parseHelloSessionInfo(JsonObject data) throws RoomClientException {
        String sessionId = extractNonBlankString(data, "sessionId");
        String displayName = extractNonBlankString(data, "displayName");
        if (sessionId == null || displayName == null) {
            throw new RoomClientException(
                    "Malformed HELLO response: missing non-blank 'sessionId' or 'displayName'",
                    true
            );
        }
        return new SessionInfo(sessionId, displayName);
    }

    private static AdvertisedServicePorts parseHelloPorts(JsonObject data) throws RoomClientException {
        if (!data.has("ports") || !data.get("ports").isJsonObject()) {
            throw new RoomClientException("Malformed HELLO response: missing 'ports' object", true);
        }
        JsonObject portsObj = data.getAsJsonObject("ports");
        Integer discoveryUdp = extractPort(portsObj, "discoveryUdp");
        Integer roomsTcp = extractPort(portsObj, "roomsTcp");
        Integer chatNioTcp = extractPort(portsObj, "chatNioTcp");
        Integer filesTcp = extractPort(portsObj, "filesTcp");
        Integer notesTlsTcp = extractPort(portsObj, "notesTlsTcp");
        if (discoveryUdp == null
                || roomsTcp == null
                || chatNioTcp == null
                || filesTcp == null
                || notesTlsTcp == null) {
            throw new RoomClientException(
                    "Malformed HELLO response: invalid or missing service ports in 'ports'",
                    true
            );
        }
        return new AdvertisedServicePorts(discoveryUdp, roomsTcp, chatNioTcp, filesTcp, notesTlsTcp);
    }

    private static JsonObject buildRequestEnvelope(String requestId, String op, JsonObject data) {
        JsonObject req = new JsonObject();
        req.addProperty("v", ProtocolConstants.PROTOCOL_VERSION);
        req.addProperty("id", requestId);
        req.addProperty("op", op);
        req.add("data", data != null ? data : new JsonObject());
        return req;
    }

    private static boolean hasExactProtocolVersion(JsonObject envelope) {
        if (!envelope.has("v")) {
            return false;
        }
        JsonElement vElem = envelope.get("v");
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

    private static String extractStrictString(JsonObject obj, String key) {
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

    private static String extractNonBlankString(JsonObject obj, String key) {
        String value = extractStrictString(obj, key);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static Boolean extractBoolean(JsonObject obj, String key) {
        if (!obj.has(key)) {
            return null;
        }
        JsonElement elem = obj.get(key);
        if (elem == null || !elem.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive prim = elem.getAsJsonPrimitive();
        return prim.isBoolean() ? prim.getAsBoolean() : null;
    }

    private static Long extractPositiveLong(JsonObject obj, String key) {
        if (!obj.has(key)) {
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
            BigDecimal bd = prim.getAsBigDecimal();
            long val = bd.longValueExact();
            return val >= 1L ? val : null;
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    private static Integer extractPort(JsonObject obj, String key) {
        Long val = extractPositiveLong(obj, key);
        if (val == null || val < 1L || val > 65_535L) {
            return null;
        }
        return val.intValue();
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    @FunctionalInterface
    private interface ResponseProcessor<T> {
        T process(JsonObject responseData) throws RoomClientException;
    }

    private static final class ActiveConnection {
        private final long generation;
        private volatile InetSocketAddress endpoint;
        private final Socket socket;
        private final BooleanSupplier externalCancelCheck;
        private final ReentrantLock exchangeLock = new ReentrantLock(true);
        private final AtomicLong requestCounter = new AtomicLong(1L);
        private final AtomicBoolean invalidated = new AtomicBoolean(false);
        private final AtomicBoolean established = new AtomicBoolean(false);

        private volatile InputStream in;
        private volatile OutputStream out;
        private volatile SessionInfo sessionInfo;
        private volatile AdvertisedServicePorts advertisedPorts;
        private volatile RoomBinding currentBinding;
        private volatile ScheduledExecutorService pingScheduler;
        private volatile ScheduledFuture<?> pingFuture;

        private ActiveConnection(
                long generation,
                InetSocketAddress endpoint,
                Socket socket,
                BooleanSupplier externalCancelCheck
        ) {
            this.generation = generation;
            this.endpoint = endpoint;
            this.socket = socket;
            this.externalCancelCheck = externalCancelCheck;
        }

        private boolean isExternallyCancelled() {
            return externalCancelCheck != null && externalCancelCheck.getAsBoolean();
        }

        private void initStreams(InputStream in, OutputStream out) {
            this.in = in;
            this.out = out;
        }

        private String nextRequestId() {
            return "c" + generation + "-r" + requestCounter.getAndIncrement();
        }

        private boolean isEstablishedAndOpen() {
            return established.get() && !invalidated.get() && !socket.isClosed();
        }

        private void startAutoPing(RoomClient owner, int intervalMs) {
            if (invalidated.get() || !established.get() || !owner.isCurrentAttempt(this)) {
                return;
            }
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread t = new Thread(runnable, "studylink-room-client-ping-" + generation);
                t.setDaemon(true);
                return t;
            });
            this.pingScheduler = scheduler;
            this.pingFuture = scheduler.scheduleWithFixedDelay(
                    () -> owner.runScheduledPing(this),
                    intervalMs,
                    intervalMs,
                    TimeUnit.MILLISECONDS
            );
            if (invalidated.get() || !owner.isCurrentAttempt(this)) {
                cancelAutoPing();
            }
        }

        private void cancelAutoPing() {
            ScheduledFuture<?> future = this.pingFuture;
            if (future != null) {
                future.cancel(false);
            }
            ScheduledExecutorService scheduler = this.pingScheduler;
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
        }

        private void invalidateAndCloseSocket() {
            invalidated.set(true);
            established.set(false);
            currentBinding = null;
            cancelAutoPing();
            closeQuietly(socket);
        }
    }
}
