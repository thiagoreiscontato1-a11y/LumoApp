from pathlib import Path
root = Path(__file__).resolve().parents[1]
main = (root / 'app/src/main/java/com/hisho/tether/MainActivity.java').read_text(encoding='utf-8')
sync = (root / 'app/src/main/java/com/hisho/tether/FottoSync.java').read_text(encoding='utf-8')
cap = (root / 'app/src/main/java/com/hisho/tether/CaptureService.java').read_text(encoding='utf-8')
ptp = (root / 'app/src/main/java/com/hisho/tether/Ptp.java').read_text(encoding='utf-8')
assert 'Encerrar envio' in main
assert 'FottoSync.stop(MainActivity.this)' in main
assert 'STOP_REQUESTED' in sync
assert 'if(STOP_REQUESTED.get())break' in sync
assert 'retroativo em segundo plano' in cap
assert 'boolean liveReceived=false' in cap
assert 'if(capturing&&!liveReceived&&!backlog.isEmpty())' in cap
assert 'if(e.code!=0x2019&&e.code!=0x2009)throw e' in ptp
print('PASS: botão de parada e compatibilidade R8/backfill presentes.')
