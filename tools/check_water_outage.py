"""Exercise the water kit on an unlocked offline QA handset with an empty water ledger."""
import argparse
import re
import subprocess
import time
import unicodedata
from pathlib import Path
from xml.etree import ElementTree

from check_pair_navigation import PACKAGE, Phone


def run(phone, language, output, guide_only):
    resources = Path(__file__).resolve().parents[1] / "app/src/main/res"
    folder = "values-ar" if language == "ar" else "values"
    labels = {n.attrib["name"]: n.text for n in ElementTree.parse(resources / folder / "water.xml").getroot()}
    output.mkdir(parents=True, exist_ok=True)

    def text(key):
        return labels["water_" + key]

    def normalize(label):
        return "".join(str(unicodedata.decimal(c)) if c.isdecimal() else c for c in label).replace("٫", ".")

    def seek(label, tap=False):
        expected = normalize(label)
        for _ in range(12):
            nodes = phone.nodes()
            if not nodes:
                time.sleep(.4)
                continue
            for node in nodes:
                if normalize(node.get("text", "")) == expected:
                    if tap:
                        phone.tap_node(node)
                    return
            l, t, r, b = map(int, re.findall(r"\d+", nodes[0].get("bounds")))
            phone.shell("input", "swipe", str((l+r)//2), str(t+(b-t)*3//4), str((l+r)//2), str(t+(b-t)//3), "280")
        raise AssertionError(f"Could not reach {label!r}")

    def restart():
        phone.shell("am", "force-stop", PACKAGE)
        phone.shell("am", "start", "-W", "-n", PACKAGE + "/ae.fieldguide.MainActivity")
        seek(text("title"), True)
        seek(text("safe_body"))

    def snap(name):
        raw = subprocess.check_output(phone.command + ["exec-out", "screencap", "-p"])
        (output / f"{language}-{name}.png").write_bytes(raw[raw.index(b"\x89PNG"):])

    def ledger():
        seek(text("ledger"), True)

    def balance(n):
        expected = text("litres").replace("%1$s", str(n))
        for _ in range(5):
            nodes = phone.nodes()
            if any(normalize(node.get("text", "")) == expected for node in nodes):
                return
            if nodes:
                l, t, r, b = map(int, re.findall(r"\d+", nodes[0].get("bounds")))
                phone.shell("input", "swipe", str((l+r)//2), str(t+(b-t)//3), str((l+r)//2), str(t+(b-t)*3//4), "250")
        raise AssertionError(f"Expected balance {expected!r}")

    def fill(index, value):
        fields = [n for n in phone.nodes() if n.get("class") == "android.widget.EditText"]
        phone.tap_node(fields[index])
        phone.shell("input", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_A")
        phone.shell("input", "text", value)
        if b"mInputShown=true" in phone.shell("dumpsys", "input_method"):
            phone.back(False)

    def change(action, value, count=None):
        seek(text(action), True)
        fill(0, value)
        if count:
            fill(1, count)
        seek(text("save"), True)

    assert phone.shell("settings", "get", "global", "airplane_mode_on").strip() == b"1"
    route = subprocess.run(phone.command + ["shell", "ip", "route", "get", "1.1.1.1"], capture_output=True)
    assert route.returncode != 0 and b"unreachable" in (route.stderr + route.stdout).lower()
    assert float(phone.shell("settings", "get", "system", "font_scale")) >= 1.5
    restart()
    snap("guide")
    if not guide_only:
        ledger()
        balance(0)
        # Only add test transactions to an empty ledger. Undo each one after validation.
        phone.find(text("empty"))
        change("add", "5", "2")
        balance(10)
        change("use", "3")
        balance(7)
        restart()
        ledger()
        balance(7)
        snap("restored-ledger")
        used = text("used").replace("%1$s", text("litres").replace("%1$s", "3"))
        seek(used)
        seek(text("undo"), True)
        balance(10)
        change("use", "11")
        seek(text("over"))
        phone.back(False)
        balance(10)
        change("correct", "8.5")
        balance(8.5)
        seek(text("undo"), True)
        balance(10)
        seek(text("undo"), True)
        balance(0)
        phone.find(text("empty"))
        print("PASS add, use, restart, undo, overdraw rejection, correction and cleanup", flush=True)
        restart()
    def expect_foreground(activity):
        pattern = rb"(?:mResumedActivity|topResumedActivity)[^\r\n]*" + activity
        for _ in range(10):
            if re.search(pattern, phone.shell("dumpsys", "activity", "activities"), re.I):
                return
            time.sleep(.25)
        raise AssertionError(f"{activity.decode()} did not open in the foreground")

    seek(text("return_body"))
    seek(text("print"), True)
    expect_foreground(b"printspooler")
    snap("print")
    phone.back(False)
    seek(text("share"), True)
    expect_foreground(b"chooseractivity")
    phone.back(False)
    print(f"PASS {language} large-text guide, print preview and PDF sharing with no network", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--port", type=int, default=5037)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--language", choices=["en", "ar"], required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--guide-only", action="store_true")
    args = parser.parse_args()
    run(Phone(args.adb, args.serial, args.port), args.language, args.output, args.guide_only)
