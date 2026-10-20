#!/usr/bin/env python3
"""release.json и текст релиза из только что подписанных APK (docs/PROMPT.md, 19.1).

Никогда руками: версия, имена, размеры и хэши — из файлов, сертификат — из apksigner. Отказывает, если
«Что нового» не на всех семи языках, если APK подписаны разными ключами, если версии в них разные или
если отпечаток не совпал с ожидаемым (`--expect-cert`, переменная CI `ISKRA_CERT_SHA256`).

    scripts/release_json.py --check-notes release-notes/1.4.0.json
    scripts/release_json.py --dist dist --notes release-notes/1.4.0.json \\
        --apksigner $ANDROID_HOME/build-tools/37.0.0/apksigner \\
        --aapt2 $ANDROID_HOME/build-tools/37.0.0/aapt2 --expect-cert <sha256>

В `--dist` лежат `iskra-<версия>-<flavor>-<abi>.apk`; рядом появятся `release.json` и `notes.md`.
"""

import argparse
import datetime
import hashlib
import json
import pathlib
import re
import subprocess
import sys

LANGUAGES = ["ru", "en", "de", "es", "fr", "ja", "zh-Hans"]
NAME = re.compile(r"^iskra-(?P<version>[^-]+)-(?P<flavor>gms|foss)-(?P<abi>arm64-v8a|x86_64)\.apk$")


def fail(message):
    print(f"release_json: {message}", file=sys.stderr)
    sys.exit(1)


def read_notes(path):
    notes = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
    missing = [language for language in LANGUAGES if not str(notes.get(language, "")).strip()]
    if missing:
        fail(f"«Что нового» без языков: {', '.join(missing)} ({path})")
    extra = sorted(set(notes) - set(LANGUAGES))
    if extra:
        fail(f"в «Что нового» лишние ключи: {', '.join(extra)}")
    return {language: notes[language].strip() for language in LANGUAGES}


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as file:
        for chunk in iter(lambda: file.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def certificate(apksigner, apk):
    out = subprocess.run([apksigner, "verify", "--print-certs", "-v", str(apk)], capture_output=True, text=True)
    if out.returncode != 0:
        fail(f"{apk.name} не проходит apksigner verify:\n{out.stderr}")
    # v3 — обязательно: она даёт ротацию ключа без потери обновлений. v2 при minSdk 29 apksigner не
    # ставит: все поддерживаемые Android проверяют v3, и вторая подпись ничего не добавила бы.
    if "Verified using v3 scheme (APK Signature Scheme v3): true" not in out.stdout:
        fail(f"{apk.name} не подписан схемой v3")
    digests = re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", out.stdout)
    if len(set(digests)) != 1:
        fail(f"{apk.name}: не один сертификат подписи ({digests})")
    return digests[0]


def badging(aapt2, apk):
    out = subprocess.run([aapt2, "dump", "badging", str(apk)], capture_output=True, text=True)
    if out.returncode != 0:
        fail(f"{apk.name} не читается aapt2:\n{out.stderr}")
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", out.stdout)
    sdk = re.search(r"minSdkVersion:'(\d+)'", out.stdout) or re.search(r"sdkVersion:'(\d+)'", out.stdout)
    if not package or not sdk:
        fail(f"{apk.name}: aapt2 не сказал пакет, версию или minSdk")
    return package.group(1), int(package.group(2)), package.group(3), int(sdk.group(1))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check-notes")
    parser.add_argument("--dist")
    parser.add_argument("--notes")
    parser.add_argument("--apksigner")
    parser.add_argument("--aapt2")
    parser.add_argument("--expect-cert", default="")
    args = parser.parse_args()

    if args.check_notes:
        read_notes(args.check_notes)
        print("release_json: «Что нового» — на всех семи языках")
        return

    if not (args.dist and args.notes and args.apksigner and args.aapt2):
        fail("нужны --dist, --notes, --apksigner и --aapt2")

    notes = read_notes(args.notes)
    dist = pathlib.Path(args.dist)
    apks = sorted(dist.glob("*.apk"))
    if not apks:
        fail(f"в {dist} нет APK")

    assets, certificates, versions = {}, set(), set()
    for apk in apks:
        match = NAME.match(apk.name)
        if not match:
            fail(f"{apk.name}: имя не iskra-<версия>-<flavor>-<abi>.apk")
        package, code, name, min_sdk = badging(args.aapt2, apk)
        if package != "wtf.iskra":
            fail(f"{apk.name}: пакет {package}, а не wtf.iskra")
        if name != match["version"]:
            fail(f"{apk.name}: внутри версия {name}")
        versions.add((name, code, min_sdk))
        certificates.add(certificate(args.apksigner, apk))
        assets[f"{match['flavor']}-{match['abi']}"] = {"name": apk.name, "size": apk.stat().st_size, "sha256": sha256(apk)}

    if len(versions) != 1:
        fail(f"у APK разные версии: {sorted(versions)}")
    if len(certificates) != 1:
        fail("APK подписаны разными ключами")
    for flavor in ("gms", "foss"):
        if f"{flavor}-arm64-v8a" not in assets:
            fail(f"нет {flavor}-arm64-v8a: в каждом релизе оба flavor'а")
    (version_name, version_code, min_sdk), = versions
    cert, = certificates
    expected = args.expect_cert.lower().replace(":", "")
    if expected and cert != expected:
        fail(f"сертификат {cert} — не тот, что ожидается ({expected})")

    manifest = {
        "schema": 1,
        "versionName": version_name,
        "versionCode": version_code,
        "minSdk": min_sdk,
        "published": datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "notes": notes,
        "assets": dict(sorted(assets.items())),
        "signingCertSha256": cert,
    }
    (dist / "release.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    sizes = "\n".join(f"| `{a['name']}` | {a['size'] / 1024 / 1024:.1f} МБ |" for a in manifest["assets"].values())
    (dist / "notes.md").write_text(
        f"{notes['ru']}\n\n"
        f"**{version_name}** · versionCode {version_code} · Android {min_sdk}+\n\n"
        f"Какой файл: `gms` — телефоны с сервисами Google (пуши через FCM), `foss` — без них "
        f"(пуши через UnifiedPush). `arm64-v8a` — почти любой телефон, `x86_64` — эмулятор и ChromeOS.\n\n"
        f"| Файл | Размер |\n|---|---|\n{sizes}\n\n"
        f"Отпечаток сертификата подписи (SHA-256), сверить: `apksigner verify --print-certs <файл>`\n\n"
        f"`{cert}`\n",
        encoding="utf-8",
    )
    print(f"release_json: {version_name} ({version_code}), {len(assets)} APK, сертификат {cert}")


if __name__ == "__main__":
    main()
