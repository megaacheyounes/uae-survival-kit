"""Walk five narrated incidents and their citations on an unlocked English-language phone."""
import argparse
import shlex
import time

from check_pair_navigation import PACKAGE, Phone

CASES = [
    ("I ate a dog's foot now I feel sick, how can I deal with it?", "Sick after eating questionable food", "Stop eating the food"),
    ("I went into a cave and the air was cloudy, I feel strange, what is it ? what should I do ?", "Feeling unwell in an enclosed space", "Get to fresh air now"),
    ('I found a mushroom with a red top "head", is it safe to it? how can I tell ?', "An unidentified wild mushroom", "Leave it uneaten"),
    ("some bug bit me, it doesn't feel itchy but I can see the spot red and bleeding!", "Bleeding after an insect bite or sting", "Check breathing and stop the bleeding"),
    ("its 4: 00 PM I think, but there is no sun, and the birds aren't singing, what is happening ?", "Unexpected darkness during the day", "Check visibility before moving"),
]

VARIANTS = [
    ("I felt woozy inside an old mine shaft.", "Feeling unwell in an enclosed space", "Get to fresh air now", 1),
    ("My child nibbled a toadstool but feels fine.", "An unidentified wild mushroom", "Get poison advice now if any was swallowed", 2),
    ("Someone swallowed a button battery at lunch.", "A possible poisoning or swallowed object", "A battery, magnet or sharp object needs emergency care now", 2),
    ("It suddenly got dark at lunch.", "Unexpected darkness during the day", "Check visibility before moving", 1),
    ("A stray cat scratched my leg.", "A bite or scratch from a mammal", "Wash the wound and get assessed today", 1),
]


def start(phone):
    phone.shell("am", "force-stop", PACKAGE)
    phone.shell("am", "start", "-W", "-n", PACKAGE + "/ae.fieldguide.MainActivity")
    phone.tap("Ask")


def submit(phone, query):
    if any(n.get("text") == "Reset Ask" for n in phone.nodes()):
        phone.tap("Reset Ask")
    phone.tap("Ask a question")
    # Give Compose a frame to consume each word even on lower-memory phones.
    words = query.split(" ")
    command = []
    for i, word in enumerate(words):
        command.append("input text " + shlex.quote(("%s" if i else "") + word))
        command.append("sleep 0.09")
    phone.shell("; ".join(command))
    def field_text():
        return next(n.get("text") for n in phone.nodes() if n.get("class") == "android.widget.EditText")
    entered = field_text()
    if entered != query:
        phone.shell("input", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_A")
        phone.shell("input", "keyevent", "KEYCODE_DEL")
        for offset in range(0, len(query), 16):
            phone.shell("; ".join("input text " + shlex.quote("%s" if c == " " else c) + "; sleep 0.12"
                                 for c in query[offset:offset+16]))
        entered = field_text()
    assert entered == query, f"Keyboard entered {entered!r}"
    if b"mInputShown=true" in phone.shell("dumpsys", "input_method"):
        phone.back(False)
    phone.tap("Look up")


def verify(phone, variants=False):
    start(phone)
    cases = VARIANTS if variants else [(*case, 1) for case in CASES]
    for question, title, heading, step in cases:
        submit(phone, question)
        phone.find(title)
        phone.tap_node(phone.find(f"Open guide step {step}", scroll=True))
        phone.find(title)
        phone.find(heading)
        phone.back(False)
        phone.find("Reset Ask")
        print("PASS", title, "and cited first action", flush=True)


def verify_model(phone):
    start(phone)
    phone.find("On-device AI ready · Qwen runs offline")
    cases = [
        ("How can I get back to the car later using a sketch of my walk?", "Make a paper route and turnaround card", "Draw the return route first"),
        ("How can two shadow marks show east and west?", "Find east and west with a stick", "Wait for the second crossing"),
        ("Which chess opening did Bobby Fischer play in Reykjavik?", None, None),
    ]
    for question, title, heading in cases:
        submit(phone, question)
        begin = time.monotonic()
        while time.monotonic() - begin < 180:
            labels = [n.get("text", "") for n in phone.nodes()]
            if "ON-DEVICE AI ANSWER" in labels:
                assert title and title in labels, (question, labels)
                link = next(text for text in labels if text.startswith("Open guide step "))
                phone.tap_node(phone.find(link, scroll=True))
                phone.find(title)
                phone.find(heading)
                phone.back(False)
                break
            if any(s.startswith("I don’t have enough evidence") for s in labels):
                assert title is None, question
                break
            time.sleep(.3)
        else:
            raise AssertionError(f"Timed out waiting for an answer to {question!r}")
        print("PASS", title or "Unsupported question", round(time.monotonic() - begin, 1), "seconds", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--port", type=int, default=5037)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--variants", action="store_true")
    mode.add_argument("--model", action="store_true", help="Requires the optional model to be enabled")
    args = parser.parse_args()
    phone = Phone(args.adb, args.serial, args.port)
    if args.model:
        verify_model(phone)
    else:
        verify(phone, args.variants)
