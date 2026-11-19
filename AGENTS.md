# AGENTS.md — StudyLink Coding Assistant Guide

Concise conventions and boundaries for AI coding assistants working in the `studylink` repository.

## 1. Project Overview & Stack Constraints

- **Product:** StudyLink — a classroom IPv4 LAN study collaboration tool consisting of one Java host server (`studylink-server.jar`) and a Java Swing desktop client (`studylink-client.jar`).
- **Authoritative spec:** [docs/report/StudyLink_Project_Report_and_SRS.pdf](docs/report/StudyLink_Project_Report_and_SRS.pdf), [docs/architecture/protocol.md](docs/architecture/protocol.md), and [docs/testing/test-plan.md](docs/testing/test-plan.md).
- **Locked stack (do not add external frameworks or Netty/Spring/Jetty dependencies):**
  - **Java:** JDK 21 (`maven.compiler.release=21`, UTF-8 source/output encoding)
  - **Build:** Apache Maven `3.9.11` via the checked-in Maven Wrapper (`./mvnw` / `.\mvnw.cmd`)
  - **Desktop UI (`client` only):** Java Swing + `com.formdev:flatlaf:3.6.1` (FlatLaf Light)
  - **JSON (`shared`):** `com.google.code.gson:gson:2.13.2`
  - **Testing (`test` scope):** `org.junit.jupiter:junit-jupiter:5.13.4`
  - **Networking APIs (JDK standard library only):**
    - `M1 (rooms)` & `M2 (discovery)`: `java.net` (`ServerSocket`, `Socket`, `DatagramSocket`, `DatagramPacket`)
    - `M3 (files)`: `java.net` + `java.util.concurrent.ThreadPoolExecutor` (`4` workers, `8` queue) + `java.nio.file` + `java.security.MessageDigest` (`SHA-256`)
    - `M4 (notes)`: `javax.net.ssl` (JSSE `SSLContext`, `SSLServerSocket`, `SSLSocket` with `TLSv1.3` and SAN endpoint identification)
    - `M5 (chat)`: `java.nio.channels` (`ServerSocketChannel`, `SocketChannel`, `Selector`) + `java.nio.ByteBuffer`

## 2. Module Boundaries & Package Ownership

- **Maven reactor:** Root `pom.xml` builds `shared`, `server`, and `client`.
- **Dependency rule:** Both `server` and `client` depend on `shared`. **`client` and `server` must never depend on each other.**
- **Main classes & shaded JAR outputs:**
  - `server/target/studylink-server.jar` -> `lk.studylink.server.ServerMain`
  - `client/target/studylink-client.jar` -> `lk.studylink.client.DesktopMain`

| Member | Feature | Port (Default) | Owned Client Package(s) | Owned Server Package | Shared Responsibility |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **M1** | `rooms` | `TCP 45401` | `lk.studylink.client.rooms`, window shell in `lk.studylink.client.ui` | `lk.studylink.server.rooms` | 4-byte big-endian JSON framing codec & `MembershipView` |
| **M2** | `discovery` | `UDP 45400` | `lk.studylink.client.discovery`, connection panel in `ui` | `lk.studylink.server.discovery` | `Endpoint` and `PresenceEntry` models |
| **M3** | `files` | `TCP 45403` | `lk.studylink.client.files`, Shared files tab in `ui` | `lk.studylink.server.files` | File metadata models & SHA-256 helpers |
| **M4** | `notes` | `TLS 45404` | `lk.studylink.client.notes`, Private note dialog in `ui` | `lk.studylink.server.notes` | TLS setup scripts in `tools/` & note receipt models |
| **M5** | `chat` | `NIO 45402` | `lk.studylink.client.chat`, Chat tab in `ui` | `lk.studylink.server.chat` | Chat event models |

## 3. Build, Test, and Verification Commands

Always run commands from the repository root with JDK 21 active:

```powershell
# Windows PowerShell
.\mvnw.cmd -v
.\mvnw.cmd clean verify
java -jar server/target/studylink-server.jar --config config/studylink.example.properties --check-config
java -jar client/target/studylink-client.jar --config config/studylink.example.properties --check-config
```

```bash
# macOS / Linux
./mvnw -v
./mvnw clean verify
java -jar server/target/studylink-server.jar --config config/studylink.example.properties --check-config
java -jar client/target/studylink-client.jar --config config/studylink.example.properties --check-config
```

## 4. Rules for Making a Scoped Change

1. **Stay within the assigned member scope:** When implementing a feature for `Mx`, modify only `lk.studylink.client.<feature>`, `lk.studylink.server.<feature>`, the corresponding UI panel in `lk.studylink.client.ui`, and unit/integration tests for that feature.
2. **Coordinate `shared` changes first:** Any change to `lk.studylink.shared.*`, `config/studylink.example.properties`, or wire messages must be documented in [docs/architecture/protocol.md](docs/architecture/protocol.md) and reviewed by a consuming feature owner.
3. **Preserve CLI & headless testability:**
   - Both JARs must always support `--help` and `--config <path> --check-config` without an X11/Windows desktop display.
   - All JUnit tests run by `./mvnw clean verify` must pass in headless CI and use ephemeral ports (`0` on loopback) with bounded timeouts (`@Timeout` / `setSoTimeout`) rather than hardcoded busy ports.
   - Perform all Swing UI updates on the Event Dispatch Thread (`SwingUtilities.invokeLater`), and keep network/file I/O off the EDT.
4. **Never commit local secrets or runtime data:** Keep `config/studylink.properties`, `local/` (`*.p12`), `var/`, passwords, and real LAN IP overrides out of version control.
