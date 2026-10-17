#!/usr/bin/env python3
"""Привозит из iOS-репозитория всё, что у платформ общее (docs/PROMPT.md, 2.3).

Общее не копируется руками: оно читается из *коммита* `iskra` (и `iskra-bridge`), а не из рабочего
дерева, и рядом с каждым привезённым кладётся `UPSTREAM` с хэшем этого коммита. Поэтому то, что
лежит здесь, всегда можно сверить с тем, откуда оно взято, и незакоммиченная правка в соседнем
репозитории сюда не попадает.

Скрипт идемпотентен: второй запуск на том же коммите не меняет дерево. CI проверяет именно это —
если после запуска дерево изменилось, значит, кто-то правил сгенерированное руками.

Что привозится:
  * строки семи языков: `.strings`/`.stringsdict` → `values*/strings.xml` трёх модулей;
  * golden-фикстура blobatar → ресурсы тестов `:design`;
  * векторы NIP-44 → ресурсы тестов `:core` (до появления `:tunnel`, раздел 9.3);
  * исходники крейта `iskra-tunnel-proto` из `iskra-bridge` → `third_party/`;
  * знак Искры (spark) → adaptive icon лаунчера;
  * стикеры «Искорки» (PNG) → Java-ресурсы `:core` (демо-мир и, на этапе 6, свой набор стикеров).
"""

from __future__ import annotations

import argparse
import plistlib
import re
import subprocess
import sys
import tomllib
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# iOS-каталог → каталог ресурсов Android. Английский — `values/`: на Android отсутствующая строка
# тихо берётся оттуда, поэтому все семь языков полные и это проверяется тестом и lint'ом.
LANGUAGES = {
    "en": "values",
    "ru": "values-ru",
    "de": "values-de",
    "es": "values-es",
    "fr": "values-fr",
    "ja": "values-ja",
    "zh-Hans": "values-b+zh+Hans",
}

# Откуда в iOS и в какой модуль здесь. Строки модели (`IskraCore`) живут в `:core`: тексты
# уведомлений собираются без UI (раздел 7).
STRING_SOURCES = [
    ("Core/Sources/IskraCore/Resources", "core"),
    ("Sources/IskraFeatures/Resources", "features"),
    # Только `Localizable`: `AppShortcuts.strings` — фразы Siri, у которых на Android нет адресата.
    ("Apps/Shared", "app"),
]

GENERATED_HEADER = (
    "<!-- Сгенерировано scripts/sync-from-ios.sh из iskra@{rev}: {source}.\n"
    "     Не править руками: строки правятся в iOS-репозитории и привозятся сюда. -->\n"
)


class SyncError(Exception):
    pass


# ---------------------------------------------------------------- чтение из git, а не с диска


@dataclass
class Repo:
    path: Path
    rev: str

    def resolve(self) -> str:
        return git(self.path, "rev-parse", f"{self.rev}^{{commit}}").strip()

    def read(self, relative: str) -> bytes:
        return subprocess.run(
            ["git", "-C", str(self.path), "show", f"{self.rev}:{relative}"],
            check=True, capture_output=True,
        ).stdout

    def list(self, relative: str) -> list[str]:
        out = git(self.path, "ls-tree", "-r", "--name-only", self.rev, "--", relative)
        return [line for line in out.splitlines() if line]


def git(path: Path, *args: str) -> str:
    result = subprocess.run(["git", "-C", str(path), *args], capture_output=True, text=True)
    if result.returncode != 0:
        raise SyncError(f"git {' '.join(args)} в {path}: {result.stderr.strip()}")
    return result.stdout


def write(path: Path, content: bytes | str) -> None:
    data = content.encode() if isinstance(content, str) else content
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and path.read_bytes() == data:
        return
    path.write_bytes(data)


# ------------------------------------------------------------------------ .strings → strings.xml

def _tokens(text: str) -> list[str]:
    """Строки в кавычках и знаки `=`/`;` из `.strings`; комментарии пропускаются.

    Руками, а не регулярным выражением: `//` внутри значения (`wss://…`) — это адрес, а не
    комментарий, и отличить одно от другого можно, только зная, в кавычках ли мы сейчас.
    """
    tokens, i, n = [], 0, len(text)
    while i < n:
        ch = text[i]
        if ch.isspace():
            i += 1
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            if end < 0:
                raise SyncError("незакрытый комментарий в .strings")
            i = end + 2
        elif text.startswith("//", i):
            end = text.find("\n", i)
            i = n if end < 0 else end + 1
        elif ch == '"':
            j = i + 1
            while j < n and text[j] != '"':
                j += 2 if text[j] == "\\" else 1
            if j >= n:
                raise SyncError("незакрытая кавычка в .strings")
            tokens.append(text[i:j + 1])
            i = j + 1
        elif ch in "=;":
            tokens.append(ch)
            i += 1
        else:
            raise SyncError(f"непонятный кусок в .strings: {text[i:i + 80]!r}")
    return tokens


