"""Exercise the offline shelter kit using an explicitly selected, unlocked QA device."""
import argparse
import os
import re
import shlex
import subprocess
import time
from pathlib import Path
from xml.etree import ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("serial")
parser.add_argument("--package", default="ae.fieldguide.personal.reviewqa")
parser.add_argument("--language", choices=("en", "ar"), default="en", help="Expected Android resource language")
args = parser.parse_args()
folder = "values-ar" if args.language == "ar" else "values"
strings = ET.parse(Path(__file__).resolve().parents[1] / "app/src/main/res" / folder / "kit.xml")
labels = {node.attrib["name"]: node.text for node in strings.getroot()}
adb = [str(Path(os.environ.get("ANDROID_HOME", r"D:\android\sdk")) / "platform-tools/adb.exe"), "-s", args.serial]

def shell(*parts):
    result = subprocess.run(adb + ["shell", shlex.join(parts)], capture_output=True, timeout=45, check=True)
    return result.stdout.decode("utf8", "replace").strip()

def nodes():
    raw = subprocess.check_output(adb + ["exec-out","uiautomator","dump","/dev/tty"], timeout=30)
    start, end = raw.find(b"<hierarchy"), raw.rfind(b"</hierarchy>")
    return [] if start < 0 or end < start else list(ET.fromstring(raw[start:end+12]).iter("node"))

def seek(label, scroll=False, click=False):
    for i in range(9):
        ns = nodes()
        n = next((v for v in ns if label in [v.get("text"),v.get("content-desc")]),None)
        if n is not None:
            if click:
                a,b,c,d=map(int,re.findall(r"\d+",n.get("bounds")))
                shell("input","tap",str((a+c)//2),str((b+d)//2))
            return
        if scroll and i and ns:
            a,b,c,d=map(int,re.findall(r"\d+",ns[0].get("bounds")))
            shell("input","swipe",str(c//2),str(int(d*.78)),str(c//2),str(int(d*.31)),"350")
    raise AssertionError("Missing "+label+"; visible "+str([v.get("text") for v in ns if v.get("text")])[:450])

def tap(label): seek(label,scroll=True,click=True)
def back(): shell("input","keyevent","4")
def start(): shell("am","start","-W","-n",args.package+"/ae.fieldguide.MainActivity")

assert 'Active default network: none' in shell("dumpsys","connectivity")
assert shell("settings","get","global","airplane_mode_on")=="1"
start()
tap(labels["kit_shelter"])
seek(labels["kit_inside"])
assert not any(n.get("text") in ("العربية", "English") for n in nodes())
tap(labels["kit_outside"])
seek(labels["kit_outside_text"])
if args.serial.startswith("emulator-"):
    shell("input","keyevent","223")
    time.sleep(1)
    shell("input","keyevent","224")
    shell("wm","dismiss-keyguard")
    seek(labels["kit_outside_text"])
back()
tap(labels["kit_print"])
time.sleep(1)
assert 'printspooler' in shell("dumpsys","activity","activities").lower()
back()
tap(labels["kit_card"])
time.sleep(1)
assert 'chooseractivity' in shell("dumpsys","activity","activities").lower()
back()
back()
tap(labels["kit_power"])
tap(labels["kit_low"])
tap(labels["kit_settings"])
time.sleep(1)
assert 'settings' in shell("dumpsys","activity","activities").lower()
back()
tap(labels["kit_log"])
tap(labels["kit_record"])
def readings(): return [n.get("text","") for n in nodes() if "·" in n.get("text","")]
before = readings()
assert before
shell("am","force-stop",args.package)
start()
tap(labels["kit_power"])
tap(labels["kit_log"])
assert before[0] in readings()
assert 'Active default network: none' in shell("dumpsys","connectivity")
print("PASS offline shelter ("+args.language+"), outside instructions, print, share, settings and persisted charge reading",flush=True)
