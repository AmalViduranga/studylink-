# StudyLink Verification and Acceptance Test Plan

- **Baseline reference:** [StudyLink Project Report and SRS](../report/StudyLink_Project_Report_and_SRS.pdf) (Sections 6, 7, and 7.1)
- **Current status:** Phase-0 bootstrap verification is **implemented and passing**. SRS acceptance tests `T01`–`T12` are **planned (`PENDING`)** and will be executed as feature owners (`M1`–`M5`) integrate their modules.

---

## 1. Phase-0 Bootstrap Tests (Implemented & Verified Now)

These automated JUnit Jupiter 5.13.4 tests run headlessly on every `./mvnw clean verify` (and in `.github/workflows/verify.yml`):

| Test Suite | Module | Purpose | Current Status |
| :--- | :--- | :--- | :--- |
| `lk.studylink.shared.ConfigLoaderTest` | `shared` | Validates loading `config/studylink.example.properties`, SRS defaults (`127.0.0.1`, ports `45400`–`45404`, `data.dir=var/room-files`, `tls.serverStore=local/host.p12`, `tls.clientTrustStore=local/host-trust.p12`), pre-TLS path checks, Gson non-secret JSON summary, CLI flag parsing, and rejection of missing files, missing keys, invalid/out-of-range/duplicate ports, and bad TLS/room values. | **PASS** |
| `lk.studylink.server.ServerStarterAndSocketSmokeTest` | `server` | Validates `ServerMain` headless `--help`, `--check-config` JSON output, nonzero exit on missing/invalid config, clean starter launch reporting planned listeners, plus a **bounded loopback TCP and UDP socket smoke test** using ephemeral ports (`0`) to confirm the Java 21 networking test environment (separate from the graded StudyLink protocol). | **PASS** |
| `lk.studylink.client.ClientStarterCliTest` | `client` | Validates `DesktopMain` headless `--help`, `--check-config` JSON output with Gson, and nonzero exit on missing/invalid configuration files without requiring an active display. | **PASS** |

---

## 2. SRS Acceptance Tests `T01`–`T12` (Planned — Results Pending)

All functional requirements (`F01`–`F14`) and quality requirements (`Q01`–`Q09`) from SRS Sections 6 and 7 are verified by tests `T01`–`T12`. Results remain `PENDING` until the corresponding feature branches are merged and measured on the release commit.

| Test ID | Owner(s) | Covers (SRS) | Procedure & Expected Evidence (SRS Section 7 / 7.1) | Status |
| :--- | :--- | :--- | :--- | :--- |
| **T01** Discovery | `M2` | `F01`, `F02`, `Q07` | Start host and discover it from a second laptop. Repeat with a duplicate reply, a wrong nonce, and an unanswered search. Record one valid listing, a 3-second timeout, and a successful manual connection fallback. | `PENDING` |
| **T02** Room lifecycle | `M1` | `F03`, `F04`, `F13`, `Q03` | Connect two names, reject a case-equivalent duplicate and an empty name, and verify the three predefined rooms (`netprog`, `revision`, `workshop`). Join, switch, and leave. Confirm at most one active room, incremented revision, and name release after a clean close. | `PENDING` |
| **T03** Presence | `M2` | `F05`, `Q07` | Pause one member's UDP heartbeat while TCP remains open. Verify that the roster removes the silent member within 12 seconds. Resume heartbeats and verify the entry returns. Inject an older sequence roster response and verify it is ignored. | `PENDING` |
| **T04** Room chat | `M5`, `M1` | `F04`, `F06`, `F07`, `F13`, `Q03` | Place students A and B in `Network Programming` and C in `Exam Revision`. Send a message, then switch B's room and send again. Record intended recipients and zero cross-room messages in the unrelated room. Reject empty or oversized (`> 2 KiB`) texts. | `PENDING` |
| **T05** Stream bounds | `M5`, `M2` | `F06`, `F07`, `Q06` | Split a chat frame across socket writes, send two frames in one write, and supply an excessive length prefix (`> 16,384` bytes). Stop one peer from reading until its pending outbound queue exceeds `64 KiB`; verify slow peer disconnect while other clients continue chatting. Confirm an oversized UDP packet (`> 1,200` bytes) is ignored. | `PENDING` |
| **T06** Group load | `M5` | `F06`, `Q01` | Run 10 live clients across two rooms and send 100 messages spread over 60 seconds. Measure send-to-echo latency per sender on one monotonic clock. Verify 95th percentile latency `<= 1.0 s` and 100% of intended deliveries accounted for. | `PENDING` |
| **T07** Parallel files | `M3` | `F08`, `F09`, `Q02`, `Q04` | Run two simultaneous PDF/TXT transfers while chatting and using the Shared files tab. Compare source and downloaded SHA-256 digests. Record 100 ms Swing timer dispatch delays (`<= 500 ms` late), transfer progress updates, and file listing in the active room. | `PENDING` |
| **T08** Transfer limits | `M3` | `F08`, `F09`, `F10`, `Q04`, `Q06` | Test files at `5 MiB` and `5 MiB + 1 byte`, a bad SHA-256 digest, a prohibited filename/extension, and two equal display filenames. Drop a client mid-upload and fill the worker pool (`4` workers + `8` queued). Verify clean rejection or `BUSY`, distinct server IDs for valid duplicate names, and zero partial files in the catalogue. | `PENDING` |
| **T09** Private note | `M4` | `F11`, `F12`, `Q05` | Send a synthetic private note over TLS 1.3 and retry with the same `requestId`. Confirm a single host inbox entry and matching receipt. Repeat against an untrusted certificate and a trusted certificate for a mismatched hostname/IP; both must fail before sending the note. Verify the note phrase is absent from public traffic, durable files, and normal logs. | `PENDING` |
| **T10** Recovery | `M1`, `M3` | `F10`, `F13`, `F14`, `Q04`, `Q07` | Complete one upload and interrupt another. Stop control pings without closing a test connection and observe session expiry by 21 seconds (`20 s` + `1 s` tick). Restart the server. Verify the completed file survives in `var/room-files`, the partial file is discarded, transient state (sessions, presence, chat, notes) is cleared, and clients can rejoin. | `PENDING` |
| **T11** Fresh setup | All owners | `F02`, `Q08`, `Q09` | Build from a clean clone using `./mvnw clean verify` on JDK 21 and start both packaged JARs. Connect from a second team laptop using the written README instructions. Using only the keyboard, join a room, send a message, and select a file. Verify readable text labels for connected, disconnected, transfer progress, and private-note error states. | `PENDING` |
| **T12** Stale binding | `M1` with `M3`–`M5` | `F04`, `F07`–`F13`, `Q03` | Switch rooms during an active upload. Attempt to reuse the old `RoomBinding` for chat, file list/download, a new upload, and a private note. Confirm `STALE_BINDING` rejection and zero old-room commits, receipts, or leaked events. Confirm student clients cannot list or read the host inbox, and a fresh binding works. | `PENDING` |

