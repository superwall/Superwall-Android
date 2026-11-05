#!/usr/bin/env bash
# Fails when SDK code reads the `Superwall.instance` service locator outside the public facade.
# Internal classes get their dependencies through constructors; framework-built entry points
# (Activities, SWWebView, Logger, error tracking) use the internal `SuperwallGraph` instead.
#
# Allowed: Superwall.kt (the facade itself), SuperwallDSL.kt (the public `configureSuperwall`
# helper returns the configured instance), comment lines, and text inside string literals.
set -euo pipefail

root="${1:-superwall/src/main/java}"

hits=$(
  grep -rnE 'Superwall\.instance' "$root" --include='*.kt' --include='*.java' \
    | grep -vE '/Superwall\.kt:|/SuperwallDSL\.kt:' \
    | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(\*|//|/\*)' \
    | sed -E 's/"([^"\\]|\\.)*"/""/g' \
    | grep -E 'Superwall\.instance' \
    || true
)

if [ -n "$hits" ]; then
  echo "Internal Superwall.instance reads found. Inject the dependency or use SuperwallGraph:" >&2
  echo "$hits" >&2
  exit 1
fi
echo "No internal Superwall.instance reads."
