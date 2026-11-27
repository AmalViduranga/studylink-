# Live Room Chat Architecture (Member 5 — Java NIO)

- **Feature:** Live Room Chat (`M5`)
- **Transport & Port:** TCP over Java NIO on port `45402` (`port.chat`)
- **Core JDK NIO APIs:** `ServerSocketChannel`, `SocketChannel`, `Selector`, `SelectionKey`, `ByteBuffer`
- **SRS Requirements Covered:** `F06`, `F07`, `F14`, `Q01`, `Q03`, `Q06`, `Q09` (Verification tests `T04`, `T05`, `T06`, `T12`)

---

## 1. High-Level Architecture

StudyLink's Live Room Chat enables real-time, transient UTF-8 messaging between students connected to the same classroom room (`netprog`, `revision`, or `workshop`) over the local IPv4 LAN without blocking threads per chat connection or using external cloud services.

```text
  Student Swing UI (EDT)             Client NIO Thread                 Server Selector Thread (Port 45402)
  ──────────────────────             ─────────────────                 ─────────────────────────────────────
  ChatPanel                          ChatClient                        ChatServer
    │ joinRoomChat(room, binding)      │                                 │
    ├─────────────────────────────────►│ SocketChannel.connect(45402)    ├─► OP_ACCEPT -> ChatClientState
    │                                  │ write SUBSCRIBE(binding) ──────►├─► OP_READ -> verify MembershipView
    │                                  │◄───────── ACK (ok=true) ────────┤
    │ sendCurrentInput()               │                                 │
    ├─────────────────────────────────►│ write CHAT_SEND(text) ─────────►├─► OP_READ -> verify binding & text
    │                                  │                                 ├─► broadcast CHAT_EVENT to room
    │                                  │◄───────── ACK + CHAT_EVENT ─────┤   (OP_WRITE per recipient queue)
    │ SwingUtilities.invokeLater(...)  │                                 │
    │◄─── onChatEvent(ChatEvent) ──────┤ discard if old revision         │
                                                                         ▲
  M1 RoomSessionRegistry ── BindingInvalidationListener ──► pendingInvalidations + selector.wakeup()
```

---

## 2. Client-to-Server Message Flow

1. **Session & Room Binding (M1):** A student first registers a display name (`HELLO`) and joins a room (`JOIN`) via Member 1's room control protocol, obtaining an authoritative `RoomBinding(sessionId, roomId, revision)`.
2. **Subscribe (`SUBSCRIBE`):** `ChatClient.subscribe(binding, listener)` closes any previous room subscription, opens a `SocketChannel` to port `45402`, and sends `SUBSCRIBE` with `(sessionId, roomId, revision)`.
   - `ChatServer` checks `MembershipView.isCurrent(binding)` and resolves the student's authoritative display name from the active session registry.
   - If valid, `ChatServer` attaches the `RoomBinding` to that channel's `ChatClientState` and registers it under `roomSubscribers.get(binding.roomId())`.
3. **Send Message (`CHAT_SEND`):** `ChatClient.send(text)` validates the text locally and sends `CHAT_SEND` containing `{"text": "..."}`.
   - `ChatServer` verifies the channel has an active subscription, re-checks `MembershipView.isCurrent(activeBinding)`, validates non-empty text (`<= 2,048` UTF-8 bytes), assigns a monotonic `messageId` and server timestamp (`sentAtEpochMs` and `displayTime`), replies with a correlated ACK (`ok: true`), and broadcasts a `CHAT_EVENT` frame to all current subscribers of that room (including the sender).
4. **Receive Event (`CHAT_EVENT`):** `ChatClient`'s NIO reader decodes incoming `CHAT_EVENT` frames, confirms the active subscription revision/room matches, and notifies `ChatListener.onChatEvent(event)`. `ChatPanel` marshals UI updates onto the Swing Event Dispatch Thread via `SwingUtilities.invokeLater(...)`.
5. **Leave / Room Switch:** When a student leaves or joins a different room in M1's `RoomSessionRegistry`, M1 invokes `BindingInvalidationListener.onBindingInvalidated(oldBinding)` outside the registry lock. `ChatServer` enqueues the invalidated binding and calls `selector.wakeup()`. On the selector thread, `ChatServer` unsubscribes any channel holding that exact `(sessionId, roomId, revision)` and drops unstarted queued events for that binding. Simultaneously, `ChatClient.subscribe(newBinding, ...)` closes the old socket and binds UI updates to the new revision.

