"""Снимки экранов демо на эмуляторе: три шрифта × список чатов, чат, «Внешний вид». Временное."""
import re
import subprocess
import sys
import time
from pathlib import Path

PKG = "wtf.iskra.dev"
OUT = Path("shots")
OUT.mkdir(exist_ok=True)


def adb(*args, check=True, capture=True):
    return subprocess.run(["adb", *args], check=check, capture_output=capture, text=True).stdout


def nodes():
    for _ in range(8):
        adb("shell", "rm", "-f", "/sdcard/ui.xml", check=False)
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
        xml = adb("shell", "cat", "/sdcard/ui.xml", check=False)
        if "<hierarchy" in xml:
            return re.findall(r'<node [^>]*>', xml)
        time.sleep(1)
    return []


def find(pattern):
    for node in nodes():
        text = re.search(r' text="([^"]*)"', node).group(1)
        desc = re.search(r'content-desc="([^"]*)"', node).group(1)
        if re.search(pattern, text) or re.search(pattern, desc):
            x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def wait(pattern, seconds=90):
    deadline = time.time() + seconds
    while time.time() < deadline:
        point = find(pattern)
        if point:
            return point
        time.sleep(2)
    shot("failed-" + re.sub(r"\W+", "_", pattern))
    print("не нашлось:", pattern, file=sys.stderr)
    return None


def tap(pattern):
    point = wait(pattern)
    if point:
        adb("shell", "input", "tap", str(point[0]), str(point[1]))
        time.sleep(2)
    return point


def shot(name):
    time.sleep(2)
    with open(OUT / f"{name}.png", "wb") as file:
        subprocess.run(["adb", "exec-out", "screencap", "-p"], check=True, stdout=file)


def launch(font, dark):
    adb("shell", "am", "force-stop", PKG)
    prefs = (
        "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
        f'    <string name="appearance.font">{font}</string>\n'
        f'    <string name="appearance.mode">{"dark" if dark else "light"}</string>\n'
        '    <boolean name="beta.mockBackend" value="true" />\n</map>\n'
    )
    Path("prefs.xml").write_text(prefs)
    adb("push", "prefs.xml", "/data/local/tmp/prefs.xml")
    adb("shell", "chmod", "644", "/data/local/tmp/prefs.xml")
    adb("shell", "run-as", PKG, "mkdir", "-p", "shared_prefs")
    adb("shell", "run-as", PKG, "cp", "/data/local/tmp/prefs.xml", "shared_prefs/iskra.settings.xml")
    adb("shell", "am", "start", "-W", "-n", f"{PKG}/wtf.iskra.MainActivity", "--ez", "mock_backend", "true")


def main():
    apk = sys.argv[1]
    adb("install", "-r", "-g", apk)
    adb("shell", "cmd", "locale", "set-app-locales", PKG, "--locales", "ru-RU", check=False)
    for font in ["googleSans", "inter", "system"]:
        for dark in [False, True]:
            suffix = f"{font}-{'dark' if dark else 'light'}"
            launch(font, dark)
            wait("Семья")
            time.sleep(6)
            shot(f"1-chats-{suffix}")
            if tap("^Искра — разработка$"):
                time.sleep(4)
                shot(f"2-chat-{suffix}")
                adb("shell", "input", "keyevent", "4")
                time.sleep(2)
            if dark:
                continue
            if tap("^Настройки$"):
                shot(f"3-settings-{suffix}")
                if tap("^Внешний вид$"):
                    shot(f"4-appearance-{suffix}")
                    adb("shell", "input", "swipe", "540", "1800", "540", "600", "400")
                    shot(f"5-appearance-font-{suffix}")


main()
