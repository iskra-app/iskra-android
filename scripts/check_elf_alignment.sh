#!/bin/sh
# Каждый LOAD-сегмент каждой .so в APK выровнен минимум на 16 KB (docs/PROMPT.md, 19).
#
# `zipalign -P 16` проверяет только то, где .so лежат внутри архива. Телефон на Android 15+ с 16 KB
# страницами смотрит ещё и в саму библиотеку: сегмент, собранный под 4 KB (`align 2**12`), не
# отобразится, и приложение упадёт при загрузке — у SDK, у :tunnel и у любой зависимости с нативным кодом.
#
#   scripts/check_elf_alignment.sh app/build/outputs/apk/*/release/*.apk
#
# llvm-objdump берётся из NDK: $ANDROID_NDK_HOME, иначе самый новый в $ANDROID_HOME/ndk.
set -eu

objdump=""
for candidate in "${ANDROID_NDK_HOME:-}" $(ls -d "${ANDROID_HOME:-/nonexistent}"/ndk/* 2>/dev/null | sort -r); do
    [ -n "$candidate" ] || continue
    found=$(ls "$candidate"/toolchains/llvm/prebuilt/*/bin/llvm-objdump 2>/dev/null | head -1)
    if [ -n "$found" ]; then objdump=$found; break; fi
done
[ -n "$objdump" ] || { echo "check_elf_alignment: нет llvm-objdump из NDK (ANDROID_NDK_HOME или ANDROID_HOME/ndk)" >&2; exit 2; }

[ $# -gt 0 ] || { echo "check_elf_alignment: какие APK?" >&2; exit 64; }

scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
bad=0
checked=0
for apk in "$@"; do
    rm -rf "$scratch/lib"
    unzip -q -o "$apk" 'lib/*' -d "$scratch" 2>/dev/null || continue
    for so in $(find "$scratch/lib" -name '*.so'); do
        checked=$((checked + 1))
        # «LOAD off … align 2**N» — N меньше 14 значит страница меньше 16 KB.
        small=$("$objdump" -p "$so" | awk '/LOAD/ { for (i = 1; i <= NF; i++) if ($i == "align") { split($(i + 1), p, "\\*\\*"); if (p[2] < 14) print $(i + 1) } }')
        if [ -n "$small" ]; then
            echo "$(basename "$apk"): ${so#"$scratch"/} — LOAD выровнен на $(echo "$small" | sort -u | tr '\n' ' ')" >&2
            bad=1
        fi
    done
done
[ "$checked" -gt 0 ] || { echo "check_elf_alignment: в APK нет ни одной .so — нечего проверять" >&2; exit 1; }
[ "$bad" -eq 0 ] && echo "check_elf_alignment: $checked .so, все LOAD-сегменты на 16 KB и больше"
exit "$bad"
