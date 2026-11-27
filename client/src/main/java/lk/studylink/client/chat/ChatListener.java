package lk.studylink.client.chat;

import lk.studylink.shared.model.ChatEvent;

/**
 * Callback interface notified by {@link ChatClient} when live room chat events arrive or the
 * active chat subscription disconnects or fails (SRS Section 5.5).
 *
 * <p>Callbacks are invoked on the client's NIO I/O thread; Swing UI callers (such as
 * {@link lk.studylink.client.ui.ChatPanel}) must marshal component updates onto the Swing Event
 * Dispatch Thread via {@link javax.swing.SwingUtilities#invokeLater(Runnable)}.
 */
@FunctionalInterface
public interface ChatListener {

    /**
     * Called when a verified {@link ChatEvent} for the current room subscription arrives.
     *
     * @param event received chat event
     */
    void onChatEvent(ChatEvent event);

    /**
     * Called if the active chat connection closes or drops unexpectedly.
     *
     * @param reason human-readable disconnect explanation
     */
    default void onDisconnected(String reason) {
    }

    /**
     * Called if an asynchronous protocol or framing error occurs on the active chat stream.
     *
     * @param errorCode stable error code (e.g. {@code STALE_BINDING}, {@code FRAME_ERROR})
     * @param message   human-readable error description
     */
    default void onError(String errorCode, String message) {
    }
}
