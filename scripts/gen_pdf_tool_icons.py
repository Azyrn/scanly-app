"""Generates the PDF tools icon layers (Android vector XML) plus an SVG preview sheet.

    python3 scripts/gen_pdf_tool_icons.py app/src/main/res/drawable /tmp/preview.svg

The tool -> layer table printed at the end must match PdfTool.layers in ui/pdftools/ToolVisuals.kt.

Every icon = a large back file (top-left) + a front element overlapping its lower-right corner.
24-unit grid, stroke 1.5 everywhere (scaled Tabler glyphs divide it by their scale).
"""
import os
import sys

OUT = sys.argv[1]  # drawable dir
PREVIEW = sys.argv[2]  # svg path
S = 1.5
HOLE = 10.0  # back layer is cut away right of / below this (front starts at 12; gap 1.25 + half stroke)


def doc(l, t, r, b, f, rad):
    outline = (f"M{r - f} {t}H{l + rad}A{rad} {rad} 0 0 0 {l} {t + rad}V{b - rad}"
               f"A{rad} {rad} 0 0 0 {l + rad} {b}H{r - rad}A{rad} {rad} 0 0 0 {r} {b - rad}V{t + f}Z")
    fold = f"M{r - f} {t}V{t + f - 1}A1 1 0 0 0 {r - f + 1} {t + f}H{r}"
    return [outline, fold]


def rrect(l, t, r, b, rad):
    return (f"M{l + rad} {t}H{r - rad}A{rad} {rad} 0 0 1 {r} {t + rad}V{b - rad}"
            f"A{rad} {rad} 0 0 1 {r - rad} {b}H{l + rad}A{rad} {rad} 0 0 1 {l} {b - rad}"
            f"V{t + rad}A{rad} {rad} 0 0 1 {l + rad} {t}Z")


# Stroked letters in a w x h box at (x, y).
def letter(ch, x, y, w, h):
    if ch == "W":
        q = w / 4
        return [f"M{x} {y}L{x + q} {y + h}L{x + 2 * q} {y + h * 0.45}L{x + 3 * q} {y + h}L{x + w} {y}"]
    if ch == "X":
        return [f"M{x} {y}L{x + w} {y + h}", f"M{x + w} {y}L{x} {y + h}"]
    if ch == "P":
        bowl = h * 0.58
        rr = bowl / 2
        return [f"M{x} {y + h}V{y}H{x + w - rr}A{rr} {rr} 0 0 1 {x + w - rr} {y + bowl}H{x}"]
    raise ValueError(ch)


def lines(x, y, w, gap, last):
    return [f"M{x} {y}H{x + w}", f"M{x} {y + gap}H{x + w}", f"M{x} {y + 2 * gap}H{x + last}"]


# A Tabler 24-grid glyph fitted into a box: (paths, scale, tx, ty)
def tabler(paths, cx, cy, size, span=18.0, mid=12.0):
    s = size / span
    return {"paths": paths, "scale": s, "tx": cx - mid * s, "ty": cy - mid * s}


