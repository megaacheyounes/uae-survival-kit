"""Regression check for Library Back navigation on the project emulator."""
from emulator_smoke import launch, shell, type_in_box
from emulator_ui import find, nodes, tap


def check_navigation():
    launch()
    tap("Library")
    title = "Household fire and evacuation"
    for _ in range(12):
        match = next((n for n in nodes() if n.get("text") == title), None)
        if match is not None:
            break
        shell("input", "swipe", "400", "1160", "400", "450", "350")
    else:
        raise AssertionError("Checklist missing from Library")
    before = match.get("bounds")
    tap(title)
    assert not any(n.get("text") == "Library" for n in nodes())
    shell("input", "keyevent", "4")
    assert find(title).get("bounds") == before

    tap("Home")
    tap("Library")
    assert find(title).get("bounds") == before
    for _ in range(12):
        if any(n.get("class") == "android.widget.EditText" for n in nodes()):
            break
        shell("input", "swipe", "400", "450", "400", "1160", "350")
    type_in_box("Kite Beach")
    tap("Find a dependable water supply")
    find("3 / 6")
    tap("Back")
    find("Kite Beach")
    find("Find a dependable water supply")


if __name__ == "__main__":
    check_navigation()
    print("PASS Library anchor, tab context, focused reader and search restoration")
