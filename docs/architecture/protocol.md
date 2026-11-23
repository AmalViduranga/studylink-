# StudyLink Wire Protocol Specification (Protocol v1)

- **Baseline reference:** [StudyLink Project Report and SRS](../report/StudyLink_Project_Report_and_SRS.pdf) (Sections 3, 5, and 6.3–6.4)
- **Protocol version:** `v = 1`
- **Current implementation status:** Protocol constants (`lk.studylink.shared.protocol.ProtocolConstants`) and the shared 4-byte length-prefixed UTF-8 JSON stream frame codec (`lk.studylink.shared.protocol.FrameCodec` and `FrameFormatException`) are **implemented and tested** in `shared`. Active socket listeners and operation handlers remain planned for their respective feature owners (`M1`–`M5`).

---

## 1. Endpoints and Feature Ownership

One host server process operates all five network services on configurable ports. All feature handlers consult Member 1's in-memory session and room membership registry (`MembershipView.isCurrent(binding)`) so that stale room revisions cannot read or publish data after a student leaves or switches rooms.

| Port (Default) | Config Property | Transport & JDK API | Owner | Planned Operations |
| :--- | :--- | :--- | :--- | :--- |
| `45400` | `port.discovery` | UDP (`java.net.DatagramSocket`, `DatagramPacket`) | **M2** (`discovery`) | `DISCOVER` (broadcast request), `OFFER` (unicast reply), `HEARTBEAT` (unicast every 3 s), `ROSTER` (unicast online member list) |
| `45401` | `port.rooms` | Blocking TCP (`java.net.ServerSocket`, `Socket`) | **M1** (`rooms`) | `HELLO`, `LIST_ROOMS`, `JOIN`, `LEAVE`, `PING`, `QUIT` |
| `45402` | `port.chat` | Non-blocking TCP with Java NIO (`ServerSocketChannel`, `SocketChannel`, `Selector`, `ByteBuffer`) | **M5** (`chat`) | `SUBSCRIBE`, `CHAT_SEND`, and server-pushed `CHAT_EVENT` |
| `45403` | `port.files` | Blocking TCP with bounded `ThreadPoolExecutor` (`4` workers + `8` queued) and `java.nio.file` | **M3** (`files`) | `FILE_LIST`, `FILE_PUT` (metadata -> `READY` -> raw bytes -> commit), `FILE_GET` (request -> metadata header -> raw bytes) |
| `45404` | `port.notes` | TLS 1.3 over TCP (`javax.net.ssl.SSLServerSocket`, `SSLSocket`, `SSLContext`) | **M4** (`notes`) | `PRIVATE_NOTE` and delivery receipt (`noteId`); host reads inbox locally from server console |

---

## 2. Predefined Classroom Rooms

The server exposes three predefined study rooms (SRS Section 1 & Section 6.4):

| Room ID (`roomId`) | Visible Display Name |
| :--- | :--- |
| `netprog` | `Network Programming` |
| `revision` | `Exam Revision` |
| `workshop` | `Project Workshop` |

Each student session holds **at most one active room** at a time. Joining or switching rooms issues a fresh monotonic `revision` and invalidates any previous `RoomBinding(sessionId, roomId, revision)`.

---

## 3. Framing and Transport Rules

### 3.1 Reliable Stream Control Messages (TCP `45401`, NIO TCP `45402`, TCP `45403` metadata, TLS `45404`)

Every stream control message consists of:

1. **Length prefix:** 4-byte unsigned big-endian integer (`int32`, network byte order) declaring the exact byte length $L$ of the UTF-8 JSON payload.
2. **Payload:** Exactly $L$ bytes of UTF-8 encoded JSON object.

```text
+-----------------------------------+-------------------------------------------+
| 4-byte big-endian length L        | UTF-8 JSON payload (L bytes)              |
| (1 <= L <= 16,384)                | {"v":1,"id":"...","op":"...","data":{...}}|
+-----------------------------------+-------------------------------------------+
```

