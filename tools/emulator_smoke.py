"""Exercise the signed build in airplane mode on FieldGuideTest, without placing a call."""
import re
import shlex
import time
from emulator_ui import adb, find, nodes, tap


def shell(*args):
    return adb("shell",*args,capture_output=True).stdout.decode(errors="replace").strip()


def launch():
    shell("am","start","-W","-f","0x10008000","-n","ae.fieldguide.personal/ae.fieldguide.MainActivity")
    find("UAE Survival Kit",timeout=45)


def check(label):
    find(label,timeout=45)
    print("PASS",label,flush=True)


def pick(name):
    find("Show roots",timeout=30)
    try:
        find(name,timeout=7)
    except ValueError:
        tap("Show roots")
        tap("Downloads")
    tap(name)


def type_in_box(text):
    field=next(n for n in nodes() if n.get("class")=="android.widget.EditText")
    x,y,r,b=map(int,re.findall(r"\d+",field.get("bounds")))
    shell("input","tap",str((x+r)//2),str((y+b)//2))
    existing = field.get("text", "")
    if existing:
        # The cursor can be anywhere in a wrapped question. Some Android IMEs consume Ctrl+A.
        count = len(existing) + 1
        shell("input", "keyevent", *(["KEYCODE_FORWARD_DEL"] * count + ["KEYCODE_DEL"] * count))
    shell("input","text",shlex.quote(text.replace(" ","%s")))
    if "mInputShown=true" in shell("dumpsys","input_method"):
        shell("input","keyevent","4")
        time.sleep(.4)

def select_map_result(title):
    deadline=time.monotonic()+15
    while time.monotonic()<deadline:
        for n in nodes():
            if n.get("text")==title and n.get("class")=="android.widget.TextView":
                x,y,r,b=map(int,re.findall(r"\d+",n.get("bounds")))
                shell("input","tap",str((x+r)//2),str((y+b)//2))
                find(title)
                assert all(n.get("text")!=title for n in nodes() if n.get("class")=="android.widget.EditText")
                return
    raise ValueError("Missing map result "+title)


def go_to_personal_import():
    tap("Library")
    tap("Files")
    tap("Personal files")
    tap("Import PDF or video")
    tap("Choose file")


def basic():
    assert shell("settings","get","global","airplane_mode_on")=="1"
    launch()
    tap("Fallout shelter")
    check("Get inside immediately")
    assert not any(n.get("text") in {"Home", "Map", "Library", "Ask"} for n in nodes())
    for _ in range(8):
        if any(n.get("text") == "Illustrated shelter guide (English)" for n in nodes()):
            break
        shell("input","swipe","400","1200","400","450","350")
    tap("Illustrated shelter guide (English)")
    tap("Next step")
    find("2 / 6")
    tap("Tools")
    tap("Guide source")
    find("Guide source")
    shell("input", "keyevent", "4")
    find("2 / 6")
    shell("input","keyevent","4")
    shell("input","keyevent","4")
    tap("998")
    time.sleep(1)
    activity=shell("dumpsys","activity","activities")
    assert "DialtactsActivity" in activity or "dialer" in activity.lower()
    phone=nodes()
    assert any("998" in (n.get("text","")+n.get("content-desc","")).replace(" ","") for n in phone)
    print("PASS ambulance dialer 998",flush=True)
    shell("input","keyevent","4")
    launch()
    tap("Settings and downloads")
    tap("Offline downloads")
    tap("Import pack")
    pick("UAE-map.zip")
    check("UAE map is ready.")
    tap("Dismiss")
    shell("input","keyevent","4")
    shell("input","keyevent","4")
    tap("Map")
    tap("Layers")
    find("Show UAE overview")
    find("Larger map labels")
    assert not any("copyright" in n.get("text", "").lower() or "https://" in n.get("text", "") for n in nodes())
    tap("Close map options")
    type_in_box("Hatta")
    select_map_result("Hatta")
    check("Hatta")
    print("PASS imported map and selected Hatta",flush=True)
    go_to_personal_import()
    pick("QA-Manual.pdf")
    check("File imported.")
    tap("Dismiss")
    type_in_box("QA-Manual")
    tap("QA-Manual.pdf")
    bookmark_pdf()

def bookmark_pdf():
    find("Page 1")
    tap("Next")
    find("Page 2")
    tap("Tools")
    tap("Bookmark this page")
    shell("am","force-stop","ae.fieldguide.personal")
    launch()
    tap("Library")
    type_in_box("QA-Manual")
    tap("QA-Manual.pdf")
    find("Page 2")
    shell("input","swipe","450","1200","450","450","350")
    check("2 / 2")
    print("PASS persisted PDF bookmark",flush=True)


if __name__=="__main__":
    basic()
