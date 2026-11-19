# StudyLink Tooling & TLS Certificate Handoff (`tools/`)

## Ownership and Current Status

- **Owner:** Member 4 (`M4` — Private notes over TLS 1.3, port `45404`)
- **Primary reviewer:** Member 2 (`M2` — Discovery and manual endpoint naming alignment)
- **Current status:** Reserved in Phase 0. The local TLS generation script (`tools/prepare-demo-tls.ps1`) and test certificates are planned for Member 4's feature branch (`feat/private-notes-tls`).

> **Phase-0 note:** The starter configuration check (`--check-config`) validates `tls.serverStore` and `tls.clientTrustStore` path settings **before** any PKCS12 files exist on disk. Teammates working on `M1`, `M2`, `M3` and `M5` can build, test and run `--check-config` without generating TLS material first.

---

## Planned TLS 1.3 Architecture (SRS Section 4.1, 5.4, F11, F12, Q05)

Only the private-note service (`TCP 45404`) uses TLS 1.3 via JSSE (`SSLContext`, `SSLServerSocket`, `SSLSocket`). Public room control, discovery, NIO chat and study file transfers run over standard TCP/UDP on the classroom IPv4 LAN.

| Artefact | Default Local Path | Machine Role | Contents | Password Environment Variable |
| :--- | :--- | :--- | :--- | :--- |
| Host keystore | `local/host.p12` | Host server laptop only | Host private key + X.509 certificate with Subject Alternative Name (SAN) matching the host's LAN IPv4 address / hostname (plus `127.0.0.1` / `localhost` for local testing) | `STUDYLINK_KEYSTORE_PASSWORD` |
| Client truststore | `local/host-trust.p12` | Student client laptops (and host test client) | Trusted public certificate of the host server **only** (never contains the private key) | `STUDYLINK_TRUSTSTORE_PASSWORD` |
| Untrusted test store | `local/untrusted-host.p12` | M4 test / demo fixture | Separate self-signed identity not present in `host-trust.p12`, used to demonstrate `T09` certificate/hostname rejection | Local test env only |

---

## Planned M4 Deliverables in `tools/`

When Member 4 implements `feat/private-notes-tls`, this directory will contain:

1. `tools/prepare-demo-tls.ps1` (and equivalent cross-platform `keytool` commands) that invoke JDK 21's `keytool` to:
   - Generate a `PKCS12` host keystore at `local/host.p12` with `-ext "SAN=IP:<host-lan-ip>,IP:127.0.0.1,DNS:localhost"`.
   - Export the host's public certificate and import it into `local/host-trust.p12`.
   - Print the SHA-256 certificate fingerprint so the team can verify the public truststore before the classroom demonstration.
2. Instructions for coordinating the host's Subject Alternative Name (SAN) with Member 2 (`M2`) so both UDP-discovered endpoints and manually entered IP/hostname addresses pass JSSE endpoint identification (`SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")`).

---

## Security & Git Hygiene Rules

- **Never commit** `local/`, `*.p12`, `*.pfx`, `*.jks`, `*.key`, `*.pem`, `*.cer`, `*.crt`, or real passwords to Git. Both `.gitignore` and PR checks exclude these paths.
- **Never store passwords** in `config/studylink.example.properties` or `config/studylink.properties`. Passwords must be supplied at runtime via `STUDYLINK_KEYSTORE_PASSWORD` (host) and `STUDYLINK_TRUSTSTORE_PASSWORD` (client).
- See [../config/studylink.example.properties](../config/studylink.example.properties) and [../docs/architecture/protocol.md](../docs/architecture/protocol.md) for the configured paths and TLS 1.3 handshake requirements.
