#!/usr/bin/env bash
set -e

# Start radiale from the current directory or RADIALE_HOME
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${RADIALE_HOME:-$SCRIPT_DIR}"

exec clojure -J--add-opens=java.base/java.nio=ALL-UNNAMED -i config/setup.clj
