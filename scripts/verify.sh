#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
"${MAVEN:-mvn}" ${MAVEN_ARGS:-} verify
swift build --package-path macos -c release