**Stream framing rules (SRS Section 5.1, 5.5, 6.2 Q06, 6.3):**
- Valid frame payload lengths satisfy `1 <= L <= 16,384` bytes (`ProtocolConstants.MAX_FRAME_PAYLOAD_BYTES`).
- Frames with `L <= 0` or `L > 16,384` must be rejected immediately before allocating payload memory or dispatching.
- Readers must loop (blocking TCP) or accumulate per-channel `ByteBuffer` state (NIO) until all `4 + L` bytes have arrived, preserving trailing bytes across reads when multiple frames arrive in a single socket read.
- **Shared implementation (`lk.studylink.shared.protocol.FrameCodec`):**
  - **Blocking streams (`M1`, `M3` metadata, `M4` TLS):** `FrameCodec.writeFrame(OutputStream, JsonObject)` validates that the serialized UTF-8 byte count is in `1..16,384` before writing any byte to the stream. `FrameCodec.readFrame(InputStream)` reads raw bytes without a lookahead buffer (leaving subsequent frames or `M3` raw file bytes untouched), returns `null` on a clean EOF at a frame boundary (`0` prefix bytes available), throws `EOFException` on a truncated prefix or payload, and throws `FrameFormatException` on out-of-range lengths, invalid UTF-8, malformed JSON, trailing non-whitespace tokens, or non-object JSON roots.
  - **Non-blocking NIO reuse (`M5`):** `M5` owns `Selector` registrations, per-channel `ByteBuffer` accumulation, and partial socket writes, and reuses `FrameCodec.encodeFrameBytes(JsonObject)` for outbound write queues plus `FrameCodec.validatePayloadLength(int)` / `FrameCodec.decodeLengthPrefix(byte[], int)` and `FrameCodec.decodePayloadBytes(byte[], int, int)` once a complete `4 + L` frame has accumulated in a channel buffer.

### 3.2 Consistent `JOIN` Exchange Example (SRS Section 6.3)

After connecting on TCP port `45401` and completing `HELLO` (which registers the student's display name and returns `sessionId` plus the server's active service ports), the client requests to join a room:

**Request (`Client -> Server` on TCP `45401`):**
```json
{"v":1,"id":"r17","op":"JOIN","data":{"roomId":"netprog"}}
```

**Success Response (`Server -> Client` on TCP `45401`):**
```json
{"v":1,"id":"r17","ok":true,"data":{"sessionId":"s-demo","roomId":"netprog","revision":2}}
```

**Error Response Envelope (`ok = false`):**
When an operation fails validation, the server echoes the request `"id"` with `"ok": false` and a stable error code:
```json
{"v":1,"id":"r17","ok":false,"error":{"code":"NAME_IN_USE","message":"Display name is already active"}}
```

Standard error codes across features (SRS Sections 5 & 6.3–6.4):
- `NAME_IN_USE` — Display name is already taken by an active session (case-insensitive comparison).
- `INVALID_NAME` — Display name is empty or outside `2–24` letters/digits/spaces (`<= 48` UTF-8 bytes after trim).
- `UNKNOWN_ROOM` — `roomId` is not one of the configured rooms (`netprog`, `revision`, `workshop`).
- `STALE_BINDING` — `(sessionId, roomId, revision)` is no longer the current active binding for that session.
- `TOO_LARGE` — Frame (`> 16,384` bytes), chat/note text (`> 2,048` UTF-8 bytes), or file upload (`> 5,242,880` bytes) exceeds limits.
- `INVALID_FILE` — Disallowed extension (only `.pdf` and `.txt` allowed), path separator/traversal in basename,basename `> 100` UTF-8 bytes, empty file, truncated stream, or SHA-256 digest mismatch.
- `ROOM_FILE_LIMIT` — Room already holds the maximum `25` completed files.
- `BUSY` — File transfer worker pool (`4` active workers + `8` queued tasks) is full.
- `INBOX_FULL` — Host private-note inbox for the room has reached `100` notes.

### 3.3 UDP Datagrams (Port `45400` — M2 Discovery & Presence)

- Each UDP packet carries **one** UTF-8 JSON object without a 4-byte length prefix.
- Datagrams exceeding `1,200` bytes (`ProtocolConstants.MAX_UDP_PAYLOAD_BYTES`, SRS Q06) or carrying an unknown protocol version `"v"` are silently ignored.
- **Discovery:** Client broadcasts `DISCOVER` with `"v": 1` and a random `"nonce"`. Server replies via unicast `OFFER` echoing `"nonce"`, `"serverId"`, and `"controlPort"` (`45401`). Client derives the host IP address from the responding datagram source and deduplicates offers by `serverId` within a `3,000 ms` window (retrying once). If no reply arrives within 3 seconds, manual host/port entry is offered.
- **Presence:** Joined client sends unicast `HEARTBEAT` every `3,000 ms` with its current `RoomBinding` and a monotonic `"seq"`. Server validates the binding, updates a `10,000 ms` lease on a monotonic clock, and replies with `ROSTER` echoing `"seq"` and current active members. Silent members are hidden within `12,000 ms`.

### 3.4 File Transfer Stream Handshake (Port `45403` — M3 Files)

