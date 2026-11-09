#!/usr/bin/env python3
"""Shaders da revelação na GPU (GpuProgram) contra o motor Java, rodando em OpenGL por software (Mesa llvmpipe).

Reproduz o fluxo do GpuDevelop no Android: mesmas texturas (RGBA8 em B,G,R,A, campos R32F/RG32F, tabela R32F,
LUT 3D RGB32F), mesmas passadas (principal, nitidez horizontal em textura cheia, nitidez vertical em faixas) e
leitura em faixas. Precisa de moderngl, numpy e EGL do Mesa. Sem eles, o teste é pulado (o CI do Android ainda
roda o autoteste no emulador).
"""
import json, os, pathlib, subprocess, sys, tempfile
ROOT = pathlib.Path(__file__).resolve().parents[1]
try:
    import moderngl, numpy as np
    ctx = moderngl.create_standalone_context(backend="egl", require=330)
except Exception as e:  # noqa: BLE001
    if os.environ.get("GPU_TEST_REQUIRED") == "1":
        sys.exit(f"gpu_test: OpenGL indisponível ({e.__class__.__name__}: {e})")
    print(f"gpu_test pulado: sem OpenGL local ({e.__class__.__name__}: {e})")
    sys.exit(0)

SAMPLERS = {"uSrc": 0, "uFTone": 1, "uFClar": 2, "uFTex": 3, "uFHaze": 4, "uTable": 5, "uLut": 6, "uImg": 7, "uBlur": 8}
W, H, STRIP = int(os.environ.get("GPU_TEST_W", 1800)), int(os.environ.get("GPU_TEST_H", 1200)), 256


def run(case_dir: pathlib.Path, common: pathlib.Path):
    meta = json.loads((case_dir / "meta.json").read_text())
    w, h = meta["w"], meta["h"]
    vert = (common / "vertex.glsl").read_text()
    prog = {n: ctx.program(vertex_shader=vert, fragment_shader=(common / f"{n}.glsl").read_text()) for n in ("develop", "blur_h", "blur_v")}
    vao = {n: ctx.vertex_array(p, []) for n, p in prog.items()}
    def tex2d(size, comps, data, dtype):
        t = ctx.texture(size, comps, data, dtype=dtype); t.filter = (moderngl.NEAREST, moderngl.NEAREST); t.repeat_x = t.repeat_y = False; return t
    src = tex2d((w, h), 4, (common / "src.bin").read_bytes(), "f1")
    units = {"uSrc": (0, src)}
    for name, unit, uni in (("tone", 1, "uFTone"), ("clarity", 2, "uFClar"), ("texture", 3, "uFTex"), ("haze", 4, "uFHaze")):
        f = meta["fields"].get(name)
        if f: units[uni] = (unit, tex2d((f["gw"], f["gh"]), f["comps"], (case_dir / f"{name}.bin").read_bytes(), "f4"))
    if (case_dir / "table.bin").exists(): units["uTable"] = (5, tex2d((1025, 1), 1, (case_dir / "table.bin").read_bytes(), "f4"))
    if meta["lutSize"]:
        n = meta["lutSize"]; lut = ctx.texture3d((n, n, n), 3, (case_dir / "lut.bin").read_bytes(), dtype="f4"); lut.filter = (moderngl.NEAREST, moderngl.NEAREST); units["uLut"] = (6, lut)
    def setup(p, extra):
        for k, v in meta["uniforms"].items():
            if k in p: p[k].value = v[0] if len(v) == 1 else (list(v) if k == "uK" else tuple(v))
        # Cada sampler sempre na sua unidade (dois tipos de sampler na mesma unidade invalidam o desenho).
        for k, unit in SAMPLERS.items():
            if k in p: p[k].value = unit
        for k, (unit, t) in {**units, **extra}.items():
            t.use(location=unit)
    out = np.zeros(w * h, dtype="<u4")
    strip = ctx.texture((w, STRIP), 4, dtype="f1"); strip_fbo = ctx.framebuffer([strip])
    def strips(name, extra):
        p = prog[name]; setup(p, extra)
        for y0 in range(0, h, STRIP):
            rows = min(STRIP, h - y0); strip_fbo.use(); ctx.viewport = (0, 0, w, rows)
            if "uOrigin" in p: p["uOrigin"].value = (0.0, float(y0))
            vao[name].render(moderngl.TRIANGLES, vertices=3)
            out[y0 * w:(y0 + rows) * w] = np.frombuffer(strip_fbo.read(viewport=(0, 0, w, rows), components=4), dtype="<u4")
    if not meta["sharpen"]:
        strips("develop", {})
    else:
        t1 = ctx.texture((w, h), 4, dtype="f1"); t1.filter = (moderngl.NEAREST, moderngl.NEAREST); fbo1 = ctx.framebuffer([t1])
        p = prog["develop"]; setup(p, {}); fbo1.use(); ctx.viewport = (0, 0, w, h)
        if "uOrigin" in p: p["uOrigin"].value = (0.0, 0.0)
        vao["develop"].render(moderngl.TRIANGLES, vertices=3)
        fbo0 = ctx.framebuffer([src])  # a foto original não é mais necessária: vira o destino do desfoque horizontal
        p = prog["blur_h"]; setup(p, {"uImg": (7, t1)}); fbo0.use(); ctx.viewport = (0, 0, w, h); vao["blur_h"].render(moderngl.TRIANGLES, vertices=3)
        strips("blur_v", {"uImg": (7, t1), "uBlur": (8, src)})
    cpu = np.frombuffer((case_dir / "cpu.bin").read_bytes(), dtype="<u4")
    diff = np.zeros(w * h, dtype=np.int32)
    for sh in (0, 8, 16):
        diff = np.maximum(diff, np.abs(((out >> sh) & 255).astype(np.int32) - ((cpu >> sh) & 255).astype(np.int32)))
    return int(diff.max()), float((diff == 0).mean()), float((diff <= 1).mean()), float((diff <= 2).mean())


with tempfile.TemporaryDirectory() as d:
    d = pathlib.Path(d)
    srcs = [ROOT / "tests/stubs/org/xmlpull/v1/XmlPullParser.java", ROOT / "app/src/main/java/com/hisho/tether/Develop.java",
            ROOT / "app/src/main/java/com/hisho/tether/GpuProgram.java", ROOT / "tests/com/hisho/tether/DevelopTest.java", ROOT / "tests/gpu/GpuDump.java"]
    subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-encoding", "UTF-8", "-nowarn", "-d", str(d / "cls"), *map(str, srcs)], check=True)
    subprocess.run(["java", "-cp", str(d / "cls"), "com.hisho.tether.GpuDump", str(d / "data"), str(W), str(H)], check=True)
    failed = []
    for case in json.loads((d / "data/cases.json").read_text()):
        worst, same, within1, within2 = run(d / "data" / case, d / "data")
        print(f"{case:14s} máx {worst}  idênticos {same*100:6.2f}%  ≤1 {within1*100:6.2f}%  ≤2 {within2*100:7.3f}%")
        # Nitidez forte amplia a diferença de arredondamento de 1 nível no intermediário de 8 bits: alguns pixels
        # isolados passam de 2. O que importa é que quase tudo fique igual ou a 1 nível.
        if worst > 8 or within1 < .99 or within2 < .9999:
            failed.append(case)
    if failed:
        sys.exit("GPU diverge do motor Java em: " + ", ".join(failed))
    print("GPU igual ao motor Java (tolerância: 99% até 1 nível, 99,99% até 2, máx 8)")
