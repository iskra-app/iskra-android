#!/bin/sh
# Привозит общее из iOS-репозитория с привязкой к коммиту (docs/PROMPT.md, 2.3).
#
#   scripts/sync-from-ios.sh <путь-к-iskra> [rev] [--bridge <путь-к-iskra-bridge>] [--bridge-rev <rev>]
#
# Идемпотентен: CI запускает его и проверяет, что дерево не изменилось.
set -eu
exec python3 "$(dirname "$0")/sync_from_ios.py" "$@"