TABLER_PHOTO = [
    "M15 8h.01",
    "M3 6a3 3 0 0 1 3 -3h12a3 3 0 0 1 3 3v12a3 3 0 0 1 -3 3h-12a3 3 0 0 1 -3 -3v-12z",
    "M3 16l5 -5c.928 -.893 2.072 -.893 3 0l5 5",
    "M14 14l1 -1c.928 -.893 2.072 -.893 3 0l3 3",
]
GLYPHS = {
    "plus": ["M12 5l0 14", "M5 12l14 0"],
    "scissors": ["M6 7m-3 0a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "M6 17m-3 0a3 3 0 1 0 6 0a3 3 0 1 0 -6 0",
                 "M8.6 8.6l10.4 10.4", "M8.6 15.4l10.4 -10.4"],
    "compress": ["M5 9h4v-4", "M3 3l6 6", "M5 15h4v4", "M3 21l6 -6", "M19 9h-4v-4", "M15 9l6 -6",
                 "M19 15h-4v4", "M15 15l6 6"],
    "maximize": ["M16 4l4 0l0 4", "M14 10l6 -6", "M8 20l-4 0l0 -4", "M4 20l6 -6",
                 "M16 20l4 0l0 -4", "M14 14l6 6", "M8 4l-4 0l0 4", "M4 4l6 6"],
    "pencil": ["M4 20h4l10.5 -10.5a2.828 2.828 0 1 0 -4 -4l-10.5 10.5v4", "M13.5 6.5l4 4"],
    "hash": ["M5 9l14 0", "M5 15l14 0", "M11 4l-4 16", "M17 4l-4 16"],
    "droplet": ["M7.502 19.423c2.602 2.105 6.395 2.105 8.996 0c2.602 -2.105 3.262 -5.708 1.566 -8.546l-4.89 -7.26"
                "c-.42 -.625 -1.287 -.803 -1.936 -.397a1.376 1.376 0 0 0 -.41 .397l-4.893 7.26"
                "c-1.695 2.838 -1.035 6.441 1.567 8.546"],
    "rotate": ["M4.05 11a8 8 0 1 1 .5 4m-.5 5v-5h5"],
    "lock": ["M5 13a2 2 0 0 1 2 -2h10a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-6z",
             "M11 16a1 1 0 1 0 2 0a1 1 0 0 0 -2 0", "M8 11v-4a4 4 0 1 1 8 0v4"],
    "lock_open": ["M5 13a2 2 0 0 1 2 -2h10a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-6z",
                  "M11 16a1 1 0 1 0 2 0a1 1 0 0 0 -2 0", "M8 11v-5a4 4 0 0 1 8 0"],
    "sort": ["M3 8l4 -4l4 4", "M7 4l0 16", "M13 16l4 4l4 -4", "M17 4l0 16"],
    "wrench": ["M7 10h3v-3l-3.5 -3.5a6 6 0 0 1 8 8l6 6a2 2 0 0 1 -3 3l-6 -6a6 6 0 0 1 -8 -8l3.5 3.5"],
    "signature": ["M3 17c3.333 -3.333 5 -6 5 -8c0 -3 -1 -3 -2 -3s-2.032 1.085 -2 3c.034 2.048 1.658 4.877 2.5 6"
                  "c1.5 2 2.5 2.5 3.5 1l2 -3c.333 2.667 1.333 4 3 4c.53 0 2.639 -2 3 -2c.517 0 1.517 .667 3 2"],
    "letter_t": ["M6 4l12 0", "M12 4l0 16"],
}

# ---- geometry -------------------------------------------------------------------------------
BACK = (2.5, 1.5, 15.5, 18.0)  # big file
FRONT = (12.0, 12.0, 21.0, 23.0)  # small file badge
ARROW_Y = 18.25
GLYPH_C, GLYPH_SIZE = (17.25, 17.25), 10.5  # front action glyph box 12..22.5

HOLE_CLIP = f"M0 0H24V{HOLE}H{HOLE}V24H0Z"  # everything except the lower-right corner
NOTCH = f"M0 0H24V{ARROW_Y - 2.25}H19.25V{ARROW_Y + 2.25}H24V24H0Z"  # badge minus the arrow's exit


def back_doc(content):
    l, t, r, b = BACK
    return {"paths": doc(l, t, r, b, 4, 2) + content, "clip": HOLE_CLIP}


BACK_GLYPH = (5.25, 5.5, 5.5, 6.0)  # x, y, w, h of a letter on the back file
PARTS = {
    "back_pdf": back_doc(lines(5.5, 6.5, 6.5, 2.75, 3.5)),
    "back_word": back_doc(letter("W", *BACK_GLYPH)),
    "back_powerpoint": back_doc(letter("P", 6.0, 5.5, 4.25, 6.0)),
    "back_excel": back_doc(letter("X", 5.75, 5.5, 4.5, 6.0)),
    "back_image": {"tabler": [tabler(TABLER_PHOTO, 9.25, 9.75, 13.5)], "clip": HOLE_CLIP},
    "back_image_stack": {"tabler": [tabler(TABLER_PHOTO, 10.0, 10.5, 12.0)],
                         "paths": ["M2.25 14.25V5.25A3 3 0 0 1 5.25 2.25H13.75"], "clip": HOLE_CLIP},
}


def badge_doc(content, notch=True):
    l, t, r, b = FRONT
    part = {"paths": doc(l, t, r, b, 3, 1.5) + content}
    if notch:
        part["clip"] = NOTCH
    return part


