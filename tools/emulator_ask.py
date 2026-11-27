"""Exercise offline place answers and guide citations on the dedicated emulator's Ask screen."""
import json
import re
from pathlib import Path
from emulator_smoke import shell, type_in_box
from emulator_ui import find, nodes, tap

ROOT = Path(__file__).resolve().parents[1]

def scroll_to(label, up=False):
    width, height = map(int, re.findall(r"(\d+)x(\d+)", shell("wm", "size"))[-1])
    for _ in range(9):
        for node in nodes():
            if node.get("text") != label and node.get("content-desc") != label:
                continue
            bounds = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
            if len(bounds) == 4 and bounds[3] - bounds[1] > 12:
                return
        start, end = (.30, .78) if up else (.78, .30)
        shell("input", "swipe", str(width//2), str(round(height*start)), str(width//2), str(round(height*end)), "240")
    raise AssertionError(f"Could not reveal {label}")

def submit(question):
    if any(n.get("text") == "Reset Ask" for n in nodes()):
        tap("Reset Ask")
    scroll_to("Ask a question", up=True)
    type_in_box(question)
    find(question)
    tap("Look up")

def verify():
    lessons = json.loads((ROOT / "app/src/main/assets/manual/lessons.json").read_text(encoding="utf-8"))
    by_id = {lesson["id"]: lesson for lesson in lessons}
    for case in json.loads((ROOT / "tools/ask_cases.json").read_text()):
        question = case["question"]
        lesson = by_id[case["guide"]]
        number = case["step"]
        step = lesson["steps"][number-1]
        submit(question)
        scroll_to(f"Open guide step {number}")
        tap(f"Open guide step {number}")
        find(lesson["title"])
        find(f'{number} / {len(lesson["steps"])}', partial=True)
        find(step["heading"], partial=True)
        shell("input", "keyevent", "4")
        print("PASS", case["guide"], number, question, flush=True)

    submit("I'm in hatta, where can I find fresh water?")
    find("DRINKING WATER NEAR HATTA")
    scroll_to("Open map results")
    tap("Open map results")
    find("distance from Hatta", partial=True)
    shell("input","keyevent","4")
    find("Directory")
    shell("input","keyevent","4")
    scroll_to("Open guide step 1")
    tap("Open guide step 1")
    find(by_id["findwater"]["title"])
    find("1 / 6", partial=True)
    shell("input","keyevent","4")
    submit("Which page mentions Bluewaters Island?")
    page = "Dubai Can drinking-water refill map (2024) · page 1"
    scroll_to(page)
    tap(page)
    find("Page 1", partial=True)
    print("PASS Hatta map distance origin, water guide and standalone PDF page", flush=True)
    shell("input", "keyevent", "4")
    tap("Reset Ask")
    type_in_box("Another question")
    tap("Clear question")
    form = nodes()
    field = next(n for n in form if n.get("class") == "android.widget.EditText")
    assert field.get("text", "") in ("", "Ask a question", "Where can I find water in Hatta?") and field.get("focused") == "true"
    assert all(n.get("text") != "Reset Ask" and n.get("content-desc") != "Clear question" for n in form)
    print("PASS clearing a submitted search and an unsubmitted draft", flush=True)
    shell("input", "keyevent", "4")
    tap("How do I find north?")
    for label, heading in [("By day", "Set a vertical stick"),
                           ("At night", "Trace the pointer stars")]:
        tap(label)
        find(heading, partial=True)
        shell("input", "keyevent", "4")
    tap("Reset Ask")
    tap("How long do I boil drinking water?")
    scroll_to("Open guide step 3")
    tap("Open guide step 3")
    find(by_id["treat"]["steps"][2]["heading"], partial=True)
    shell("input", "keyevent", "4")
    tap("Reset Ask")
    find("Where can I find drinking water near me?")
    print("PASS one-tap questions open their cited passages and Reset restores suggestions", flush=True)
if __name__ == "__main__":
    verify()
