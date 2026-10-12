"""Pictures for the Help guide (DOC-1), cropped from the screenshots the tests take.

Run the tests first (./gradlew testDebugUnitTest writes app/build/screenshots), then
    python3 tools/build_help_images.py
It writes app/src/main/assets/help/img/<name>.webp. The guide shows one with a line of its own:
    ![Caption](name.webp)
Crops are fractions of the screenshot (left, top, right, bottom), so they survive small layout changes.
"""
import os
import sys
from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SHOTS = os.path.join(ROOT, "app", "build", "screenshots")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "help", "img")
MAX_WIDTH = 1000

DIALOG = (0.03, 0.06, 0.97, 0.94)  # a big window on the tablet
PICTURES = {
    # name: (screenshot, crop)
    "book-picker": ("15-picker-books", (0.035, 0.10, 0.965, 0.99)),
    "glance": ("157-chapter-at-a-glance", (0.0, 0.11, 0.95, 0.50)),
    "hard-word": ("159-hard-word-meaning", (0.03, 0.06, 0.97, 0.42)),
    "points-to-christ": ("201-points-to-christ-isaiah-53", (0.0, 0.10, 0.66, 0.80)),
    "points-to-christ-card": ("202-points-to-christ-card", (0.03, 0.06, 0.97, 0.42)),
    "toolbar": ("70-toolbar", (0.0, 0.0, 0.54, 0.07)),
    "pen-colours": ("205-pen-colours-dark-picker", (0.0, 0.0, 0.60, 0.50)),
    "phone": ("180-phone-393", (0.0, 0.0, 1.0, 0.50)),
    "highlight-edit": ("40-highlight-edit", (0.0, 0.12, 0.76, 0.45)),
    "text-selection": ("14-text-selection", (0.0, 0.12, 0.76, 0.45)),
    "margin-arrow": ("77-arrow", (0.0, 0.12, 0.65, 0.60)),
    "text-box": ("73-text-box-selected", (0.0, 0.12, 1.0, 0.55)),
    "lasso": ("12-lasso-selection", (0.0, 0.12, 0.70, 0.50)),
    "layers": ("91-layer-menu", DIALOG),
    "verse-details": ("131-verse-commentary", DIALOG),
    "greek": ("102-greek", (0.03, 0.06, 0.97, 0.60)),
    "word-study": ("85-word-study", DIALOG),
    "search": ("45-search-grouped", DIALOG),
    "passage-popover": ("18-passage-popover", (0.0, 0.11, 0.60, 0.75)),
    "panel-menu": ("70c-panel-menu", (0.0, 0.08, 0.40, 0.80)),
    "tabs": ("133-two-tabs", (0.0, 0.08, 0.65, 0.45)),
    "commentary": ("148-ai-commentary", (0.0, 0.05, 1.0, 1.0)),
    "reflections": ("150-reflections", (0.0, 0.05, 1.0, 1.0)),
    "ai-chat": ("132-ai-chat-window", (0.62, 0.10, 1.0, 1.0)),
    "person": ("94-person", (0.50, 0.05, 1.0, 0.62)),
    "family-tree": ("104-family-tree", DIALOG),
    "sketch-page": ("130-paul", (0.0, 0.05, 1.0, 1.0)),
    "sketch-list": ("116-sketch-list", (0.0, 0.07, 1.0, 0.65)),
    "my-notes": ("42-highlights-list", (0.0, 0.07, 1.0, 0.33)),
    "reading-stats": ("194-phone-stats-top", (0.0, 0.0, 1.0, 0.55)),
    "settings": ("71-settings", DIALOG),
    "sync": ("189-settings-sync-on", DIALOG),
}


def main():
    os.makedirs(OUT, exist_ok=True)
    missing = [s for s, _ in PICTURES.values() if not os.path.exists(os.path.join(SHOTS, s + ".png"))]
    if missing:
        sys.exit("Run the tests first; missing screenshots: " + ", ".join(missing))
    total = 0
    for name, (shot, (l, t, r, b)) in PICTURES.items():
        im = Image.open(os.path.join(SHOTS, shot + ".png")).convert("RGB")
        w, h = im.size
        im = im.crop((round(l * w), round(t * h), round(r * w), round(b * h)))
        if im.width > MAX_WIDTH:
            im = im.resize((MAX_WIDTH, round(im.height * MAX_WIDTH / im.width)), Image.LANCZOS)
        path = os.path.join(OUT, name + ".webp")
        im.save(path, "WEBP", quality=80, method=6)
        total += os.path.getsize(path)
    for f in os.listdir(OUT):  # pictures no longer listed
        if f[:-5] not in PICTURES:
            os.remove(os.path.join(OUT, f))
    print(f"{len(PICTURES)} pictures, {total / 1e6:.2f} MB, in {OUT}")


if __name__ == "__main__":
    main()