---

## 3. Java NIO `Selector` Usage

`ChatServer` runs a single dedicated event-loop thread (`studylink-chat-selector-<port>`):

1. `ServerSocketChannel.open()` is configured non-blocking (`configureBlocking(false)`) with `SO_REUSEADDR` and registered with `Selector.open()` for `SelectionKey.OP_ACCEPT`.
2. Each iteration:
   - Drains `pendingInvalidations` so room leaves/switches take effect immediately.
   - Waits in `selector.select()` until I/O events arrive or `selector.wakeup()` is triggered.
   - Processes `selectedKeys()` and removes each processed key from the iterator:
     - `isAcceptable()`: accepts waiting `SocketChannel`s, sets `configureBlocking(false)` and `TCP_NODELAY`, and registers `OP_READ` with a `ChatClientState` attachment.
     - `isReadable()`: reads available bytes into a reusable `ByteBuffer` and feeds the channel's `NioFrameAccumulator`.
     - `isWritable()`: drains queued outbound `ByteBuffer` frames non-blockingly and clears `OP_WRITE` once the channel's outbound queue is empty.
3. Because no thread blocks per client, one slow or stalled student socket never delays `accept()`, reads, or broadcasts for any other student.

---

## 4. Length-Prefixed Framing, Partial Reads, and Partial Writes

### Wire Format
Every frame on port `45402` uses StudyLink's 4-byte unsigned big-endian prefix format:

```text
+-----------------------------------+-----------------------------------+
| 4-byte big-endian uint32 length L | UTF-8 JSON object payload (L B)   |
| (1 <= L <= 16,384)                | {"v":1,"op":"...","data":{...}}   |
+-----------------------------------+-----------------------------------+
```

### Partial & Combined Reads (`NioFrameAccumulator`)
Each channel maintains an independent `NioFrameAccumulator` state machine:
- **Prefix stage:** Collects up to 4 prefix bytes across arbitrary socket read boundaries (even 1 byte per `read()`).
- **Pre-allocation validation:** Once 4 bytes arrive, `FrameCodec.decodeLengthPrefix` checks `1 <= L <= 16,384` **before** allocating `new byte[L]`. Invalid lengths (`<= 0`, `> 16,384`, negative 32-bit integers) throw `FrameFormatException` immediately, and `ChatServer` closes that client without allocating memory.
- **Payload stage:** Accumulates `L` bytes across partial reads.
- **Combined frames:** Loops `while (readBuffer.hasRemaining())` so multiple complete frames arriving in a single TCP segment are all decoded in order while any trailing partial frame stays buffered for the next `OP_READ`.

### Partial Writes (`ChatClientState.outboundQueue`)
- Outbound frames (`FrameCodec.encodeFrameBytes`) are wrapped in `ByteBuffer`s and appended to the recipient's per-channel `ArrayDeque<OutboundFrame>`.
- `SelectionKey.OP_WRITE` is enabled only while `outboundQueue` is non-empty.
- On `OP_WRITE`, `channel.write(buffer)` writes as many bytes as the OS send buffer accepts; if `buffer.hasRemaining()` is still `true`, the remaining slice stays at the head of `outboundQueue` until the next `OP_WRITE` readiness event.

---

## 5. Room Isolation, Validation, and Slow-Client Protection

- **Authoritative Room Isolation:**
  - `CHAT_SEND` never trusts a client-supplied target room. It uses only the channel's verified `RoomBinding.roomId()` captured at `SUBSCRIBE`.
  - Any mismatched `sessionId`, `roomId`, or `revision` supplied inside `CHAT_SEND` `data` is rejected with `STALE_BINDING`.
  - Both `CHAT_SEND` and `broadcastToRoom` verify `MembershipView.isCurrent(...)` before broadcasting or enqueueing events.
  - `BindingInvalidationListener` (`onBindingInvalidated(oldBinding)`) wakes the selector to evict superseded subscriptions by exact `(sessionId, roomId, revision)` equality, preventing delayed callbacks for revision `1` from ever touching revision `3`.
