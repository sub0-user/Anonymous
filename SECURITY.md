# Security

Anonymous is a decentralized, self-hosted, Tor-based messenger. Your `.onion`
address is your number, messages travel directly between your node and your
contact's node — no server in the middle.

## Reporting a vulnerability

Please **do not** open a public issue for security problems. Report privately
to the maintainers (see `README.md` for contact) with:

- the affected version and platform,
- a minimal reproduction,
- the impact you believe it has.

We will acknowledge within 7 days and work toward a fix before disclosure.

## What the app protects

- **Messages** are end-to-end encrypted (X25519 + ChaCha20-Poly1305, HKDF-SHA256
  session keys) and travel only between the two nodes, over Tor.
- **Your IP** is hidden by Tor; the onion address reveals only that something
  runs there, not where.
- **Identity**: your number is derived from a local Ed25519 seed stored under
  `~/.anonymous` with user-only permissions. Back it up (passphrase-protected)
  to keep it across devices.
- **No telemetry, no trackers, no third-party requests.** The app talks only to
  your Tor node and to other Anonymous peers' onion services.

## What it does not protect (be honest about this)

- **Timing correlation** is a known Tor weakness; traffic patterns can
  occasionally be correlated by network observers.
- **A compromised device is game over.** Anyone with access to your machine and
  your passphrase controls your identity and your messages.
- **The people you talk to.** A contact can screenshot or forward what you send.
- **Your memory.** A removed contact keeps what they saw while they were a
  contact — as with every messenger.
- This project is a **security tool in development**; treat it accordingly.

## Hardening notes

- All cryptography uses JDK built-ins (no third-party crypto code): Ed25519,
  X25519, HKDF-SHA256, ChaCha20-Poly1305, AES-GCM, PBKDF2.
- Every inbound frame is validated and size-capped; malformed input drops the
  connection, never the app.
- The bundled Tor binary is pinned by SHA-256 (see `build.gradle.kts`).
- The data directory is created with `0700`/`0600` permissions on POSIX.
