"""Exercise Trusted pair navigation on a connected, unlocked English-language test phone.

Starts a fresh Field Guide activity and briefly creates a local-only hotspot.
Run with python tools/check_pair_navigation.py --adb PATH --serial SERIAL [--port PORT].
"""
import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "ae.fieldguide.personal"
METHODS = ("Invite on this Wi-Fi", "Start a hotspot", "I have an invitation")


class Phone:
    def __init__(self, adb, serial, port):
        self.command = [adb, "-P", str(port), "-s", serial]

    def shell(self, *args):
        return subprocess.check_output(self.command + ["shell", *args], timeout=30)

    def nodes(self):
        raw = subprocess.check_output(self.command + ["exec-out", "uiautomator", "dump", "/dev/tty"], timeout=25)
        start = raw.find(b"<hierarchy")
        end = raw.rfind(b"</hierarchy>")
        if start < 0 or end < 0:
            return []
        return list(ET.fromstring(raw[start:end + len(b"</hierarchy>")]).iter("node"))

    def find(self, label, scroll=False):
        for _ in range(4):
            nodes = self.nodes()
            for node in nodes:
                if label in (node.get("text"), node.get("content-desc")):
                    return node
            if scroll and nodes:
                left, top, right, bottom = map(int, re.findall(r"\d+", nodes[0].get("bounds")))
                x = str((left + right) // 2)
                self.shell("input", "swipe", x, str(top + (bottom-top)*3//4), x, str(top + (bottom-top)//3), "300")
            time.sleep(.2)
        raise AssertionError(f"Could not find {label!r}")

    def tap_node(self, node):
        left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
        self.shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def tap(self, label):
        self.tap_node(self.find(label))

    def back(self, toolbar):
        if toolbar:
            self.tap("Back")
        else:
            self.shell("input", "keyevent", "KEYCODE_BACK")

    def methods(self):
        labels = {node.get("text") for node in self.nodes()}
        assert set(METHODS).issubset(labels), "Back skipped the pairing methods or a choice is unreachable"


def run(phone):
    phone.shell("am", "force-stop", PACKAGE)
    phone.shell("am", "start", "-W", "-n", PACKAGE + "/ae.fieldguide.MainActivity")
    phone.tap_node(phone.find("Field radio", scroll=True))
    phone.tap("Trusted pair")
    for toolbar in (True, False):
        phone.tap(METHODS[0])
        phone.find("Pairing QR code")
        codes = [n.get("text", "") for n in phone.nodes() if re.fullmatch(r"[0-9]{3} [0-9]{3}", n.get("text", ""))]
        assert len(codes) == 1, "Expected a fresh one-use invitation"
        phone.back(toolbar)
        phone.methods()
        phone.tap(METHODS[1])
        for node in phone.nodes():
            if "permissioncontroller" in node.get("package", "") and node.get("text", "").lower() in ("allow", "while using the app"):
                phone.tap_node(node)
                break
        phone.find("CONNECT THE OTHER PHONE TO THIS HOTSPOT")
        phone.back(toolbar)
        phone.methods()
        phone.tap(METHODS[2])
        phone.find("Pairing code")
        phone.back(toolbar)
        phone.methods()
        print("PASS Wi-Fi, hotspot and join return to methods with " + ("toolbar Back" if toolbar else "Android Back"), flush=True)
    phone.back(False)
    phone.find("Open channel")
    phone.back(True)
    phone.find("Field radio", scroll=True)
    print("PASS methods to Field radio to Home", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--port", default=5037, type=int)
    args = parser.parse_args()
    run(Phone(args.adb, args.serial, args.port))