FG = (13.75, ARROW_Y - 2.25, 3.5, 4.5)  # letter box inside the badge, left of the arrow
PARTS.update({
    "front_pdf": badge_doc(["M14.5 16.75H16.5", "M14.5 19.75H17.25"] + []),
    "front_word": badge_doc(letter("W", 13.5, ARROW_Y - 2.25, 3.75, 4.5)),
    "front_powerpoint": badge_doc(letter("P", 14.0, ARROW_Y - 2.25, 3.0, 4.5)),
    "front_excel": badge_doc(letter("X", 13.75, ARROW_Y - 2.0, 3.0, 4.0)),
    "front_pdf_plain": badge_doc([], notch=False),
    "front_image": {"paths": [rrect(12.0, 12.75, 21.0, 22.5, 2.0), "M13 20.5l2.75 -2.75c.5 -.5 1 -.5 1.5 0l1 1",
                              "M17.5 15.5h.01"], "clip": NOTCH},
    "front_image_stack": {"paths": [rrect(13.25, 14.0, 21.0, 22.5, 2.0), "M11.5 20V14A2 2 0 0 1 13.5 12H19",
                                    "M14.5 21l2 -2c.5 -.5 1 -.5 1.5 0l.75 .75", "M18 16.5h.01"], "clip": NOTCH},
    "arrow": {"paths": [f"M18.75 {ARROW_Y}H22.75", f"M21 {ARROW_Y - 1.75}L22.75 {ARROW_Y}L21 {ARROW_Y + 1.75}"]},
    "plus": {"paths": ["M16.5 15.5V20.5", "M14 18H19"]},
})
for name, g in GLYPHS.items():
    if name == "plus":
        continue
    PARTS["glyph_" + name] = {"tabler": [tabler(g, *GLYPH_C, GLYPH_SIZE)]}

# tool -> [(part, role)]; role is a ToolFormat name or INK
CONVERT = lambda src, dst: [(f"back_{src}", src), (f"front_{dst}", dst), ("arrow", "ink")]
ACTION = lambda glyph, back="pdf": [(f"back_{back}", "pdf" if back == "pdf" else "image"),
                                   (f"glyph_{glyph}", "ink")]
TOOLS = {
    "MERGE": [("back_pdf", "pdf"), ("front_pdf_plain", "pdf"), ("plus", "ink")],
    "SPLIT": ACTION("scissors"),
    "COMPRESS": ACTION("compress"),
    "PDF_TO_WORD": CONVERT("pdf", "word"),
    "PDF_TO_POWERPOINT": CONVERT("pdf", "powerpoint"),
    "PDF_TO_EXCEL": CONVERT("pdf", "excel"),
    "WORD_TO_PDF": CONVERT("word", "pdf"),
    "POWERPOINT_TO_PDF": CONVERT("powerpoint", "pdf"),
    "EXCEL_TO_PDF": CONVERT("excel", "pdf"),
    "EDIT": ACTION("pencil"),
    "PDF_TO_JPG": CONVERT("pdf", "image"),
    "IMAGE_TO_PDF": CONVERT("image", "pdf"),
    "PAGE_NUMBERS": ACTION("hash"),
    "WATERMARK": ACTION("droplet"),
    "ROTATE": ACTION("rotate"),
    "UNLOCK": ACTION("lock_open"),
    "PROTECT": ACTION("lock"),
    "ORGANIZE": ACTION("sort"),
    "REPAIR": ACTION("wrench"),
    "SIGN": ACTION("signature"),
    "EXTRACT_TEXT": ACTION("letter_t"),
    "COMPRESS_IMAGE": ACTION("compress", "image"),
    "CONVERT_TO_JPG": [("back_image_stack", "image"), ("front_image", "image"), ("arrow", "ink")],
    "CONVERT_FROM_JPG": [("back_image", "image"), ("front_image_stack", "image"), ("arrow", "ink")],
    "RESIZE_IMAGE": ACTION("maximize", "image"),
}


def fmt(v):
    return f"{v:.4f}".rstrip("0").rstrip(".")


# ---- android xml ----------------------------------------------------------------------------
def xml_path(d, width, indent):
    p = " " * indent
    return (f'{p}<path\n{p}    android:pathData="{d}"\n{p}    android:strokeWidth="{fmt(width)}"\n'
            f'{p}    android:strokeColor="@android:color/white"\n{p}    android:strokeLineCap="round"\n'
            f'{p}    android:strokeLineJoin="round" />\n')


