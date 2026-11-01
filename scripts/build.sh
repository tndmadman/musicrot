#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Emit Java 20 class files even when building on JDK 21+.
rm -rf build
mkdir -p build
javac --release 20 -encoding UTF-8 -d build src/chordflow/*.java
java -Djava.awt.headless=true -cp build chordflow.Main selftest
