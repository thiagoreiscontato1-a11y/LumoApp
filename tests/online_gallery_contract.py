"""Contrato da galeria online: app Android ↔ servidor (online-gallery/worker)."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/hisho/tether"
api = (SRC / "GalleryApi.java").read_text(encoding="utf-8")
sync = (SRC / "GallerySync.java").read_text(encoding="utf-8")
jobs = (SRC / "Jobs.java").read_text(encoding="utf-8")
fotto = (SRC / "FottoSync.java").read_text(encoding="utf-8")
main = (SRC / "MainActivity.java").read_text(encoding="utf-8")
worker = (ROOT / "online-gallery/worker/src/index.js").read_text(encoding="utf-8")
schema = (ROOT / "online-gallery/worker/migrations/0001_init.sql").read_text(encoding="utf-8")
errors = []


def need(label, source, tokens):
    for token in tokens:
        if token not in source:
            errors.append(f"{label}: ausente: {token}")


need("FottoSync", fotto, ["static void kick(Context c){GallerySync.kick(c);"])
need("Jobs", jobs, [
    "createOnline(d);",
    "CREATE TABLE IF NOT EXISTS online_uploads",
    "synchronized String[] nextOnline(String galleryId,String event)",
    "j.edited_at>0 THEN j.edited_at",  # reedição gera nova revisão
    "synchronized void onlineRecover(String galleryId)",
])
need("GallerySync", sync, [
    "DemoLicense.allowed(c)",
    'getString("localEventId","")',
    "GalleryApi.galleryFor(c,event)",
    "jobs.nextOnline(g.id,event)",
    'jobs.onlineSet(id,g.id,"done",revision,remote,"",false)',
    "QueueRecovery.schedule(c)",
])
need("GalleryApi", api, [
    '"/v1/me"', '"/v1/galleries"', '"/v1/photos"', 'plan.getString("completeUrl")',
    "setFixedLengthStreamingMode(data.length)",
    'put("sha256",sha256(files[i]))',
    '"thumb","preview","full"',
    '"Authorization","Bearer "+token',
])
need("MainActivity", main, [
    '.item("photos",CYAN,"Galeria online",galleryMenuSubtitle(),this::galleryMenu)',
    "void connectGalleryDialog()",
    "void createGalleryDialog()",
    "GalleryApi.createGallery(this,name,event,secret,allow)",
    "Intent.ACTION_SEND",
])

# Rotas usadas pelo app existem no servidor.
for route in [r'path === "/v1/me"', r'path === "/v1/galleries"', r'path === "/v1/photos" && method === "POST"',
              r'\/v1\/photos\/([a-z0-9]+)\/r\/(\d+)\/([a-z]+)$/)) && method === "PUT"',
              r'\/v1\/photos\/([a-z0-9]+)\/complete$/)) && method === "POST"']:
    if route not in worker:
        errors.append(f"worker: rota ausente: {route}")

# Limites de tamanho do servidor comportam as variantes geradas pelo app.
limits = dict(re.findall(r"(thumb|preview|full): ([\d_]+),", worker))
if set(limits) != {"thumb", "preview", "full"}:
    errors.append("worker: variantes devem ser thumb, preview e full")
elif int(limits["full"].replace("_", "")) < 40_000_000:
    errors.append("worker: limite da foto completa pequeno demais para JPEG de câmera")
edges = dict(re.findall(r"(THUMB_EDGE|PREVIEW_EDGE)=(\d+)", api))
if edges != {"THUMB_EDGE": "640", "PREVIEW_EDGE": "2048"}:
    errors.append(f"GalleryApi: tamanhos de variantes inesperados {edges}")

for table in ["galleries", "photos", "photo_variants", "visitors", "favorites", "pin_failures"]:
    if f"CREATE TABLE {table} " not in schema:
        errors.append(f"schema: tabela ausente: {table}")

if errors:
    print("FAIL: contrato da galeria online:")
    print("\n".join(f" - {e}" for e in errors))
    sys.exit(1)
print("PASS: app publica fotos aprovadas na galeria online pelas rotas e variantes que o servidor aceita.")