---

## 3. Requirement Traceability Matrix (SRS Section 6 & 7)

- **Functional requirements (`F01`–`F14`):**
  - `F01` (UDP broadcast discovery, 3 s deduplicated list) -> `T01`
  - `F02` (Manual host/port entry & readable failure) -> `T01`, `T11`
  - `F03` (Display name validation & duplicate rejection) -> `T02`
  - `F04` (Three rooms, one active room per session, fresh revision) -> `T02`, `T04`, `T12`
  - `F05` (Unicast heartbeat online roster, expiry, TCP unaffected by UDP loss) -> `T03`
  - `F06` (Live room chat `<= 2 KiB` UTF-8 echoed with metadata) -> `T04`, `T05`, `T06`
  - `F07` (Room chat isolation & discarding stale subscription events) -> `T04`, `T05`, `T12`
  - `F08` (Room file listing & SHA-256 verified download) -> `T07`, `T08`, `T12`
  - `F09` (PDF/TXT upload `<= 5 MiB`, unique server ID, atomic publication) -> `T07`, `T08`, `T12`
  - `F10` (Cleanup of truncated/mismatched/revoked-binding uploads) -> `T08`, `T10`, `T12`
  - `F11` (Private note `<= 2 KiB` over TLS 1.3 with trust & SAN hostname/IP check) -> `T09`, `T12`
  - `F12` (Host note receipt, idempotent retry by `requestId`, local-only inbox read) -> `T09`, `T12`
  - `F13` (Leave/close/timeout releases name & invalidates bindings; reconnect creates new session) -> `T02`, `T04`, `T10`, `T12`
  - `F14` (Server restart preserves completed room files & clears transient state) -> `T10`
- **Quality requirements (`Q01`–`Q09`):**
  - `Q01` (10 clients, 100 messages / 60 s, p95 send-to-echo `<= 1 s`) -> `T06`
  - `Q02` (Swing responsiveness during 2 parallel transfers; 100 ms timer `<= 500 ms` late) -> `T07`
  - `Q03` (Zero stale-binding operations or cross-room deliveries) -> `T02`, `T04`, `T12`
  - `Q04` (100% SHA-256 download match; zero partial files before/after restart) -> `T07`, `T08`, `T10`
  - `Q05` (Reject untrusted issuer & mismatched SAN; note body absent from public logs/files) -> `T09`
  - `Q06` (Frame bounds `1..16,384` B, UDP `<= 1,200` B, file pool `4+8`, slow client `64 KiB`) -> `T05`, `T08`
  - `Q07` (Manual fallback `<= 3 s`, hide silent member `<= 12 s`, expire control session `<= 21 s`) -> `T01`, `T03`, `T10`
  - `Q08` (Clean checkout builds both runnable JARs via wrapper on JDK 21; second laptop connects) -> `T11` (build portion verified in Phase 0)
  - `Q09` (Keyboard navigation & visible text labels for connection, progress, and error states) -> `T11`

---

## 4. Evidence Record Template (For Feature PRs & Final Release)

Save measured test reports, sanitized logs, or screenshots under `docs/testing/` using the template below (SRS Section 7):

```markdown
### Test Evidence: Txx - <Test Name>
- **Commit SHA:** <git-rev>
- **Tester / Owner:** Mx (<Name>)
- **Date:** YYYY-MM-DD
- **Environment:** <OS versions>, <JDK 21 version>, <Network / Wi-Fi / Loopback setup>
- **Requirements Covered:** Fxx, Qxx
- **Procedure Run:** <Commands or steps executed>
- **Expected Result:** <From SRS Table 7 / 7.1>
- **Observed Result & Metrics:** <Measured latency / digests / log excerpt>
- **Outcome:** PASS / FAIL
```
