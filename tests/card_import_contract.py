from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
main = (ROOT / "app/src/main/java/com/hisho/tether/MainActivity.java").read_text(encoding="utf-8")
jobs = (ROOT / "app/src/main/java/com/hisho/tether/Jobs.java").read_text(encoding="utf-8")
service = (ROOT / "app/src/main/java/com/hisho/tether/CaptureService.java").read_text(encoding="utf-8")
errors = []

for label, source, required in [
    ("MainActivity", main, [
        "ACTION_OPEN_DOCUMENT_TREE",
        "takePersistableUriPermission(tree,flags)",
        "scanCardFolder(tree,child,depth+1,visited)",
        "lower.endsWith(\".jpg\")||lower.endsWith(\".jpeg\")",
        "database.duplicateByContent(hash)",
        "database.addImported(id,temp,outName,settings",
        "database.set(id,\"ready\",\"original\",original.toString())",
    ]),
    ("Jobs", jobs, [
        "void addImported(",
        'imported?"Importada":"Capturada"',
    ]),
    ("CaptureService", service, [
        '"queue_import".equals(intent.getAction())',
        '"finish_import".equals(intent.getAction())',
    ]),
]:
    for token in required:
        if token not in source:
            errors.append(f"{label}: requisito de importação do cartão ausente: {token}")

if errors:
    print("FAIL: contrato de importação por cartão USB:")
    print("\n".join(f" - {error}" for error in errors))
    sys.exit(1)

print("PASS: cartão USB usa seletor SAF recursivo e entrega JPEGs à fila normal de edição.")
