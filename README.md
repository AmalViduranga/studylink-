# StudyLink — Classroom LAN Study Collaboration Suite

StudyLink is a Java 21 desktop application and local host server designed for supervised classroom study sessions over an IPv4 local area network (LAN). Students on the classroom network discover or manually enter the session host, choose a display name, and join one subject room at a time to participate in live room chat, exchange study documents (PDF and plain-text files) concurrently, and send confidential questions to the session host over certificate-validated TLS 1.3.

- **Approved Project Report & SRS Baseline:** [docs/report/StudyLink_Project_Report_and_SRS.pdf](docs/report/StudyLink_Project_Report_and_SRS.pdf)
- **Wire Protocol v1 Specification:** [docs/architecture/protocol.md](docs/architecture/protocol.md)
- **Test Plan & Traceability (`T01`–`T12`):** [docs/testing/test-plan.md](docs/testing/test-plan.md)
- **Team Workflow & Review Policy:** [CONTRIBUTING.md](CONTRIBUTING.md)
- **Coding Assistant Guidelines:** [AGENTS.md](AGENTS.md)
- **TLS 1.3 Setup & Certificate Handoff (`M4`):** [tools/README.md](tools/README.md)

---

## 1. Purpose and Classroom Walkthrough

During a classroom tutorial or revision session, students often split their attention across one tool for live questions and another to locate shared lecture sheets. StudyLink unifies these workflows on the classroom LAN without requiring internet access or external cloud services:

### Session Host Walkthrough
1. **Prepare & Start:** Before class, the host prepares local TLS key material (`local/host.p12` and `local/host-trust.p12`), sets `STUDYLINK_KEYSTORE_PASSWORD`, configures the host's LAN IPv4 address in `config/studylink.properties`, and launches `studylink-server.jar`.
2. **Serve All Rooms:** One Java server process manages all three predefined study rooms (**Network Programming**, **Exam Revision**, and **Project Workshop**) across five configurable listener ports (`45400`–`45404`).
3. **Read Confidential Notes Locally:** When a student submits a private question over TLS 1.3, the host receives it in an in-memory local inbox and inspects it from the server console (`list` / `read <noteId>`). Note bodies never appear in public room chat, shared files, or standard console logs.

### Student Walkthrough
1. **Discover or Enter Host Manually:** The student opens `studylink-client.jar` and clicks **Find server** (which broadcasts a UDP discovery probe on port `45400` and displays deduplicated replies within 3 seconds). If the classroom Wi-Fi access point filters UDP broadcasts, the student uses manual fallback to enter the host's IPv4 address/hostname and TCP control port (`45401`).
2. **Choose Display Name & Join a Room:** The student enters an available display name (`2–24` letters, digits, or spaces, up to `48` UTF-8 bytes, case-insensitive uniqueness) and selects one of the three study rooms:
   - `Network Programming` (`netprog`)
   - `Exam Revision` (`revision`)
   - `Project Workshop` (`workshop`)
3. **Collaborate Inside One Active Room:**
   - **Room Membership & Revision Guard (`M1`):** An accepted room join returns a `RoomBinding(sessionId, roomId, revision)`. Each student has at most one active room at a time. Leaving or switching rooms increments the revision and immediately invalidates the old binding across chat, file transfers, presence, and private notes.
   - **Online Presence (`M2`):** The client sends a unicast UDP heartbeat every 3 seconds (`10 s` lease; silent members disappear within `12 s`). Loss of UDP affects only the online indicator while keeping the TCP room session usable.
   - **Live Room Chat (`M5`):** Joined students send UTF-8 messages up to `2 KiB` (`2,048` bytes) through a non-blocking Java NIO `Selector` server on port `45402`. Messages are delivered only to current subscribers of that room.
   - **Concurrent Shared Files (`M3`):** Students list, upload, and download PDF (`.pdf`) and plain-text (`.txt`) files up to `5 MiB` (`5,242,880` bytes) on port `45403`. Transfers run on background worker threads with a progress indicator so the Swing UI and live chat remain responsive. Every upload and download is verified by byte count and `SHA-256` digest before publication.
   - **Private Note to Host (`M4`):** Students submit confidential notes up to `2 KiB` over TLS 1.3 on port `45404` after verifying the host server's certificate chain and Subject Alternative Name (SAN) IP/hostname. The server returns a delivery receipt (`noteId`), supporting idempotent retry by `requestId`.
