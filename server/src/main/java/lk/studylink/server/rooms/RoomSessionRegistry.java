package lk.studylink.server.rooms;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import lk.studylink.shared.config.StudyLinkConfig;
import lk.studylink.shared.model.BindingInvalidationListener;
import lk.studylink.shared.model.MembershipView;
import lk.studylink.shared.model.RoomBinding;
import lk.studylink.shared.model.RoomDefinition;
import lk.studylink.shared.model.SessionInfo;

/**
 * Thread-safe in-memory session and room membership registry for StudyLink (SRS Sections 3, 5.1,
 * F03, F04, F13, Q03, T02, and T12).
 *
 * <h2>Concurrency and Handoff Guarantees</h2>
 * <ul>
 *   <li><strong>Atomic name reservation:</strong> Display name validation, locale-independent
 *       case-insensitive duplicate checking, and reservation occur atomically under the registry lock.</li>
 *   <li><strong>Monotonic per-session revisions:</strong> Each successful {@link #joinRoom(String, String)}
 *       increments the session's revision counter ({@code 1, 2, 3, ...}) and replaces any prior binding.
 *       Switching {@code A -> B -> A} issues revision {@code 3} and never makes revision {@code 1}
 *       current again. Failed joins (such as an unknown room ID) leave the existing binding intact.</li>
 *   <li><strong>Idempotent session cleanup:</strong> {@link #closeSession(String)} removes the session
 *       by its unique {@code sessionId} and releases its display name only if that name is still mapped
 *       to the closing {@code sessionId}. Repeated or late cleanup calls for an old session can never
 *       release a name acquired by a reconnected session.</li>
 *   <li><strong>M3 atomic publication guard:</strong> {@link #executeIfCurrent(RoomBinding, GuardedAction)}
 *       and {@link #runIfCurrent(RoomBinding, Runnable)} verify {@link #isCurrent(RoomBinding)} and run
 *       the caller's short final commit action while holding the registry lock, serializing the commit
 *       against concurrent room switches, leaves, or session closures.</li>
 *   <li><strong>M5 lock-free invalidation notifications:</strong> State mutations are committed under
 *       the lock before the lock is released; registered {@link BindingInvalidationListener}s are then
 *       invoked <em>outside</em> the registry lock with the exact invalidated {@link RoomBinding}.
 *       If a listener throws a {@link RuntimeException}, the failure is logged via
 *       {@link System.Logger} and remaining listeners still execute while the caller receives the
 *       committed result. JVM {@link Error}s are not swallowed.</li>
 * </ul>
 */
public final class RoomSessionRegistry implements MembershipView {

    private static final System.Logger LOGGER = System.getLogger(RoomSessionRegistry.class.getName());

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, RoomDefinition> configuredRoomsById;
    private final List<RoomDefinition> configuredRoomsList;
    private final Map<String, SessionState> sessionsById = new HashMap<>();
    private final Map<String, String> sessionIdByCanonicalName = new HashMap<>();
    private final CopyOnWriteArrayList<BindingInvalidationListener> invalidationListeners =
            new CopyOnWriteArrayList<>();

    /**
     * Creates a registry backed by the room catalogue in {@code config}.
     *
     * @param config validated StudyLink configuration (must not be {@code null})
     */
    public RoomSessionRegistry(StudyLinkConfig config) {
        this(Objects.requireNonNull(config, "config must not be null").rooms());
    }

    /**
     * Creates a registry backed by the supplied configured rooms list.
     *
     * @param configuredRooms non-empty list of configured rooms with unique room IDs
     */
    public RoomSessionRegistry(List<RoomDefinition> configuredRooms) {
        Objects.requireNonNull(configuredRooms, "configuredRooms must not be null");
        if (configuredRooms.isEmpty()) {
            throw new IllegalArgumentException("configuredRooms must not be empty");
        }
        Map<String, RoomDefinition> byId = new LinkedHashMap<>();
        for (RoomDefinition room : configuredRooms) {
            Objects.requireNonNull(room, "room entry must not be null");
            if (byId.putIfAbsent(room.id(), room) != null) {
                throw new IllegalArgumentException("Duplicate room id in catalogue: " + room.id());
            }
        }
        this.configuredRoomsById = Map.copyOf(byId);
        this.configuredRoomsList = List.copyOf(byId.values());
    }

    /**
     * Returns the immutable list of configured rooms in catalogue display order.
     */
    public List<RoomDefinition> listRooms() {
        return configuredRoomsList;
    }

