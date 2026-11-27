"""Check dashboard, tab history, directory pins and Ask on an unlocked English-language phone."""
import argparse
import time

from check_pair_navigation import PACKAGE, Phone


def check(phone):
    phone.shell("am", "force-stop", PACKAGE)
    phone.shell("am", "start", "-W", "-n", PACKAGE + "/ae.fieldguide.MainActivity")
    phone.tap_node(phone.find("Field radio", scroll=True))
    phone.back(True)
    phone.find("Field radio")
    phone.tap_node(phone.find("Emirate guides", scroll=True))
    phone.tap("Dubai")
    phone.tap("Open offline map")
    phone.tap("Search UAE map")
    for letter in "Hatta":
        phone.shell("input", "text", letter)
        time.sleep(.12)
    if b"mInputShown=true" in phone.shell("dumpsys", "input_method"):
        phone.back(False)
    phone.find("Browse all matches")
    phone.back(True)
    phone.find("Search UAE map")
    phone.back(True)
    phone.find("Open offline map")
    phone.back(False)
    phone.find("Emirate guides")
    phone.back(True)
    phone.tap("Map")
    phone.tap("Directory")
    phone.tap("Hospitals")
    phone.tap("Nearby pins")
    phone.back(False)
    phone.find("Nearby pins")
    phone.back(True)
    phone.find("Place directory")
    phone.back(False)
    phone.find("Search UAE map")
    phone.tap("Library")
    phone.tap("Ask")
    phone.tap("How do I find north?")
    phone.tap("By day")
    phone.back(True)
    phone.find("By day")
    phone.back(False)
    phone.find("Look up")
    phone.back(False)
    phone.find("QUICK QUESTIONS")
    phone.back(False)
    phone.find("Field library")
    phone.back(False)
    phone.find("Search UAE map")
    phone.back(False)
    phone.find("UAE Survival Kit")
    print("PASS directory pins, cited passage, question and tab history")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--port", type=int, default=5037)
    args = parser.parse_args()
    check(Phone(args.adb, args.serial, args.port))
