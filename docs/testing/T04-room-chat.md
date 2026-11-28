# T04 Live Room Chat Isolation Verification Evidence (`M1 + M5` Run on `feat/rooms-chat-integration` from `dev` Commit `6ec6a5a`)

### Test Evidence: T04 - Live room chat isolation
- **Commit SHA:** Starting base `6ec6a5a42f7baf48a9f5dd2cb220f6b527f59f22` (`6ec6a5a` on `origin/dev` — *Merge pull request #17 from AmalViduranga/test/m1-t02-room-lifecycle*) plus uncommitted working-tree changes on branch `feat/rooms-chat-integration` (GitHub Issue #18: `[M1 + M5] Connect room chat to the packaged apps and verify T04`).
- **Run Classification:** End-to-end `M1 + M5` integration verification run on `feat/rooms-chat-integration` (based on `origin/dev` `6ec6a5a`) over IPv4 loopback (`127.0.0.1:45401` room control + `127.0.0.1:45402` NIO live room chat) using the packaged host server JAR (`server/target/studylink-server.jar`) and three concurrent packaged Swing desktop client windows (`client/target/studylink-client.jar`).
- **Tester / Owner:** `M1` (Rooms & Session Control) + `M5` (Live Room Chat over Java NIO)
- **Date:** `2026-11-28`
- **Environment:** Windows 11 x86_64 (Windows PowerShell), OpenJDK `21.0.13` LTS (`Temurin-21.0.13+9`), Apache Maven `3.9.11` via `.\mvnw.cmd`, IPv4 loopback (`127.0.0.1`, TCP room-control port `45401` and Java NIO chat port `45402`), desktop display active (`GraphicsEnvironment.isHeadless() == false`, FlatLaf Light `3.6.1`).
- **Requirements Covered:** `F06`, `F07`, `F13`, `Q03`
- **Procedure Run:**
  1. Built and verified the full multi-module reactor from the repository root on JDK 21:
     ```powershell
     .\mvnw.cmd clean verify
     ```
  2. Verified headless configuration validation on both packaged JARs:
     ```powershell
     java -jar server/target/studylink-server.jar --config config/studylink.example.properties --check-config
     java -jar client/target/studylink-client.jar --config config/studylink.example.properties --check-config
     ```
  3. Launched the packaged host server (`server/target/studylink-server.jar --config config/studylink.example.properties`) on loopback, confirming that `ServerMain` bound and reported both `M1` (`127.0.0.1:45401`) and `M5` (`127.0.0.1:45402`) backed by a single shared `RoomSessionRegistry`.
  4. Launched three concurrent packaged Swing desktop client windows (`client/target/studylink-client.jar --config config/studylink.example.properties`, hosting `RoomLifecyclePanel` and `ChatPanel` inside `StarterFrame`) for **Student A (`Amal`)**, **Student B (`Kasun`)**, and **Student C (`Nimali`)**:
     - Connected `Amal` and `Kasun` and joined both to `netprog` (`Revision 1`); connected `Nimali` and joined `Nimali` to `revision` (`Revision 1`).
     - **Boundary Validation in `Amal`'s `ChatPanel`:**
       - Attempted to send a blank/whitespace-only message (`"   "`, `3` bytes).
       - Attempted to send a multibyte UTF-8 message exceeding `2,048` UTF-8 bytes (`"\u0D86".repeat(700)`, 700 Sinhala `'ආ'` characters = `700` Java `char`s `< 2,048`, but `2,100` UTF-8 bytes `> 2,048`).
     - **Delivery Case 1 (`Amal` and `Kasun` in `netprog`; `Nimali` in `revision`):**
       - `Amal` sent `[T04-MSG-1]_Amal_in_netprog_before_Kasun_switch` in `netprog`.
     - **Delivery Case 2 (`Kasun` switches from `netprog` to `revision`; `Amal` sends in `netprog`):**
       - `Kasun` switched from `netprog` (`Revision 1`) to `revision` (`Revision 2`), invalidating `Kasun`'s `netprog` binding, clearing `Kasun`'s `ChatPanel` transcript, and subscribing `Kasun` to `revision`.
       - `Amal` sent `[T04-MSG-2]_Amal_in_netprog_after_Kasun_switch` in `netprog`.
     - **Delivery Case 3 (`Kasun` and `Nimali` in `revision`; `Amal` in `netprog`):**
       - `Kasun` sent `[T04-MSG-3]_Kasun_in_revision_after_switch` in `revision`.
- **Expected Result (SRS Table 7 / Section 7.1 `T04`):**
  - **Case 1:** `[T04-MSG-1]_Amal_in_netprog_before_Kasun_switch` is delivered to **Student A (`Amal`)** and **Student B (`Kasun`)** in `netprog` with timestamp, sender display name, and body, and is **not** delivered to **Student C (`Nimali`)** in `revision`.
  - **Case 2:** After **Student B (`Kasun`)** switches to `revision`, `[T04-MSG-2]_Amal_in_netprog_after_Kasun_switch` sent in `netprog` is delivered only to **Student A (`Amal`)**, and is **not** delivered to **Student B (`Kasun`)** or **Student C (`Nimali`)**.
  - **Case 3:** `[T04-MSG-3]_Kasun_in_revision_after_switch` sent by **Student B (`Kasun`)** in `revision` is delivered to **Student B (`Kasun`)** and **Student C (`Nimali`)** in `revision`, and is **not** delivered to **Student A (`Amal`)** in `netprog`.
  - Empty/blank messages and messages exceeding `2,048` UTF-8 bytes (including multibyte UTF-8 strings whose character count is `< 2,048` but UTF-8 byte count is `> 2,048`) are rejected cleanly without crashing or delivering invalid frames.
- **Observed Result & Metrics:**
  - **Build & Automated Test Suite (`.\mvnw.cmd clean verify`):** `BUILD SUCCESS` — **126 tests run, 0 failures, 0 errors, 0 skipped** (`studylink-shared`: 56, `studylink-server`: 45, `studylink-client`: 25).
  - **Headless `--check-config` Validation:** Both `studylink-server.jar` and `studylink-client.jar` exited with code `0` and printed `"status":"OK"` with `roomsTcp: 45401`, `chatNioTcp: 45402`, and `maxChatTextBytes: 2048`.
  - **Live Swing Desktop Window vs. Automated Test Attribution:** All server startup banners, client session IDs, room revisions, `ChatPanel` transcripts, and empty/multibyte boundary rejections in Section 1 were captured directly from the packaged `server/target/studylink-server.jar` and three live packaged `StarterFrame` (`RoomLifecyclePanel` + `ChatPanel`) Swing windows launched from `client/target/studylink-client.jar`. Partial server startup rollback when `port.chat` is occupied, dynamic `HELLO` `chatNioTcp` port handoff, rejected room switch preserving the active chat subscription, and wire-level `BAD_REQUEST` / `TOO_LARGE` / `STALE_BINDING` enforcement in Section 2 were corroborated by the automated JUnit tests (`ServerStarterAndSocketSmokeTest`, `RoomLifecyclePanelTest`, `ChatServerIntegrationTest`, and `ChatClientAndPanelTest`).
- **Outcome:** **PASS**

---

## 1. Observations From Live Packaged Server & Three Packaged Swing Desktop Windows (`127.0.0.1:45401` & `127.0.0.1:45402`)

### 1.1 Packaged Server Startup (`server/target/studylink-server.jar`)

Command:
```powershell
java -jar server/target/studylink-server.jar --config config/studylink.example.properties
```

Observed console output:
```text
========================================================================
 StudyLink Host Server
========================================================================
Configuration source : config\studylink.example.properties
Server ID / Host     : studylink-local @ 127.0.0.1 (interface: loopback)
Predefined rooms     : Network Programming [netprog], Exam Revision [revision], Project Workshop [workshop]
Durable data dir     : var/room-files
TLS server store     : local/host.p12 (TLSv1.3)
------------------------------------------------------------------------
Network Service Listeners:
  M1  rooms      | port 45401 | TCP (java.net.ServerSocket / Socket)    | LISTENING on 127.0.0.1:45401
  M2  discovery  | port 45400 | UDP (java.net.DatagramSocket / DatagramPacket) | Planned (reserved for feature owner)
  M3  files      | port 45403 | TCP (java.util.concurrent pool + java.nio.file) | Planned (reserved for feature owner)
  M4  notes      | port 45404 | TLS 1.3 over TCP (javax.net.ssl / JSSE) | Planned (reserved for feature owner)
  M5  chat       | port 45402 | NIO over TCP (java.nio.channels.Selector + ByteBuffer) | LISTENING on 127.0.0.1:45402
------------------------------------------------------------------------
[studylink-server] TCP room-control service listening on 127.0.0.1:45401
[studylink-server] NIO live room-chat service listening on 127.0.0.1:45402
Press Ctrl+C to stop the server.
```

---

### 1.2 Expected vs. Observed Recipients Across All Three `T04` Delivery Cases (Live Packaged Swing Windows)

| Case | Sender & Active Room | Message Label Sent | Expected Recipients | Observed in Window A (`Amal`) | Observed in Window B (`Kasun`) | Observed in Window C (`Nimali`) | Result |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Case 1** | `Amal` in `netprog` (`rev 1`) | `[T04-MSG-1]_Amal_in_netprog_before_Kasun_switch` | `Amal` (`netprog`), `Kasun` (`netprog`) | **Received:** `[23:16:13] Amal: [T04-MSG-1]_Amal_in_netprog_before_Kasun_switch` | **Received:** `[23:16:13] Amal: [T04-MSG-1]_Amal_in_netprog_before_Kasun_switch` | **Not received** (`revision`, `rev 1`) | **PASS** |
| **Case 2** (after `Kasun` switches to `revision`, `rev 2`) | `Amal` in `netprog` (`rev 1`) | `[T04-MSG-2]_Amal_in_netprog_after_Kasun_switch` | `Amal` (`netprog`) only | **Received:** `[23:16:16] Amal: [T04-MSG-2]_Amal_in_netprog_after_Kasun_switch` | **Not received** (`transcript="<EMPTY>"` in `revision`, `rev 2`) | **Not received** (`revision`, `rev 1`) | **PASS** |
| **Case 3** | `Kasun` in `revision` (`rev 2`) | `[T04-MSG-3]_Kasun_in_revision_after_switch` | `Kasun` (`revision`), `Nimali` (`revision`) | **Not received** (only `[T04-MSG-1]` and `[T04-MSG-2]` present in `netprog`) | **Received:** `[23:16:16] Kasun: [T04-MSG-3]_Kasun_in_revision_after_switch` | **Received:** `[23:16:16] Kasun: [T04-MSG-3]_Kasun_in_revision_after_switch` | **PASS** |

---

### 1.3 Boundary Rejections: Empty Message & Multibyte UTF-8 Message Exceeding `2,048` Bytes (Live Packaged Swing Window A)

Before sending `[T04-MSG-1]`, Window A (`Amal` in `netprog`) exercised both invalid message boundaries in `ChatPanel`:

| Boundary Case | Input Payload | Character Count vs. UTF-8 Byte Count | Observed `ChatPanel` Status & Counter | Delivered to Transcript? | Result |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Empty / whitespace-only message (`chatblank`)** | `"   "` | `3` chars / `3` UTF-8 bytes (blank) | `chatStatus="Enter a non-empty message before sending."`, `byteCounter="3 / 2048 B"`, `sendEnabled=false` | No (`transcript="<EMPTY>"`) | **PASS** |
| **Multibyte UTF-8 oversized message (`chatover2048`)** | `"\u0D86".repeat(700)` (`700` Sinhala `'ආ'` characters) | `700` Java `char`s (`< 2,048`) / **`2,100` UTF-8 bytes (`> 2,048`)** | `chatStatus="Message is 2100 UTF-8 bytes (limit is 2048 bytes)."`, `byteCounter="2100 / 2048 B"`, `sendEnabled=false` | No (`transcript="<EMPTY>"`) | **PASS** |

---

### 1.4 Full Live Window Logs From the Three Packaged Swing Clients (`client/target/studylink-client.jar`)

#### Student A (`Amal` — `s-e9663a5b-9290-44f2-9f58-6f7c95c533fb`, `netprog` `Revision 1`)
```text
[studylink-client] Opened StudyLink desktop window on Swing EDT using FlatLaf Light (config: config\studylink.example.properties).
[studylink-client-ui] STEP=AFTER_CONNECT | connectionState="Connected (127.0.0.1:45401)" | session="Amal [s-e9663a5b-9290-44f2-9f58-6f7c95c533fb]" | activeRoom="None (outside all rooms)" | roomsCount=3 | statusBanner="Connected as 'Amal' (s-e9663a5b-9290-44f2-9f58-6f7c95c533fb). Loaded 3 study rooms." | ioOffEdt=true | uiOnEdt=true
[studylink-client-ui] STEP=AFTER_JOIN(netprog) | connectionState="Connected (127.0.0.1:45401)" | session="Amal [s-e9663a5b-9290-44f2-9f58-6f7c95c533fb]" | activeRoom="Network Programming (netprog) - Revision 1" | roomsCount=3 | statusBanner="Joined room Network Programming (netprog) - Revision 1." | ioOffEdt=true | uiOnEdt=true
[studylink-chat-ui] STEP=AFTER_CHAT_BLANK | roomTitle="Network Programming (netprog)" | chatStatus="Enter a non-empty message before sending." | byteCounter="3 / 2048 B" | sendEnabled=false | transcript="<EMPTY>"
[studylink-chat-ui] STEP=AFTER_CHAT_MULTIBYTE_OVER_2048 | roomTitle="Network Programming (netprog)" | chatStatus="Message is 2100 UTF-8 bytes (limit is 2048 bytes)." | byteCounter="2100 / 2048 B" | sendEnabled=false | transcript="<EMPTY>"
[studylink-chat-ui] STEP=AFTER_CHATSEND([T04-MSG-1]_Amal_in_netprog_before_Kasun_switch) | roomTitle="Network Programming (netprog)" | chatStatus="Connected" | byteCounter="0 / 2048 B" | sendEnabled=false | transcript="[23:16:13] Amal: [T04-MSG-1]_Amal_in_netprog_before_Kasun_switch"
[studylink-chat-ui] STEP=CASE1_AMAL | roomTitle="Network Programming (netprog)" | chatStatus="Connected" | byteCounter="0 / 2048 B" | sendEnabled=false | transcript="[23:16:13] Amal: [T04-MSG-1]_Amal_in_netprog_before_Kasun_switch"
[studylink-chat-ui] STEP=AFTER_CHATSEND([T04-MSG-2]_Amal_in_netprog_after_Kasun_switch) | roomTitle="Network Programming (netprog)" | chatStatus="Connected" | byteCounter="0 / 2048 B" | sendEnabled=false | transcript="[23:16:13] Amal: [T04-MSG-1]_Amal_in_netprog_before_Kasun_switch || [23:16:16] Amal: [T04-MSG-2]_Amal_in_netprog_after_Kasun_switch"
[studylink-chat-ui] STEP=CASE2_AMAL | roomTitle="Network Programming (netprog)" | chatStatus="Connected" | byteCounter="0 / 2048 B" | sendEnabled=false | transcript="[23:16:13] Amal: [T04-MSG-1]_Amal_in_netprog_before_Kasun_switch || [23:16:16] Amal: [T04-MSG-2]_Amal_in_netprog_after_Kasun_switch"
[studylink-chat-ui] STEP=CASE3_AMAL | roomTitle="Network Programming (netprog)" | chatStatus="Connected" | byteCounter="0 / 2048 B" | sendEnabled=false | transcript="[23:16:13] Amal: [T04-MSG-1]_Amal_in_netprog_before_Kasun_switch || [23:16:16] Amal: [T04-MSG-2]_Amal_in_netprog_after_Kasun_switch"
[studylink-client-ui] STEP=AFTER_DISCONNECT | connectionState="Disconnected" | session="None" | activeRoom="None (outside all rooms)" | roomsCount=0 | statusBanner="Disconnected from server. Display name released; ready to reconnect." | ioOffEdt=true | uiOnEdt=true
[studylink-client-ui] STEP=AFTER_CLOSE windowDisposed=true
```

#### Student B (`Kasun` — `s-288bd1b1-ca78-4d75-ad40-18999477c448`, `netprog` `Revision 1` -> switches to `revision` `Revision 2`)
```text
[studylink-client] Opened StudyLink desktop window on Swing EDT using FlatLaf Light (config: config\studylink.example.properties).
[studylink-client-ui] STEP=AFTER_CONNECT | connectionState="Connected (127.0.0.1:45401)" | session="Kasun [s-288bd1b1-ca78-4d75-ad40-18999477c448]" | activeRoom="None (outside all rooms)" | roomsCount=3 | statusBanner="Connected as 'Kasun' (s-288bd1b1-ca78-4d75-ad40-18999477c448). Loaded 3 study rooms." | ioOffEdt=true | uiOnEdt=true
[studylink-client-ui] STEP=AFTER_JOIN(netprog) | connectionState="Connected (127.0.0.1:45401)" | session="Kasun [s-288bd1b1-ca78-4d75-ad40-18999477c448]" | activeRoom="Network Programming (netprog) - Revision 1" | roomsCount=3 | statusBanner="Joined room Network Programming (netprog) - Revision 1." | ioOffEdt=true | uiOnEdt=true
[studylink-chat-ui] STEP=CASE1_KASUN | roomTitle="Network Programming (netprog)" | chatStatus="Connected to Network Programming" | byteCounter="0 / 2048 B" | sendEnabled=false | transcript="[23:16:13] Amal: [T04-MSG-1]_Amal_in_netprog_before_Kasun_switch"
[studylink-client-ui] STEP=AFTER_SWITCH(revision) | connectionState="Connected (127.0.0.1:45401)" | session="Kasun [s-288bd1b1-ca78-4d75-ad40-18999477c448]" | activeRoom="Exam Revision (revision) - Revision 2" | roomsCount=3 | statusBanner="Joined room Exam Revision (revision) - Revision 2." | ioOffEdt=true | uiOnEdt=true
[studylink-chat-ui] STEP=AFTER_SWITCH_CLEAR_KASUN | roomTitle="Exam Revision (revision)" | chatStatus="Connected to Exam Revision" | byteCounter="0 / 2048 B" | sendEnabled=false | transcript="<EMPTY>"
[studylink-chat-ui] STEP=CASE2_KASUN | roomTitle="Exam Revision (revision)" | chatStatus="Connected to Exam Revision" | byteCounter="0 / 2048 B" | sendEnabled=false | transcript="<EMPTY>"
[studylink-chat-ui] STEP=AFTER_CHATSEND([T04-MSG-3]_Kasun_in_revision_after_switch) | roomTitle="Exam Revision (revision)" | chatStatus="Connected" | byteCounter="0 / 2048 B" | sendEnabled=false | transcript="[23:16:16] Kasun: [T04-MSG-3]_Kasun_in_revision_after_switch"
[studylink-chat-ui] STEP=CASE3_KASUN | roomTitle="Exam Revision (revision)" | chatStatus="Connected" | byteCounter="0 / 2048 B" | sendEnabled=false | transcript="[23:16:16] Kasun: [T04-MSG-3]_Kasun_in_revision_after_switch"
[studylink-client-ui] STEP=AFTER_DISCONNECT | connectionState="Disconnected" | session="None" | activeRoom="None (outside all rooms)" | roomsCount=0 | statusBanner="Disconnected from server. Display name released; ready to reconnect." | ioOffEdt=true | uiOnEdt=true
[studylink-client-ui] STEP=AFTER_CLOSE windowDisposed=true
```

#### Student C (`Nimali` — `s-b0cab9dc-0850-46da-a615-e371fed0695b`, `revision` `Revision 1`)
```text
[studylink-client] Opened StudyLink desktop window on Swing EDT using FlatLaf Light (config: config\studylink.example.properties).
[studylink-client-ui] STEP=AFTER_CONNECT | connectionState="Connected (127.0.0.1:45401)" | session="Nimali [s-b0cab9dc-0850-46da-a615-e371fed0695b]" | activeRoom="None (outside all rooms)" | roomsCount=3 | statusBanner="Connected as 'Nimali' (s-b0cab9dc-0850-46da-a615-e371fed0695b). Loaded 3 study rooms." | ioOffEdt=true | uiOnEdt=true
[studylink-client-ui] STEP=AFTER_JOIN(revision) | connectionState="Connected (127.0.0.1:45401)" | session="Nimali [s-b0cab9dc-0850-46da-a615-e371fed0695b]" | activeRoom="Exam Revision (revision) - Revision 1" | roomsCount=3 | statusBanner="Joined room Exam Revision (revision) - Revision 1." | ioOffEdt=true | uiOnEdt=true
[studylink-chat-ui] STEP=CASE1_AND_2_NIMALI | roomTitle="Exam Revision (revision)" | chatStatus="Connected to Exam Revision" | byteCounter="0 / 2048 B" | sendEnabled=false | transcript="[23:16:16] Kasun: [T04-MSG-3]_Kasun_in_revision_after_switch"
[studylink-chat-ui] STEP=CASE3_NIMALI | roomTitle="Exam Revision (revision)" | chatStatus="Connected to Exam Revision" | byteCounter="0 / 2048 B" | sendEnabled=false | transcript="[23:16:16] Kasun: [T04-MSG-3]_Kasun_in_revision_after_switch"
[studylink-client-ui] STEP=AFTER_DISCONNECT | connectionState="Disconnected" | session="None" | activeRoom="None (outside all rooms)" | roomsCount=0 | statusBanner="Disconnected from server. Display name released; ready to reconnect." | ioOffEdt=true | uiOnEdt=true
[studylink-client-ui] STEP=AFTER_CLOSE windowDisposed=true
```

---

## 2. Automated JUnit 5 Verification Summary (`.\mvnw.cmd clean verify`)

All `126` automated tests passed on JDK 21 (`BUILD SUCCESS`), including the new `M1 + M5` integration tests and existing `M5` NIO chat suite:

| Module | Test Class | Tests Run | Key `M1 + M5` / `T04` Assertions Verified |
| :--- | :--- | :---: | :--- |
| `studylink-shared` | `ConfigLoaderTest`, `FrameCodecTest`, `NioFrameAccumulatorTest` | 56 | 4-byte big-endian framing, split/coalesced NIO frame accumulation, oversized header rejection (`> 16,384` B). |
| `studylink-server` | `ServerStarterAndSocketSmokeTest` | 5 | `ServerMain` starts both `RoomControlServer` and `ChatServer` sharing one `RoomSessionRegistry`, reports both listening endpoints, serves `HELLO` + `LIST_ROOMS` + `JOIN` + `SUBSCRIBE` + `CHAT_SEND` + `CHAT_EVENT`, rolls back `roomsPort` and returns exit code `1` when `chatPort` is already occupied, and releases both ports on shutdown for immediate reuse. |
| `studylink-server` | `ChatServerIntegrationTest` | 7 | Server-side `T04` 3-student room isolation, `STALE_BINDING` push after room switch/leave, wire-level `BAD_REQUEST` for blank text and `TOO_LARGE` for `> 2,048` UTF-8 bytes, `T05` split/multi-frame NIO TCP reads, and `T06` slow-client protection (`> 65,536` B outbound queue). |
| `studylink-server` | `RoomControlServerTest`, `RoomSessionRegistryTest` | 33 | Room lifecycle (`HELLO`, `LIST_ROOMS`, `JOIN`, `LEAVE`, `PING`, `QUIT`), monotonic revision increments, and `BindingInvalidationListener` notifications to `ChatServer`. |
| `studylink-client` | `RoomLifecyclePanelTest` | 7 | `RoomLifecyclePanel` + `ChatPanel` integration: `HELLO`-advertised `chatNioTcp` port handoff, automatic `joinRoomChat` on room join/switch, **rejected room switch (`UNKNOWN_ROOM`) leaving the current room chat subscription usable**, `leaveRoomChat()` on room leave/disconnect/reconnect, and non-blocking EDT window disposal. |
| `studylink-client` | `ChatClientAndPanelTest`, `ClientStarterCliTest`, `RoomClientTest` | 18 | `ChatClient` NIO subscription/send/receive, `ChatPanel` 2,048-byte UTF-8 counter and validation, `DesktopMain` CLI checks, and `RoomClient` heartbeat/expiry lifecycle. |
