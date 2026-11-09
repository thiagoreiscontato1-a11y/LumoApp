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

# GPU: tenta primeiro e volta para a CPU; falha no meio da leitura refaz a foto na CPU.
for token in ("boolean gpu=GpuDevelop.render(s,developSettings,why);", "if(!gpu)s.renderInPlace(developSettings);", "catch(GpuDevelop.Dirty e)"):
    need(edit, token, "PhotoEditor.edit")
gpu = (SRC / "GpuDevelop.java").read_text(encoding="utf-8")
for token in ("selfTest();", "if(p1<99||p2<99.9){disable(", "if(wrote[0])throw new Dirty(c);", "throw new Unsupported(\"foto maior que a textura da GPU",
              "GLES30.glUniform1i(loc,i);", "check(\"primeira faixa\");"):
    need(gpu, token, "GpuDevelop")

# Curadoria: nitidez medida na original antes de revelar; exposição corrigida antes de revelar; só reprova o que não tem conserto.
for token in ("PhotoCheck.shrink(pixels,size[0],size[1],PhotoCheck.DETAIL_EDGE,dsize)", "PhotoCheck.fixExposure(small,psize[0],psize[1],developSettings,PhotoEditor::faces)"):
    need(edit, token, "PhotoEditor.edit")
if edit.index("PhotoCheck.shrink(pixels") > edit.index("GpuDevelop.render(") or edit.index("fixExposure(") > edit.index("GpuDevelop.render("):
    errors.append("nitidez e exposição precisam ser medidas antes de revelar")
semantic = (SRC / "SemanticCurator.java").read_text(encoding="utf-8")
for bad in ("eyeDarkness", "faceSideAsymmetry", "mouthAsymmetry", "Possível olhos fechados"):
    if bad in semantic:
        errors.append(f"SemanticCurator voltou a usar a heurística retirada: {bad}")
need(semantic, "PhotoCheck.cutFraction(", "SemanticCurator")
finish = smart[smart.index("static Result finish("):]
finish = finish[:finish.index("\n }\n")]
for bad in ("tech.review", "tech.reason"):
    if bad in finish:
        errors.append(f"SmartCurator.finish não pode reprovar por luz/contraste/cor: {bad}")
need(finish, "if(s>=2)problems.add(\"Possível duplicata de outra foto\");else notes.add(", "SmartCurator.finish")

if errors:
    print("\n".join(errors)); sys.exit(1)
print("contrato da exportação rápida ok")
