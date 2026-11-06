#!/usr/bin/env bash
# Runs the transport scenarios on an Android emulator against the fixture server
# on the host (:8791, reached as 10.0.2.2), and saves each run's app log.
#   run-emulator-scenarios.sh <serial> <out-dir>
set -uo pipefail
S="$1"; OUT="$2"; P=com.superwall.support.transporttest
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb} -s $S"
DIRECT=http://localhost:8791
mkdir -p "$OUT"

applog() { $ADB shell run-as $P cat files/support-transport.log; }
launch() { $ADB shell am force-stop $P; $ADB shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $P/com.superwallsupport.testapp.MainActivity "$@" >/dev/null; }
token() { applog | grep -o 'token tok_[A-Z0-9]*' | awk '{print $2}'; }
conv() { applog | grep -o 'conversation conv_[A-Z0-9]*' | awk '{print $2}'; }
post() {
  local n=$1 prefix=$2 t c; t=$(token); c=$(conv)
  for i in $(seq 1 "$n"); do
    curl -s -o /dev/null -X POST "$DIRECT/v1/conversations/$c/messages" -H "authorization: Bearer $t" \
      -H 'content-type: application/json' -d "{\"clientMessageId\":\"$prefix-$i\",\"text\":\"host $i\"}"
  done
}
background() { $ADB shell input keyevent KEYCODE_HOME; }
# Like tapping the launcher icon: brings the existing task back, no new activity.
foreground() { $ADB shell monkey -p $P -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; }
airplane() { $ADB shell cmd connectivity airplane-mode "$1"; }
save() { applog > "$OUT/$1.log"; echo "== $1"; grep -E "verify|state (Live|Idle|Offline)" "$OUT/$1.log" | tail -3 | cut -c1-220; }

# 1. The transport cuts its own socket after every new seq while 20 sends land.
launch --ez dropEverySeq true --ei sendBurst 20 --ei verifyEvery 2
sleep 16; save 1-drop-every-seq

# 2. Airplane mode on mid-burst for 8 s, then off: a real OS network loss.
launch --ei sendBurst 15 --ei verifyEvery 2
sleep 0.5; airplane enable; sleep 8; airplane disable
sleep 12; save 2-airplane-mode

# 3. HOME for 15 s (Android cuts background network ~5 s in) while another device posts 10.
launch --ei verifyEvery 2
sleep 3; background; sleep 2; post 10 bg; sleep 13; foreground
sleep 6; save 3-background-foreground

# 4. Far behind: 75 messages land while backgrounded, more than the socket replay cap (60).
launch --ei verifyEvery 2
sleep 3; background; sleep 1; post 75 far; foreground
sleep 8; save 4-far-behind-http-catchup

# 5. A paced fixture script (tails, an unknown kind) with the socket cut after every seq.
launch --es apiKey pk_fixture_future-kinds --ez dropEverySeq true --ei verifyEvery 2
sleep 12; save 5-fixture-future-kinds