4. **Leave or Recover:** Leaving returns to the room list; disconnecting or missing the 5-second TCP control ping for 20 seconds releases the display name and invalidates all bindings. Reconnecting starts a fresh session.

---

## 2. Current Capabilities vs. Planned Features

> **Honest Implementation Status:** `M1` TCP room control (`FrameCodec`, `RoomSessionRegistry`, `RoomControlServer`, `RoomClient`, `RoomLifecyclePanel`) and `M5` Java NIO live room chat (`NioFrameAccumulator`, `ChatProtocol`, `ChatServer`, `ChatClient`, `ChatPanel`) are implemented, tested, and wired end-to-end into `ServerMain` and `DesktopMain` / `StarterFrame` (`T02` & `T04`). The `M2`–`M4` network services remain planned for their respective feature branches.

| Capability / Component | Current Status (Today's Build) | Final LAN Demo Target (Release Baseline) |
| :--- | :--- | :--- |
| **Maven Reactor & Wrapper (`shared`, `server`, `client`)** | **Implemented & Verified** — Builds on JDK 21 with Maven Wrapper `3.9.11`; produces shaded `studylink-server.jar` and `studylink-client.jar`. | Same build contract (`./mvnw clean verify`). |
| **Shared Config Loader & CLI Diagnostics (`--help`, `--check-config`)** | **Implemented & Verified** — Validates required properties, port ranges (`1..65535`), distinct service ports, limits, and pre-TLS paths headlessly; prints non-secret JSON summary with Gson. | Used by both applications at startup and in CI. |
| **Host Server Launch (`ServerMain`)** | **Implemented & Verified (`M1 + M5`)** — Loads config, initializes the shared `RoomSessionRegistry`, binds and starts both the blocking TCP `RoomControlServer` (`server.host:port.rooms`, default `127.0.0.1:45401`) and the non-blocking Java NIO `ChatServer` (`server.host:port.chat`, default `127.0.0.1:45402`), reports both listening endpoints, rolls back cleanly on partial bind failure (exit code `1`), and releases both ports on shutdown. | Binds and runs all 5 live services concurrently in one host process. |
| **Desktop Client Launch (`DesktopMain` + `StarterFrame` + `RoomLifecyclePanel` + `ChatPanel`)** | **Implemented & Verified (`M1 + M5`)** — Applies FlatLaf Light `3.6.1` on the Swing EDT and opens the unified room-control and live room-chat workspace (`RoomLifecyclePanel` + `ChatPanel` inside `StarterFrame`), automatically subscribing `ChatPanel` to the active control host and `HELLO`-advertised `chatNioTcp` port on room join/switch and cleaning up on leave, disconnect, or window close. | Full UDP discovery panel (`M2`), room switcher (`M1`), live chat tab (`M5`), shared files tab (`M3`), and private note dialog (`M4`). |
| **Unit & Loopback Socket Integration Tests** | **Implemented & Verified** — Headless JUnit Jupiter suite covering config validation, CLI modes, `ServerMain` dual-listener launcher lifecycle & partial-startup rollback, `FrameCodec`, `RoomSessionRegistry`, `RoomControlServer`, `RoomClient` (including 5 s ping / 20 s inactivity expiry), `RoomLifecyclePanel` + `ChatPanel` Swing EDT integration, and `M5` NIO chat (`NioFrameAccumulator`, `ChatServer`, `ChatClient`, `ChatPanel`). | Extended with `M2`–`M4` unit/integration tests and SRS acceptance tests `T01`–`T12` ([docs/testing/test-plan.md](docs/testing/test-plan.md)). |
| **M1–M5 Network Features (`rooms`, `discovery`, `files`, `notes`, `chat`)** | **In Progress (`M1` & `M5` Integrated)** — `M1` room control and `M5` Java NIO live room chat run end-to-end from `ServerMain` and `DesktopMain` (`T02` & `T04` verified); `M2` (`discovery`), `M3` (`files`), and `M4` (`notes`) remain planned for their feature owners. | Fully integrated across two or more classroom laptops on IPv4 LAN. |

---

## 3. Architecture, Module Map, and Five Ownership Areas

StudyLink uses a single Maven multi-module reactor (`groupId`: `lk.studylink`) with strict separation between `client` and `server`:

```text
studylink/
├── pom.xml                                  # Parent POM (Java 21, UTF-8, dependency & plugin versions)
├── mvnw, mvnw.cmd, .mvn/wrapper/            # Apache Maven Wrapper 3.3.4 pinned to Maven 3.9.11
├── shared/                                  # Common config loader, models & protocol constants
│   ├── pom.xml
│   ├── src/main/java/lk/studylink/shared/{config,model,protocol}
│   └── src/test/java/lk/studylink/shared
├── server/                                  # Host server executable -> server/target/studylink-server.jar
│   ├── pom.xml
│   ├── src/main/java/lk/studylink/server/ServerMain.java
│   ├── src/main/java/lk/studylink/server/{rooms,discovery,files,notes,chat}
│   └── src/test/java/lk/studylink/server
├── client/                                  # Swing desktop executable -> client/target/studylink-client.jar
│   ├── pom.xml
│   ├── src/main/java/lk/studylink/client/DesktopMain.java
│   ├── src/main/java/lk/studylink/client/{ui,rooms,discovery,files,notes,chat}
│   ├── src/main/resources/studylink-ui.properties
│   └── src/test/java/lk/studylink/client
├── config/studylink.example.properties      # Committed loopback baseline configuration
├── tools/README.md                          # Reserved for M4 local TLS 1.3 PKCS12 setup & handoff
├── docs/
│   ├── architecture/protocol.md             # Protocol v1 endpoints, framing & JOIN example
│   ├── testing/test-plan.md                 # Bootstrap tests vs. SRS T01–T12 acceptance plan
│   └── report/StudyLink_Project_Report_and_SRS.pdf
└── .github/
    ├── pull_request_template.md
    └── workflows/verify.yml                 # JDK 21 CI build & headless JAR checks on PRs to dev/main
```

- **Module dependency rule:** Both `server` and `client` depend on `shared`. Neither application depends on the other.

### Five Feature Ownership Areas and Configurable Ports

| Owner | Feature Package | Default Port | Transport & JDK API | Feature Scope & Key Rules | Main Reviewer |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **M1** | `rooms` | `TCP 45401` (`port.rooms`) | `java.net.ServerSocket`, `Socket` | Display name validation (`2–24` chars, case-insensitive), `HELLO`, `LIST_ROOMS`, `JOIN`, `LEAVE`, `PING` (`5 s` / `20 s` expiry), `QUIT`, 4-byte big-endian UTF-8 JSON framing codec, and `MembershipView.isCurrent(binding)`. | **M5** |
| **M2** | `discovery` | `UDP 45400` (`port.discovery`) | `java.net.DatagramSocket`, `DatagramPacket` | Broadcast `DISCOVER` and unicast `OFFER` (`3 s` search window + manual host/port fallback); unicast `HEARTBEAT` (`3 s`) and `ROSTER` (`10 s` lease, hide silent member within `12 s`). | **M1** |
| **M3** | `files` | `TCP 45403` (`port.files`) | `java.net` + `ThreadPoolExecutor` (`4` workers, `8` queue) + `java.nio.file` | Room file catalogue (`<= 25` files/room), concurrent `.pdf`/`.txt` uploads and downloads (`<= 5 MiB`), temporary staging, `SHA-256` verification, binding recheck before atomic commit, and `BUSY` backpressure. | **M5** |
| **M4** | `notes` | `TLS 45404` (`port.notes`) | `javax.net.ssl` (`SSLContext`, `SSLServerSocket`, `SSLSocket`, `TLSv1.3`) | Private notes (`<= 2 KiB` UTF-8) to host over TLS 1.3 with truststore + SAN hostname/IP verification, idempotent receipt (`requestId`), and local host console inbox (`<= 100` notes/room). | **M2** |
| **M5** | `chat` | `NIO 45402` (`port.chat`) | `java.nio.channels` (`ServerSocketChannel`, `SocketChannel`, `Selector`) + `ByteBuffer` | Non-blocking single-selector live room chat (`<= 2 KiB` UTF-8), split/multi-frame accumulation (`1..16,384` B), room isolation by revision, and slow-client disconnect at `64 KiB` pending output. | **M3** |

---

## 4. Technology Stack and Version Rationale

| Component | Selected Version | Rationale in StudyLink (SRS Section 3.1) |
| :--- | :--- | :--- |
| **Java Development Kit** | **JDK 21** (`maven.compiler.release=21`) | Long-Term Support (LTS) release providing modern language features (records, pattern matching, text blocks) and direct access to standard `java.net`, `java.nio.channels`, `java.util.concurrent`, `java.nio.file`, and JSSE `TLSv1.3` APIs taught in the module. |
| **Build System** | **Apache Maven 3.9.11** (via Maven Wrapper `3.3.4`) | Multi-module reactor build (`shared`, `server`, `client`) with reproducible dependency versions and zero pre-installed Maven requirement for teammates or evaluators. |
| **Desktop UI** | **Java Swing + FlatLaf Light `3.6.1`** | Ships with the JDK desktop runtime and applies a clean, cross-platform light look-and-feel without external browser or Electron overhead. |
| **JSON Serialization** | **Gson `2.13.2`** | Lightweight, zero-transitive-dependency JSON library in `shared` for UTF-8 control frames, UDP datagrams, file metadata records, and `--check-config` diagnostics. |
| **Automated Testing** | **JUnit Jupiter `5.13.4`** (`test` scope) | Standard unit and real-socket testing framework run via `maven-surefire-plugin` (`3.5.2`). |
| **Packaging** | **Maven Shade Plugin `3.6.0`** | Produces self-contained executable fat JARs (`server/target/studylink-server.jar` and `client/target/studylink-client.jar`) with embedded runtime dependencies and `Main-Class` manifests. |

---

## 5. Data Lifetime and Protocol Limits (SRS Section 6.2–6.4)

### Data Lifetime Across Server Restarts
- **Durable on disk (`var/room-files`):** Only **completed, SHA-256 verified room files** and their committed metadata survive a server restart. On startup, the file service rebuilds the room catalogue from committed bundles and discards any incomplete staging files.
- **Transient in RAM (cleared on server restart):** Active sessions, display name reservations, room bindings/revisions, UDP presence leases, live chat messages (no chat history replay), and private notes in the host inbox live strictly in memory.

### Summary of Enforced Limits
- **Predefined rooms:** `netprog` (`Network Programming`), `revision` (`Exam Revision`), `workshop` (`Project Workshop`)
- **Stream frame length:** 4-byte unsigned big-endian prefix, `1 .. 16,384` bytes payload
- **UDP datagram size:** 1 JSON object per packet, max `1,200` bytes
- **Chat message / private note size:** `1 .. 2,048` UTF-8 bytes (`2 KiB`)
- **Shared file upload size:** `1 .. 5,242,880` bytes (`5 MiB`), `.pdf` or `.txt` only, basename `<= 100` UTF-8 bytes, max `25` completed files per room
- **File transfer concurrency:** Bounded pool of `4` worker threads + `8` queued tasks (returns `BUSY` when full)
- **Slow chat client threshold:** Disconnect NIO chat peer when unsent outbound queue exceeds `65,536` bytes (`64 KiB`)
- **Host private-note inbox:** Up to `100` notes per room in host RAM (returns `INBOX_FULL` when full)
- **Target group load benchmark (`Q01` / `T06`):** `10` concurrent clients across two rooms, `100` messages over `60` seconds, 95th percentile send-to-echo latency `<= 1.0` second.

---

## 6. Prerequisites and Configuration

### Prerequisites
- **JDK 21** installed and selected on `PATH` (and `JAVA_HOME` pointing to JDK 21).
  Verify with:
  ```text
  java -version
  javac -version
  ```
- **Maven:** No global Maven installation is required; always use `.\mvnw.cmd` on Windows PowerShell or `./mvnw` on macOS/Linux.

### Configuration (`config/studylink.example.properties`)
The committed baseline configuration uses loopback (`127.0.0.1`) and standard relative paths:
- `server.host=127.0.0.1`
- `port.discovery=45400`
- `port.rooms=45401`
- `port.chat=45402`
- `port.files=45403`
- `port.notes=45404`
- `data.dir=var/room-files`
- `tls.protocol=TLSv1.3`
- `tls.serverStore=local/host.p12`
- `tls.clientTrustStore=local/host-trust.p12`

**Important Security Rules:**
- Copy `config/studylink.example.properties` to `config/studylink.properties` for local overrides (ignored by Git).
- Never commit real LAN IP addresses, `local/*.p12` stores, private keys, or passwords.
- When TLS private notes (`M4`) are active, provide keystore passwords via environment variables:
  - Host laptop: `STUDYLINK_KEYSTORE_PASSWORD`
  - Client laptops: `STUDYLINK_TRUSTSTORE_PASSWORD`
- Starter `--check-config` validation works **before** `local/host.p12` or `local/host-trust.p12` exist on disk.

---

## 7. Quickstart Commands (Build, Test, Verify, and Run)

Run all commands from the **repository root (`studylink/`)**.

### Windows PowerShell

```powershell
# 1. Check Java 21 and Maven Wrapper 3.9.11
java -version
javac -version
.\mvnw.cmd -v

# 2. Clean build, run headless JUnit tests, and package executable JARs
.\mvnw.cmd clean verify

# 3. Create your local untracked configuration file from the example
Copy-Item config/studylink.example.properties config/studylink.properties

# 4. Run headless CLI help and configuration validation on both packaged JARs
java -jar server/target/studylink-server.jar --help
java -jar client/target/studylink-client.jar --help
java -jar server/target/studylink-server.jar --config config/studylink.example.properties --check-config
java -jar client/target/studylink-client.jar --config config/studylink.example.properties --check-config

# 5. Launch the host server (binds TCP room-control on server.host:port.rooms, default 127.0.0.1:45401)
#    Stop interactively with Ctrl+C, or from another terminal with: Stop-Process -Id <PID>
java -jar server/target/studylink-server.jar --config config/studylink.properties

# 6. Launch the Swing desktop client
java -jar client/target/studylink-client.jar --config config/studylink.properties
```

### macOS and Linux

```bash
# 1. Check Java 21 and Maven Wrapper 3.9.11
java -version
javac -version
chmod +x ./mvnw
./mvnw -v

# 2. Clean build, run headless JUnit tests, and package executable JARs
./mvnw clean verify

# 3. Create your local untracked configuration file from the example
cp config/studylink.example.properties config/studylink.properties

# 4. Run headless CLI help and configuration validation on both packaged JARs
java -jar server/target/studylink-server.jar --help
java -jar client/target/studylink-client.jar --help
java -jar server/target/studylink-server.jar --config config/studylink.example.properties --check-config
java -jar client/target/studylink-client.jar --config config/studylink.example.properties --check-config

# 5. Launch the host server (binds TCP room-control on server.host:port.rooms, default 127.0.0.1:45401)
#    Stop interactively with Ctrl+C, or from another terminal with: kill -TERM <PID>
java -jar server/target/studylink-server.jar --config config/studylink.properties

# 6. Launch the Swing desktop client
java -jar client/target/studylink-client.jar --config config/studylink.properties
```

### Expected Output (Today's Build vs. Final Release)

- **`--check-config` (Valid Config):** Exits with code `0` and prints a single-line Gson JSON summary of effective non-secret settings:
  ```json
  {"status":"OK","component":"studylink-server","configSource":"config\\studylink.example.properties","protocolVersion":1,"serverId":"studylink-local","serverHost":"127.0.0.1","networkInterface":"loopback","ports":{"discoveryUdp":45400,"roomsTcp":45401,"chatNioTcp":45402,"filesTcp":45403,"notesTlsTcp":45404},"dataDir":"var/room-files","tls":{"protocol":"TLSv1.3","serverStore":"local/host.p12","clientTrustStore":"local/host-trust.p12"},"rooms":[{"id":"netprog","name":"Network Programming"},{"id":"revision","name":"Exam Revision"},{"id":"workshop","name":"Project Workshop"}],"limits":{"maxFrameBytes":16384,"maxUdpBytes":1200,"maxChatTextBytes":2048,"maxNoteTextBytes":2048,"maxFileSizeBytes":5242880,"fileWorkerThreads":4,"fileQueueCapacity":8,"maxSlowClientQueueBytes":65536}}
  ```
- **`--check-config` (Missing or Invalid Config):** Exits with a nonzero code (`1` or `2`) and prints a descriptive diagnostic to `stderr` (for example, `Configuration file not found` or `Port out of range for 'port.rooms'`).
- **Today's Normal Server Launch (`ServerMain`):** Validates configuration, initializes the shared `RoomSessionRegistry`, binds and starts both `RoomControlServer` (`server.host:port.rooms`, default `127.0.0.1:45401`) and `ChatServer` (`server.host:port.chat`, default `127.0.0.1:45402`), prints `[studylink-server] TCP room-control service listening on 127.0.0.1:45401` and `[studylink-server] NIO live room-chat service listening on 127.0.0.1:45402`, and stays running until stopped with `Ctrl+C` (or `Stop-Process -Id <PID>` / `kill -TERM <PID>`). If either configured port is occupied, it rolls back any already-bound listener, prints a descriptive bind error to `stderr`, and exits with code `1`.
- **Today's Normal Client Launch (`DesktopMain`):** Initializes FlatLaf Light `3.6.1` on the Swing Event Dispatch Thread and opens the `StudyLink Desktop Client` window (`StarterFrame`) with the unified `RoomLifecyclePanel` + `ChatPanel` workspace tab (`Room Session & Live Chat (M1 + M5)`) alongside the configuration and endpoint overview tab.
- **Final Classroom Release Launch:** Once `M1`–`M5` merge their feature branches, `studylink-server.jar` remains running with all five listeners active (`45400`–`45404`) and the local host inbox console ready, while `studylink-client.jar` opens the interactive connection screen and room workspace.

---

## 8. Running Across Two (or Three) Machines on a Classroom LAN

Once the networking features (`M1`–`M5`) are integrated, use the following multi-laptop procedure for integration testing and the live classroom demonstration (SRS Section 4.1 & Section 8.1):

1. **Connect laptops to the same IPv4 LAN / Wi-Fi** and determine the host laptop's LAN IPv4 address (e.g., `192.168.1.50`).
2. **Prepare TLS Identity & Truststore (`M4`):**
   - On the host laptop, generate `local/host.p12` (including `SAN=IP:192.168.1.50,IP:127.0.0.1,DNS:localhost`) and export the public certificate to `local/host-trust.p12` following [tools/README.md](tools/README.md).
   - Copy **only** `local/host-trust.p12` to each student client laptop and verify its SHA-256 fingerprint.
   - Set environment variables:
     - Host PowerShell: `$env:STUDYLINK_KEYSTORE_PASSWORD = "<host-keystore-password>"`
     - Client PowerShell: `$env:STUDYLINK_TRUSTSTORE_PASSWORD = "<client-truststore-password>"`
3. **Configure `config/studylink.properties`:**
   - Set `server.host=192.168.1.50` (and the active `network.interface`) on the host and clients.
   - Ensure firewall rules on the host allow inbound **UDP `45400`** and **TCP `45401`–`45404`**.
4. **Start the Host Server (Laptop 1):**
   ```powershell
   java -jar server/target/studylink-server.jar --config config/studylink.properties
   ```
5. **Start Student Clients (Laptop 2 & Laptop 3):**
   ```powershell
   java -jar client/target/studylink-client.jar --config config/studylink.properties
   ```
   - Use **Find server** (UDP `45400`) or enter `192.168.1.50:45401` manually if the Wi-Fi access point blocks broadcast packets.
   - Join two clients into `Network Programming` and a third client into `Exam Revision` to demonstrate room isolation (`T04`), file transfer (`T07`), presence expiry (`T03`), and TLS private notes (`T09`).
6. **Reset Synthetic Demo Data Before Evaluation:**
   - Stop the server process.
   - Clear `var/room-files/` (or replace it with the clean synthetic sample PDF/TXT files) to reset durable room files; restarting the server automatically clears all transient sessions, presence, chat, and private notes.

---

## 9. Troubleshooting

| Symptom | Likely Cause | Resolution |
| :--- | :--- | :--- |
| `UnsupportedClassVersionError` or compiler release error | Active Java version is older than JDK 21 | Run `java -version` and `javac -version`; set `JAVA_HOME` to a JDK 21 installation and ensure `$JAVA_HOME/bin` is first on `PATH`. |
| `Configuration file not found` or nonzero exit on `--check-config` | Path passed to `--config` does not exist or `config/studylink.properties` was not copied yet | Copy `config/studylink.example.properties` to `config/studylink.properties`, or pass `--config config/studylink.example.properties`. |
| `Duplicate port` or `Port out of range` error | Two services share a port or a port is outside `1..65535` | Assign distinct ports in `1..65535` (defaults: `45400`, `45401`, `45402`, `45403`, `45404`). |
| UDP discovery finds no server across two laptops on Wi-Fi | Classroom Wi-Fi filters UDP broadcast packets or Windows Defender Firewall is set to Public profile | Wait 3 seconds (or click manual entry immediately) and enter the host's LAN IPv4 address and TCP control port `45401`. Allow Java on private/domain networks or open UDP `45400` and TCP `45401`–`45404`. |
| TLS private note fails handshake (`M4`) | Client truststore does not match host keystore, or host IP changed after certificate generation | Regenerate `local/host.p12` with the current host LAN IP in the Subject Alternative Name (SAN) and redistribute `local/host-trust.p12` ([tools/README.md](tools/README.md)). |

---

## 10. Pull Request Workflow, Demonstration, and Moodle Submission

### Pull Request and Review Policy
See [CONTRIBUTING.md](CONTRIBUTING.md) for full details:
- Branch feature work from **`dev`** (`feat/rooms-tcp`, `feat/discovery-udp`, `feat/file-transfers`, `feat/private-notes-tls`, `feat/chat-nio`).
- **Feature PRs (`feat/* -> dev`):** Require **1 other teammate's approval** and a passing GitHub Actions `verify` check ([.github/workflows/verify.yml](.github/workflows/verify.yml)).
- **Release PRs (`dev -> main`):** Require **2 distinct other teammates' approvals** and a passing `verify` check.
- Use [.github/pull_request_template.md](.github/pull_request_template.md) on every pull request.

### Submission & Live Evaluation Schedule (SRS Sections 1, 8, and 8.1)
- **Submission Deadline:** **26 November 2026**
- **Submission Format:** **One single ZIP archive via Moodle** submitted by one designated team member.
  - The ZIP must build cleanly from its extracted root using `./mvnw clean verify` on JDK 21.
  - Include: complete source tree, parent and module `pom.xml` files, Maven Wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/`), `config/studylink.example.properties`, TLS setup script/instructions in `tools/`, `README.md`, test sources/evidence, and the final project report PDF (`docs/report/StudyLink_Project_Report_and_SRS.pdf`).
  - Exclude: `target/`, `local/` private keystores, passwords/credentials, real private notes, and local `var/` caches.
- **Live Group Demonstration:** **10 minutes total**, with **up to 2 minutes per member** (SRS Section 8.1):
  1. `0:00–1:55` — **M2 (`discovery`):** Introduce classroom scenario, discover host via UDP, and demonstrate online presence expiry.
  2. `1:55–3:50` — **M1 (`rooms`):** Connect and join a room over TCP, handle a duplicate display name, and explain a length-prefixed request/response frame.
  3. `3:50–5:45` — **M5 (`chat`):** Exchange live room chat messages, show the isolated third room, and highlight ready channels in the NIO `Selector` loop.
  4. `5:45–7:40` — **M3 (`files`):** Run two concurrent PDF/TXT transfers while chat stays responsive and verify the completed file's `SHA-256` digest.
  5. `7:40–9:35` — **M4 (`notes`):** Submit a confidential note over TLS 1.3, read it locally at the host inbox, and show rejection against an untrusted endpoint.
  6. `9:35–10:00` — **Reserved:** Handoffs and brief Q&A recovery buffer.
