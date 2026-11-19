# Contributing to StudyLink

This guide defines the branching workflow, package ownership boundaries, shared-contract coordination, testing requirements, and review rules for the five StudyLink contributors (`M1`–`M5`).

- **Product & SRS Baseline:** [docs/report/StudyLink_Project_Report_and_SRS.pdf](docs/report/StudyLink_Project_Report_and_SRS.pdf)
- **Wire Protocol:** [docs/architecture/protocol.md](docs/architecture/protocol.md)
- **Test Plan & Evidence:** [docs/testing/test-plan.md](docs/testing/test-plan.md)

---

## 1. Branch Policy and Pull Request Workflow

The repository uses two protected integration tiers (`dev` and `main`) plus short-lived feature branches:

1. **`main` (Release baseline):** Holds the verified project setup and reviewed release milestones for evaluation and Moodle submission.
2. **`dev` (Team integration branch):** Default integration target for all feature, bugfix, test, and documentation pull requests during development.
3. **Feature branches (branched from `dev`):** Each member branches from current `dev` using a descriptive branch name:
   - `feat/rooms-tcp` (`M1` — Room sessions & shared framing)
   - `feat/discovery-udp` (`M2` — UDP broadcast discovery & unicast presence)
   - `feat/file-transfers` (`M3` — Concurrent PDF/TXT transfers & SHA-256 catalogue)
   - `feat/private-notes-tls` (`M4` — TLS 1.3 private notes & host console inbox)
   - `feat/chat-nio` (`M5` — Java NIO selector room chat)
   - Use `fix/<topic>` or `docs/<topic>` for focused fixes and test evidence updates.

### Review and Approval Policy

> **Team Policy Notice:** The review requirements below govern all merges immediately as the team's agreed policy, including before a repository maintainer activates the corresponding GitHub branch rulesets on `dev` and `main`.

| Pull Request Type | Source -> Target | Required Approvals | Automated Checks Required |
| :--- | :--- | :--- | :--- |
| **Feature / Fix PR** | `feat/*` or `fix/*` -> `dev` | **1 other teammate** (prefer the designated main reviewer from SRS Section 5, or a consuming owner for `shared` changes) | GitHub Actions job `verify` (`.github/workflows/verify.yml`) must pass |
| **Release PR** | `dev` -> `main` | **2 distinct other teammates** (neither reviewer may be the PR author) | GitHub Actions job `verify` + all applicable acceptance evidence linked |

---

## 2. Package Ownership and Main Reviewers (SRS Section 5)

Each member owns an end-to-end classroom action across `client` and `server`, plus their normal-path and failure-path tests and a two-minute demonstration slot:

| Owner | Primary Concept | Client Packages Owned | Server Package Owned | Shared / Tooling Scope | Main Reviewer |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **M1** | TCP sockets (`java.net`) | `lk.studylink.client.rooms`, window shell & room switcher in `lk.studylink.client.ui` | `lk.studylink.server.rooms` | 4-byte length-prefixed UTF-8 JSON framing codec (`lk.studylink.shared.protocol`), `RoomBinding`, `MembershipView` | **M5** |
| **M2** | UDP datagrams (`java.net`) | `lk.studylink.client.discovery`, connection & online roster UI in `lk.studylink.client.ui` | `lk.studylink.server.discovery` | `Endpoint` and `PresenceEntry` models (`lk.studylink.shared.model`) | **M1** |
| **M3** | Server concurrency (`ThreadPoolExecutor` + `java.nio.file`) | `lk.studylink.client.files`, Shared files tab & progress bar in `lk.studylink.client.ui` | `lk.studylink.server.files` | File metadata models & SHA-256 verification helpers | **M5** |
| **M4** | TLS 1.3 & JSSE (`javax.net.ssl`) | `lk.studylink.client.notes`, Private note dialog in `lk.studylink.client.ui` | `lk.studylink.server.notes` | Local PKCS12 certificate setup (`tools/prepare-demo-tls.ps1`, [tools/README.md](tools/README.md)) | **M2** |
| **M5** | Java NIO (`Selector`, `SocketChannel`, `ByteBuffer`) | `lk.studylink.client.chat`, Chat tab in `lk.studylink.client.ui` | `lk.studylink.server.chat` | Chat event models & membership-invalidation selector wakeup hook | **M3** |

**Rule:** Do not edit another member's `client/<feature>` or `server/<feature>` package without coordinating on their issue or pull request.

---

## 3. Coordinating Changes to `shared`, Configuration, and Protocol

Changes to any of the following affect multiple members:
- `shared/src/main/java/lk/studylink/shared/**`
- `config/studylink.example.properties`
- Ports, message fields, room rules, error codes, or size limits in [docs/architecture/protocol.md](docs/architecture/protocol.md)

When proposing a shared change:
1. Open a small, focused PR into `dev` **before** client and server feature branches diverge on that contract.
2. Update [docs/architecture/protocol.md](docs/architecture/protocol.md) and shared JSON fixtures/tests in `shared/src/test/java/lk/studylink/shared/` in the same PR.
3. Request review from **at least one owner who consumes the interface** (for example, `M1` publishing `MembershipView` or frame codecs requests review from `M5` or `M3`).

---

## 4. Testing and Local Verification Before Opening a PR

Every pull request must pass the full wrapper build and headless JAR diagnostics from the repository root on **JDK 21**:

### Windows PowerShell
```powershell
.\mvnw.cmd -v
.\mvnw.cmd clean verify
java -jar server/target/studylink-server.jar --config config/studylink.example.properties --check-config
java -jar client/target/studylink-client.jar --config config/studylink.example.properties --check-config
```

### macOS / Linux
```bash
./mvnw -v
./mvnw clean verify
java -jar server/target/studylink-server.jar --config config/studylink.example.properties --check-config
java -jar client/target/studylink-client.jar --config config/studylink.example.properties --check-config
```

### Testing Guidelines
- **Headless CI compatibility:** Automated JUnit tests executed by Surefire must run headlessly without requiring a physical monitor. Use mock peers or ephemeral loopback sockets (`new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)`) with explicit socket and test timeouts.
- **Normal and failure paths:** Each feature owner must include at least one normal-path test and one failure-path test (e.g., duplicate display name, stale room binding, truncated upload or bad SHA-256 digest, untrusted TLS certificate, split/oversized NIO frame, or slow reader disconnect).
- **Never commit secrets or runtime state:** Ensure `git status` does not include `config/studylink.properties`, `local/`, `var/`, `*.p12`, or `target/`.

---

## 5. Submitting Acceptance Evidence (`docs/testing/`)

Each feature PR that completes an SRS requirement (`F01`–`F14`, `Q01`–`Q09`) or acceptance test (`T01`–`T12`) must:
1. Fill out [.github/pull_request_template.md](.github/pull_request_template.md) with the owner ID (`M1`–`M5`), requirement IDs, and test IDs.
2. Record the test run in [docs/testing/test-plan.md](docs/testing/test-plan.md) (or an evidence file under `docs/testing/`) with the commit hash, tester, date, OS, JDK version, network setup, expected result, observed result, and pass/fail status.
