"""Test the actual minified APK on a fresh rooted CI emulator, without user data."""
import argparse
import hashlib
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "com.notessync.app"
OUT = Path("release-settings-probe")
OUT.mkdir(exist_ok=True)
events = []

def adb(*args, binary=False):
    return subprocess.check_output(["adb", *map(str, args)], timeout=40, text=not binary)

def tree():
    adb("shell", "uiautomator", "dump", "/sdcard/notels-settings-probe.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/notels-settings-probe.xml"))

def match_path(root, label, parents=()):
    for node in root:
        path = (*parents, node)
        if node.get("text") == label or node.get("content-desc") == label:
            return path
        found = match_path(node, label, path)
        if found: return found
    return None

def bounds(node):
    values = [int(x) for x in re.findall(r"\d+", node.get("bounds", ""))]
    return values if len(values) == 4 and values[2] > values[0] and values[3] > values[1] else None

def find(label, scroll=False):
    for attempt in range(9 if scroll else 1):
        root = tree(); path = match_path(root, label)
        if path:
            node = next((n for n in reversed(path) if n.get("clickable") == "true"), path[-1])
            box = bounds(node)
            if box: return root, path, box
        if not scroll: break
        size = [int(x) for x in re.findall(r"\d+", adb("shell", "wm", "size"))][-2:]
        adb("shell", "input", "swipe", size[0] // 2, int(size[1] * .78), size[0] // 2, int(size[1] * .35), 350)
    raise AssertionError("Visible control missing: " + label)

def tap(label, scroll=False):
    _, _, box = find(label, scroll)
    adb("shell", "input", "tap", (box[0] + box[2]) // 2, (box[1] + box[3]) // 2)

def selected(label):
    _, path, _ = find(label)
    return any(n.get("selected") == "true" or n.get("checked") == "true" for n in path)

def prefs():
    root = ET.fromstring(adb("shell", "cat", f"/data/user/0/{PACKAGE}/shared_prefs/settings.xml"))
    allowed = {"theme_mode", "font_scale", "accent_color", "interface_style", "app_font"}
    return {n.get("name"): n.get("value", n.text) for n in root if n.get("name") in allowed}

def snapshot(name):
    ET.ElementTree(tree()).write(OUT / f"{name}.xml", encoding="utf-8")
    (OUT / f"{name}.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))

def open_settings():
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    tap("Действия со списком"); tap("Настройки"); tap("Внешний вид")

def force_gc():
    pid = adb("shell", "pidof", "-s", PACKAGE).strip()
    assert re.fullmatch(r"\d+", pid)
    adb("logcat", "-c"); adb("shell", "kill", "-10", pid)
    log = ""
    for _ in range(15):
        log = adb("logcat", "-d", "--pid=" + pid, "-v", "brief")
        if "SIGUSR1 forcing GC" in log: break
        time.sleep(.2)
    assert "SIGUSR1 forcing GC" in log, "ART did not confirm explicit GC"
    (OUT / "gc.log").write_text("\n".join(l for l in log.splitlines() if "GC" in l or "reacting to signal" in l))
    tree(); tree()
    assert adb("shell", "pidof", "-s", PACKAGE).strip() == pid

def check_change(label, key, value, expected_stale, scroll=False):
    tap(label, scroll); after = prefs()
    assert after.get(key) == value, (key, after)
    is_selected = selected(label)
    event = {"label": label, "key": key, "persisted": after[key], "ui_selected": is_selected}
    events.append(event); snapshot(key + "-after-gc")
    assert is_selected != expected_stale, event

def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--apk", required=True)
    parser.add_argument("--expect-stale", action="store_true"); args = parser.parse_args()
    assert adb("shell", "getprop", "ro.kernel.qemu").strip() == "1", "CI emulator required"
    adb("root"); adb("wait-for-device")
    assert adb("shell", "id", "-u").strip() == "0"
    apk = Path(args.apk); digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    adb("install", "-r", str(apk)); open_settings(); tap("Тёмная")
    assert prefs()["theme_mode"] == "DARK"
    adb("shell", "am", "force-stop", PACKAGE); open_settings()
    assert selected("Тёмная"); snapshot("dark-before-gc"); force_gc()
    check_change("Светлая", "theme_mode", "LIGHT", args.expect_stale)
    check_change("Крупный", "font_scale", "1.15", args.expect_stale)
    check_change("Оранжевый", "accent_color", "ORANGE", args.expect_stale, scroll=True)
    result = {"status": "success", "mode": "reproduce-stale" if args.expect_stale else "require-live",
              "apk_sha256": digest, "minified_installed_apk": True, "art_gc_confirmed": True,
              "physical_phone": False, "events": events}
    (OUT / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps(result, ensure_ascii=False))

if __name__ == "__main__":
    try: main()
    except BaseException as error:
        (OUT / "failure.json").write_text(json.dumps({"status": "failure", "error_type": type(error).__name__, "events": events}, ensure_ascii=False, indent=2))
        try: snapshot("failure")
        except BaseException: pass
        raise
