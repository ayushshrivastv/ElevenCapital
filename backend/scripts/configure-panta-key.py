#!/usr/bin/env python3
"""Prompt privately for a Panta key without exposing it in shell history or the APK."""
import getpass
import json
import os
from pathlib import Path
import stat
import sys
import tempfile


def valid_key(value):
    return isinstance(value, str) and value.startswith(("pk_test_", "pk_live_")) and 9 <= len(value) <= 256 and all(
        character.isascii() and (character.isalnum() or character in "_-") for character in value
    )


def valid_existing(value):
    return isinstance(value, str) and 0 < len(value) <= 2048 and value.strip() == value and not any(
        ord(character) < 32 or ord(character) == 127 for character in value
    )


def main():
    if not sys.stdin.isatty():
        raise ValueError("Run in a local terminal to enter the key privately.")
    path = Path.home() / "Library/Application Support/ElevenCapital/backend-secrets.json"
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    config = {}
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    except FileNotFoundError:
        pass
    else:
        with os.fdopen(fd, "r", encoding="utf8") as source:
            info = os.fstat(source.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_size > 16384 or info.st_uid != os.getuid() or info.st_mode & 0o177:
                raise ValueError("Existing backend secrets must be an owner-only regular file (chmod 600).")
            config = json.load(source)
        if not isinstance(config, dict) or any(
            name not in ("JUPITER_API_KEY", "PYTH_API_KEY", "PANTA_API_KEY") or not valid_existing(value)
            for name, value in config.items()
        ):
            raise ValueError("Existing backend configuration is invalid; no changes were made.")
    key = getpass.getpass("Panta API key (hidden): ")
    if not valid_key(key):
        raise ValueError("Panta key is invalid; no changes were made.")
    config["PANTA_API_KEY"] = key
    fd, temporary = tempfile.mkstemp(prefix=".backend-secrets-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf8") as output:
            os.fchmod(output.fileno(), 0o600)
            json.dump(config, output, indent=2)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    print("Panta key saved privately. Restart the backend to use it.")


if __name__ == "__main__":
    try:
        main()
    except (KeyboardInterrupt, EOFError):
        sys.exit("Cancelled; no changes were made.")
    except (ValueError, OSError):
        sys.exit("Could not securely update the key. Check the existing file format, ownership and chmod 600 permissions.")
