#!/usr/bin/env python3
"""Keep APK builds in a persistent, sequentially numbered archive."""

import argparse
from contextlib import contextmanager
import fcntl
import hashlib
import os
from pathlib import Path
import re
import shutil
import stat
import tempfile


_APK_NAME = re.compile(r"apk ([0-9]+)\.apk", re.IGNORECASE)
_COUNTER_NAME = ".last-build-number"
_BUFFER_SIZE = 1024 * 1024


@contextmanager
def _regular_file(path: Path):
    """Open a regular file without following a symlink, including a raced link."""
    if not stat.S_ISREG(path.lstat().st_mode):
        raise ValueError(f"Expected a regular file, not a directory or symlink: {path}")
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    with os.fdopen(descriptor, "rb") as stream:
        if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
            raise ValueError(f"Expected a regular file: {path}")
        yield stream


def _sha256(stream):
    stream.seek(0)
    digest = hashlib.sha256()
    size = 0
    while chunk := stream.read(_BUFFER_SIZE):
        digest.update(chunk)
        size += len(chunk)
    return digest.digest(), size


def _existing_apks(archive_dir: Path):
    existing = []
    for path in archive_dir.iterdir():
        match = _APK_NAME.fullmatch(path.name)
        if match is None:
            continue
        if not stat.S_ISREG(path.lstat().st_mode):
            raise ValueError(f"Numbered APK must be a regular file, not a symlink: {path}")
        existing.append((int(match.group(1)), path))
    return sorted(existing, key=lambda item: (item[0], item[1].name))


def _read_counter(archive_dir: Path):
    path = archive_dir / _COUNTER_NAME
    try:
        with _regular_file(path) as stream:
            content = stream.read()
    except FileNotFoundError:
        return 0
    if re.fullmatch(rb"(?:0|[1-9][0-9]*)\n?", content) is None:
        raise ValueError(f"Invalid APK build counter in {path}; repair it before archiving")
    return int(content)


def _write_counter(archive_dir: Path, number: int):
    descriptor, name = tempfile.mkstemp(prefix=".counter-", dir=archive_dir)
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(f"{number}\n".encode("ascii"))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, archive_dir / _COUNTER_NAME)
    finally:
        temporary.unlink(missing_ok=True)


def archive_apk(source: Path, archive_dir: Path, minimum_number=0) -> Path:
    """Archive a new APK and return its path, or reuse identical newest bytes.

    ``minimum_number`` reserves earlier build numbers. The persistent counter
    keeps those numbers reserved even when APKs are subsequently removed.
    The caller is responsible for validating that the source is a usable APK.
    """
    source = Path(source)
    archive_dir = Path(archive_dir)
    if not isinstance(minimum_number, int) or minimum_number < 0:
        raise ValueError("minimum_number must be a nonnegative integer")

    with _regular_file(source) as source_stream:
        if os.fstat(source_stream.fileno()).st_size == 0:
            raise ValueError(f"APK source is empty: {source}")
        archive_dir.mkdir(parents=True, exist_ok=True)
        lock_path = archive_dir / ".archive.lock"
        descriptor = os.open(
            lock_path, os.O_CREAT | os.O_RDWR | getattr(os, "O_NOFOLLOW", 0), 0o600
        )
        with os.fdopen(descriptor, "rb+") as lock:
            if not stat.S_ISREG(os.fstat(lock.fileno()).st_mode):
                raise ValueError(f"Archive lock must be a regular file: {lock_path}")
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
            existing = _existing_apks(archive_dir)
            counter = _read_counter(archive_dir)
            highest = existing[-1][0] if existing else 0
            last_number = max(counter, highest, minimum_number)
            source_digest, source_size = _sha256(source_stream)
            if source_size == 0:
                raise ValueError(f"APK source is empty: {source}")

            if existing:
                latest = existing[-1][1]
                with _regular_file(latest) as latest_stream:
                    latest_digest, latest_size = _sha256(latest_stream)
                if (latest_digest, latest_size) == (source_digest, source_size):
                    if counter != last_number:
                        _write_counter(archive_dir, last_number)
                    return latest

            destination = archive_dir / f"Apk {last_number + 1}.apk"
            descriptor, name = tempfile.mkstemp(prefix=".apk-", dir=archive_dir)
            temporary = Path(name)
            try:
                with os.fdopen(descriptor, "wb") as output:
                    source_stream.seek(0)
                    shutil.copyfileobj(source_stream, output, _BUFFER_SIZE)
                    output.flush()
                    os.fsync(output.fileno())
                with _regular_file(temporary) as copy_stream:
                    copy_digest, copy_size = _sha256(copy_stream)
                current_digest, current_size = _sha256(source_stream)
                if (copy_digest, copy_size) != (source_digest, source_size) or (
                    current_digest, current_size
                ) != (source_digest, source_size):
                    raise ValueError(f"APK source changed while being copied: {source}")

                # A hard link publishes complete bytes atomically and cannot
                # overwrite a file created by a noncooperating writer.
                os.link(temporary, destination)
                _write_counter(archive_dir, last_number + 1)
                return destination
            finally:
                temporary.unlink(missing_ok=True)


def _nonnegative_integer(value):
    number = int(value)
    if number < 0:
        raise argparse.ArgumentTypeError("Must be a nonnegative integer")
    return number


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="APK file to archive")
    parser.add_argument(
        "--archive-dir", type=Path,
        default=Path(__file__).resolve().parent.parent / "Apk build",
        help="Destination folder (default: project/Apk build)",
    )
    parser.add_argument("--minimum-number", type=_nonnegative_integer, default=0)
    args = parser.parse_args()
    try:
        destination = archive_apk(args.source, args.archive_dir, args.minimum_number)
    except (OSError, ValueError) as error:
        parser.exit(1, f"APK archive failed: {error}\n")
    print(destination)


if __name__ == "__main__":
    main()
