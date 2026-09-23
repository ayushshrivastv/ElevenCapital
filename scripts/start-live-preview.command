#!/bin/zsh
set -eu

export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
project_dir="$(cd -- "$(dirname -- "$0")/.." && pwd -P)"
if ! command -v node >/dev/null; then
  print 'Node.js 22 is required. See docs/integrations/BACKEND.md.'
  exit 1
fi
node_bin="$(command -v node)"
"$node_bin" -e 'if (process.versions.node.split(".")[0] !== "22") { console.error("Node.js 22 is required."); process.exit(1); }'
export PATH="$(dirname -- "$node_bin"):$PATH"
manager="$project_dir/scripts/preview-service.mjs"
adb_manager="$project_dir/scripts/adb-manager.mjs"
adb_bin="$HOME/Library/Android/sdk/platform-tools/adb"

print 'Checking the S22 and repairing its USB reverse route when safe…'
"$node_bin" "$adb_manager" wait "$adb_bin"

if "$node_bin" "$manager" running "$project_dir"; then
  "$node_bin" "$manager" wait-ready "$project_dir"
  "$node_bin" "$adb_manager" wait "$adb_bin"
  "$node_bin" "$manager" status "$project_dir"
  print 'Preview service already active. S22 USB forwarding is verified and checked every 5 seconds.'
  exit 0
fi

cd -- "$project_dir/backend"
if [[ ! -d node_modules ]]; then npm ci; fi
npm run build
"$node_bin" "$manager" start "$project_dir" "$adb_bin"
"$node_bin" "$manager" wait-ready "$project_dir"
"$node_bin" "$adb_manager" wait "$adb_bin"
print 'The preview service now runs for this login session, even after Terminal closes.'
print 'S22 USB forwarding tcp:8787 -> tcp:8787 is verified. Reopen Eleven Capital.'
print 'Use stop-live-preview.command to stop it. It will not restart automatically after logout.'
