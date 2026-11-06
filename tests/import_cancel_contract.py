from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
main = (ROOT / "app/src/main/java/com/hisho/tether/MainActivity.java").read_text(encoding="utf-8")
model = (ROOT / "ios/Lumo/AppModel.swift").read_text(encoding="utf-8")
content = (ROOT / "ios/Lumo/ContentView.swift").read_text(encoding="utf-8")
errors = []

for label, source, required in [
    ("MainActivity", main, [
        "private static volatile boolean importingEvent=false,importCancelRequested=false;",
        "void buildImportBar(LinearLayout root)",
        "buildImportBar(root);",
        "updateImportBar();",
        "void confirmCancelImport()",
        "compactButton(\"Cancelar\"",
        # cancela entre pastas, entre fotos e no meio da cópia de uma foto
        "if(importCancelRequested)throw new CancellationException();\n  if(depth>24)",
        "for(CardPhoto photo:photos){if(importCancelRequested||",
        "if(importCancelRequested)throw new CancellationException();out.write(buf,0,n);",
        "catch(CancellationException e){if(temp!=null)temp.delete();importProcessed--;break;}",
        "Importação cancelada: ",
        # a fila de edição das fotos já importadas é encerrada normalmente
        "setAction(\"finish_import\")",
    ]),
    ("AppModel.swift", model, [
        "func startImport(_ urls: [URL])",
        "func cancelImport()",
        "if Task.isCancelled || importCancelling { cancelled = true; break }",
        "Importação cancelada: ",
    ]),
    ("ContentView.swift", content, [
        "struct ImportProgressBar: View",
        "if model.importing { ImportProgressBar() }",
        "model.cancelImport()",
        "model.startImport(urls)",
    ]),
]:
    for token in required:
        if token not in source:
            errors.append(f"{label}: requisito de cancelamento da importação ausente: {token!r}")

if errors:
    print("FAIL: contrato de cancelamento da importação por pasta:")
    print("\n".join(f" - {error}" for error in errors))
    sys.exit(1)

print("PASS: importação por pasta mostra progresso e pode ser cancelada (Android e iOS).")
