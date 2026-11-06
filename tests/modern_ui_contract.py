#!/usr/bin/env python3
"""Contrato da interface Android: ícones vetoriais existentes, sem glifos de texto como ícone, alvos de 48dp e demo resistente à recriação."""
import pathlib, re, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/hisho/tether"
RES = ROOT / "app/src/main/res/drawable"
main = (SRC / "MainActivity.java").read_text(encoding="utf-8")
viewer = (SRC / "PhotoViewerActivity.java").read_text(encoding="utf-8")
theme = (SRC / "LumoTheme.java").read_text(encoding="utf-8")
errors = []

# Todo ícone pedido pelo nome precisa existir (o build não gera R: um nome errado só aparece como ícone vazio no aparelho).
names = set()
for src in (main, viewer, theme):
    # Literais dentro de chamadas icon(...)/LumoTheme.icon(...) (inclui ternários) e dos construtores que recebem o nome do ícone.
    for call in re.findall(r'\bicon\(([^;]*?)\)', src):
        names |= set(re.findall(r'"([a-z_]+)"', call))
    for fn in ('iconButton', 'emptyState', 'circle'):
        names |= set(re.findall(fn + r'\(\s*"([a-z_]+)"', src))
    names |= set(re.findall(r'leadingIcon\([^,]+,\s*"([a-z_]+)"', src))
for n in sorted(names):
    if not (RES / f"ic_{n}.xml").exists():
        errors.append(f"ícone ic_{n}.xml não existe em res/drawable")
if len(names) < 13:
    errors.append(f"poucos ícones detectados ({len(names)} de 13): o contrato não está lendo o código")

# Controles do topo e do visualizador usam vetores com nome acessível, não glifos.
for glyph in ('"☰"', '"⋯"', '"✕"', '"☆"', '"★"', '"⚠ Revisão"', '"✓ Aprovada"', '"‹  Ao vivo"'):
    if glyph in main or glyph in viewer:
        errors.append(f"glifo usado como ícone: {glyph}")
for token in ('ImageButton iconButton(', 'b.setContentDescription(description)', 'b.setMinimumHeight(dp(48))',
              'b.setMinHeight(dp(48));b.setMinimumHeight(dp(48))', 'iconButton("menu","Menu"'):
    if token not in main:
        errors.append(f"MainActivity sem: {token}")
if 'b.setContentDescription(description)' not in viewer or 'LayoutParams(dp(42),dp(42))' in viewer:
    errors.append("visualizador sem botões de ícone acessíveis de 48dp")

# Contraste: rótulos pequenos >= 55% de branco, texto escuro sobre o degradê, azul sólido legível com branco.
for token in ('TERTIARY=0x8cffffff', 'ON_ACCENT=0xff07090c', 'BLUE_SOLID=0xff0a66e6'):
    if token not in theme:
        errors.append(f"LumoTheme sem: {token}")
if 'connect.setTextColor(LumoTheme.ON_ACCENT)' not in main:
    errors.append("botão Conectar câmera precisa de texto escuro sobre o degradê")

# Demo/recriação: posts antigos não podem rodar em executores encerrados.
if 'handler.removeCallbacksAndMessages(null)' not in main or 'if(destroyed||isFinishing()||images.isShutdown())return;' not in main:
    errors.append("demoSeed/onDestroy sem proteção contra Activity recriada")

# Entrega mostra o próximo passo.
for token in ('void updateFottoActions(', '"Conectar conta Fotto":noEvent?"Escolher evento do Fotto"', 'fottoStop.setVisibility('):
    if token not in main:
        errors.append(f"Entrega sem: {token}")

if errors:
    print("FAIL: contrato da interface moderna:\n- " + "\n- ".join(errors)); sys.exit(1)
print(f"PASS: {len(names)} ícones vetoriais presentes, controles de 48dp com nome acessível, contraste e demo protegidos.")
