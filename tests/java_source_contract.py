from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "java"
errors = []

for path in ROOT.rglob("*.java"):
    text = path.read_text(encoding="utf-8")
    in_string = False
    in_char = False
    line_comment = False
    block_comment = False
    escape = False
    start_line = 0
    line = 1
    i = 0
    while i < len(text):
        ch = text[i]
        nxt = text[i + 1] if i + 1 < len(text) else ""

        if ch == "\n":
            if in_string:
                errors.append(f"{path.relative_to(ROOT)}:{start_line}: string literal atravessa quebra de linha")
                in_string = False
                escape = False
            line += 1
            line_comment = False
            i += 1
            continue

        if line_comment:
            i += 1
            continue
        if block_comment:
            if ch == "*" and nxt == "/":
                block_comment = False
                i += 2
            else:
                i += 1
            continue
        if in_string:
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == '"':
                in_string = False
            i += 1
            continue
        if in_char:
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == "'":
                in_char = False
            i += 1
            continue

        if ch == "/" and nxt == "/":
            line_comment = True
            i += 2
            continue
        if ch == "/" and nxt == "*":
            block_comment = True
            i += 2
            continue
        if ch == '"':
            in_string = True
            start_line = line
            i += 1
            continue
        if ch == "'":
            in_char = True
            i += 1
            continue
        i += 1

if errors:
    print("FAIL: strings Java inválidas encontradas:")
    for e in errors:
        print(" -", e)
    sys.exit(1)

main = ROOT / "com" / "hisho" / "tether" / "MainActivity.java"
s = main.read_text(encoding="utf-8")
for required in [
    'flowStatus=text("Canon • Sem câmera\\nBateria · armazenamento\\nPendentes · processadas · enviadas"',
    'line3+="\\nFotto: fila "',
    'flowStatus.setText(line1+"\\n"+line2+"\\n"+line3);',
]:
    if required not in s:
        print("FAIL: contrato do cabeçalho não encontrado:", required)
        sys.exit(1)

print("PASS: fontes Java sem string literal atravessando linha; cabeçalho retrato usa escapes \\n válidos.")
