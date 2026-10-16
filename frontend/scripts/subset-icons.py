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
EXTRA = {"progress_activity", "check_circle", "error", "warning", "info", "close",
         # Íconos de los perfiles de negocio (vienen del servidor).
         "storefront", "checkroom", "smartphone", "scale", "restaurant", "content_cut", "local_shipping", "category"}

candidates = set(EXTRA)
pattern = re.compile(r"""(?:name|icon)\s*[=:]\s*["']([a-z0-9_]+)["']|["']([a-z][a-z0-9_]{2,40})["']""")
for f in SRC.rglob("*.tsx"):
    for m in pattern.finditer(f.read_text(encoding="utf-8")):
        candidates.add(m.group(1) or m.group(2))
for f in SRC.rglob("*.ts"):
    for m in pattern.finditer(f.read_text(encoding="utf-8")):
        candidates.add(m.group(1) or m.group(2))

font = TTFont(str(FONT))
# Ligaduras reales de la fuente: texto -> glifo. Algunos nombres son alias (p. ej. "smartphone"
# apunta al glifo "mobile"), por eso se resuelve por el texto y no por el nombre del glifo.
rev = {g: chr(c) for c, g in font.getBestCmap().items()}
gsub = font["GSUB"].table


def lig_tables():
    for lookup in gsub.LookupList.Lookup:
        for st in lookup.SubTable:
            target = st.ExtSubTable if hasattr(st, "ExtSubTable") else st
            lig = getattr(target, "ligatures", None)
            if lig:
                yield lig


def text_of(first, l):
    try:
        return rev[first] + "".join(rev[c] for c in l.Component)
    except KeyError:
        return None


by_text = {}
for lig in lig_tables():
    for first, ligs in lig.items():
        for l in ligs:
            t = text_of(first, l)
            if t:
                by_text[t] = l.LigGlyph
valid = sorted(n for n in candidates if n in by_text)
valid_set = set(valid)
missing = sorted(n for n in candidates if n not in by_text and "_" in n and n.islower())

# Poda las ligaduras que no se usan; si no, el cierre de GSUB volvería a meter toda la fuente.
for lig in lig_tables():
    for first in list(lig):
        lig[first] = [l for l in lig[first] if text_of(first, l) in valid_set]
        if not lig[first]:
            del lig[first]

opts = subset.Options()
opts.flavor = "woff2"
opts.layout_features = ["*"]
opts.ignore_missing_glyphs = True
opts.notdef_outline = True
sub = subset.Subsetter(opts)
glyph_order = set(font.getGlyphOrder())
keep = [g for n in valid for g in (by_text[n], by_text[n] + ".fill") if g in glyph_order]
sub.populate(glyphs=keep, text="abcdefghijklmnopqrstuvwxyz0123456789_ ")
sub.subset(font)
OUT.parent.mkdir(parents=True, exist_ok=True)
font.flavor = "woff2"
font.save(str(OUT))
print(f"{len(valid)} íconos -> {OUT.relative_to(ROOT)} ({OUT.stat().st_size // 1024} KB)")
if "-v" in sys.argv:
    print("posibles íconos no encontrados:", ", ".join(missing))