Each file operation opens a dedicated TCP connection to port `45403` served by the bounded worker pool (`4` workers, queue capacity `8`):
- **`FILE_LIST`:** Request carries `RoomBinding`; response returns committed file records (`fileId`, `name`, `size`, `sha256`) for that room.
- **`FILE_PUT` (Upload):**
  1. Client sends a length-prefixed JSON `FILE_PUT` request containing `RoomBinding`, display `name` (`<= 100` UTF-8 bytes, `.pdf` or `.txt`), `size` (`1..5,242,880` bytes), and expected `sha256` hex digest.
  2. Server validates metadata and binding; if accepted, replies with a length-prefixed JSON `READY` frame (or an error such as `BUSY`, `TOO_LARGE`, `INVALID_FILE`, `STALE_BINDING`).
  3. Client streams **raw file bytes** (exact announced `size` bytes, unframed) into a temporary staging bundle under `data.dir` (`var/room-files`).
  4. Server verifies exact byte count and SHA-256 digest, re-verifies that the client's `RoomBinding` is still current under the membership guard, atomically publishes the bundle into the room catalogue on the same filesystem, and sends a final length-prefixed JSON response with the server-assigned `fileId`. On any failure or disconnect, temporary data is deleted.
- **`FILE_GET` (Download):**
  1. Client sends a length-prefixed JSON `FILE_GET` request with `RoomBinding` and `fileId`.
  2. Server verifies the binding and replies with a length-prefixed JSON header carrying `size` and `sha256`, followed immediately by `size` raw bytes.
  3. Client verifies byte count and SHA-256 digest before exposing the downloaded file to the user.

### 3.5 Private Notes over TLS 1.3 (Port `45404` — M4 Notes)

- Uses JSSE `SSLSocket` / `SSLServerSocket` restricted to **`TLSv1.3`** with certificate chain validation against `local/host-trust.p12` and hostname/IP endpoint identification against the server certificate's Subject Alternative Name (SAN).
- Client sends a length-prefixed JSON `PRIVATE_NOTE` request containing `RoomBinding`, idempotency `requestId`, and UTF-8 `text` (`1..2,048` bytes).
- Server verifies the room binding, stores the note in host memory (up to `100` notes per room), and returns a length-prefixed JSON receipt containing `noteId` and `requestId`. Retrying the same `(sessionId, requestId)` returns the existing receipt without duplicating the note.
- Note bodies are **never** written to public room traffic, durable disk files, or normal console logs; the host operator inspects notes locally via the server console (`list` / `read <noteId>`).

---

## 4. Limits and Timeouts Summary (SRS Sections 5 & 6.2–6.4)

| Parameter | SRS Value | Config Property |
| :--- | :--- | :--- |
| Stream frame payload range | `1 .. 16,384` bytes | `limits.maxFrameBytes` |
| Max UDP datagram payload | `1,200` bytes | `limits.maxUdpBytes` |
| Max chat or private note text | `2,048` UTF-8 bytes (2 KiB) | `limits.maxChatTextBytes`, `limits.maxNoteTextBytes` |
| Max PDF/TXT file upload size | `5,242,880` bytes (5 MiB) | `limits.maxFileSizeBytes` |
| File worker pool threads / queue | `4` threads / `8` waiting tasks | `limits.fileWorkerThreads`, `limits.fileQueueCapacity` |
| Slow-client NIO outbound queue cap | `65,536` bytes (64 KiB) | `limits.maxSlowClientQueueBytes` |
| Max completed files per room | `25` files | `limits.maxFilesPerRoom` |
| Max private notes per room in inbox | `100` notes | `limits.maxInboxNotesPerRoom` |
| Display name length | `2 .. 24` chars (`<= 48` UTF-8 bytes, case-insensitive) | Protocol rule (SRS 6.4) |
| Upload basename length | `<= 100` UTF-8 bytes, `.pdf` or `.txt` only | Protocol rule (SRS 6.4) |
| UDP discovery search window | `3,000 ms` (1 retry, then manual fallback) | `timeouts.discoveryMs` |
| Unicast presence heartbeat interval | `3,000 ms` | `timeouts.heartbeatIntervalMs` |
| Presence lease / hide threshold | `10,000 ms` lease / hide within `12,000 ms` | `timeouts.presenceExpiryMs`, `timeouts.presenceHideMs` |
| TCP control ping / inactivity expiry | `5,000 ms` ping / `20,000 ms` (+1 s tick) expiry | `timeouts.controlPingIntervalMs`, `timeouts.controlSessionExpiryMs` |
