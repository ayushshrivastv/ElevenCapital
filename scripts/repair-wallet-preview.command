#!/bin/zsh
set -eu

# Capture before entering a function: zsh may change $0 to the function name.
typeset -r eleven_repair_entry="${0:A}"

# Parse the complete procedure before execution, so edits during a long check
# cannot change the shell program that is already running.
function eleven_repair_main() {
  if (( $# > 0 )); then
    print 'Usage: repair-wallet-preview.command'
    return 2
  fi
  export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
  project_dir="$(cd -- "$(dirname -- "$eleven_repair_entry")/.." && pwd -P)"
  node_bin="$(command -v node)"
  "$node_bin" -e 'if (process.versions.node.split(".")[0] !== "22") { console.error("Node.js 22 is required."); process.exit(1); }'
  export PATH="$(dirname -- "$node_bin"):$PATH"
  adb_bin="$HOME/Library/Android/sdk/platform-tools/adb"
  manager="$project_dir/scripts/preview-service.mjs"
  adb_manager="$project_dir/scripts/adb-manager.mjs"
  if [[ ! -x "$adb_bin" ]]; then
    print 'Android platform-tools were not found in ~/Library/Android/sdk.'
    return 1
  fi

  print 'Checking and rebuilding the wallet backend; the installed Android app is kept…'
  cd -- "$project_dir/backend"
  if [[ ! -d node_modules ]]; then npm ci; fi
  npm run check
  cd -- "$project_dir"

  print 'Restarting this project’s owned preview service with the corrected Solana check…'
  "$node_bin" "$manager" stop "$project_dir"
  backend_state='occupied'
  for attempt in {1..20}; do
    backend_state="$("$node_bin" "$manager" health "$project_dir")"
    [[ "$backend_state" == free ]] && break
    sleep 1
  done
  if [[ "$backend_state" != free ]]; then
    print 'Another server still occupies port 8787. It was left unchanged; stop its original Terminal process before retrying this repair.'
    return 1
  fi
  "$node_bin" "$manager" start "$project_dir" "$adb_bin"
  "$node_bin" "$manager" wait-ready "$project_dir"
  "$node_bin" "$adb_manager" wait "$adb_bin" "${ELEVEN_ANDROID_SERIAL:-RZCW92MJRCT}"
  "$node_bin" "$manager" verify-catalog "$project_dir"

  print 'Checking the running wallet endpoint with synthetic public addresses…'
  diagnostic_file="$project_dir/verification/wallet-repair/live-probe.json"
  mkdir -p -- "$(dirname -- "$diagnostic_file")"
  wallet_probe_exit=0
  "$node_bin" "$project_dir/backend/dist/portfolio-probe.js" > "$diagnostic_file" || wallet_probe_exit=$?
  cat -- "$diagnostic_file"
  print "Wallet diagnostic saved to: $diagnostic_file"

  print 'Completing the earlier source/APK backup and old-build cleanup…'
  "$project_dir/scripts/update-live-preview.command" --finish-only
  "$node_bin" "$manager" status "$project_dir"
  if (( wallet_probe_exit != 0 )); then
    print 'The corrected backend is running, but the wallet probe still reports an upstream or catalog issue. The diagnostic output above identifies the remaining issue; no zero balance was fabricated.'
    return "$wallet_probe_exit"
  fi
  "$node_bin" "$adb_manager" wait "$adb_bin" "${ELEVEN_ANDROID_SERIAL:-RZCW92MJRCT}"
  print 'Wallet backend probe and S22 USB forwarding passed. Leave Eleven Capital open on the S22; its balance will refresh automatically.'
}

eleven_repair_main "$@"
