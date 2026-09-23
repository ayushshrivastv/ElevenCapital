#!/bin/zsh
set -eu
# Capture the script path before a Zsh function changes $0.
project_dir="$(cd -- "$(dirname -- "$0")/.." && pwd -P)"

# Parse the entire procedure before running it. Zsh normally reads a script in
# chunks; source updates during a long build must not change an active run.
function eleven_update_main() {
# User-run completion path: run from Terminal outside an agent's restricted sandbox.
finish_only=0
if [[ "${1:-}" == --finish-only && $# == 1 ]]; then
  finish_only=1
elif (( $# > 0 )); then
  print 'Usage: update-live-preview.command [--finish-only]'
  return 2
fi
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
if ! command -v node >/dev/null; then
  print 'Node.js 22 is required. See docs/integrations/BACKEND.md.'
  exit 1
fi
node_bin="$(command -v node)"
"$node_bin" -e 'if (process.versions.node.split(".")[0] !== "22") { console.error("Node.js 22 is required."); process.exit(1); }'
export PATH="$(dirname -- "$node_bin"):$PATH"
if (( ! finish_only )); then
  print 'Checking local build and USB tooling permissions…'
  "$node_bin" "$project_dir/scripts/update-preflight.mjs"
fi
adb_bin="$HOME/Library/Android/sdk/platform-tools/adb"
manager="$project_dir/scripts/preview-service.mjs"
adb_manager="$project_dir/scripts/adb-manager.mjs"
if [[ ! -x "$adb_bin" ]]; then
  print 'Android platform-tools were not found in ~/Library/Android/sdk.'
  exit 1
fi
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
if (( ! finish_only )) && { [[ -z "${JAVA_HOME:-}" ]] || ! "$JAVA_HOME/bin/java" -version >/dev/null 2>&1; }; then
  arm_java_home='/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'
  if [[ -x "$arm_java_home/bin/java" ]]; then
    export JAVA_HOME="$arm_java_home"
  else
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  fi
fi

if (( ! finish_only )); then
print 'Checking the backend and preview controller…'
cd -- "$project_dir/backend"
if [[ ! -d node_modules ]]; then npm ci; fi
npm run check
cd -- "$project_dir"
"$node_bin" --test scripts/adb-manager.test.mjs scripts/preview-service.test.mjs

print 'Building and verifying the Android APK…'
./gradlew -PandroidBuild=true -PmarketDataUrl=http://127.0.0.1:8787 \
  :core:test :app:testDebugUnitTest \
  --tests com.elevencapital.app.LiveMarketDataParserTest \
  --tests com.elevencapital.app.LiveMarketStreamTest \
  --tests com.elevencapital.app.OrderedMarketCatalogTest \
  --tests com.elevencapital.app.MarketChartObservationTest \
  --tests com.elevencapital.app.MarketQueryTest \
  --tests 'com.elevencapital.app.LiveWalletPortfolio*' \
  --tests com.elevencapital.app.WalletPortfolioTrackerTest \
  --tests 'com.elevencapital.app.auth.*' \
  --tests 'com.elevencapital.app.purchase.*' \
  --tests 'com.elevencapital.app.wallet.*' \
  :app:assembleDebug :app:lintDebug --console=plain --max-workers=1
else
  print 'Finishing backup and cleanup for an already-installed build; no build or backend restart.'
fi
apk_source="$project_dir/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$apk_source" ]]; then
  print 'Build finished without the expected APK; installation was not attempted.'
  exit 1
fi
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify "$apk_source"
apk_metadata="$("$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging "$apk_source")"
expected_package="$(print -r -- "$apk_metadata" | awk -F "'" '/^package:/ { print $2; exit }')"
expected_version="$(print -r -- "$apk_metadata" | awk -F "'" '/^package:/ { print $4; exit }')"
if [[ "$expected_package" != com.elevencapital.app || "$expected_version" != <-> ]]; then
  print 'Unexpected APK identity. Installation and cleanup were not attempted.'
  exit 1
fi

print 'Verifying the authorized S22 and its USB reverse route…'
selected_device="${ELEVEN_ANDROID_SERIAL:-RZCW92MJRCT}"
"$node_bin" "$adb_manager" wait "$adb_bin" "$selected_device"

previous_server_remains=0
if (( ! finish_only )); then
# Removing our job only stops children we own. A previous external server is
# deliberately never killed by PID; it is reported separately below.
"$node_bin" "$manager" stop "$project_dir"
health_state='occupied'
for attempt in {1..15}; do
  health_state="$("$node_bin" "$manager" health "$project_dir")"
  [[ "$health_state" == free ]] && break
  sleep 1
done
if [[ "$health_state" == eleven ]]; then
  previous_server_remains=1
  print 'An Eleven backend started outside this service is still running. It was left unchanged.'
elif [[ "$health_state" == occupied ]]; then
  print 'Port 8787 is occupied by an unverified service. It was left unchanged.'
  print 'The APK was built but not installed. Its backend cannot start. Stop the process using that port yourself, then rerun this script.'
  exit 1
fi

"$project_dir/scripts/start-live-preview.command"
health_state='free'
for attempt in {1..20}; do
  health_state="$("$node_bin" "$manager" health "$project_dir")"
  [[ "$health_state" == eleven ]] && break
  sleep 1
done
if [[ "$health_state" != eleven ]]; then
  print 'The backend did not become healthy. Run status-live-preview.command and inspect its log.'
  exit 1
fi
# Health alone cannot distinguish an old server without streaming or wallet balance support.
# Validate catalog rows, a real WebSocket snapshot/heartbeat and the wallet route before installing.
# Checks have bounded deadlines; a cold catalog may take 45 seconds.
if ! "$node_bin" "$manager" verify-catalog "$project_dir"; then
  "$node_bin" "$manager" stop "$project_dir"
  print 'The APK was built but NOT installed: the current backend did not pass catalog, streaming and wallet compatibility checks.'
  if (( previous_server_remains )); then
    print 'The old externally started backend was left unchanged. Stop its original process, then rerun this script.'
  else
    print 'Check internet/provider availability and the preview log, then rerun this script.'
  fi
  exit 1
fi
# Re-read the exact mapping after backend startup. Success is withheld when ADB
# drops the route or another process replaces it.
"$node_bin" "$adb_manager" wait "$adb_bin" "$selected_device"
print "Installing on authorized USB phone: $selected_device"
"$adb_bin" -s "$selected_device" install -r "$apk_source"
fi
installed_version="$("$adb_bin" -s "$selected_device" shell dumpsys package com.elevencapital.app | awk '{ for (i=1; i<=NF; i++) if ($i ~ /^versionCode=/) { sub(/^versionCode=/, "", $i); print $i; exit } }')"
if [[ "$installed_version" != "$expected_version" ]]; then
  print 'The installed version does not match the verified local APK. Backup and cleanup were not attempted; rerun without --finish-only to update the phone.'
  exit 1
fi
"$adb_bin" -s "$selected_device" shell am start -W -n com.elevencapital.app/.MainActivity
if [[ -z "$("$adb_bin" -s "$selected_device" shell pidof com.elevencapital.app)" ]]; then
  print 'The updated app did not stay running. Older APKs have been kept.'
  exit 1
fi
"$node_bin" "$manager" status "$project_dir"
apk_destination="$project_dir/Eleven-Capital-purchase-preview-debug.apk"
if [[ -e "$apk_destination" ]]; then
  apk_destination="$project_dir/Eleven-Capital-purchase-preview-debug-$(date +%Y%m%d-%H%M%S)-$$.apk"
fi
cp -- "$apk_source" "$apk_destination"
print "Verified build saved to: $apk_destination"
downloads_dir="$HOME/Downloads/eleven capital"
downloads_apk="$apk_destination"
if [[ "$project_dir" != "$downloads_dir" ]]; then
  print 'Saving the updated source and verified APK in Downloads/eleven capital…'
  mkdir -p -- "$downloads_dir"
  # Keep local credentials, runtime state, caches and earlier APKs in place.
  # No deletion; the copied source is a backup, not a second running service.
  rsync -a --exclude='.git/' --exclude='.gradle/' --exclude='.gradle-wallet-check/' \
    --exclude='.kotlin/' --exclude='.tools/' --exclude='build/' --exclude='node_modules/' \
    --exclude='dist/' --exclude='local.properties' --exclude='.env' --exclude='.env.*' \
    --exclude='backend/runtime/private-transfer-intents.json' --exclude='backend/runtime/private-transfer-intents.json.tmp-*' \
    --exclude='backend/runtime/private-purchase-intents.json' --exclude='backend/runtime/private-purchase-intents.json.tmp-*' \
    --exclude='.preview-service/' --exclude='direct-check/' --exclude='*.apk' \
    "$project_dir/" "$downloads_dir/"
  downloads_apk="$downloads_dir/$(basename -- "$apk_destination")"
  if [[ -e "$downloads_apk" ]]; then
    downloads_apk="$downloads_dir/Eleven-Capital-purchase-preview-debug-$(date +%Y%m%d-%H%M%S)-$$.apk"
  fi
  cp -- "$apk_destination" "$downloads_apk"
fi
# User requested old build cleanup. Restrict removal to this app's older APKs
# directly in the two project folders, after verified installation and backup.
# Never uninstall the package or clear app data; install -r replaces it in place.
for cleanup_dir in "$project_dir" "$downloads_dir"; do
  for old_apk in "$cleanup_dir"/Eleven-Capital*.apk(N.); do
    [[ "$old_apk" == "$apk_destination" || "$old_apk" == "$downloads_apk" ]] && continue
    old_metadata="$("$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging "$old_apk" 2>/dev/null)" || continue
    old_package="$(print -r -- "$old_metadata" | awk -F "'" '/^package:/ { print $2; exit }')"
    old_version="$(print -r -- "$old_metadata" | awk -F "'" '/^package:/ { print $4; exit }')"
    if [[ "$old_package" == com.elevencapital.app && "$old_version" == <-> ]] && (( old_version <= expected_version )); then
      rm -- "$old_apk"
      print "Removed older APK: $old_apk"
    fi
  done
done
if (( previous_server_remains )); then
  print 'The existing external backend passed the current catalog, WebSocket and wallet contracts; the controller is monitoring it without taking ownership.'
  print 'To reload its process with this project’s latest code, stop its original server and rerun this script. Do not stop unrelated processes.'
fi
if (( finish_only )); then
  print 'Installed version verified, source and APK saved, older app APKs removed, and Eleven Capital opened.'
  print 'Backend and wallet health were not rechecked by --finish-only.'
else
  print 'Updated APK installed, backend health, streaming and wallet route verified, USB forwarding ready, and Eleven Capital opened.'
fi
print 'Wallet values are read automatically when the signed-in app is visible; upstream RPC/price availability is checked then.'
print 'Keep the Mac awake and USB connected. The preview job continues after this Terminal window closes.'

}
eleven_update_main "$@"
