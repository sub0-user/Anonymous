# Contributing to Anonymous

Thanks for contributing! Anonymous is a decentralized, self-hosted, Tor-based private
messenger (Kotlin + JavaFX on JDK 21).

**Questions or ideas? Chat with the maintainer on X (Twitter):**
[@Sub0_User](https://x.com/Sub0_User).

## Getting started

1. JDK 21 required.
2. `./gradlew check` must pass before any change is submitted (lint + tests).
3. Build with `./gradlew run`.

## Process

- **Small, focused changes.** One logical change per commit.
- **Conventional commit messages:** `feat:`, `fix:`, `docs:`, `chore:`, `test:`,
  `refactor:`.
- **Branching:** work on a `feat/<name>` branch off `main`; open a pull request.
- **Tests:** every change ships with or updates tests. Untested changes are rejected.

## Finding work

The repository is tagged on GitHub with these topics — browse issues by tag and add
them to your fork or PR to make work easy to find:

- `privacy`, `tor`, `p2p`, `decentralized`
- `encrypted-messaging`, `end-to-end-encryption`
- `kotlin`, `javafx`, `java`
- `self-hosted`, `hidden-service`, `onion`

## What to know

- The UI is defined **only** in FXML + CSS under `src/main/resources`. Never construct
  UI in Kotlin code.
- Code is organized in three layers: `controller` (FXML controllers + view models),
  `business` (services), `data` (persistence/models). One-way dependencies only.
- Onion addresses are user identities — handle and display them carefully.
- All node/crypto/network work runs off the JavaFX Application Thread; the UI updates
  only through view-model properties bound on the FX thread.

## Security

This project deals with cryptography, key material, and Tor. Security-sensitive changes
receive extra review. Do not log key material or private data. Report vulnerabilities
privately to the maintainer on X ([@Sub0_User](https://x.com/Sub0_User)) — do not open
a public issue for them.

## User guides

End-user documentation lives in `guide/user/`. Update it when user-facing behavior
changes.
