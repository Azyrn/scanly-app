#!/usr/bin/env python3
"""Regenerates rotated.pdf: 4 A4 pages carrying /Rotate 0, 90, 180 and 270.

    python3 make_rotated.py rotated.pdf

Each page draws, in *displayed* space, labels at the four displayed corners and the guide
lines where a BOTTOM_RIGHT signature must land: right edge 0.07*DW in from the right, bottom
edge 0.07*DW up from the bottom, left edge a further 0.28*DW in. The guides only mean
anything while these two fractions match DocToolsViewModel's; change them there and the
fixture has to be regenerated.

Stdlib only, so the fixture can be rebuilt without a PDF toolchain installed.
"""

W, H = 595.0, 842.0
WIDTH_FRACTION = 0.28
MARGIN_FRACTION = 0.07


def display_matrix(rotation):
    """Maps displayed space (origin displayed bottom-left, y up) into unrotated PDF space."""
    return {
        0: (1, 0, 0, 1, 0, 0),
        90: (0, 1, -1, 0, W, 0),
        180: (-1, 0, 0, -1, W, H),
        270: (0, -1, 1, 0, 0, H),
    }[rotation]


def displayed_size(rotation):
    return (H, W) if rotation in (90, 270) else (W, H)


def text(x, y, size, body, gray=0):
    return f"BT {gray} g /F1 {size} Tf 1 0 0 1 {x:.2f} {y:.2f} Tm ({body}) Tj ET\n"


def content_for(rotation):
    dw, dh = displayed_size(rotation)
    margin = dw * MARGIN_FRACTION
    box_w = dw * WIDTH_FRACTION
    right = dw - margin
    left = right - box_w

    ops = ["q %g %g %g %g %g %g cm\n" % display_matrix(rotation)]
    ops.append(f"0.85 g 0 0 {dw:.2f} {dh:.2f} re f\n")
    ops.append(f"1 g 10 10 {dw - 20:.2f} {dh - 20:.2f} re f\n")
    ops.append(f"0 G 2 w 10 10 {dw - 20:.2f} {dh - 20:.2f} re S\n")

    ops.append(text(24, dh - 40, 13, "TOP-LEFT"))
    ops.append(text(dw - 110, dh - 40, 13, "TOP-RIGHT"))
    ops.append(text(24, 28, 13, "BOTTOM-LEFT"))
    ops.append(text(dw - 130, 28, 13, "BOTTOM-RIGHT"))

    ops.append(text(dw / 2 - 70, dh / 2 + 40, 26, f"/Rotate {rotation}"))
    ops.append(text(dw / 2 - 92, dh / 2, 11, f"displayed {dw:.0f} x {dh:.0f} pt"))
    ops.append(text(dw / 2 - 92, dh / 2 - 16, 11, f"mediabox {W:.0f} x {H:.0f} pt"))

    # The box the app should fill: right and bottom edges are exact, the top edge depends on
    # the stamp's aspect ratio, so only the three known edges are drawn.
    ops.append("1 0 0 RG 1.5 w [4 3] 0 d\n")
    ops.append(f"{left:.2f} {margin:.2f} m {right:.2f} {margin:.2f} l S\n")
    ops.append(f"{right:.2f} {margin:.2f} m {right:.2f} {margin + 0.32 * dh:.2f} l S\n")
    ops.append(f"{left:.2f} {margin:.2f} m {left:.2f} {margin + 0.32 * dh:.2f} l S\n")
    ops.append("[] 0 d\n")
    ops.append(text(left, margin - 16, 9, f"expected mark: w={box_w:.0f}pt, margin={margin:.0f}pt"))

    ops.append("Q\n")
    return "".join(ops).encode("latin-1")


def build(path):
    rotations = [0, 90, 180, 270]
    objects = {}

    objects[1] = b"<< /Type /Catalog /Pages 2 0 R >>"
    kids = " ".join(f"{4 + i * 2} 0 R" for i in range(len(rotations)))
    objects[2] = f"<< /Type /Pages /Count {len(rotations)} /Kids [{kids}] >>".encode()
    objects[3] = b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"

    for i, rotation in enumerate(rotations):
        page_id = 4 + i * 2
        stream_id = page_id + 1
        objects[page_id] = (
            f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {W:.0f} {H:.0f}] "
            f"/Rotate {rotation} /Resources << /Font << /F1 3 0 R >> >> "
            f"/Contents {stream_id} 0 R >>"
        ).encode()
        body = content_for(rotation)
        objects[stream_id] = f"<< /Length {len(body)} >>\nstream\n".encode() + body + b"endstream"

    out = bytearray(b"%PDF-1.7\n%\xe2\xe3\xcf\xd3\n")
    offsets = {}
    for number in sorted(objects):
        offsets[number] = len(out)
        out += f"{number} 0 obj\n".encode() + objects[number] + b"\nendobj\n"

    xref_at = len(out)
    count = max(objects) + 1
    out += f"xref\n0 {count}\n".encode()
    out += b"0000000000 65535 f \n"
    for number in range(1, count):
        out += f"{offsets[number]:010d} 00000 n \n".encode()
    out += f"trailer\n<< /Size {count} /Root 1 0 R >>\nstartxref\n{xref_at}\n%%EOF\n".encode()

    with open(path, "wb") as handle:
        handle.write(out)
    print(f"wrote {path} ({len(out)} bytes)")


if __name__ == "__main__":
    import sys

    build(sys.argv[1])
