# T02 Room Lifecycle Verification Evidence (`M1` Run on `dev` Commit `a5a27dd`)

### Test Evidence: T02 - Room lifecycle
- **Commit SHA:** `a5a27dda0b63f0f412ac686e2da6a5a73ec2ad7a` (`a5a27dd` on `origin/dev` — *Merge pull request #15 from AmalViduranga/feat/rooms-swing-ui*)
- **Run Classification:** Feature-complete `M1` room-lifecycle verification run on the tested `dev` commit (`a5a27dd`) over IPv4 loopback (`127.0.0.1:45401`) with the packaged server JAR (`server/target/studylink-server.jar`) and live Swing desktop client JAR (`client/target/studylink-client.jar`).
- **Tester / Owner:** `M1` (Rooms & Session Control)
- **Date:** `2026-11-28`
- **Environment:** Windows 11 x86_64 (Windows PowerShell), OpenJDK `21.0.13` LTS (`Temurin-21.0.13+9`), Apache Maven `3.9.11` via `.\mvnw.cmd`, IPv4 loopback (`127.0.0.1`, TCP control port `45401`), desktop display active (`GraphicsEnvironment.isHeadless() == false`, FlatLaf Light `3.6.1`).
- **Requirements Covered:** `F03`, `F04`, `F13`, `Q03`
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
  3. Launched the packaged host server (`server/target/studylink-server.jar`) on `127.0.0.1:45401` and drove multiple live packaged Swing desktop client windows (`client/target/studylink-client.jar`, opening `StarterFrame` and `RoomLifecyclePanel` on the Swing EDT with all socket I/O off the EDT) through the three `T02` verification cases:
     - **Case 1 (Connect `Amal` and `Kasun`; Reject Case-Equivalent Duplicate `"amal"` and Empty Display Name `""`):**
       - Connected Window 1 as `Amal` (joining `netprog`) and Window 2 as `Kasun` (joining `workshop`).
       - While `Amal` and `Kasun` were connected, launched Window 3a attempting to connect as `"amal"` (case-equivalent duplicate) and Window 3b attempting to connect with an empty display name `""`.
     - **Case 2 (Verify Three Predefined Rooms; Join `netprog` -> Switch to `revision` -> Leave; Verify Old Binding Invalidation):**
       - Verified `LIST_ROOMS` populated all three predefined study rooms (`netprog`, `revision`, `workshop`).
       - In `Amal`'s Swing window, joined `netprog` (`Revision 1`), switched to `revision` (`Revision 2`), and left (`None (outside all rooms)` while keeping `Amal`'s session connected).
       - Corroborated server-side `MembershipView.isCurrent(oldBinding) == false` upon room switch and leave via the JUnit integration tests executed on the same `a5a27dd` commit.
     - **Case 3 (Close `Amal`'s Window -> Immediate Reconnect as `Amal` with New `sessionId` While `Kasun` Stays Connected):**
       - Closed `Amal`'s first Swing window directly (`StarterFrame.dispose()`) and immediately connected a new Swing client window (Window 4) as `Amal`.
       - Verified that `Amal` received a new `sessionId` and joined `revision` (`Revision 1`), while `Kasun`'s original session remained connected throughout and switched from `workshop` (`Revision 1`) to `netprog` (`Revision 2`).
- **Expected Result (SRS Table 7 / Section 7.1 `T02`):**
  - Connect two valid student display names (`Amal`, `Kasun`), reject a case-equivalent duplicate (`"amal"` -> `NAME_IN_USE`) and an empty display name (`""` -> `INVALID_NAME`), and verify the three predefined rooms (`netprog`, `revision`, `workshop`).
  - Join, switch, and leave rooms; confirm at most one active room per session, monotonic revision increment (`1 -> 2`), and immediate invalidation of the previous `RoomBinding`.
  - Confirm that closing a client window releases its display name so the name can be immediately reused with a fresh `sessionId` while other connected sessions (`Kasun`) remain unaffected.
- **Observed Result & Metrics:**
  - **Build & Automated Test Suite (`.\mvnw.cmd clean verify`):** `BUILD SUCCESS` — **125 tests run, 0 failures, 0 errors, 0 skipped** (`studylink-shared`: 56, `studylink-server`: 45, `studylink-client`: 24).
  - **Headless `--check-config` Validation:** Both `studylink-server.jar` and `studylink-client.jar` exited with code `0` and printed `"status":"OK"` with rooms `netprog`, `revision`, and `workshop`.
  - **Live Swing Desktop Window vs. Automated Test Attribution:** All UI connection states, session IDs, room counts, active room/revision labels, and error banners in Section 1 below were captured directly from live `StarterFrame` / `RoomLifecyclePanel` Swing windows launched from `client/target/studylink-client.jar` against `server/target/studylink-server.jar`. Server-side `RoomSessionRegistry.isCurrent(oldBinding) == false` checks in Section 2 were corroborated by the in-process loopback integration tests (`RoomLifecyclePanelTest`, `RoomControlServerTest`, `RoomClientTest`, and `RoomSessionRegistryTest`) executed during the same `a5a27dd` verification run.
- **Outcome:** **PASS**

---

## 1. Observations From Live Packaged Server & Swing Desktop Windows (`127.0.0.1:45401`)

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
  M5  chat       | port 45402 | NIO over TCP (java.nio.channels.Selector + ByteBuffer) | Planned (reserved for feature owner)
------------------------------------------------------------------------
[studylink-server] TCP room-control service listening on 127.0.0.1:45401
Press Ctrl+C to stop the server.
```

---

### 1.2 Case 1 — Concurrent `Amal` & `Kasun` Connections, Duplicate `"amal"` Rejection, and Empty Display Name `""` Rejection (Live Swing Windows)

While Window 1 (`Amal`, session `s-8c4df7a6-ab0d-4563-bc65-1191d347b1d7`) and Window 2 (`Kasun`, session `s-87540163-9647-4c7f-ac1b-ba319bceee7a`) were connected to `127.0.0.1:45401`:

1. **Third Swing Window Attempting Case-Equivalent Duplicate Display Name `"amal"`:**
   ```text
   [studylink-client] Opened StudyLink desktop window on Swing EDT using FlatLaf Light (config: config\studylink.example.properties).
   [studylink-client-ui] STEP=AFTER_CONNECT | connectionState="Disconnected" | session="None" | activeRoom="None (outside all rooms)" | roomsCount=0 | statusBanner="Connection failed [NAME_IN_USE]: [NAME_IN_USE] Display name 'amal' is already in use" | ioOffEdt=true | uiOnEdt=true
   [studylink-client-ui] STEP=AFTER_CLOSE windowDisposed=true
   ```
2. **Third Swing Window Attempting Empty Display Name `""`:**
   ```text
   [studylink-client] Opened StudyLink desktop window on Swing EDT using FlatLaf Light (config: config\studylink.example.properties).
   [studylink-client-ui] STEP=AFTER_CONNECT | connectionState="Disconnected" | session="None" | activeRoom="None (outside all rooms)" | roomsCount=0 | statusBanner="Connection failed [INVALID_NAME]: [INVALID_NAME] Display name must not be empty or blank" | ioOffEdt=true | uiOnEdt=true
   [studylink-client-ui] STEP=AFTER_CLOSE windowDisposed=true
   ```

| Client Window | Attempted Display Name | Observed Connection State | Observed Session ID | Observed Error Code & Swing Status Banner |
| :--- | :--- | :--- | :--- | :--- |
| **Window 1 (`Amal`)** | `Amal` | `Connected (127.0.0.1:45401)` | `s-8c4df7a6-ab0d-4563-bc65-1191d347b1d7` | `Connected as 'Amal' (s-8c4df7a6-ab0d-4563-bc65-1191d347b1d7). Loaded 3 study rooms.` |
| **Window 2 (`Kasun`)** | `Kasun` | `Connected (127.0.0.1:45401)` | `s-87540163-9647-4c7f-ac1b-ba319bceee7a` | `Connected as 'Kasun' (s-87540163-9647-4c7f-ac1b-ba319bceee7a). Loaded 3 study rooms.` |
| **Window 3a (Duplicate)** | `amal` | `Disconnected` | `None` | `Connection failed [NAME_IN_USE]: [NAME_IN_USE] Display name 'amal' is already in use` |
| **Window 3b (Empty)** | `""` (empty) | `Disconnected` | `None` | `Connection failed [INVALID_NAME]: [INVALID_NAME] Display name must not be empty or blank` |

---

### 1.3 Case 2 — Predefined Room Catalogue (`netprog`, `revision`, `workshop`), Join -> Switch -> Leave Lifecycle (Live Swing Window 1)

Observed transcript from `Amal`'s initial Swing window (`client/target/studylink-client.jar`):
```text
[studylink-client] Opened StudyLink desktop window on Swing EDT using FlatLaf Light (config: config\studylink.example.properties).
[studylink-client-ui] STEP=AFTER_CONNECT | connectionState="Connected (127.0.0.1:45401)" | session="Amal [s-8c4df7a6-ab0d-4563-bc65-1191d347b1d7]" | activeRoom="None (outside all rooms)" | roomsCount=3 | statusBanner="Connected as 'Amal' (s-8c4df7a6-ab0d-4563-bc65-1191d347b1d7). Loaded 3 study rooms." | ioOffEdt=true | uiOnEdt=true
[studylink-client-ui] STEP=AFTER_JOIN(netprog) | connectionState="Connected (127.0.0.1:45401)" | session="Amal [s-8c4df7a6-ab0d-4563-bc65-1191d347b1d7]" | activeRoom="Network Programming (netprog) - Revision 1" | roomsCount=3 | statusBanner="Joined room Network Programming (netprog) - Revision 1." | ioOffEdt=true | uiOnEdt=true
[studylink-client-ui] STEP=AFTER_SWITCH(revision) | connectionState="Connected (127.0.0.1:45401)" | session="Amal [s-8c4df7a6-ab0d-4563-bc65-1191d347b1d7]" | activeRoom="Exam Revision (revision) - Revision 2" | roomsCount=3 | statusBanner="Joined room Exam Revision (revision) - Revision 2." | ioOffEdt=true | uiOnEdt=true
[studylink-client-ui] STEP=AFTER_LEAVE | connectionState="Connected (127.0.0.1:45401)" | session="Amal [s-8c4df7a6-ab0d-4563-bc65-1191d347b1d7]" | activeRoom="None (outside all rooms)" | roomsCount=3 | statusBanner="Left 'revision'. Session remains connected." | ioOffEdt=true | uiOnEdt=true
[studylink-client-ui] STEP=AFTER_CLOSE windowDisposed=true
```

| Step | Operation | Active Room ID | Active Room Revision | Visible `activeRoom` Label in Swing UI |
| :--- | :--- | :--- | :--- | :--- |
| 1 | `HELLO` + `LIST_ROOMS` (`AFTER_CONNECT`) | None (`Optional.empty()`) | — | `"None (outside all rooms)"` (`3` rooms loaded: `netprog`, `revision`, `workshop`) |
| 2 | `JOIN` `netprog` (`AFTER_JOIN(netprog)`) | `netprog` | `1` | `"Network Programming (netprog) - Revision 1"` |
| 3 | `JOIN` `revision` (`AFTER_SWITCH(revision)`) | `revision` | `2` | `"Exam Revision (revision) - Revision 2"` |
| 4 | `LEAVE` (`AFTER_LEAVE`) | None (`Optional.empty()`) | — | `"None (outside all rooms)"` (session `s-8c4df7a6-ab0d-4563-bc65-1191d347b1d7` still connected) |

---

### 1.4 Case 3 — Window Close Name Release, Immediate Reconnect as `Amal` With Fresh `sessionId`, and `Kasun` Continuity (Live Swing Windows 1, 4, and 2)

1. **`Amal` Closes Initial Window (`STEP=AFTER_CLOSE windowDisposed=true`):**
   - Closing Window 1 triggered `StarterFrame.dispose()` -> `RoomLifecyclePanel.dispose()`, which closed `RoomClient` off the Swing EDT and released `"Amal"` (`s-8c4df7a6-ab0d-4563-bc65-1191d347b1d7`) on the server.
2. **Immediate Reconnect as `Amal` in Window 4:**
   ```text
   [studylink-client] Opened StudyLink desktop window on Swing EDT using FlatLaf Light (config: config\studylink.example.properties).
   [studylink-client-ui] STEP=AFTER_CONNECT | connectionState="Connected (127.0.0.1:45401)" | session="Amal [s-1b1e5b39-13f3-4c33-84f8-95e196db91d8]" | activeRoom="None (outside all rooms)" | roomsCount=3 | statusBanner="Connected as 'Amal' (s-1b1e5b39-13f3-4c33-84f8-95e196db91d8). Loaded 3 study rooms." | ioOffEdt=true | uiOnEdt=true
   [studylink-client-ui] STEP=AFTER_JOIN(revision) | connectionState="Connected (127.0.0.1:45401)" | session="Amal [s-1b1e5b39-13f3-4c33-84f8-95e196db91d8]" | activeRoom="Exam Revision (revision) - Revision 1" | roomsCount=3 | statusBanner="Joined room Exam Revision (revision) - Revision 1." | ioOffEdt=true | uiOnEdt=true
   [studylink-client-ui] STEP=AFTER_DISCONNECT | connectionState="Disconnected" | session="None" | activeRoom="None (outside all rooms)" | roomsCount=0 | statusBanner="Disconnected from server. Display name released; ready to reconnect." | ioOffEdt=true | uiOnEdt=true
   [studylink-client-ui] STEP=AFTER_CLOSE windowDisposed=true
   ```
   - **Verified New Session ID for Reconnected `Amal`:**
     - Initial `Amal` session ID: `s-8c4df7a6-ab0d-4563-bc65-1191d347b1d7`
     - Reconnected `Amal` session ID: `s-1b1e5b39-13f3-4c33-84f8-95e196db91d8` (distinct session ID and fresh room revision counter starting at `Revision 1`).
3. **`Kasun` (Window 2) Remained Connected Throughout `Amal`'s Close and Reconnect:**
   ```text
   [studylink-client] Opened StudyLink desktop window on Swing EDT using FlatLaf Light (config: config\studylink.example.properties).
   [studylink-client-ui] STEP=AFTER_CONNECT | connectionState="Connected (127.0.0.1:45401)" | session="Kasun [s-87540163-9647-4c7f-ac1b-ba319bceee7a]" | activeRoom="None (outside all rooms)" | roomsCount=3 | statusBanner="Connected as 'Kasun' (s-87540163-9647-4c7f-ac1b-ba319bceee7a). Loaded 3 study rooms." | ioOffEdt=true | uiOnEdt=true
   [studylink-client-ui] STEP=AFTER_JOIN(workshop) | connectionState="Connected (127.0.0.1:45401)" | session="Kasun [s-87540163-9647-4c7f-ac1b-ba319bceee7a]" | activeRoom="Project Workshop (workshop) - Revision 1" | roomsCount=3 | statusBanner="Joined room Project Workshop (workshop) - Revision 1." | ioOffEdt=true | uiOnEdt=true
   [studylink-client-ui] STEP=AFTER_SWITCH(netprog) | connectionState="Connected (127.0.0.1:45401)" | session="Kasun [s-87540163-9647-4c7f-ac1b-ba319bceee7a]" | activeRoom="Network Programming (netprog) - Revision 2" | roomsCount=3 | statusBanner="Joined room Network Programming (netprog) - Revision 2." | ioOffEdt=true | uiOnEdt=true
   [studylink-client-ui] STEP=AFTER_DISCONNECT | connectionState="Disconnected" | session="None" | activeRoom="None (outside all rooms)" | roomsCount=0 | statusBanner="Disconnected from server. Display name released; ready to reconnect." | ioOffEdt=true | uiOnEdt=true
   [studylink-client-ui] STEP=AFTER_CLOSE windowDisposed=true
   ```
   - `Kasun` kept session `s-87540163-9647-4c7f-ac1b-ba319bceee7a` across the entire run and switched from `workshop` (`Revision 1`) to `netprog` (`Revision 2`) after `Amal` had closed and reconnected.

---

## 2. Server-Side Membership & Binding Invalidation Corroboration (Automated JUnit Suite on `a5a27dd`)

During the `.\mvnw.cmd clean verify` run on commit `a5a27dd`, the following automated server and client integration tests directly verified server-side `RoomSessionRegistry` (`MembershipView`) state transitions corresponding to Cases 1–3:

1. **`RoomLifecyclePanelTest.connectListRoomsJoinSwitchRejectedSwitchLeaveDisconnectAndReconnectLifecycle` (`client` module):**
   - Verifies through a live `RoomLifecyclePanel` and `RoomControlServer` that:
     - Joining `netprog` produces `binding1 = RoomBinding(session1.sessionId(), "netprog", 1L)` with `registry.isCurrent(binding1) == true`.
     - Switching to `revision` produces `binding2 = RoomBinding(session1.sessionId(), "revision", 2L)` and atomically invalidates the previous binding on the server (`registry.isCurrent(binding1) == false` and `registry.isCurrent(binding2) == true`).
     - Leaving `revision` invalidates `binding2` on the server (`registry.isCurrent(binding2) == false`) while keeping `session1` registered.
     - Disconnecting and reconnecting as `Amal` closes `session1` on the server (`server.awaitSessionClosed(session1.sessionId(), 2, TimeUnit.SECONDS) == true`) and issues a new `session2.sessionId() != session1.sessionId()`.
2. **`RoomLifecyclePanelTest.windowCloseOrPanelDisposeReleasesActiveServerSession` (`client` module):**
   - Verifies on a real `StarterFrame` (`!GraphicsEnvironment.isHeadless()`) that calling `frame.dispose()` while connected and joined to `netprog` closes `RoomClient` off the EDT and removes the session from `RoomSessionRegistry` (`server.awaitSessionClosed(session.sessionId(), 2, TimeUnit.SECONDS) == true`).
3. **`RoomControlServerTest.twoClientsHandleDuplicateNamesIndependentRoomsAndDisconnectIsolation` (`server` module):**
   - Recorded two-client wire transcript from the `a5a27dd` test run:
     ```text
     [Client-1 -> Server] {"v":1,"id":"c1-01","op":"HELLO","data":{"displayName":"Amal"}}
     [Server -> Client-1] {"v":1,"id":"c1-01","ok":true,"data":{"sessionId":"s-fc54fa31-...","displayName":"Amal","ports":{"discoveryUdp":45400,"roomsTcp":...,"chatNioTcp":45402,"filesTcp":45403,"notesTlsTcp":45404}}}
     [Client-2 -> Server] {"v":1,"id":"c2-01","op":"HELLO","data":{"displayName":"amal"}}
     [Server -> Client-2] {"v":1,"id":"c2-01","ok":false,"error":{"code":"NAME_IN_USE","message":"Display name 'amal' is already in use"}}
     [Client-2 -> Server] {"v":1,"id":"c2-02","op":"HELLO","data":{"displayName":"Kasun"}}
     [Server -> Client-2] {"v":1,"id":"c2-02","ok":true,"data":{"sessionId":"s-7428ac7a-...","displayName":"Kasun","ports":{...}}}
     ```
4. **`RoomSessionRegistryTest` (24 unit tests in `server` module):**
   - Verifies single-room membership per session, monotonic revision increments across switches and re-joins after `leaveRoom`, synchronous `executeIfCurrent` / `runIfCurrent` guards rejecting stale revisions, lock-free `BindingInvalidationListener` delivery of the exact invalidated `RoomBinding`, and case-insensitive display-name uniqueness and release upon `closeSession`.

---

## 3. Reactor Test Summary on Commit `a5a27dd`

| Module | Test Class | Tests Run | Failures | Errors | Skipped |
| :--- | :--- | :---: | :---: | :---: | :---: |
| `studylink-shared` | `ConfigLoaderTest` | 8 | 0 | 0 | 0 |
| `studylink-shared` | `FrameCodecTest` | 36 | 0 | 0 | 0 |
| `studylink-shared` | `NioFrameAccumulatorTest` | 12 | 0 | 0 | 0 |
| `studylink-server` | `ChatServerIntegrationTest` | 7 | 0 | 0 | 0 |
| `studylink-server` | `RoomControlServerTest` | 9 | 0 | 0 | 0 |
| `studylink-server` | `RoomSessionRegistryTest` | 24 | 0 | 0 | 0 |
| `studylink-server` | `ServerStarterAndSocketSmokeTest` | 5 | 0 | 0 | 0 |
| `studylink-client` | `ChatClientAndPanelTest` | 3 | 0 | 0 | 0 |
| `studylink-client` | `ClientStarterCliTest` | 3 | 0 | 0 | 0 |
| `studylink-client` | `RoomClientTest` | 12 | 0 | 0 | 0 |
| `studylink-client` | `RoomLifecyclePanelTest` | 6 | 0 | 0 | 0 |
| **Total** | **11 Test Suites** | **125** | **0** | **0** | **0** |