def parse_strings(text: str) -> dict[str, str]:
    """Разбирает `.strings`. Комментарии выбрасываются, порядок сохраняется."""
    tokens = _tokens(text)
    if len(tokens) % 4:
        raise SyncError("в .strings запись не вида \"ключ\" = \"значение\";")
    entries: dict[str, str] = {}
    for at in range(0, len(tokens), 4):
        key, eq, value, semicolon = tokens[at:at + 4]
        if eq != "=" or semicolon != ";" or not key.startswith('"') or not value.startswith('"'):
            raise SyncError(f"в .strings запись не вида \"ключ\" = \"значение\": {key[:60]}")
        key, value = unescape_strings(key[1:-1]), unescape_strings(value[1:-1])
        if key in entries:
            raise SyncError(f"ключ {key!r} встречается дважды")
        entries[key] = value
    return entries


def unescape_strings(value: str) -> str:
    out, i = [], 0
    while i < len(value):
        ch = value[i]
        if ch == "\\" and i + 1 < len(value):
            nxt = value[i + 1]
            out.append({"n": "\n", "t": "\t", '"': '"', "\\": "\\"}.get(nxt, nxt))
            i += 2
        else:
            out.append(ch)
            i += 1
    return "".join(out)


def android_key(ios_key: str) -> str:
    """`signIn.title` → `sign_in_title`; `Open Chat` → `open_chat`.

    Таблица соответствия не пишется руками: она *есть* эта функция, и столкновение двух ключей на
    одном имени — ошибка синхронизации, а не тихая перезапись.
    """
    key = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", ios_key)
    key = re.sub(r"[^A-Za-z0-9]+", "_", key).strip("_").lower()
    if not key or key[0].isdigit():
        key = "s_" + key
    return key


_FORMAT = re.compile(r"%(?:(\d+)\$)?(?:(@)|(lld|ld|d)|(\.\d+)?(f)|(%))")


def convert_format(value: str, *, plural_arg: int | None = None) -> str:
    """`%@` → `%s`, `%1$@` → `%1$s`, `%lld` → `%d`, `%.6f` → `%.6f`, `%%` остаётся.

    Если в строке больше одного аргумента, все становятся позиционными: Android требует этого от
    строк с несколькими подстановками, а перевод может переставить их местами. `plural_arg` —
    номер аргумента, которым в плюрале iOS стоит голый `%lld`.
    """
    specs = [m for m in _FORMAT.finditer(value) if not m.group(6)]
    positional = len(specs) > 1 or plural_arg is not None
    counter = 0

    def replace(match: re.Match[str]) -> str:
        nonlocal counter
        index, obj, integer, precision, floating, percent = match.groups()
        if percent:
            return "%%"
        counter += 1
        if index is None and plural_arg is not None and integer:
            number = plural_arg
        else:
            number = int(index) if index else counter
        kind = "s" if obj else "d" if integer else f"{precision or ''}f"
        return f"%{number}${kind}" if positional else f"%{kind}"

    converted = _FORMAT.sub(replace, value)
    leftover = re.search(r"%(?!%|\d+\$[sdf]|[sdf]|\.\d+f|\d+\$\.\d+f)", converted.replace("%%", ""))
    if leftover:
        raise SyncError(f"непонятный формат в {value!r}")
    return converted


def escape_android(value: str) -> str:
    """Экранирование значения `<string>`: `&`, `<`, `'`, `"`, `\\`, `@`/`?` в начале, переводы строк."""
    value = value.replace("\\", "\\\\")
    value = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    value = value.replace("'", "\\'").replace('"', '\\"')
    value = value.replace("\n", "\\n").replace("\t", "\\t")
    if value.startswith(("@", "?")):
        value = "\\" + value
    # Ведущие и концевые пробелы aapt съедает, если их не взять в кавычки.
    if value != value.strip(" "):
        value = '"' + value + '"'
    return value


# ------------------------------------------------------------------ .stringsdict → <plurals>

ANDROID_QUANTITIES = ("zero", "one", "two", "few", "many", "other")

