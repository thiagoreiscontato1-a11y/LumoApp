#!/usr/bin/env python3
"""Contrato do login Android: a conta Fotto é a entrada do Lumo; sair apaga a sessão e os cookies, não as fotos."""
import pathlib, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/hisho/tether"
main = (SRC / "MainActivity.java").read_text(encoding="utf-8")
login = (SRC / "LumoLoginActivity.java").read_text(encoding="utf-8")
session = (SRC / "LumoSession.java").read_text(encoding="utf-8")
fotto = (SRC / "FottoLoginActivity.java").read_text(encoding="utf-8")
manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
screens = (ROOT / ".github/scripts/android-screens.sh").read_text(encoding="utf-8")
errors = []

def need(src, token, where):
    if token not in src:
        errors.append(f"{where} sem: {token}")

need(manifest, '<activity android:name=".LumoLoginActivity" android:exported="false"/>', "AndroidManifest")

# O portão roda logo após super.onCreate, antes de abrir o banco ou registrar receivers.
gate = main.find("if(!LumoSession.signedIn(this)){gated=true;")
if gate < 0:
    errors.append("MainActivity sem o portão de login")
else:
    if not (main.find("super.onCreate(saved);") < gate < main.find("database=new Jobs(MainActivity.this)")):
        errors.append("portão de login precisa vir antes de abrir o banco")
    if main.find("LumoSession.enterDemo(this)") > gate:
        errors.append("modo demonstração precisa liberar o portão antes da checagem")
need(main, "if(gated){", "MainActivity.onDestroy")
need(main, 'getBooleanExtra("lumoDemo",false))LumoSession.enterDemo(this)', "MainActivity")

# A sessão é a mesma chave que o envio usa; checagem só local (funciona offline).
need(session, "!FottoApi.token(c).isEmpty()", "LumoSession.signedIn")
for key in ("fottoApiKeyV7", "fottoAccessTokenV7", "fottoApiKeyV5", "fottoAccessTokenV5", "fottoApiKeyV4",
            "fottoAccessTokenV4", "fottoTokenV3", "fottoGalleryId"):
    need(session, f'.remove("{key}")', "LumoSession.signOut")
    if key.startswith("fotto") and key != "fottoGalleryId" and f'"{key}"' not in fotto + (SRC / "FottoApi.java").read_text(encoding="utf-8"):
        errors.append(f"chave {key} não existe mais no Fotto: atualize o signOut")
for token in ('putBoolean("fottoAuto",false)', "FottoSync.stop(c)", "removeAllCookies", "WebStorage.getInstance().deleteAllData()"):
    need(session, token, "LumoSession.signOut")
# Sair não pode apagar fotos, eventos nem a fila.
for bad in ("deletePhoto", "clearEditedFolder", "DROP TABLE", "DELETE FROM"):
    if bad in session:
        errors.append(f"signOut não pode apagar dados locais: {bad}")

# Tela de entrada: usa o login do Fotto existente e volta ao app limpando a pilha.
need(login, "new Intent(this,FottoLoginActivity.class)", "LumoLoginActivity")
need(login, "if(LumoSession.signedIn(this)){openLumo();return;}", "LumoLoginActivity")
need(login, "FLAG_ACTIVITY_CLEAR_TASK", "LumoLoginActivity")
need(login, "enter.setMinHeight(dp(52))", "LumoLoginActivity (alvo de toque)")

# Menu: sair pede confirmação e não sai no meio da captura.
need(main, ".section(\"Conta\")", "menu principal")
need(main, "this::confirmSignOut", "menu principal")
need(main, 'if(CaptureService.active!=null){message("Encerre a captura', "confirmSignOut")
need(main, 'putExtra("signedOut",true)', "confirmSignOut")

# Sessão expirada não tira o fotógrafo do app: só o aviso de reconectar.
if "signOut" in (SRC / "FottoApi.java").read_text(encoding="utf-8") or "signOut" in (SRC / "FottoSync.java").read_text(encoding="utf-8"):
    errors.append("sessão expirada não deve chamar signOut automaticamente")

# CI: captura da tela de entrada antes do modo demonstração.
if not (0 <= screens.find("shot 0-login") < screens.find("start --ez lumoDemo true")):
    errors.append("android-screens.sh: capturar 0-login antes de entrar no modo demonstração")

if errors:
    print("\n".join(errors)); sys.exit(1)
print("login: ok")
