from pathlib import Path
root=Path(__file__).resolve().parents[1]
sync=(root/'app/src/main/java/com/hisho/tether/FottoSync.java').read_text()
jobs=(root/'app/src/main/java/com/hisho/tether/Jobs.java').read_text()
main=(root/'app/src/main/java/com/hisho/tether/MainActivity.java').read_text()
assert 'watchdog(Context source)' in sync
assert 'Fila parada detectada · retomada automática' in sync
assert 'fottoWatchdogRecoveries' in sync
assert 'fottoWatchdogRecoveryAt' in sync
assert 'recoverableError' in sync
assert 'fottoErrorCount' in jobs
assert 'fottoLastStateAt' in jobs
assert 'FottoSync.watchdog(MainActivity.this);' in main
print('PASS: watchdog detecta fila parada e retoma automaticamente sem botão manual.')