- **Message Validation:**
  - Rejects missing/unsupported protocol versions (`v != 1`), blank/missing request IDs, unknown operations, non-object `data`, empty/whitespace-only text, NUL bytes (`\0`), invalid UTF-8 sequences, malformed JSON, and messages exceeding `2,048` UTF-8 bytes (`TOO_LARGE`).
  - No chat history or messages are persisted to disk; all state lives transiently in RAM.
- **Slow-Client Protection (`65,536` bytes):**
  - `ChatClientState` tracks `pendingOutboundBytes` across all queued frames for a client.
  - If enqueueing a new frame would cause `pendingOutboundBytes > 65,536` (`limits.maxSlowClientQueueBytes`), `ChatServer` immediately closes and removes that slow client without affecting other clients in the room.

---

## 6. Class Responsibilities

### `shared` Module
| Class | Package | Responsibility |
| :--- | :--- | :--- |
| `ChatEvent` | `lk.studylink.shared.model` | Immutable chat broadcast record (`messageId`, `roomId`, `senderSessionId`, `senderName`, `text`, `sentAtEpochMs`, `displayTime`). |
| `NioFrameAccumulator` | `lk.studylink.shared.protocol` | Stateful per-channel `ByteBuffer` frame decoder handling split reads, combined frames, and pre-allocation length checks (`1..16,384` bytes). |
| `ChatProtocol` | `lk.studylink.shared.protocol` | Wire operation constants (`SUBSCRIBE`, `CHAT_SEND`, `CHAT_EVENT`), error codes, JSON envelope builders/parsers, and `2,048`-byte UTF-8 text validation. |

### `server` Module
| Class | Package | Responsibility |
| :--- | :--- | :--- |
| `ChatServer` | `lk.studylink.server.chat` | Single-`Selector` non-blocking TCP server on port `45402`; handles `OP_ACCEPT`/`OP_READ`/`OP_WRITE`, room routing, `MembershipView` validation, invalidation wakeup, and slow-client eviction. |
| `ChatClientState` | `lk.studylink.server.chat` | Per-channel state holding `NioFrameAccumulator`, current `RoomBinding`, outbound `ByteBuffer` queue, `pendingOutboundBytes` counter, and stale-event queue purging. |

### `client` Module
| Class | Package | Responsibility |
| :--- | :--- | :--- |
| `ChatClient` | `lk.studylink.client.chat` | Non-blocking `SocketChannel` + `Selector` client managing one room subscription at a time (`subscribe`, `send`, `unsubscribe`, `close`), request/response correlation, and stale-revision event filtering. |
| `ChatListener` | `lk.studylink.client.chat` | Callback interface for incoming `ChatEvent`s, disconnects, and stream errors. |
| `ChatException` | `lk.studylink.client.chat` | Checked exception carrying stable protocol or transport error codes. |
| `ChatPanel` | `lk.studylink.client.ui` | FlatLaf Swing Chat tab panel with transcript display, Enter-to-send input, live UTF-8 byte counter (`0 / 2048 B`), status indicator, background worker execution, and EDT-safe updates via `SwingUtilities.invokeLater`. |

---

## 7. Building, Running, and Testing

### Build and Run All Automated Tests
Run from the repository root with JDK 21 active:

```powershell
# Windows PowerShell
.\mvnw.cmd clean verify
```

```bash
# macOS / Linux
./mvnw clean verify
```

### Run Chat-Focused Test Suites Directly
```powershell
.\mvnw.cmd test -pl shared -Dtest=NioFrameAccumulatorTest
.\mvnw.cmd test -pl server -Dtest=ChatServerIntegrationTest
.\mvnw.cmd test -pl client -Dtest=ChatClientAndPanelTest
```

### Programmatic / Host Wiring Usage
```java
StudyLinkConfig config = StudyLinkConfig.srsDefaults();
RoomSessionRegistry registry = new RoomSessionRegistry(config);
try (ChatServer chatServer = new ChatServer(config, registry)) {
    chatServer.start(); // Binds TCP NIO Selector on config.chatPort() (default 45402)
    ...
}
```
