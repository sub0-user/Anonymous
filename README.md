# Anonymous

<p align="center">
  <img src="src/main/resources/org/server/anonymous/logo/icon-square.png" alt="Anonymous" width="140"/>
</p>

**Anonymous** is a decentralized, self-hosted, Tor-based private messenger.

- **No central server.** There is no company hosting your chats — ever.
- **Your number is your onion address.** Each user runs their own Tor hidden service
  from inside the app. Your `.onion` address is your identity, and you share it with
  contacts the way you'd share a phone number.
- **You host your own chat.** Messages travel peer-to-peer between users' own nodes,
  end-to-end encrypted. Chat data lives only on the participants' machines.

Built with Kotlin on JDK 21, JavaFX (UI defined entirely in FXML + CSS), and a bundled
Tor binary — one self-contained app, nothing to install or configure.

## Demo

<p align="center">
  <a href="https://www.youtube.com/watch?v=RSf1IIaImv4">
    <img src="https://img.youtube.com/vi/RSf1IIaImv4/maxresdefault.jpg"
         alt="Anonymous v1.0 — your own private messenger, no servers, no tracking (walkthrough)" width="560"/>
  </a>
</p>

Full walkthrough: creating your identity, adding a contact, 1:1 chat, creating a
group, adding members, replying to a specific message, and more.

## Version 1.0 — feature complete

v1.0 is the first full release — **Linux only** (see Platforms).

- **Your onion address is your number** — each user runs their own Tor v3 hidden
  service created inside the app; contacts come with safety numbers.
- **1:1 encrypted messaging** with offline delivery and automatic retry.
- **Groups (private & public rooms):**
  - invite-only — add any trusted contact directly ("Add member"); invites arrive in
    chat as a message with one-tap **Accept**;
  - leave or delete rooms, kick members (the room key rotates so the kicked member can
    no longer read), rename members;
  - **reply to a specific message** in any chat or group.
- **Emoji everywhere** — a color emoji picker and inline emoji in bubbles.
- **Copy message, copy your onion address** — one click each.
- **History encrypted at rest** — messages (including replies) live only on your
  machine, encrypted with your identity key.
- **Passphrase-protected backups** — identity, contacts, rooms, and history.
- **Privacy by design** — no trackers, no telemetry, no ads, no central server.

### Platforms

v1.0 supports **Linux only** (install with `scripts/install.sh`, run via the
`anonymous` launcher). Windows and macOS come in later versions.

### Release

- Latest release: https://github.com/sub0-user/Anonymous/releases
- Tag: `v1.0.0`

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

## Get in touch

- **X / Twitter:** [@Sub0_User](https://x.com/Sub0_User) — chat with the maintainer
- GitHub: [sub0-user](https://github.com/sub0-user)
- YouTube: [Sub0-User](https://www.youtube.com/@Sub0-User)
- Instagram: [sub0_user](https://www.instagram.com/sub0_user/)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Questions or ideas? Chat with the maintainer
on X: [@Sub0_User](https://x.com/Sub0_User).

## Documentation

- **User guides:** `guide/user/`
- **Contribution guide:** `CONTRIBUTING.md`

## License

TBD — to be chosen by the project owner.
