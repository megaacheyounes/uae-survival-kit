"""Run after the map has been imported on the project emulator."""
import re
import sys
import time
from emulator_ui import adb, find, nodes, tap
from emulator_smoke import shell, launch, check, pick, type_in_box, select_map_result, go_to_personal_import
from emulator_ask import submit, scroll_to


def video():
    launch()
    go_to_personal_import()
    pick("QA-Video.mp4")
    check("File imported.")
    tap("Dismiss")
    type_in_box("QA-Video")
    tap("QA-Video.mp4")
    tap("Play")
    time.sleep(6)
    tap("Pause")
    def position():
        timer=next(n.get("text","").strip() for n in nodes() if re.fullmatch(r"\d+:\d\d / \d+:\d\d",n.get("text","")))
        m,s=map(int,timer.split(" / ")[0].split(":"))
        return m*60+s
    before=position()
    assert before>=3, before
    shell("am","force-stop","ae.fieldguide.personal")
    launch()
    tap("Library")
    type_in_box("QA-Video")
    tap("QA-Video.mp4")
    after=position()
    assert abs(before-after)<=2 and after>=2,(before,after)
    print("PASS video playback and restart resume",before,after,flush=True)


def interrupt():
    launch()
    tap("Map")
    tap("Layers")
    tap("Manage offline maps")
    tap("Import pack")
    pick("UAE-map.zip")
    # Kill only this emulator app while its replacement is staging.
    shell("am","force-stop","ae.fieldguide.personal")
    launch()
    tap("Map")
    check("Map options")
    type_in_box("Hatta Hospital")
    select_map_result("Hatta Hospital")
    check("Hatta Hospital")
    print("PASS active UAE map survived replacement interruption",flush=True)


def model():
    launch()
    tap("Settings and downloads")
    tap("Offline downloads")
    scroll_to("ON-DEVICE AI")
    labels=[n.get("text","") for n in nodes()]
    start=time.monotonic()
    if "Import optional AI model" in labels:
        tap("Import optional AI model")
        pick("qwen.gguf")
    elif "Enable local AI" in labels:
        tap("Enable local AI")
    find("Local AI is ready.",timeout=300)
    print("PASS model load and generation probe",round(time.monotonic()-start,1),"seconds including import/test",flush=True)
    tap("Dismiss")
    shell("input","keyevent","4")
    shell("input","keyevent","4")
    tap("Ask")
    # The Ask screen has a text field and a content button in addition to the bottom tab.
    for question in (
        "What should I do at a flooded road",
        "Can I drive through floodwater",
        "Water is flowing across the street ahead. Is it safe to cross",
        "The highway is under water. Should I turn back",
        "Should we keep driving if the road looks flooded",
    ):
        submit(question)
        answer = find("Turn back at a flooded road.", partial=True, timeout=90).get("text")
        scroll_to("Open guide step 2")
        tap("Open guide step 2")
        find("Flash floods and wadis")
        heading, body = answer.split(". ", 1)
        find(heading)
        find(body)
        shell("input", "keyevent", "4")
        print("PASS exact flooded-road citation", question, flush=True)
    submit("How do I play chess")
    find("No matching guide or reference page found.",timeout=90)
    print("PASS unsupported question",flush=True)

def gps():
    launch()
    tap("Map")
    shell("cmd","location","set-location-enabled","true")
    adb("emu","geo","fix","55.27080","25.20480")
    tap("My location")
    if any(n.get("text")=="While using the app" for n in nodes()):
        tap("While using the app")
    adb("emu","geo","fix","55.27080","25.20480")
    fix=find("GPS ·",partial=True,timeout=40).get("text")
    assert "25.204" in fix and "55.270" in fix and "±" in fix,fix
    print("PASS emulator GPS fix and accuracy",fix,flush=True)


if __name__=="__main__":
    {"video":video,"interrupt":interrupt,"model":model,"gps":gps}[sys.argv[1]]()
