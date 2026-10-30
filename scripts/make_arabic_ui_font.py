#!/usr/bin/env python3
"""Builds res/font/arabic_ui_*.ttf from Noto Naskh Arabic UI for the Arabic UI typography.

Since Android 15 the system draws Arabic in the tall "elegant" Naskh (about 1.7 em per line against
Roboto's 1.17), and Compose always grows a line to the extents of a fallback font it uses, so Arabic
screens came out about 45% taller than English. These files are the same Naskh design with:
  - only Arabic code points plus spaces, so Latin letters, digits and punctuation keep the system font;
  - vertical metrics set to Roboto's (ascent 0.928 em, descent 0.244 em, no gap), so an Arabic line
    is exactly as tall as an English one.

    python3 scripts/make_arabic_ui_font.py   (needs fonttools and the Noto Naskh Arabic UI fonts)
"""
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont

SOURCE = "/usr/share/fonts/noto/NotoNaskhArabicUI-{}.ttf"
OUT = Path(__file__).resolve().parent.parent / "app/src/main/res/font"
UNICODES = "U+0020,U+00A0,U+0600-06FF,U+0750-077F,U+0870-08FF,U+FB50-FDFF,U+FE70-FEFF,U+200C-200F,U+25CC"
ROBOTO_ASCENT, ROBOTO_DESCENT = 0.928, 0.244

for weight in ("Regular", "Medium", "Bold"):
    font = TTFont(SOURCE.format(weight))
    options = subset.Options()
    options.layout_features = ["*"]
    options.name_IDs = ["*"]
    subsetter = subset.Subsetter(options)
    subsetter.populate(unicodes=subset.parse_unicodes(UNICODES))
    subsetter.subset(font)
    upm = font["head"].unitsPerEm
    ascent, descent = round(ROBOTO_ASCENT * upm), round(ROBOTO_DESCENT * upm)
    font["hhea"].ascent, font["hhea"].descent, font["hhea"].lineGap = ascent, -descent, 0
    os2 = font["OS/2"]
    os2.sTypoAscender, os2.sTypoDescender, os2.sTypoLineGap = ascent, -descent, 0
    os2.usWinAscent, os2.usWinDescent = ascent, descent
    os2.fsSelection |= 1 << 7  # USE_TYPO_METRICS
    font.save(OUT / f"arabic_ui_{weight.lower()}.ttf")
    print(weight, (OUT / f"arabic_ui_{weight.lower()}.ttf").stat().st_size, "bytes")
