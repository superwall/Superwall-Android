#!/bin/bash
#
# Takes the screens a Firebase Test Lab run captured and makes them the new baselines.
#
# When a comparison fails, the test leaves the screen as it was captured on the device, and Test
# Lab copies it to the run's results bucket (directories-to-pull in .github/firebase-tests.yml).
# So after a run that failed because the UI changed on purpose, the new Pixel 7 baselines are
# already in the bucket. Look at them before committing: this accepts whatever was on screen.
#
# Usage: scripts/pull_firebase_screenshots.sh gs://<bucket>/<results dir>
# The path is in the Test Lab output: "Raw results will be stored in your GCS bucket at [...]".
# Needs gsutil, signed in to the project.

set -euo pipefail

if [ $# -ne 1 ] || [[ "$1" != gs://* ]]; then
  echo "Usage: $0 gs://<bucket>/<results dir>" >&2
  exit 1
fi

cd "$(dirname "$0")/.."
results="${1%/}"
baselines=app/src/androidTest/screenshots
incoming=$(mktemp -d)
trap 'rm -rf "$incoming"' EXIT

gsutil -m cp "$results/**/screenshots/com.superwall.superapp/reference/*.png" "$incoming/"

count=$(find "$incoming" -name '*.png' | wc -l | tr -d ' ')
if [ "$count" -eq 0 ]; then
  echo "No captured screens under $results. Only failed comparisons leave one." >&2
  exit 1
fi

cp "$incoming"/*.png "$baselines/"
echo "Updated $count baselines in $baselines:"
git status --short -- "$baselines"
