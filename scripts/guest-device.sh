#!/bin/bash
# Runs IN THE VM (called by `scripts/vm device`). Signing needs the GUI session's keychain,
# so this hands the build to Terminal.app and follows its log.
# usage: bash scripts/guest-device.sh <derived-data-path>
set -euo pipefail
dd=${1:?derived data path}
here=$(pwd)
log=/tmp/sevens-device.log; status=/tmp/sevens-device.status
rm -f "$log" "$status"
udid=$(xcrun devicectl list devices --json-output /dev/stdout 2>/dev/null | python3 -c '
import json, sys
d = json.load(sys.stdin)["result"]["devices"]
for x in d:
    if x.get("hardwareProperties", {}).get("platform") == "iOS" and x.get("connectionProperties", {}).get("pairingState") == "paired":
        print(x["hardwareProperties"]["udid"]); break
')
[ -n "$udid" ] || { echo "no paired iPhone"; exit 4; }
echo "$udid" > ~/.sevens-device-udid
cmdfile=$here/.sevens-device.command
cat > "$cmdfile" <<CMD
#!/bin/bash
cd "$here"
{
  xcodebuild -project apps/qr/QR.xcodeproj -scheme QR -configuration Debug \
    -destination "platform=iOS,id=$udid" -derivedDataPath "$dd-device" -allowProvisioningUpdates build \
  && app=\$(ls -d "$dd-device"/Build/Products/Debug-iphoneos/QR.app) \
  && xcrun devicectl device install app --device "$udid" "\$app"
  echo \$? > "$status"
} > "$log" 2>&1
exit
CMD
chmod +x "$cmdfile"
open -a Terminal "$cmdfile"
for _ in $(seq 1 180); do [ -f "$status" ] && break; sleep 5; done
cat "$log"
s=$(cat "$status" 2>/dev/null || echo 124)
echo "device run exit $s"
exit "$s"
