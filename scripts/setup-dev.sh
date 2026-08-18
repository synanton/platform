#!/usr/bin/env bash
# Verifies the local toolchain needed to develop the Synanton demo.
# Exits non-zero if anything required is missing.

set -euo pipefail

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
yellow(){ printf '\033[33m%s\033[0m\n' "$*"; }

missing=0

require() {
  local name="$1" min_hint="$2"
  if command -v "$name" >/dev/null 2>&1; then
    green "✓ $name found: $(command -v "$name")"
  else
    red "✗ $name not found ($min_hint)"
    missing=$((missing + 1))
  fi
}

optional() {
  local name="$1" hint="$2"
  if command -v "$name" >/dev/null 2>&1; then
    green "✓ $name found: $(command -v "$name")"
  else
    yellow "○ $name not found ($hint)"
  fi
}

echo "─── required ───"
require java   "JDK 21+ (e.g. 'brew install openjdk@21')"
require docker "Docker Engine (e.g. Docker Desktop or 'brew install --cask docker')"

echo
echo "─── for UI work ───"
optional node "Node 20+ (e.g. 'brew install node')"
optional pnpm "pnpm 9+ (e.g. 'npm i -g pnpm')"

echo
echo "─── optional ───"
optional cargo "Rust toolchain (e.g. 'curl https://sh.rustup.rs -sSf | sh')"

echo
if [[ "$missing" -gt 0 ]]; then
  red "Missing $missing required tool(s)."
  exit 1
fi
green "Toolchain OK."
