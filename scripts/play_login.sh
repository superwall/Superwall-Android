#!/bin/bash
#
# Signs the connected emulator's Play Store in to the test Google account, with the Maestro flow
# in test_app/maestro/login.yaml. The screenshot and purchase tests need this: without an account
# Play billing is unavailable and paywalls with products don't present.
#
# Credentials come from TEST_USER and TEST_PW, read from the environment or from a .env file in
# the repository root (git-ignored):
#
#   TEST_USER=someone@example.com
#   TEST_PW=...
#
# Needs maestro and adb on the PATH, and a "Google Play" system image. Does nothing when the
# device already has a Google account. Set ANDROID_SERIAL to pick one of several devices.

set -euo pipefail
cd "$(dirname "$0")/.."

# Read only the two keys, as plain text, so a .env with other content isn't run as a script.
read_env() {
  [ -f .env ] || return 0
  sed -n "s/^[[:space:]]*\(export[[:space:]]\{1,\}\)\{0,1\}$1[[:space:]]*=[[:space:]]*//p" .env |
    tail -n 1 | sed -e 's/[[:space:]]*$//' -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"
}
TEST_USER="${TEST_USER:-$(read_env TEST_USER)}"
TEST_PW="${TEST_PW:-$(read_env TEST_PW)}"

if [ -z "$TEST_USER" ] || [ -z "$TEST_PW" ]; then
  echo "Set TEST_USER and TEST_PW in the environment or in .env." >&2
  exit 1
fi
for tool in adb maestro; do
  command -v "$tool" >/dev/null || { echo "$tool is not on the PATH." >&2; exit 1; }
done

has_google_account() {
  # "Account {name=…, type=com.google}". The authenticator is listed with the same type on
  # every device, so match the account line only.
  adb shell dumpsys account | grep -q "Account {.*type=com.google}"
}

if has_google_account; then
  echo "The device already has a Google account."
  exit 0
fi
if ! adb shell pm list packages | grep -q "com.android.vending"; then
  echo "This device has no Play Store. Use a \"Google Play\" system image." >&2
  exit 1
fi

# Keeping the credentials out of sight:
# - They go to Maestro as MAESTRO_* environment variables, not as -e arguments, which any
#   process on the machine could read from the process list.
# - Maestro's console output shows the variable names, not the values. But its debug folder
#   (commands.json, maestro.log, the screen hierarchy) holds what was typed in plain text, so it
#   is sent to a temporary folder that is always deleted. Only the screenshots are kept after a
#   failure; they can show the email address, and the password field as dots.
debug=$(mktemp -d)
kept=build/play-login
cleanup() {
  status=$?
  if [ "$status" -ne 0 ] && [ -d "$debug" ]; then
    rm -rf "$kept" && mkdir -p "$kept"
    find "$debug" -name '*.png' -exec cp {} "$kept/" \; 2>/dev/null || true
    echo "Screenshots of the failed sign-in are in $kept." >&2
  fi
  rm -rf "$debug"
  exit "$status"
}
trap cleanup EXIT

# The flow starts by tapping the Play Store icon on the home screen.
adb shell input keyevent KEYCODE_HOME
# ${device[@]+...}: bash 3.2 on macOS treats an empty array as unset under `set -u`.
device=()
[ -n "${ANDROID_SERIAL:-}" ] && device=(--device "$ANDROID_SERIAL")
MAESTRO_TEST_USER="$TEST_USER" MAESTRO_TEST_PW="$TEST_PW" \
  maestro ${device[@]+"${device[@]}"} test --debug-output "$debug" --flatten-debug-output \
  test_app/maestro/login_from_env.yaml

if ! has_google_account; then
  echo "The flow finished but the device has no Google account. Google may have asked for a" >&2
  echo "verification step; look at the emulator." >&2
  exit 1
fi
echo "Signed in."
