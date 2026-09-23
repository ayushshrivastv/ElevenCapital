"""Isolated installer control-flow tests; fake SDK/ADB, no real phone/build/service."""
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest

SOURCE = Path(__file__).with_name("update-live-preview.command").read_text()


class InstallerTest(unittest.TestCase):
    def fixture(self, installed=8, running=True, edit=False):
        temporary = tempfile.TemporaryDirectory(prefix="eleven-update-test-")
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        project, downloads, sdk = (root / name for name in ("project with spaces", "downloads with spaces", "sdk"))
        script = project / "scripts/update-live-preview.command"
        apk = project / "app/build/outputs/apk/debug/app-debug.apk"
        tools = sdk / "build-tools/35.0.0"
        commands = root / "commands.jsonl"
        for directory in (script.parent, apk.parent, downloads, tools):
            directory.mkdir(parents=True, exist_ok=True)
        metadata = lambda version, package="com.elevencapital.app": json.dumps(dict(version=version, package=package))
        apk.write_text(metadata(8))
        for directory in (project, downloads):
            for name, data in {
                "Eleven-Capital-old.apk": metadata(6),
                "Eleven-Capital-same-version.apk": metadata(8),
                "Eleven-Capital-newer.apk": metadata(9),
                "Eleven-Capital-unrelated.apk": metadata(1, "org.example.other"),
                "Eleven-Capital-invalid.apk": "invalid",
                "local.properties": "private local config",
            }.items():
                (directory / name).write_text(data)
            (directory / "Eleven-Capital-link.apk").symlink_to(directory / "Eleven-Capital-old.apk")
            (directory / "nested").mkdir()
            (directory / "nested/Eleven-Capital-old.apk").write_text(metadata(1))
        (project / "README.md").write_text("current source")

        def executable(path, body):
            path.write_text("#!" + sys.executable + "\n" + body)
            path.chmod(0o755)

        adb = root / "fake-adb"
        executable(adb, f'''
import json
import sys
from pathlib import Path
args = sys.argv[1:]
with Path({str(commands)!r}).open("a") as out:
    out.write(json.dumps(args) + "\\n")
if args == ["devices", "-l"]:
    print("List of devices attached\\nRZCW92MJRCT device usb:123")
elif args[2:] == ["get-devpath"]:
    if {edit!r}:
        Path({str(script)!r}).write_text("#!/bin/zsh\\nfi\\ninvalid edited body\\n")
    print("usb:123")
elif args[2:] == ["shell", "dumpsys", "package", "com.elevencapital.app"]:
    print("versionCode={installed} minSdk=26")
elif args[2:] == ["shell", "am", "start", "-W", "-n", "com.elevencapital.app/.MainActivity"]:
    print("Status: ok")
elif args[2:] == ["shell", "pidof", "com.elevencapital.app"]:
    print({"1234" if running else ""!r})
else:
    raise SystemExit("Unexpected ADB operation")
''')
        executable(tools / "apksigner", "raise SystemExit(0)\n")
        executable(tools / "aapt", '''
import json
import sys
from pathlib import Path
try:
    data = json.loads(Path(sys.argv[-1]).read_text())
    print("package: name='" + data["package"] + "' versionCode='" + str(data["version"]) + "' versionName='fixture'")
except Exception:
    raise SystemExit(1)
''')
        (script.parent / "preview-service.mjs").write_text('''
import { appendFileSync } from 'node:fs';
appendFileSync(''' + json.dumps(str(commands)) + ''', JSON.stringify(process.argv.slice(2)) + '\\n');
if (process.argv[2] !== 'status') process.exit(97);
console.log('Fixture service status only');
''')
        (script.parent / "adb-manager.mjs").write_text('''
import { appendFileSync, writeFileSync } from 'node:fs';
appendFileSync(''' + json.dumps(str(commands)) + ''', JSON.stringify(['adb-manager', ...process.argv.slice(2)]) + '\\n');
if (''' + json.dumps(edit) + ''') writeFileSync(''' + json.dumps(str(script)) + ''', '#!/bin/zsh\\nfi\\ninvalid edited body\\n');
console.log('ADB ready: S22 RZCW92MJRCT is connected by USB; tcp:8787 -> tcp:8787 verified.');
''')
        # Redirect only the fixture copy's paths. Never repurpose HOME or touch real tools.
        replacements = {
            'project_dir="$(cd -- "$(dirname -- "$0")/.." && pwd -P)"': "project_dir=" + shlex.quote(str(project)),
            'adb_bin="$HOME/Library/Android/sdk/platform-tools/adb"': "adb_bin=" + shlex.quote(str(adb)),
            'export ANDROID_HOME="$HOME/Library/Android/sdk"': "export ANDROID_HOME=" + shlex.quote(str(sdk)),
            'downloads_dir="$HOME/Downloads/eleven capital"': "downloads_dir=" + shlex.quote(str(downloads)),
        }
        isolated = SOURCE
        for before, after in replacements.items():
            self.assertEqual(isolated.count(before), 1, before)
            isolated = isolated.replace(before, after)
        script.write_text(isolated)
        script.chmod(0o755)
        return project, downloads, script, commands

    def run_script(self, script, option="--finish-only"):
        return subprocess.run(["/bin/zsh", str(script), option], text=True, capture_output=True, timeout=30)

    def test_script_parses(self):
        result = subprocess.run(["/bin/zsh", "-n"], input=SOURCE, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_finish_only_backup_and_conservative_cleanup(self):
        project, downloads, script, commands = self.fixture()
        result = self.run_script(script)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Backend and wallet health were not rechecked", result.stdout)
        for directory in (project, downloads):
            self.assertFalse((directory / "Eleven-Capital-old.apk").exists())
            self.assertFalse((directory / "Eleven-Capital-same-version.apk").exists())
            for name in ("Eleven-Capital-newer.apk", "Eleven-Capital-unrelated.apk", "Eleven-Capital-invalid.apk", "nested/Eleven-Capital-old.apk"):
                self.assertTrue((directory / name).exists(), name)
            self.assertTrue((directory / "Eleven-Capital-link.apk").is_symlink())
            self.assertEqual((directory / "local.properties").read_text(), "private local config")
            self.assertEqual(json.loads((directory / "Eleven-Capital-market-debug.apk").read_text())["version"], 8)
        self.assertEqual((downloads / "README.md").read_text(), "current source")
        operations = [json.loads(line) for line in commands.read_text().splitlines()]
        self.assertFalse(any("install" in args or "stop" in args for args in operations))

    def test_mismatched_installed_version_preserves_backups(self):
        project, downloads, script, commands = self.fixture(installed=7)
        result = self.run_script(script)
        self.assertEqual(result.returncode, 1)
        self.assertIn("installed version does not match", result.stdout)
        self.assertTrue((project / "Eleven-Capital-old.apk").exists())
        self.assertFalse((downloads / "README.md").exists())
        self.assertNotIn('"am"', commands.read_text())

    def test_app_not_running_preserves_backups(self):
        project, downloads, script, _ = self.fixture(running=False)
        result = self.run_script(script)
        self.assertEqual(result.returncode, 1)
        self.assertIn("did not stay running", result.stdout)
        self.assertTrue((project / "Eleven-Capital-old.apk").exists())
        self.assertFalse((downloads / "README.md").exists())

    def test_source_edit_during_run_does_not_change_parsed_procedure(self):
        project, _, script, _ = self.fixture(edit=True)
        result = self.run_script(script)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("invalid edited body", script.read_text())
        self.assertFalse((project / "Eleven-Capital-old.apk").exists())
        self.assertIn("Installed version verified", result.stdout)

    def test_unknown_option_stops_before_tools(self):
        _, _, script, commands = self.fixture()
        result = self.run_script(script, "--unknown")
        self.assertEqual(result.returncode, 2)
        self.assertIn("Usage:", result.stdout)
        self.assertFalse(commands.exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
