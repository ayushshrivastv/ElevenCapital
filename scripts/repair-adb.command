#!/bin/zsh
set -eu

# Explicit recovery path for a stale ADB daemon or a conflicting Eleven Capital
# reverse mapping. Run this from normal macOS Terminal, where local sockets and
# USB access are available.
typeset -r project_dir="$(cd -- "$(dirname -- "$0")/.." && pwd -P)"
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"

if (( $# > 0 )); then
  print 'Usage: repair-adb.command'
  exit 2
fi
if ! command -v node >/dev/null; then
  print 'Node.js 22 is required. See docs/integrations/BACKEND.md.'
  exit 1
fi
node_bin="$(command -v node)"
"$node_bin" -e 'if (process.versions.node.split(".")[0] !== "22") { console.error("Node.js 22 is required."); process.exit(1); }'
adb_bin="$HOME/Library/Android/sdk/platform-tools/adb"

print 'Repairing ADB and the Eleven Capital USB route for the S22…'
"$node_bin" "$project_dir/scripts/adb-manager.mjs" repair "$adb_bin"

print 'Starting and verifying the Eleven Capital backend…'
"$project_dir/scripts/start-live-preview.command"

print 'ADB, S22 USB forwarding, and the Eleven Capital backend are ready.'
