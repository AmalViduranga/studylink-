package lk.studylink.shared.model;

import java.util.Objects;
import java.util.Optional;

/**
 * Read-only membership verification, atomic guarded publication, and invalidation listener
 * contract published by Member 1 for server modules {@code M2}, {@code M3}, {@code M4}, and
 * {@code M5} (SRS Sections 3, 5.1, 5.3, 5.5, 6.4, and Q03).
 */
public interface MembershipView {

    /**
     * Short, non-blocking final commit action executed under the membership guard while a
     * {@link RoomBinding} is verified to be current (for example, Member 3's final atomic
     * catalogue publication step after file transfer and SHA-256 hashing have finished).
     *
     * @param <T> non-null return type produced by the action
     * @param <E> checked exception type that the action may throw
     */
    @FunctionalInterface
    interface GuardedAction<T, E extends Exception> {
        T execute() throws E;
    }

    /**
     * Checks whether {@code binding} matches the active session's current room and revision.
     *
     * @param binding binding to check (may be {@code null})
     * @return {@code true} if and only if {@code binding} is non-null, belongs to an active
     *         session, and matches that session's current {@code roomId} and {@code revision}
     */
    boolean isCurrent(RoomBinding binding);

    /**
     * Atomically verifies that {@code binding} is current and executes {@code action} under the
     * same membership-change guard so that a concurrent room switch, room leave, or session close
     * cannot interleave with {@code action}.
     *
     * <p>Callers (such as Member 3) must perform long-running socket streaming and SHA-256
     * hashing <em>outside</em> this guard and pass only the short final publication step as
     * {@code action}.
     *
     * @param binding room binding that must be current (may be {@code null}, which returns empty)
     * @param action  short final action returning a non-null result (must not be {@code null})
     * @param <T>     result type
     * @param <E>     exception type thrown by {@code action}
     * @return {@link Optional} containing the non-null result of {@code action} if {@code binding}
     *         was current, or {@link Optional#empty()} if {@code binding} was stale (in which case
     *         {@code action} is never executed)
     * @throws E if {@code binding} was current and {@code action.execute()} threw {@code E}
     */
    <T, E extends Exception> Optional<T> executeIfCurrent(
            RoomBinding binding,
            GuardedAction<T, E> action
    ) throws E;

    /**
     * Convenience wrapper around {@link #executeIfCurrent(RoomBinding, GuardedAction)} for short
     * void actions.
     *
     * @param binding room binding that must be current
     * @param action  short action to run while {@code binding} remains current (must not be {@code null})
     * @return {@code true} if {@code binding} was current and {@code action.run()} executed;
     *         {@code false} if {@code binding} was stale (and {@code action} was not executed)
     */
    default boolean runIfCurrent(RoomBinding binding, Runnable action) {
        Objects.requireNonNull(action, "action must not be null");
        return executeIfCurrent(binding, () -> {
            action.run();
            return Boolean.TRUE;
        }).isPresent();
    }

    /**
     * Registers a listener notified outside the registry lock whenever an active
     * {@link RoomBinding} is invalidated by a room switch, room leave, or session close.
     *
     * @param listener listener to add (must not be {@code null})
     */
    void addInvalidationListener(BindingInvalidationListener listener);

    /**
     * Unregisters a previously added invalidation listener.
     *
     * @param listener listener to remove (must not be {@code null})
     */
    void removeInvalidationListener(BindingInvalidationListener listener);
}
