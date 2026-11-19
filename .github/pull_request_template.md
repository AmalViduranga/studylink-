## Owner and Scope

- **Feature owner:** `M1 (rooms)` / `M2 (discovery)` / `M3 (files)` / `M4 (notes)` / `M5 (chat)` / `Setup`
- **Target branch:** `dev` (feature PR, 1 peer review required) / `main` (release PR from `dev`, 2 distinct peer reviews required)
- **SRS requirements & tests covered:** e.g. `F01`, `Q07`, `T01`

## Summary of Change

- Describe what changed in `client`, `server`, `shared`, `config`, or `docs`.
- Note whether any shared contract (`lk.studylink.shared.*`, `config/studylink.example.properties`, or `docs/architecture/protocol.md`) was updated and which consuming owner reviewed it.

## Tests and Verification

- [ ] `./mvnw clean verify` (or `.\mvnw.cmd clean verify`) passes on JDK 21
- [ ] Normal-path and failure-path tests included for new behaviour
- [ ] Headless `--check-config` passes for both `studylink-server.jar` and `studylink-client.jar`
- [ ] No local secrets, `.p12` keystores, `config/studylink.properties`, or `var/` files are staged

## Evidence

- Attach or link the test output, sanitized log, or screenshot recorded under `docs/testing/`.
