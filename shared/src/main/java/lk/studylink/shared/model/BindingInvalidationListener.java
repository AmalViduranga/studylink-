package lk.studylink.shared.model;

/**
 * Callback notified after a previously active {@link RoomBinding} has been invalidated by a room
 * switch, room leave, or session termination (SRS Sections 5.1 and 5.5).
 *
 * <p>Implementations (such as Member 5's NIO chat selector) receive the exact invalidated
 * {@link RoomBinding} (including its {@code revision}) after the registry state change has been
 * committed and outside the registry lock. Because the notification carries the specific old
 * {@code revision}, a delayed callback for an older revision cannot match or invalidate a newer
 * subscription whose {@link RoomBinding} has a higher {@code revision}.
 */
@FunctionalInterface
public interface BindingInvalidationListener {

    /**
     * Invoked outside the registry lock after {@code invalidatedBinding} is no longer current.
     *
     * @param invalidatedBinding the exact {@link RoomBinding} that was invalidated (never {@code null})
     */
    void onBindingInvalidated(RoomBinding invalidatedBinding);
}
