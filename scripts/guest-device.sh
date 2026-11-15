#!/bin/bash
# Runs IN THE VM (called by `scripts/vm device|ui --device|lock-device`). Signing needs the GUI
# session's keychain (runbook: signing over SSH fails), so this hands xcodebuild to Terminal.app
# and follows its log.
# env: SEVENS_APP, SEVENS_SCHEME (default qr / QR)
# usage: bash scripts/guest-device.sh <derived-data-path> install
#        bash scripts/guest-device.sh <derived-data-path> test <extra xcodebuild args...>
set -euo pipefail
dd=${1:?derived data path}; mode=${2:-install}; shift 2 || true
here=$(pwd)
log=/tmp/sevens-device.log; status=/tmp/sevens-device.status
rm -f "$log" "$status"
xcrun devicectl list devices --json-output /tmp/sevens-devices.json >/dev/null 2>&1 || true
udid=$(python3 -c '
import json
d = json.load(open("/tmp/sevens-devices.json"))["result"]["devices"]
for x in d:
    if x.get("hardwareProperties", {}).get("platform") == "iOS" and x.get("connectionProperties", {}).get("pairingState") == "paired":
        print(x["hardwareProperties"]["udid"]); break
' 2>/dev/null || true)
[ -n "$udid" ] || { echo "no paired iPhone"; exit 4; }
echo "$udid" > ~/.sevens-device-udid
app=${SEVENS_APP:-qr}; scheme=${SEVENS_SCHEME:-QR}
xb="xcodebuild -project apps/$app/$scheme.xcodeproj -scheme $scheme -configuration Debug -destination platform=iOS,id=$udid -derivedDataPath $dd-device -allowProvisioningUpdates"
if [ "$mode" = install ]; then
  run="$xb build && xcrun devicectl device install app --device $udid \"\$(ls -d $dd-device/Build/Products/Debug-iphoneos/$scheme.app)\""
else
  run="$xb -parallel-testing-enabled NO $* test"
fi
cmdfile=$here/.sevens-device.command
cat > "$cmdfile" <<CMD
#!/bin/bash
cd "$here"
{ $run; echo \$? > "$status"; } > "$log" 2>&1
exit
CMD
chmod +x "$cmdfile"
open -a Terminal "$cmdfile"
for _ in $(seq 1 240); do [ -f "$status" ] && break; sleep 5; done
cat "$log"
s=$(cat "$status" 2>/dev/null || echo 124)
echo "device run exit $s"
exit "$s"
