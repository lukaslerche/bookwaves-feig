#!/usr/bin/env bash

set -euo pipefail

mvn -q compile dependency:copy-dependencies

LD_LIBRARY_PATH="$PWD/native/linux.x64" \
CONFIG_FILE_PATH="$PWD/config.yaml" \
java -cp "target/classes:target/dependency/*:libs/*" de.bookwaves.Main
