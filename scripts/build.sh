#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
MAVEN="${MAVEN:-mvn}"
"$MAVEN" ${MAVEN_ARGS:-} package
swift build --package-path macos -c release
BIN_DIR=$(swift build --package-path macos -c release --show-bin-path)
APP="dist/StudyFlow.app"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN_DIR/StudyFlow" "$APP/Contents/MacOS/StudyFlow"
cp "$BIN_DIR/CredentialHelper" "$APP/Contents/Resources/CredentialHelper"
cp backend/target/studyflow-backend-1.0.0.jar "$APP/Contents/Resources/studyflow-backend.jar"
cp macos/Info.plist "$APP/Contents/Info.plist"
if [[ -n "${STUDYFLOW_BUNDLE_JAVA_HOME:-}" ]]; then
  "$STUDYFLOW_BUNDLE_JAVA_HOME/bin/jlink" --add-modules java.base,java.sql,java.net.http,jdk.httpserver,java.naming,jdk.crypto.ec --strip-debug --no-header-files --no-man-pages --output "$APP/Contents/Resources/runtime"
fi
codesign --force --deep --sign - "$APP"
printf 'Built %s\n' "$APP"
