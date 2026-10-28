#!/usr/bin/env bash
# Runs only the integration tests matching the given pattern, e.g. ./scripts/it.sh '*Concurrency*IT'
# Every pattern also matches the @Nested classes of the tests it names (HoldApiIT -> HoldApiIT$*).
set -euo pipefail
cd "$(dirname "$0")/.."
patterns=""
IFS=',' read -ra parts <<< "$1"
for p in "${parts[@]}"; do
  patterns="${patterns:+$patterns,}$p,$p\$*"
done
./mvnw -q verify -DskipUTs=true -Dit.test="$patterns" -Djacoco.skip=true
