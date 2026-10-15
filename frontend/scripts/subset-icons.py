"""Recorta la fuente Material Symbols (4 MB) a solo los íconos que usa la app.

Busca los nombres de ícono en src/ (props `name=`/`icon=`/`icon:` y textos entre comillas
que parezcan nombres de ícono) y genera src/assets/icons.woff2 con esas ligaduras.
Uso:  python scripts/subset-icons.py   (se corre antes de cada build: npm run icons)
"""
import pathlib
import re
import sys

from fontTools import subset
from fontTools.ttLib import TTFont

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "src"
FONT = ROOT / "node_modules" / "material-symbols" / "material-symbols-outlined.woff2"
OUT = SRC / "assets" / "icons.woff2"

# Íconos usados con nombre dinámico que el escaneo no puede ver.
EXTRA = {"progress_activity", "check_circle", "error", "warning", "info", "close"}

candidates = set(EXTRA)
pattern = re.compile(r"""(?:name|icon)\s*[=:]\s*["']([a-z0-9_]+)["']|["']([a-z][a-z0-9_]{2,40})["']""")
for f in SRC.rglob("*.tsx"):
    for m in pattern.finditer(f.read_text(encoding="utf-8")):
        candidates.add(m.group(1) or m.group(2))
for f in SRC.rglob("*.ts"):
    for m in pattern.finditer(f.read_text(encoding="utf-8")):
        candidates.add(m.group(1) or m.group(2))

font = TTFont(str(FONT))
# Nombres válidos = ligaduras reales de la fuente (los demás textos se descartan).
cmap_names = set()
gsub = font["GSUB"].table
for lookup in gsub.LookupList.Lookup:
    for st in lookup.SubTable:
        lig = getattr(st, "ligatures", None)
        if lig is None and hasattr(st, "ExtSubTable"):
            lig = getattr(st.ExtSubTable, "ligatures", None)
        if lig:
            for first, ligs in lig.items():
                for l in ligs:
                    cmap_names.add(l.LigGlyph)
valid = sorted(n for n in candidates if n in cmap_names)
valid_set = set(valid)
missing = sorted(n for n in candidates if n not in cmap_names and "_" in n and n.islower())

# Poda las ligaduras que no se usan; si no, el cierre de GSUB volvería a meter toda la fuente.
for lookup in gsub.LookupList.Lookup:
    for st in lookup.SubTable:
        target = st.ExtSubTable if hasattr(st, "ExtSubTable") else st
        lig = getattr(target, "ligatures", None)
        if lig:
            for first in list(lig):
                lig[first] = [l for l in lig[first] if l.LigGlyph in valid_set]
                if not lig[first]:
                    del lig[first]

opts = subset.Options()
opts.flavor = "woff2"
opts.layout_features = ["*"]
opts.ignore_missing_glyphs = True
opts.notdef_outline = True
sub = subset.Subsetter(opts)
glyph_order = set(font.getGlyphOrder())
keep = [g for n in valid for g in (n, n + ".fill") if g in glyph_order]
sub.populate(glyphs=keep, text="abcdefghijklmnopqrstuvwxyz0123456789_ ")
sub.subset(font)
OUT.parent.mkdir(parents=True, exist_ok=True)
font.flavor = "woff2"
font.save(str(OUT))
print(f"{len(valid)} íconos -> {OUT.relative_to(ROOT)} ({OUT.stat().st_size // 1024} KB)")
if "-v" in sys.argv:
    print("posibles íconos no encontrados:", ", ".join(missing))
