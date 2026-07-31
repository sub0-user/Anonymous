# Contributing to Anonymous

Thanks for contributing! Anonymous is a decentralized, self-hosted, Tor-based private
messenger (Kotlin + JavaFX on JDK 21).

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

## What to know

- The UI is defined **only** in FXML + CSS under `src/main/resources`. Never construct
  UI in Kotlin code.
- Code is organized in three layers: `controller` (FXML controllers + view models),
  `business` (services), `data` (persistence/models). One-way dependencies only.
- Onion addresses are user identities — handle and display them carefully.

## Security

This project deals with cryptography, key material, and Tor. Security-sensitive changes
receive extra review. Do not log key material or private data. Report vulnerabilities
privately to the maintainers (details TBD).

## User guides

End-user documentation lives in `guide/user/`. Update it when user-facing behavior
changes.
