#!/usr/bin/env bash
# One-command on-sale stampede + correctness report.
#   ./burst.sh <BASE_URL> [--admin-key K] [--total 20000] [--concurrency 1000] [--storm 500] [--hot-seats 5]
# Needs a JDK 21+ (single-file source launch, no build step, no dependencies).
set -euo pipefail
cd "$(dirname "$0")"
if [[ $# -lt 1 ]]; then
  echo "usage: ./burst.sh <BASE_URL> [options]   e.g. ./burst.sh http://localhost:8080" >&2
  exit 2
fi
JAVA=java
if [[ -n "${JAVA_HOME:-}" ]]; then JAVA="$JAVA_HOME/bin/java"; fi
if ! "$JAVA" -version 2>&1 | grep -qE 'version "(2[1-9]|[3-9][0-9])'; then
  for candidate in /opt/homebrew/opt/openjdk@21/bin/java /usr/lib/jvm/java-21-openjdk*/bin/java; do
    [[ -x "$candidate" ]] && JAVA="$candidate" && break
  done
fi
ulimit -n 10240 2>/dev/null || true
exec "$JAVA" ../burst/Burst.java "$@"