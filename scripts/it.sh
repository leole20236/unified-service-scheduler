#!/usr/bin/env bash
# Runs only the integration tests matching the given pattern, e.g. ./scripts/it.sh '*Concurrency*IT'
set -euo pipefail
cd "$(dirname "$0")/.."
./mvnw -q verify -DskipUTs=true -Dit.test="$1" -Djacoco.skip=true
