# User Guides

End-user documentation for Anonymous lands here as features ship.

Current status (v1 in development): the first guides will cover installing the app,
creating your identity (your onion address), adding contacts, and chatting. They'll be
added alongside the corresponding features.

## Chatrooms (v1)

Rooms are hosted on the founder's node — whoever creates a room is its admin.
There are two kinds:

- **Private** — only invited people can join (Tor client auth). Guests pick their
  own display name when joining; the admin can rename anyone at any time.
- **Public** — anyone with the entry key (shared through an invite) can join.
  Removing a member rotates the room key, but the entry key still opens the door.

The admin can remove members. A removed member loses access right away: they can't
reach the room again (private rooms) and can't read messages sent after the removal
(both kinds — the key is rotated). Messages are text only and are delivered
peer-to-peer between members, not through a server.

One thing to know: the founder can read everything in the room and sees who is in it.
Rooms are private *between* members, never *from* the admin — only join rooms hosted
by someone you trust with that power.
