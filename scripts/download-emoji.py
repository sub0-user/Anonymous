#!/usr/bin/env python3
"""Downloads Twemoji color PNGs for every emoji in emojis.properties into resources.

Twemoji (twitter/twemoji) is CC-BY 4.0 — build-time asset only, nothing fetched at runtime.
Filenames are the lowercase hex codepoints joined by '-', e.g. 1f600.png, 2764-fe0f.png.
Idempotent: existing files are kept; missing ones are fetched (skipped with a warning on 404).
"""
import os
import re
import sys
import urllib.request

BASE = "https://raw.githubusercontent.com/twitter/twemoji/master/assets/72x72/{}.png"
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROPS = os.path.join(HERE, "src", "main", "resources", "org", "server", "anonymous", "emojis.properties")
OUT = os.path.join(HERE, "src", "main", "resources", "org", "server", "anonymous", "emoji")


def emoji_name(emoji: str) -> str:
    return "-".join("%04x" % cp for cp in [ord(c) for c in emoji])


def main() -> int:
    with open(PROPS, encoding="utf-8") as f:
        line = next(l for l in f if l.startswith("emoji.list="))
    emojis = [e for e in line.split("=", 1)[1].split() if e]
    os.makedirs(OUT, exist_ok=True)
    missing = []
    for emoji in emojis:
        name = emoji_name(emoji)
        target = os.path.join(OUT, name + ".png")
        if os.path.exists(target):
            continue
        try:
            with urllib.request.urlopen(BASE.format(name), timeout=20) as resp:
                data = resp.read()
            if len(data) < 50:
                missing.append(name)
                continue
            with open(target, "wb") as f:
                f.write(data)
            print("ok", name, len(data))
        except Exception as e:
            missing.append(name)
            print("MISS", name, e)
    print(f"done: {len(emojis)} emojis, {len(missing)} missing -> {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
