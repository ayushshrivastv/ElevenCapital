"""Focused tests for APK archiving; all build bytes live in temporary folders."""

from concurrent.futures import ProcessPoolExecutor
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("archive_apk.py")
spec = importlib.util.spec_from_file_location("archive_apk", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
archive_apk = module.archive_apk


def archive_in_process(arguments):
    source, archive = arguments
    return str(archive_apk(Path(source), Path(archive)))


class ArchiveApkTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="APK project with spaces ")
        self.root = Path(self.temporary.name)
        self.archive = self.root / "Apk build"
        self.source = self.root / "source build.apk"

    def tearDown(self):
        self.temporary.cleanup()

    def save(self, content, minimum_number=0):
        self.source.write_bytes(content)
        return archive_apk(self.source, self.archive, minimum_number)

    def counter(self):
        return (self.archive / ".last-build-number").read_text()

    def test_numbered_series_continues_past_nine(self):
        for number in range(1, 13):
            destination = self.save(f"build {number}".encode())
            self.assertEqual(destination.name, f"Apk {number}.apk")
        self.assertEqual(self.counter(), "12\n")
        self.assertEqual(len(list(self.archive.glob("*.apk"))), 12)

    def test_counter_survives_removing_all_apks(self):
        self.save(b"first")
        self.save(b"second")
        for path in self.archive.glob("*.apk"):
            path.unlink()
        destination = self.save(b"third")
        self.assertEqual(destination.name, "Apk 3.apk")
        self.assertEqual(self.counter(), "3\n")

    def test_identical_latest_build_is_reused(self):
        first = self.save(b"identical bytes")
        self.assertEqual(self.save(b"identical bytes"), first)
        self.assertEqual(self.counter(), "1\n")
        self.assertEqual(len(list(self.archive.glob("*.apk"))), 1)

    def test_rebuilding_older_bytes_creates_a_new_latest_number(self):
        first = self.save(b"first build")
        second = self.save(b"second build")
        rebuilt = self.save(b"first build")
        self.assertEqual(rebuilt.name, "Apk 3.apk")
        self.assertEqual(rebuilt.read_bytes(), first.read_bytes())
        self.assertNotEqual(rebuilt, first)
        self.assertEqual(second.read_bytes(), b"second build")

    def test_dedupe_reserves_minimum_number_for_next_build(self):
        first = self.save(b"identical bytes")
        self.assertEqual(self.save(b"identical bytes", minimum_number=12), first)
        self.assertEqual(self.counter(), "12\n")
        self.assertEqual(self.save(b"changed bytes").name, "Apk 13.apk")

    def test_bad_source_leaves_existing_archive_unchanged(self):
        first = self.save(b"valid existing bytes")
        before = {p.name: p.read_bytes() for p in self.archive.iterdir()}
        missing = self.root / "missing.apk"
        directory = self.root / "directory.apk"
        directory.mkdir()
        symlink = self.root / "symlink.apk"
        symlink.symlink_to(first)
        empty = self.root / "empty.apk"
        empty.touch()
        for source in (missing, directory, symlink, empty):
            with self.subTest(source=source.name):
                with self.assertRaises((OSError, ValueError)):
                    archive_apk(source, self.archive)
                self.assertEqual(
                    {p.name: p.read_bytes() for p in self.archive.iterdir()}, before
                )

    def test_empty_source_does_not_create_archive(self):
        self.source.touch()
        with self.assertRaises(ValueError):
            archive_apk(self.source, self.archive)
        self.assertFalse(self.archive.exists())

    def test_concurrent_builds_have_unique_numbers_and_bytes(self):
        arguments = []
        expected = set()
        for number in range(12):
            source = self.root / f"build {number}.apk"
            content = f"different build bytes {number}".encode()
            source.write_bytes(content)
            expected.add(content)
            arguments.append((str(source), str(self.archive)))
        with ProcessPoolExecutor(max_workers=4) as pool:
            destinations = list(pool.map(archive_in_process, arguments))
        self.assertEqual(len(set(destinations)), 12)
        self.assertEqual(
            {Path(path).name for path in destinations},
            {f"Apk {number}.apk" for number in range(1, 13)},
        )
        self.assertEqual({Path(path).read_bytes() for path in destinations}, expected)
        self.assertEqual(self.counter(), "12\n")

    def test_existing_highest_number_uses_numeric_case_insensitive_names(self):
        self.archive.mkdir()
        (self.archive / "Apk 9.apk").write_bytes(b"nine")
        (self.archive / "aPK 12.APK").write_bytes(b"twelve")
        (self.archive / ".last-build-number").write_text("3\n")
        self.assertEqual(self.save(b"thirteen").name, "Apk 13.apk")
        self.assertEqual(self.counter(), "13\n")

    def test_dedupe_checks_numeric_highest_existing_file(self):
        self.archive.mkdir()
        (self.archive / "Apk 9.apk").write_bytes(b"old")
        latest = self.archive / "APK 12.APK"
        latest.write_bytes(b"latest")
        self.assertEqual(self.save(b"latest"), latest)
        self.assertEqual(self.counter(), "12\n")

    def test_corrupt_counter_prevents_publication_or_overwrite(self):
        first = self.save(b"keep this")
        counter = self.archive / ".last-build-number"
        for invalid in ("not a number", "-1\n", "1.5\n", "12\n13\n", "", " 12\n"):
            with self.subTest(invalid=invalid):
                counter.write_text(invalid)
                with self.assertRaises(ValueError):
                    self.save(b"must not publish")
                self.assertEqual(first.read_bytes(), b"keep this")
                self.assertEqual(counter.read_text(), invalid)
                self.assertEqual([p.name for p in self.archive.glob("*.apk")], [first.name])
                self.assertFalse(list(self.archive.glob(".apk-*")))

    def test_numbered_apk_symlink_is_rejected(self):
        self.archive.mkdir()
        external = self.root / "external.apk"
        external.write_bytes(b"external bytes")
        (self.archive / "Apk 20.apk").symlink_to(external)
        with self.assertRaises(ValueError):
            self.save(b"new bytes")
        self.assertEqual(external.read_bytes(), b"external bytes")
        self.assertFalse((self.archive / ".last-build-number").exists())

    def test_copy_hash_mismatch_does_not_publish_or_advance_counter(self):
        first = self.save(b"first build")
        self.source.write_bytes(b"second build")

        def corrupt_copy(source, destination, length):
            destination.write(b"corrupt copied bytes")

        with patch.object(module.shutil, "copyfileobj", side_effect=corrupt_copy):
            with self.assertRaisesRegex(ValueError, "changed while being copied"):
                archive_apk(self.source, self.archive)
        self.assertEqual(first.read_bytes(), b"first build")
        self.assertEqual(self.counter(), "1\n")
        self.assertFalse((self.archive / "Apk 2.apk").exists())
        self.assertFalse(list(self.archive.glob(".apk-*")))

    def test_publication_cannot_overwrite_a_racing_existing_file(self):
        self.save(b"first build")
        self.source.write_bytes(b"second build")
        link = module.os.link

        def competing_writer(temporary, destination):
            destination.write_bytes(b"competing writer bytes")
            return link(temporary, destination)

        with patch.object(module.os, "link", side_effect=competing_writer):
            with self.assertRaises(FileExistsError):
                archive_apk(self.source, self.archive)
        self.assertEqual((self.archive / "Apk 2.apk").read_bytes(), b"competing writer bytes")
        self.assertEqual(self.counter(), "1\n")
        self.assertFalse(list(self.archive.glob(".apk-*")))

    def test_cli_handles_spaces_and_minimum_number(self):
        self.source.write_bytes(b"CLI build bytes")
        result = subprocess.run(
            [sys.executable, str(SCRIPT), str(self.source), "--archive-dir",
             str(self.archive), "--minimum-number", "12"],
            check=True, capture_output=True, text=True,
        )
        destination = self.archive / "Apk 13.apk"
        self.assertEqual(result.stdout.strip(), str(destination))
        self.assertEqual(destination.read_bytes(), b"CLI build bytes")


if __name__ == "__main__":
    unittest.main()
