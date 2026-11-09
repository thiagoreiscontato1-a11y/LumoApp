#!/usr/bin/env python3
"""Contrato da 0.19.0: chave de produção pelos Secrets (senhas nunca na linha de comando) e relatório de falhas ligado."""
import pathlib, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/hisho/tether"
build = (ROOT / "build.py").read_text(encoding="utf-8")
workflow = (ROOT / ".github/workflows/android.yml").read_text(encoding="utf-8")
ignore = (ROOT / ".gitignore").read_text(encoding="utf-8")
app = (SRC / "LumoApplication.java").read_text(encoding="utf-8")
main = (SRC / "MainActivity.java").read_text(encoding="utf-8")
capture = (SRC / "CaptureService.java").read_text(encoding="utf-8")
errors = []

def need(src, token, where):
    if token not in src:
        errors.append(f"{where} sem: {token}")

# Assinatura: senhas só por variável de ambiente; chave de produção completa ou erro; chave temporária apagada.
if "pass:" in build:
    errors.append("build.py passa senha na linha de comando (use env:)")
for token in ("'--ks-pass','env:LUMO_KEYSTORE_PASSWORD'", "'--key-pass','env:LUMO_KEY_PASSWORD'", "LUMO_REQUIRE_RELEASE_KEY",
              "Chave de produção incompleta", "(build/'release.keystore').unlink()", "'--print-certs'", "lumo-build.json"):
    need(build, token, "build.py")
for secret in ("LUMO_KEYSTORE_BASE64", "LUMO_KEYSTORE_PASSWORD", "LUMO_KEY_ALIAS", "LUMO_KEY_PASSWORD", "SENTRY_DSN"):
    need(workflow, f"{secret}: ${{{{ secrets.{secret} }}}}", "android.yml")
need(workflow, "LUMO_DEMO=1 python3 build.py", "android.yml")
for pattern in ("*.p12", "*.jks", "lumo-chave-producao/"):
    need(ignore, pattern, ".gitignore")
if any(ROOT.rglob("*.p12")) or any(ROOT.rglob("*.jks")):
    errors.append("há uma chave .p12/.jks dentro do repositório")

# Relatório de falhas: instalado antes de tudo, rastro da captura, menu para compartilhar/desligar.
start = app.find("public void onCreate(){super.onCreate();")
if start < 0 or app.find("CrashReporter.install(this);", start) != start + len("public void onCreate(){super.onCreate();"):
    errors.append("CrashReporter.install precisa ser a primeira coisa em LumoApplication.onCreate")
need(capture, 'CrashReporter.note("captura · "+message);', "CaptureService.log")
need(main, '"Relatórios de falha",crashSubtitle(),this::showCrashReports', "MainActivity menu")
for token in ("Compartilhar último relatório", "Enviar relatório de teste", "CrashReporter.setEnabled(MainActivity.this,!on)"):
    need(main, token, "MainActivity.showCrashReports")

if errors:
    print("\n".join(errors)); sys.exit(1)
print("contrato de assinatura e relatório de falhas ok")
