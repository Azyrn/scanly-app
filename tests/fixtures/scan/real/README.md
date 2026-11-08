# Real scan fixtures

Six phone photos that gate the scanner on real light and real lenses. Synthetic fixtures
(`../synthetic/`) are only PROVISIONAL; these are the ones that decide whether our scanner
beats Google's ML Kit scanner (GMS).

## Photos to add

| File | What to shoot |
|---|---|
| `paper_page.jpg` | An A4 printed page with text and a table on a light desk, shot from a normal standing angle (mild perspective). Leave desk visible on all four sides. |
| `angled_page.jpg` | An A4 page shot at a steep angle (~35 degrees from straight down), with uneven light (a lamp from one side) and things on the desk around it (pen, mug, another sheet) that do not cover the corners. |
| `id_front.jpg` | Front of an ID-1 card (use a specimen or bank-style card, never a real ID) lying on a desk, angled, card filling about half the frame width. |
| `id_back.jpg` | Back of the same card, same conditions. |
| `whiteboard.jpg` | A whiteboard with black, blue, red and green marker writing, the frame visible all round, room light falling off to one side, a ceiling-light glare on the board. |
| `screen.jpg` | A laptop screen showing a text document, shot so the dark bezel and part of the keyboard are visible; moire and a reflection are expected. This is the case GMS returned un-flattened and dark. |

How to shoot: the phone's main camera, default camera app, no zoom, no flash, no HDR filters,
full resolution. Hold the phone the way a user would; do not line it up perfectly.

## Orientation

`ScanQualityTest` reads pixels as stored (Java ImageIO ignores EXIF rotation). Before adding a
photo, bake its EXIF rotation into the pixels, for example:

```
python -c "import sys; from PIL import Image, ImageOps; ImageOps.exif_transpose(Image.open(sys.argv[1])).save(sys.argv[1], quality=92)" photo.jpg
```

## corners.json

Hand-mark each photo's document corners in photo pixels and add them to `corners.json` in this
folder, in the same format as `../synthetic/corners.json`:

```json
{
  "paper_page": {
    "corners": [[x, y], [x, y], [x, y], [x, y]],
    "mode": "document",
    "white_ground": true
  }
}
```

- `corners`: TL, TR, BR, BL of the document as it lies (for the screen: the lit display area,
  not the bezel; for the whiteboard: the writing surface inside the frame; for an ID card: where
  the straight edges would meet, ignoring the rounded corners).
- `mode`: `document`, `id_card` or `whiteboard` (the scan mode a user would pick).
- `white_ground`: true for paper, whiteboards and a white document on a screen (white output is the
  goal), false for cards (white clipping there is the security print lost).
- Real photos have no `truth_size` or `truth/` images, so ink-loss and hue metrics are skipped for
  them; corner error, contrast and white clipping are still measured.

## GMS baseline

For each photo, scan it with the GMS scanner on the phone (gallery import), copy its output, and run

```
scripts/fidelity/gms_quad.py tests/fixtures/scan/real/<name>.jpg <gms_output.jpg> --set real --name <name>
```

which writes `../gms_baseline/real/<name>.jpg` and `<name>.json`. From then on `ScanQualityTest`
fails unless our pipeline beats GMS on that photo: lower corner error, less ink lost, no more
clipped white on non-white-ground content, and at least GMS's contrast.
