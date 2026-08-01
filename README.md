# Anonymous

**Anonymous** is a decentralized, self-hosted, Tor-based private messenger.

- **No central server.** There is no company hosting your chats — ever.
- **Your number is your onion address.** Each user runs their own Tor hidden service
  from inside the app. Your `.onion` address is your identity, and you share it with
  contacts the way you'd share a phone number.
- **You host your own chat.** Messages travel peer-to-peer between users' own nodes,
  end-to-end encrypted. Chat data lives only on the participants' machines.

Built with Kotlin on JDK 21, JavaFX (UI defined entirely in FXML + CSS), and a bundled
Tor binary — one self-contained app, nothing to install or configure.

## Status

v1.0: encrypted text messaging and group chatrooms — identity (your onion
address is your number), contacts with safety numbers, 1:1 DMs, private and
public rooms (founder-hosted, client-auth invites, key rotation on removal),
message history encrypted at rest, offline delivery with retry, and
passphrase-protected backups that include contacts and rooms. Open source,
no ads, no trackers, no central server — donations only. Media transfer and
read receipts are planned for later versions.

## Platforms

Linux is the primary platform and is fully supported (install via
`scripts/install.sh`, run via the `anonymous` launcher). Windows install
scripts exist but are beta — Windows is not yet verified. macOS is not
currently supported.

## Build & run

Requirements: JDK 21.

```bash
./gradlew run
```

Quality gate (must pass before any commit):

```bash
./gradlew check
```

Release build + install (Linux):

```bash
./gradlew jlinkZip
bash scripts/install.sh
```

## Documentation

- **User guides:** `guide/user/`
- **Contribution guide:** `CONTRIBUTING.md`

## License

TBD — to be chosen by the project owner.