def xml_part(name, part):
    body = ""
    for t in part.get("tabler", []):
        body += (f'        <group\n            android:translateX="{fmt(t["tx"])}"\n'
                 f'            android:translateY="{fmt(t["ty"])}"\n            android:scaleX="{fmt(t["scale"])}"\n'
                 f'            android:scaleY="{fmt(t["scale"])}">\n')
        body += "".join(xml_path(d, S / t["scale"], 12) for d in t["paths"])
        body += "        </group>\n"
    body += "".join(xml_path(d, S, 8) for d in part.get("paths", []))
    clip = part.get("clip")
    head = ('<?xml version="1.0" encoding="utf-8"?>\n'
            "<!-- One layer of a PDF tools icon, generated by scripts/gen_pdf_tool_icons.py (glyphs: Tabler Icons, MIT). 24 grid, stroke 1.5; tinted per layer in ToolVisuals.kt. -->\n"
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="24dp"\n    android:height="24dp"\n'
            '    android:viewportWidth="24"\n    android:viewportHeight="24">\n    <group>\n')
    if clip:
        head += f'        <clip-path android:pathData="{clip}" />\n'
    return head + body + "    </group>\n</vector>\n"


used = sorted({p for layers in TOOLS.values() for p, _ in layers})
for name in used:
    with open(os.path.join(OUT, f"ic_tool_{name}.xml"), "w") as fh:
        fh.write(xml_part(name, PARTS[name]))

# ---- preview --------------------------------------------------------------------------------
LIGHT = {"pdf": "#E00E00", "word": "#185ABD", "excel": "#107C41", "powerpoint": "#C43E1C",
         "image": "#8A6C33", "ink": "#1C1B1F"}
DARK = {"pdf": "#FF5A4F", "word": "#6794D7", "excel": "#55B17E", "powerpoint": "#DC816A",
        "image": "#D8B878", "ink": "#E6E1E5"}


def svg_part(part, color, cid):
    out = ""
    clip = part.get("clip")
    if clip:
        out += f'<clipPath id="{cid}"><path d="{clip}"/></clipPath><g clip-path="url(#{cid})">'
    else:
        out += "<g>"
    st = f'fill="none" stroke="{color}" stroke-linecap="round" stroke-linejoin="round"'
    for t in part.get("tabler", []):
        out += f'<g transform="translate({t["tx"]} {t["ty"]}) scale({t["scale"]})">'
        out += "".join(f'<path d="{d}" {st} stroke-width="{S / t["scale"]}"/>' for d in t["paths"])
        out += "</g>"
    out += "".join(f'<path d="{d}" {st} stroke-width="{S}"/>' for d in part.get("paths", []))
    return out + "</g>"


cell, cols = 130, 5
names = list(TOOLS)
rows = (len(names) + cols - 1) // cols
svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{cell * cols * 2}" height="{cell * rows}">']
n = 0
for half, pal, bg, tile in ((0, LIGHT, "#FEF7FF", "#F3EDF7"), (1, DARK, "#141218", "#211F26")):
    svg.append(f'<rect x="{half * cell * cols}" width="{cell * cols}" height="{cell * rows}" fill="{bg}"/>')
    for i, tool in enumerate(names):
        x = half * cell * cols + (i % cols) * cell
        y = (i // cols) * cell
        svg.append(f'<rect x="{x + 6}" y="{y + 6}" width="{cell - 12}" height="{cell - 12}" rx="18" fill="{tile}"/>')
        svg.append(f'<g transform="translate({x + 25} {y + 14}) scale(3.333)">')
        for part, role in TOOLS[tool]:
            n += 1
            svg.append(svg_part(PARTS[part], pal[role], f"c{n}"))
        svg.append("</g>")
        svg.append(f'<text x="{x + cell / 2}" y="{y + cell - 16}" font-size="11" text-anchor="middle" '
                   f'fill="{pal["ink"]}" font-family="sans-serif">{tool}</text>')
svg.append("</svg>")
with open(PREVIEW, "w") as fh:
    fh.write("".join(svg))

for tool, layers in TOOLS.items():
    print(tool, layers)