# Формы, которые по CLDR обязаны быть у языка. На iOS испанское и французское `many` (для миллионов)
# в .stringsdict не пишется, и система берёт `other`; Android того же не делает и считает отсутствие
# ошибкой (lint MissingQuantity). Поэтому недостающая обязательная форма получает текст `other` —
# ровно то, что показал бы iPhone.
REQUIRED_QUANTITIES = {
    "en": {"one", "other"}, "ru": {"one", "few", "many", "other"}, "de": {"one", "other"},
    "es": {"one", "many", "other"}, "fr": {"one", "many", "other"}, "ja": {"other"}, "zh-Hans": {"other"},
}


def parse_stringsdict(data: bytes, language: str) -> dict[str, dict[str, str]]:
    """`.stringsdict` → {ключ: {количество: строка Android}}.

    Поддерживается то, что Искра на самом деле пишет: одна плюральная переменная на строку, вокруг
    неё могут стоять обычные аргументы (`%1$@ and %2$#@count@`).
    """
    plist = plistlib.loads(data)
    result: dict[str, dict[str, str]] = {}
    for key, entry in plist.items():
        format_key = entry["NSStringLocalizedFormatKey"]
        variables = re.findall(r"%(?:(\d+)\$)?#@(\w+)@", format_key)
        if len(variables) != 1:
            raise SyncError(f"{key}: ожидалась одна плюральная переменная, а не {variables}")
        position, name = variables[0]
        spec = entry[name]
        if spec.get("NSStringFormatSpecTypeKey") != "NSStringPluralRuleType":
            raise SyncError(f"{key}: переменная {name} не плюральная")
        other_args = len(re.findall(r"%(?!#@)(?:\d+\$)?[@a-z]", format_key))
        plural_arg = int(position) if position else 1
        forms: dict[str, str] = {}
        for quantity in ANDROID_QUANTITIES:
            if quantity not in spec:
                continue
            form = re.sub(r"%(?:\d+\$)?#@\w+@", lambda _: spec[quantity], format_key)
            forms[quantity] = convert_format(form, plural_arg=plural_arg if other_args else None)
        if "other" not in forms:
            raise SyncError(f"{key}: нет формы other")
        for quantity in REQUIRED_QUANTITIES[language] - forms.keys():
            forms[quantity] = forms["other"]
        result[key] = {q: forms[q] for q in ANDROID_QUANTITIES if q in forms}
    return result


# --------------------------------------------------------------------------------- сборка XML


def render_resources(
    strings: dict[str, str], plurals: dict[str, dict[str, str]], *, rev: str, source: str,
) -> str:
    lines = ['<?xml version="1.0" encoding="utf-8"?>', GENERATED_HEADER.format(rev=rev[:12], source=source).rstrip("\n"), "<resources>"]
    for ios_key, value in strings.items():
        lines.append(f'    <string name="{android_key(ios_key)}">{escape_android(convert_format(value))}</string>')
    for ios_key, forms in plurals.items():
        lines.append(f'    <plurals name="{android_key(ios_key)}">')
        for quantity, form in forms.items():
            lines.append(f'        <item quantity="{quantity}">{escape_android(form)}</item>')
        lines.append("    </plurals>")
    lines.append("</resources>")
    return "\n".join(lines) + "\n"


def sync_strings(iskra: Repo, rev: str) -> None:
    owners: dict[str, str] = {}
    for source, module in STRING_SOURCES:
        per_language: dict[str, set[str]] = {}
        for language, folder in LANGUAGES.items():
            base = f"{source}/{language}.lproj/Localizable"
            strings = parse_strings(iskra.read(f"{base}.strings").decode("utf-8"))
            plurals = parse_stringsdict(iskra.read(f"{base}.stringsdict"), language) if iskra.list(f"{base}.stringsdict") else {}
            # На iOS ключ из .stringsdict главнее того же ключа в .strings: строка там — запасная
            # для систем без плюралей. Здесь она не нужна.
            strings = {k: v for k, v in strings.items() if k not in plurals}
            names = [android_key(k) for k in [*strings, *plurals]]
            duplicates = {n for n in names if names.count(n) > 1}
            if duplicates:
                raise SyncError(f"{base}: разные ключи iOS дают одно имя Android: {sorted(duplicates)}")
            per_language[language] = set(names)
            if language == "en":
                # Ресурсы модулей сливаются в одно пространство имён приложения, и одинаковое имя в
                # двух модулях молча перекрыло бы одно другим.
                for name in names:
                    if name in owners and owners[name] != module:
                        raise SyncError(f"строка {name} есть и в :{owners[name]}, и в :{module}")
                    owners[name] = module
            xml = render_resources(strings, plurals, rev=rev, source=f"{base}.strings")
            write(ROOT / module / "src/main/res" / folder / "strings.xml", xml)
        english = per_language["en"]
        for language, names in per_language.items():
            if names != english:
                missing, extra = sorted(english - names), sorted(names - english)
                raise SyncError(f"{source} {language}: не хватает {missing[:5]}, лишние {extra[:5]}")
        write(ROOT / module / "src/main/res/UPSTREAM", upstream_note("iskra", rev, f"{source}/*.lproj"))


