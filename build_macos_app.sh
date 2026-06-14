#!/bin/zsh
set -euo pipefail

ROOT="${0:A:h}"
APP_DIR="$ROOT/dist/LAN Drop.app"
BIN_DIR="$APP_DIR/Contents/MacOS"
CONTENTS_DIR="$APP_DIR/Contents"
RESOURCES_DIR="$CONTENTS_DIR/Resources"

mkdir -p "$BIN_DIR"
mkdir -p "$RESOURCES_DIR"
cp "$ROOT/macos/Info.plist" "$CONTENTS_DIR/Info.plist"
cp "$ROOT/macos/AppIcon.icns" "$RESOURCES_DIR/AppIcon.icns"
printf "%s\n" "$ROOT" > "$RESOURCES_DIR/project-root.txt"

swiftc \
  -parse-as-library \
  -framework AppKit \
  -framework WebKit \
  "$ROOT/macos/LanDropApp.swift" \
  -o "$BIN_DIR/LanDrop"

echo "Built: $APP_DIR"
