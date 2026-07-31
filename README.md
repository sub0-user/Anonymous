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

v1 (in development): 1:1 encrypted text messaging — identity creation, contacts,
chat, delivery status, offline retry queue. Media, groups, read receipts and forward
secrecy are planned for later versions.

## Build & run

Requirements: JDK 21.

```bash
./gradlew run
```

Quality gate (must pass before any commit):

```bash
./gradlew check
```

## Documentation

- **User guides:** `guide/user/`
- **Contribution guide:** `CONTRIBUTING.md`

## License

TBD — to be chosen by the project owner.
