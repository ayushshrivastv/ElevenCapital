"""Isolated repair orchestration checks; no real backend, phone or network."""
from pathlib import Path
import json, os, shlex, subprocess, sys, tempfile, unittest

SOURCE = Path(__file__).with_name('repair-wallet-preview.command').read_text()

class WalletRepairTest(unittest.TestCase):
    def run_repair(self, probe_exit=0, occupied=False, catalog_exit=0, usb_target='tcp:8787'):
        temporary = tempfile.TemporaryDirectory(prefix='eleven-repair-test-')
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        project = root/'project with spaces'
        scripts = project/'scripts'; scripts.mkdir(parents=True)
        (project/'backend/node_modules').mkdir(parents=True)
        commands = root/'calls.jsonl'
        binaries=root/'bin'; binaries.mkdir()
        def executable(path,body):
            path.write_text('#!'+sys.executable+'\n'+body); path.chmod(0o755)
        executable(binaries/'node', f'''
import json,sys
from pathlib import Path
args=sys.argv[1:]
with Path({str(commands)!r}).open('a') as f: f.write(json.dumps(['node']+args)+'\\n')
if args[0]=='-e': raise SystemExit(0)
if args[0].endswith('portfolio-probe.js'): raise SystemExit({probe_exit})
if args[0].endswith('adb-manager.mjs'):
    if {usb_target!r} != 'tcp:8787':
        print('ADB reverse route is not ready', file=sys.stderr)
        raise SystemExit(1)
    print('ADB ready: S22 RZCW92MJRCT is connected by USB; tcp:8787 -> tcp:8787 verified.')
    raise SystemExit(0)
action=args[1]
if action=='health': print({'eleven' if occupied else 'free'!r})
if action=='verify-catalog': raise SystemExit({catalog_exit})
''')
        executable(binaries/'npm',f"import json,sys\nfrom pathlib import Path\nwith Path({str(commands)!r}).open('a') as f: f.write(json.dumps(['npm']+sys.argv[1:])+'\\n')\n")
        executable(binaries/'fake-adb',"raise SystemExit('fake adb should be reached only through the tested manager')\n")
        executable(scripts/'update-live-preview.command',f"import json,sys\nfrom pathlib import Path\nwith Path({str(commands)!r}).open('a') as f: f.write(json.dumps(['finish']+sys.argv[1:])+'\\n')\n")
        source=SOURCE.replace('export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"','export PATH='+shlex.quote(str(binaries))+':"$PATH"')
        source=source.replace('adb_bin="$HOME/Library/Android/sdk/platform-tools/adb"','adb_bin='+shlex.quote(str(binaries/'fake-adb')))
        source=source.replace('{1..20}','{1..2}').replace('{1..10}','{1..2}').replace('sleep 1','sleep 0')
        script=scripts/'repair-wallet-preview.command'; script.write_text(source)
        result=subprocess.run(['/bin/zsh',str(script)],text=True,capture_output=True,timeout=10)
        events=[json.loads(line) for line in commands.read_text().splitlines()]
        return result,events

    def test_success_checks_backend_then_finishes_without_building_android(self):
        result,events=self.run_repair()
        self.assertEqual(result.returncode,0,result.stdout+result.stderr)
        actions=[e[2] for e in events if e[0]=='node' and e[1].endswith('preview-service.mjs')]
        self.assertEqual(actions,['stop','health','start','wait-ready','verify-catalog','status'])
        self.assertIn(['npm','run','check'],events)
        self.assertEqual([e for e in events if e[0]=='finish'],[['finish','--finish-only']])
        self.assertLess(next(i for i,e in enumerate(events) if e[0]=='node' and e[1].endswith('portfolio-probe.js')),events.index(['finish','--finish-only']))
        self.assertIn('Wallet backend probe and S22 USB forwarding passed',result.stdout)

    def test_failed_wallet_probe_still_finishes_backup_but_reports_failure(self):
        result,events=self.run_repair(probe_exit=1)
        self.assertEqual(result.returncode,1)
        self.assertIn(['finish','--finish-only'],events)
        self.assertIn('still reports an upstream or catalog issue',result.stdout)
        self.assertNotIn('Leave Eleven Capital open',result.stdout)

    def test_success_is_withheld_when_s22_forward_is_conflicting_or_missing(self):
        for target in ('tcp:9999',''):
            with self.subTest(target=target):
                result,events=self.run_repair(usb_target=target)
                self.assertEqual(result.returncode,1)
                self.assertNotIn(['finish','--finish-only'],events)
                self.assertNotIn('Leave Eleven Capital open',result.stdout)

    def test_external_server_is_not_replaced(self):
        result,events=self.run_repair(occupied=True)
        self.assertEqual(result.returncode,1)
        self.assertIn('Another server still occupies',result.stdout)
        self.assertFalse(any(e[0]=='node' and len(e)>2 and e[2]=='start' for e in events))
        self.assertFalse(any(e[0]=='finish' for e in events))

    def test_incompatible_backend_cannot_continue_to_backup_cleanup(self):
        result,events=self.run_repair(catalog_exit=1)
        self.assertEqual(result.returncode,1)
        self.assertFalse(any(e[0]=='finish' for e in events))

if __name__=='__main__': unittest.main(verbosity=2)
