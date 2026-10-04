#!/usr/bin/env python3
"""Prompt locally without echoing a credential; preserve the existing Jupiter key."""
import getpass
import json
import os
from pathlib import Path
import stat
import sys
import tempfile


def valid_key(value):
    return isinstance(value, str) and 0 < len(value) <= 2048 and value.strip() == value and not any(
        ord(char) < 32 or ord(char) == 127 for char in value
    )


def main():
    if not sys.stdin.isatty():
        raise ValueError("Run this command in a local terminal to enter the key privately.")
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
            name not in ("JUPITER_API_KEY", "PYTH_API_KEY", "PANTA_API_KEY") or not valid_key(value)
            for name, value in config.items()
        ):
            raise ValueError("Existing backend configuration is invalid; no changes were made.")
    key = getpass.getpass("Pyth API key (hidden): ")
    if not valid_key(key):
        raise ValueError("Pyth key is empty or invalid; no changes were made.")
    config["PYTH_API_KEY"] = key
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
    print("Pyth key saved privately. Restart the backend to use it.")


if __name__ == "__main__":
    try:
        main()
    except (KeyboardInterrupt, EOFError):
        sys.exit("Cancelled; no changes were made.")
    except (ValueError, OSError):
        # Never print parsed configuration, exception context, or the credential.
        sys.exit("Could not securely update the key. Check the existing file format, ownership and chmod 600 permissions.")
