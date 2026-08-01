# User Guides

End-user documentation for Anonymous.

## Installing

- **Linux:** run `bash scripts/install.sh` (or `./gradlew jlinkZip` first if you
  build from source), then launch the `anonymous` command. Your data lives in
  `~/.anonymous` and is never uploaded anywhere.
- **Windows:** install scripts exist (`scripts/install.ps1`) but are beta —
  not yet verified.
- No account, no email, no phone number. Nothing to sign up for.

## Your identity — your onion address is your number

On first launch the app creates your identity: a private key that derives your
`.onion` address. That address is your "phone number" — share it with contacts
the way you'd share a number, and it never changes.

- **Back it up.** Use **My number → Backup** to export a passphrase-protected
  file that contains your identity, your contacts (with their safety numbers),
  your block list, and your rooms. If you lose the device without a backup,
  you lose everything.
- **Restore** the backup on a fresh install with the same passphrase.
- Safety numbers: after the first message with a contact, an E2E safety number
  appears — verify it with them out of band to defeat impersonation.

## Contacts and 1:1 chats

Add a contact by pasting their `.onion` address. You can block, delete, or
clear a chat's history from the chat header. If someone you don't know sends
you a message, it lands in **Requests** — you decide to accept or ignore.

## Chatrooms

Rooms are hosted on the founder's node — whoever creates a room is its admin.
Two kinds:

- **Private** — only invited people can join (Tor client auth). Guests pick their
  own display name when joining; the admin can rename anyone at any time.
- **Public** — anyone with the entry key (shared through an invite) can join.
  Removing a member rotates the room key, but the entry key still opens the door.

The admin can remove members. A removed member loses access right away: they can't
reach the room again (private rooms) and can't read messages sent after the removal
(both kinds — the key is rotated). Messages are text only and are delivered
peer-to-peer between members, not through a server.

**Know the tradeoffs:** the founder can read everything in the room and sees who is
in it. Rooms are private *between* members, never *from* the admin — only join rooms
hosted by someone you trust with that power.

## How it behaves

- **Messages survive restarts** — history is stored on your machine, encrypted at
  rest with a key derived from your identity.
- **Offline delivery** — a message to someone offline is queued and retried with
  backoff; it lands (marked delivered) when they come online.
- **Tor resilience** — if Tor crashes, the app restarts it and comes back online
  on its own with the same address.
- **Media is out of scope** — no file or photo transfer, by design. Payloads are
  your job.
- **Single device** — your identity lives on one machine.

## Supporting the project

Anonymous is free and open source — no ads, no trackers, no paywall. If it's
useful, the Settings screen shows a donation address. That's the whole business
model.