# ---------------------------------------------------------------------- фикстуры, крейт, иконка


def upstream_note(repo: str, rev: str, what: str) -> str:
    return (
        f"{repo} {rev}\n"
        f"{what}\n"
        "Привезено scripts/sync-from-ios.sh. Не править руками: правка делается там и привозится.\n"
    )


def sync_fixtures(iskra: Repo, rev: str) -> None:
    target = ROOT / "design/src/test/resources"
    write(target / "blobatar-gen2-golden.txt", iskra.read("Tests/IskraDesignTests/Resources/blobatar-gen2-golden.txt"))
    write(target / "blobatar-LICENSE.txt", iskra.read("Licenses/blobatar.txt"))
    write(target / "UPSTREAM", upstream_note("iskra", rev, "Tests/IskraDesignTests/Resources/blobatar-gen2-golden.txt (MIT, см. blobatar-LICENSE.txt)"))

    target = ROOT / "core/src/test/resources"
    write(target / "nip44.vectors.json", iskra.read("Tests/IskraCoreTests/Resources/nip44.vectors.json"))
    write(target / "UPSTREAM", upstream_note("iskra", rev, "Tests/IskraCoreTests/Resources/nip44.vectors.json"))


def sync_stickers(iskra: Repo, rev: str) -> None:
    # Java-ресурсы, а не assets: их читает и приложение, и JVM-тест демо-сервера — через загрузчик классов.
    source = "Core/Sources/IskraCore/Resources/Stickers"
    target = ROOT / "core/src/main/resources/stickers"
    names = [Path(path).name for path in iskra.list(source) if path.endswith(".png")]
    if not names:
        raise SyncError(f"в {source} нет стикеров")
    for name in names:
        write(target / name, iskra.read(f"{source}/{name}"))
    for stale in target.glob("*.png"):
        if stale.name not in names:
            stale.unlink()
    write(target / "UPSTREAM", upstream_note("iskra", rev, f"{source}/*.png"))


def resolve_workspace(manifest: dict, workspace: dict) -> dict:
    """Подставляет `*.workspace = true` из корня `iskra-bridge`, чтобы копия крейта собиралась одна."""
    package = dict(manifest["package"])
    for field, value in list(package.items()):
        if isinstance(value, dict) and value.get("workspace"):
            package[field] = workspace["package"][field]
    deps = {}
    for name, spec in manifest.get("dependencies", {}).items():
        if isinstance(spec, dict) and spec.get("workspace"):
            base = workspace["dependencies"][name]
            base = {"version": base} if isinstance(base, str) else dict(base)
            extra = {k: v for k, v in spec.items() if k != "workspace"}
            if "features" in extra:
                base["features"] = sorted(set(base.get("features", [])) | set(extra.pop("features")))
            base.update(extra)
            spec = base
        deps[name] = spec
    return {**manifest, "package": package, "dependencies": deps}


def toml_value(value) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, str):
        return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'
    if isinstance(value, list):
        return "[" + ", ".join(toml_value(v) for v in value) + "]"
    if isinstance(value, dict):
        return "{ " + ", ".join(f"{k} = {toml_value(v)}" for k, v in value.items()) + " }"
    return str(value)


def render_manifest(manifest: dict) -> str:
    out = ["# Сгенерировано scripts/sync-from-ios.sh из Cargo.toml крейта и корня iskra-bridge.", ""]
    for table, values in manifest.items():
        out.append(f"[{table}]")
        out += [f"{k} = {toml_value(v)}" for k, v in values.items()]
        out.append("")
    return "\n".join(out)


