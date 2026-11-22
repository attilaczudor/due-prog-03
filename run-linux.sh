#!/usr/bin/env bash
set -euo pipefail

# ReWear WMS (due-prog-03) — Linux Bootstrap Launcher
# Automatically detects Java 17+ or downloads a portable Adoptium Temurin JDK 17 into ./.runtime/jdk-17.

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

JAR_PATH="$DIR/target/due-prog-03.jar"
if [[ ! -f "$JAR_PATH" && -f "$DIR/due-prog-03.jar" ]]; then
  JAR_PATH="$DIR/due-prog-03.jar"
fi

if [[ ! -f "$JAR_PATH" ]]; then
  echo "[ReWear WMS] JAR not found at $JAR_PATH. Run 'mvn package' first."
  exit 1
fi

find_java17() {
  local candidates=()
  [[ -n "${JAVA_HOME:-}" ]] && candidates+=("$JAVA_HOME/bin/java")
  [[ -x "$DIR/.runtime/jdk-17/bin/java" ]] && candidates+=("$DIR/.runtime/jdk-17/bin/java")
  command -v java >/dev/null 2>&1 && candidates+=("$(command -v java)")

  for j in "${candidates[@]}"; do
    if [[ -x "$j" ]]; then
      local ver
      ver="$("$j" -version 2>&1 | awk -F '"' '/version/ {print $2}' | awk -F '.' '{print ($1=="1"?$2:$1)}')"
      if [[ "${ver:-0}" -ge 17 ]]; then
        echo "$j"
        return 0
      fi
    fi
  done
  return 1
}

JAVA_BIN="$(find_java17 || true)"

if [[ -z "$JAVA_BIN" ]]; then
  ARCH="$(uname -m)"
  if [[ "$ARCH" == "aarch64" || "$ARCH" == "arm64" ]]; then
    ADOPTIUM_ARCH="aarch64"
  else
    ADOPTIUM_ARCH="x64"
  fi
  echo "[ReWear WMS] Java 17+ not found. Downloading portable Temurin JDK 17 ($ADOPTIUM_ARCH)..."
  mkdir -p "$DIR/.runtime"
  TMP_TGZ="$DIR/.runtime/temurin17.tar.gz"
  curl -fL --progress-bar \
    "https://api.adoptium.net/v3/binary/latest/17/ga/linux/${ADOPTIUM_ARCH}/jdk/hotspot/normal/eclipse" \
    -o "$TMP_TGZ"
  rm -rf "$DIR/.runtime/jdk-17"
  mkdir -p "$DIR/.runtime/jdk-17"
  tar -xzf "$TMP_TGZ" -C "$DIR/.runtime/jdk-17" --strip-components=1
  rm -f "$TMP_TGZ"
  JAVA_BIN="$DIR/.runtime/jdk-17/bin/java"
fi

echo "[ReWear WMS] Launching with $JAVA_BIN ..."
exec "$JAVA_BIN" -jar "$JAR_PATH"