    /**
     * Validates {@code rawDisplayName}, atomically reserves it if available (case-insensitive under
     * {@code Locale.ROOT}), and creates a new session starting outside all rooms.
     *
     * @param rawDisplayName student's requested display name
     * @return newly created {@link SessionInfo} with a fresh random {@code sessionId} and trimmed
     *         {@code displayName}
     * @throws RoomRegistryException with {@link RoomRegistryErrorCode#INVALID_NAME} if the name fails
     *                               validation, or {@link RoomRegistryErrorCode#NAME_IN_USE} if an
     *                               active session already holds a case-equivalent name
     */
    public SessionInfo registerSession(String rawDisplayName) throws RoomRegistryException {
        String trimmedName = DisplayNamePolicy.validateAndTrim(rawDisplayName);
        String canonicalKey = DisplayNamePolicy.canonicalKey(trimmedName);

        lock.lock();
        try {
            if (sessionIdByCanonicalName.containsKey(canonicalKey)) {
                throw new RoomRegistryException(
                        RoomRegistryErrorCode.NAME_IN_USE,
                        "Display name '" + trimmedName + "' is already in use"
                );
            }

            String sessionId = generateUniqueSessionIdLocked();
            SessionInfo sessionInfo = new SessionInfo(sessionId, trimmedName);
            SessionState state = new SessionState(sessionInfo, canonicalKey);
            sessionsById.put(sessionId, state);
            sessionIdByCanonicalName.put(canonicalKey, sessionId);
            return sessionInfo;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Joins {@code sessionId} to {@code roomId}, replacing any previous room binding and issuing a
     * fresh monotonically increasing revision for that session.
     *
     * <p>If {@code roomId} is unknown, throws {@link RoomRegistryException} with
     * {@link RoomRegistryErrorCode#UNKNOWN_ROOM} and leaves the session's existing binding intact.
     *
     * @param sessionId active session ID
     * @param roomId    target configured room ID (such as {@code "netprog"})
     * @return new active {@link RoomBinding}
     * @throws RoomRegistryException with {@link RoomRegistryErrorCode#STALE_BINDING} if {@code sessionId}
     *                               is unknown or closed, or {@link RoomRegistryErrorCode#UNKNOWN_ROOM}
     *                               if {@code roomId} is not in the configured room catalogue
     */
    public RoomBinding joinRoom(String sessionId, String roomId) throws RoomRegistryException {
        String normalizedSessionId = normalizeSessionIdOrThrow(sessionId);
        String normalizedRoomId = (roomId == null) ? "" : roomId.trim();

        final RoomBinding invalidatedBinding;
        final RoomBinding newBinding;

        lock.lock();
        try {
            SessionState session = sessionsById.get(normalizedSessionId);
            if (session == null) {
                throw new RoomRegistryException(
                        RoomRegistryErrorCode.STALE_BINDING,
                        "Unknown or closed session: " + normalizedSessionId
                );
            }
            if (normalizedRoomId.isEmpty() || !configuredRoomsById.containsKey(normalizedRoomId)) {
                throw new RoomRegistryException(
                        RoomRegistryErrorCode.UNKNOWN_ROOM,
                        "Unknown room ID: '" + normalizedRoomId + "'"
                );
            }

            invalidatedBinding = session.currentBinding;
            long nextRevision = ++session.lastIssuedRevision;
            newBinding = new RoomBinding(session.info.sessionId(), normalizedRoomId, nextRevision);
            session.currentBinding = newBinding;
        } finally {
            lock.unlock();
        }

        if (invalidatedBinding != null) {
            notifyInvalidatedOutsideLock(invalidatedBinding);
        }
        return newBinding;
    }

    /**
     * Removes {@code sessionId} from its active room (if any) while keeping the session and its
     * display name reservation active.
     *
     * @param sessionId active session ID
     * @return the invalidated {@link RoomBinding} if the session was in a room, or
     *         {@link Optional#empty()} if the session was already outside all rooms
     * @throws RoomRegistryException with {@link RoomRegistryErrorCode#STALE_BINDING} if {@code sessionId}
     *                               is unknown or closed
     */
    public Optional<RoomBinding> leaveRoom(String sessionId) throws RoomRegistryException {
        String normalizedSessionId = normalizeSessionIdOrThrow(sessionId);
        final RoomBinding invalidatedBinding;

        lock.lock();
        try {
            SessionState session = sessionsById.get(normalizedSessionId);
            if (session == null) {
                throw new RoomRegistryException(
                        RoomRegistryErrorCode.STALE_BINDING,
                        "Unknown or closed session: " + normalizedSessionId
                );
            }
            invalidatedBinding = session.currentBinding;
            session.currentBinding = null;
        } finally {
            lock.unlock();
        }

        if (invalidatedBinding != null) {
            notifyInvalidatedOutsideLock(invalidatedBinding);
        }
        return Optional.ofNullable(invalidatedBinding);
    }

    /**
     * Ends {@code sessionId}, invalidating its active room binding (if any) and releasing its
     * display name reservation.
     *
     * <p>This method is idempotent and safe to call multiple times from disconnect and timeout
     * cleanup paths. A late or duplicate call for an already-closed {@code sessionId} returns
     * {@code false} and never releases a display name that a newer session has acquired.
     *
     * @param sessionId session ID to close (may be {@code null} or blank, which returns {@code false})
     * @return {@code true} if an active session was closed; {@code false} if {@code sessionId} was
     *         already closed or unknown
     */
    public boolean closeSession(String sessionId) {
        if (sessionId == null) {
            return false;
        }
        String normalizedSessionId = sessionId.trim();
        if (normalizedSessionId.isEmpty()) {
            return false;
        }

        final RoomBinding invalidatedBinding;
        lock.lock();
        try {
            SessionState removed = sessionsById.remove(normalizedSessionId);
            if (removed == null) {
                return false;
            }
            invalidatedBinding = removed.currentBinding;
            removed.currentBinding = null;
            sessionIdByCanonicalName.remove(removed.canonicalNameKey, normalizedSessionId);
        } finally {
            lock.unlock();
        }

        if (invalidatedBinding != null) {
            notifyInvalidatedOutsideLock(invalidatedBinding);
        }
        return true;
    }

    /**
     * Returns the {@link SessionInfo} for an active session, or {@link Optional#empty()} if unknown/closed.
     */
    public Optional<SessionInfo> findSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        String normalized = sessionId.trim();
        lock.lock();
        try {
            SessionState session = sessionsById.get(normalized);
            return session != null ? Optional.of(session.info) : Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current active {@link RoomBinding} for {@code sessionId}, or
     * {@link Optional#empty()} if the session is outside all rooms or closed.
     */
    public Optional<RoomBinding> currentBinding(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        String normalized = sessionId.trim();
        lock.lock();
        try {
            SessionState session = sessionsById.get(normalized);
            return (session != null && session.currentBinding != null)
                    ? Optional.of(session.currentBinding)
                    : Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an immutable snapshot of active sessions currently joined to {@code roomId}.
     */
    public List<SessionInfo> listRoomMembers(String roomId) {
        if (roomId == null || roomId.isBlank()) {
            return List.of();
        }
        String normalizedRoomId = roomId.trim();
        lock.lock();
        try {
            List<SessionInfo> members = new ArrayList<>();
            for (SessionState session : sessionsById.values()) {
                RoomBinding binding = session.currentBinding;
                if (binding != null && binding.roomId().equals(normalizedRoomId)) {
                    members.add(session.info);
                }
            }
            return List.copyOf(members);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isCurrent(RoomBinding binding) {
        if (binding == null) {
            return false;
        }
        lock.lock();
        try {
            return isCurrentLocked(binding);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public <T, E extends Exception> Optional<T> executeIfCurrent(
            RoomBinding binding,
            GuardedAction<T, E> action
    ) throws E {
        Objects.requireNonNull(action, "action must not be null");
        if (binding == null) {
            return Optional.empty();
        }
        lock.lock();
        try {
            if (!isCurrentLocked(binding)) {
                return Optional.empty();
            }
            T result = action.execute();
            return Optional.of(Objects.requireNonNull(
                    result,
                    "GuardedAction result must not be null; use runIfCurrent for void actions"
            ));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void addInvalidationListener(BindingInvalidationListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        invalidationListeners.add(listener);
    }

    @Override
    public void removeInvalidationListener(BindingInvalidationListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        invalidationListeners.remove(listener);
    }

    /**
     * Package-private inspection hook used by tests to verify that listener notifications are
     * never dispatched while holding the registry lock.
     */
    boolean isLockHeldByCurrentThread() {
        return lock.isHeldByCurrentThread();
    }

    private boolean isCurrentLocked(RoomBinding binding) {
        SessionState session = sessionsById.get(binding.sessionId());
        return session != null && binding.equals(session.currentBinding);
    }

    private String generateUniqueSessionIdLocked() {
        String candidate;
        do {
            candidate = "s-" + UUID.randomUUID();
        } while (sessionsById.containsKey(candidate));
        return candidate;
    }

    private static String normalizeSessionIdOrThrow(String sessionId) throws RoomRegistryException {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new RoomRegistryException(
                    RoomRegistryErrorCode.STALE_BINDING,
                    "Session ID must not be null or blank"
            );
        }
        return sessionId.trim();
    }

    private void notifyInvalidatedOutsideLock(RoomBinding invalidatedBinding) {
        assert !lock.isHeldByCurrentThread() : "Listeners must never be called under the registry lock";
        for (BindingInvalidationListener listener : invalidationListeners) {
            try {
                listener.onBindingInvalidated(invalidatedBinding);
            } catch (RuntimeException e) {
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        () -> "BindingInvalidationListener failed for " + invalidatedBinding + ": " + e.getMessage(),
                        e
                );
            }
        }
    }

    private static final class SessionState {
        private final SessionInfo info;
        private final String canonicalNameKey;
        private long lastIssuedRevision = 0L;
        private RoomBinding currentBinding = null;

        private SessionState(SessionInfo info, String canonicalNameKey) {
            this.info = info;
            this.canonicalNameKey = canonicalNameKey;
        }
    }
}