def sync_proto(bridge: Repo, rev: str) -> None:
    crate = "crates/iskra-tunnel-proto"
    target = ROOT / "third_party/iskra-tunnel-proto"
    expected = set()
    for file in bridge.list(crate):
        relative = file[len(crate) + 1:]
        if relative == "Cargo.toml":
            manifest = tomllib.loads(bridge.read(file).decode())
            workspace = tomllib.loads(bridge.read("Cargo.toml").decode())["workspace"]
            write(target / relative, render_manifest(resolve_workspace(manifest, workspace)))
        else:
            write(target / relative, bridge.read(file))
        expected.add(relative)
    write(target / "LICENSE", bridge.read("LICENSE"))
    write(target / "UPSTREAM", upstream_note("iskra-bridge", rev, crate + " (MIT, см. LICENSE)"))
    expected |= {"LICENSE", "UPSTREAM"}
    for path in target.rglob("*"):
        if path.is_file() and str(path.relative_to(target)) not in expected:
            path.unlink()


_SVG_PATH = re.compile(r'<path d="([^"]+)"[^>]*fill="(#[0-9A-Fa-f]{6})"')


def sync_icon(iskra: Repo, rev: str) -> None:
    """Знак Искры как adaptive icon: тот же контур, что в Icon Composer, на том же зелёном.

    Фон — `automatic-gradient` документа `iskra-spark.icon`; знак — его слой. Монохромный слой (иконки
    в теме, Android 13+) — тот же контур. Остальные семь иконок — этап 10 (activity-alias).
    """
    import json

    document = json.loads(iskra.read("icons/iskra-spark.icon/icon.json"))
    srgb = document["fill"]["automatic-gradient"].removeprefix("srgb:").split(",")
    background = "#" + "".join(f"{round(float(c) * 255):02X}" for c in srgb[:3])
    layer = document["groups"][0]["layers"][0]
    svg = iskra.read(f"icons/iskra-spark.icon/Assets/{layer['image-name']}").decode()
    match = _SVG_PATH.search(svg)
    if not match:
        raise SyncError("в слое иконки не нашёлся контур")
    path_data, fill = match.group(1), match.group(2).upper()
    width, height = (float(x) for x in re.search(r'viewBox="0 0 ([\d.]+) ([\d.]+)"', svg).groups())

    # Видимая часть adaptive icon — круг 72 dp из 108; знак занимает 56 dp в ширину, как на iOS он
    # занимает почти всю плитку, но с запасом под маску лаунчера.
    scale = 56 / width
    tx, ty = (108 - width * scale) / 2, (108 - height * scale) / 2

    def vector(color: str) -> str:
        return (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            f"<!-- Сгенерировано scripts/sync-from-ios.sh из iskra@{rev[:12]}: icons/iskra-spark.icon. -->\n"
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp"\n    android:height="108dp"\n'
            '    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
            f'    <group android:scaleX="{scale:.6f}" android:scaleY="{scale:.6f}"\n'
            f'        android:translateX="{tx:.4f}" android:translateY="{ty:.4f}">\n'
            f'        <path android:fillColor="{color}" android:fillType="evenOdd"\n'
            f'            android:pathData="{path_data}" />\n'
            "    </group>\n</vector>\n"
        )

    res = ROOT / "app/src/main/res"
    write(res / "drawable/ic_launcher_foreground.xml", vector(fill))
    write(res / "drawable/ic_launcher_monochrome.xml", vector("#FFFFFFFF"))
    write(
        res / "values/ic_launcher_colors.xml",
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"<!-- Сгенерировано scripts/sync-from-ios.sh из iskra@{rev[:12]}: icons/iskra-spark.icon. -->\n"
        f'<resources>\n    <color name="ic_launcher_background">{background}</color>\n</resources>\n',
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("iskra", type=Path, help="путь к клону iskra")
    parser.add_argument("rev", nargs="?", default="HEAD", help="коммит iskra (по умолчанию HEAD)")
    parser.add_argument("--bridge", type=Path, help="путь к клону iskra-bridge (по умолчанию рядом с iskra)")
    parser.add_argument("--bridge-rev", default="HEAD")
    args = parser.parse_args()

    try:
        iskra = Repo(args.iskra.resolve(), args.rev)
        rev = iskra.resolve()
        bridge = Repo((args.bridge or args.iskra.resolve().parent / "iskra-bridge").resolve(), args.bridge_rev)
        bridge_rev = bridge.resolve()
        sync_strings(iskra, rev)
        sync_fixtures(iskra, rev)
        sync_icon(iskra, rev)
        sync_stickers(iskra, rev)
        sync_proto(bridge, bridge_rev)
    except (SyncError, subprocess.CalledProcessError) as error:
        print(f"sync-from-ios: {error}", file=sys.stderr)
        return 1
    print(f"sync-from-ios: iskra {rev[:12]}, iskra-bridge {bridge_rev[:12]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
