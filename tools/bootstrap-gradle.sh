#!/usr/bin/env bash
# Bootstrap the Gradle wrapper for a machine that has NO gradle installed (docs/04 P0-1).
#
#   The repo ships gradle/wrapper/gradle-wrapper.properties, but the wrapper JAR and the
#   gradlew scripts are binaries/scripts produced by a real Gradle run. This script produces
#   them once; after that `./gradlew ...` is self-contained and repeatable.
#
# Requires network access to services.gradle.org (the ONLY reason P0-1 needs the network).
set -euo pipefail

GRADLE_VERSION="${GRADLE_VERSION:-8.13}"
JAVA_HOME_DEFAULT="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

export JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_DEFAULT}"
export PATH="$JAVA_HOME/bin:$PATH"

if ! "$JAVA_HOME/bin/java" -version >/dev/null 2>&1; then
  echo "ERROR: no usable JDK at $JAVA_HOME. Set JAVA_HOME to a real JDK (this box has none)." >&2
  exit 1
fi

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/gradle-bootstrap.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT

ZIP="$WORK_DIR/gradle-${GRADLE_VERSION}-bin.zip"
URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
echo "==> downloading $URL"
curl -fL --retry 3 -o "$ZIP" "$URL"

echo "==> unzipping"
unzip -q "$ZIP" -d "$WORK_DIR"
GRADLE_BIN="$WORK_DIR/gradle-${GRADLE_VERSION}/bin/gradle"

echo "==> generating the wrapper in $REPO_DIR"
cd "$REPO_DIR"
"$GRADLE_BIN" --no-daemon wrapper --gradle-version "$GRADLE_VERSION" --distribution-type bin

echo "==> verifying"
./gradlew --version
echo "OK: ./gradlew is ready. Next: ./gradlew assembleDebug"
