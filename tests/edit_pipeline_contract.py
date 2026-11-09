#!/usr/bin/env python3
"""Contrato da exportação rápida: nada de decodificar a foto inteira de novo, girar num segundo bitmap ou esperar o fsync."""
import pathlib, re, sys
SRC = pathlib.Path(__file__).resolve().parents[1] / "app/src/main/java/com/hisho/tether"
editor = (SRC / "PhotoEditor.java").read_text(encoding="utf-8")
smart = (SRC / "SmartCurator.java").read_text(encoding="utf-8")
phash = (SRC / "PhotoHash.java").read_text(encoding="utf-8")
capture = (SRC / "CaptureService.java").read_text(encoding="utf-8")
manual = (SRC / "ManualEditorActivity.java").read_text(encoding="utf-8")
develop = (SRC / "Develop.java").read_text(encoding="utf-8")
errors = []

def need(src, token, where):
    if token not in src:
        errors.append(f"{where} sem: {token}")

edit = editor[editor.index("static Export edit("):]
for token in ("decodeUnrotated(source,edge)", "orientedPixels(bitmap,orientation,size);bitmap.recycle();", "s.renderInPlace(",
              "CURATION.submit(", "SmartCurator.shrink(bitmap,SmartCurator.FACE_EDGE)", "result.timing="):
    need(edit, token, "PhotoEditor.edit")
for bad in ("getFD().sync()", "decode(source,", "new Matrix()", "SmartCurator.analyze("):
    if bad in edit:
        errors.append(f"PhotoEditor.edit não pode usar: {bad}")
# O JPEG é gravado antes de esperar a curadoria (as duas coisas em paralelo).
if edit.index("bitmap.compress(") > edit.index("curation.get()"):
    errors.append("PhotoEditor.edit espera a curadoria antes de gravar o JPEG")
# Hash perceptual nunca decodifica a foto inteira.
if re.search(r"decodeFile\(f\.toString\(\)\)", phash):
    errors.append("PhotoHash decodifica a foto inteira")
need(phash, "o.inSampleSize*=2", "PhotoHash.perceptualHash")
for where, src in (("CaptureService", capture), ("ManualEditorActivity", manual)):
    need(src, "PhotoEditor.Export export=PhotoEditor.edit(", where)
    need(src, "SmartCurator.finish(export.curation,", where)
need(capture, '" s ("+export.timing+") · nota "', "CaptureService (tempo por etapa no registro)")
need(smart, "static Partial measure(Bitmap face,int sensitivity)", "SmartCurator")
# Motor: LUT só em fotos grandes (prévia e testes exatos), paralelismo nas passadas que eram de um núcleo.
for token in ("LUT_MIN_PIXELS=2_000_000", "lut=(curves!=null||color)&&!identity&&(long)src.w*src.h>=LUT_MIN_PIXELS?new Lut(this):null;",
              "parallel(h,(y0,y1)->{for(int y=y0;y<y1;y++){int row=y*w;", "parallel(gh,(g0,g1)->", "fx.noiseRow(y,nz)", "toneF.row(y,fT)"):
    need(develop, token, "Develop")

if errors:
    print("\n".join(errors)); sys.exit(1)
print("contrato da exportação rápida ok")
