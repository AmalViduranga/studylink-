package lk.studylink.server.chat;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Objects;
import lk.studylink.shared.model.RoomBinding;
import lk.studylink.shared.protocol.NioFrameAccumulator;

/**
 * Per-connection state attached to each client {@link SelectionKey} on {@link ChatServer}'s NIO
 * {@link java.nio.channels.Selector}. Holds the channel's inbound frame accumulator, active room
 * subscription binding, and pending outbound write queue.
 */
final class ChatClientState {

    private final SocketChannel channel;
    private final SelectionKey selectionKey;
    private final NioFrameAccumulator frameAccumulator;
    private final ArrayDeque<OutboundFrame> outboundQueue = new ArrayDeque<>();

    private RoomBinding subscribedBinding;
    private String displayName;
    private boolean subscriptionInvalidated = false;
    private long pendingOutboundBytes = 0L;

    ChatClientState(SocketChannel channel, SelectionKey selectionKey, int maxFrameBytes) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.selectionKey = Objects.requireNonNull(selectionKey, "selectionKey must not be null");
        this.frameAccumulator = new NioFrameAccumulator(maxFrameBytes);
    }

    SocketChannel channel() {
        return channel;
    }

    SelectionKey selectionKey() {
        return selectionKey;
    }

    NioFrameAccumulator frameAccumulator() {
        return frameAccumulator;
    }

    RoomBinding subscribedBinding() {
        return subscribedBinding;
    }

    String displayName() {
        return displayName;
    }

    void setSubscription(RoomBinding binding, String displayName) {
        this.subscribedBinding = Objects.requireNonNull(binding, "binding must not be null");
        this.displayName = Objects.requireNonNull(displayName, "displayName must not be null");
        this.subscriptionInvalidated = false;
    }

    void clearSubscription() {
        this.subscribedBinding = null;
        this.displayName = null;
    }

    void markSubscriptionInvalidated() {
        this.subscribedBinding = null;
        this.displayName = null;
        this.subscriptionInvalidated = true;
    }

    boolean isSubscriptionInvalidated() {
        return subscriptionInvalidated;
    }

    long pendingOutboundBytes() {
        return pendingOutboundBytes;
    }

    ArrayDeque<OutboundFrame> outboundQueue() {
        return outboundQueue;
    }

    /**
     * Appends an encoded frame to this channel's outbound queue unless doing so would exceed
     * {@code maxSlowClientQueueBytes}.
     *
     * @return {@code true} if queued, {@code false} if slow-client limit would be exceeded
     */
    boolean tryEnqueue(byte[] frameBytes, RoomBinding eventBinding, int maxSlowClientQueueBytes) {
        Objects.requireNonNull(frameBytes, "frameBytes must not be null");
        long nextTotal = pendingOutboundBytes + frameBytes.length;
        if (nextTotal > maxSlowClientQueueBytes) {
            return false;
        }
        outboundQueue.addLast(new OutboundFrame(ByteBuffer.wrap(frameBytes), eventBinding));
        pendingOutboundBytes = nextTotal;
        return true;
    }

    void recordBytesWritten(int written) {
        if (written > 0) {
            pendingOutboundBytes = Math.max(0L, pendingOutboundBytes - written);
        }
    }

    /**
     * Drops any not-yet-started {@code CHAT_EVENT} frames queued for {@code invalidatedBinding}
     * so stale-room events are not sent after a room switch or leave, while never dropping a
     * partially-transmitted frame that would corrupt stream framing.
     */
    void purgeUnstartedEventsFor(RoomBinding invalidatedBinding) {
        Iterator<OutboundFrame> iterator = outboundQueue.iterator();
        while (iterator.hasNext()) {
            OutboundFrame entry = iterator.next();
            if (entry.eventBinding() != null
                    && entry.eventBinding().equals(invalidatedBinding)
                    && entry.buffer().position() == 0) {
                pendingOutboundBytes = Math.max(0L, pendingOutboundBytes - entry.buffer().remaining());
                iterator.remove();
            }
        }
    }

    record OutboundFrame(ByteBuffer buffer, RoomBinding eventBinding) {
    }
}
