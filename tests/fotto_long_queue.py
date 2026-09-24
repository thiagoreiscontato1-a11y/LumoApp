from pathlib import Path
root=Path(__file__).resolve().parents[1]
sync=(root/'app/src/main/java/com/hisho/tether/FottoSync.java').read_text()
jobs=(root/'app/src/main/java/com/hisho/tether/Jobs.java').read_text()
api=(root/'app/src/main/java/com/hisho/tether/FottoApi.java').read_text()
assert 'while(handled<40)' not in sync
assert 'while(!STOP_REQUESTED.get())' in sync
assert 'RECONCILE_EVERY=15' in sync
assert 'processedMedia(c,gallery)' in sync
assert "f.state='error' AND f.attempts<4" in jobs
assert 'fottoErrorCount' in jobs
assert 'watchdog(Context source)' in sync
assert "f.state='error' OR f.state='unconfirmed'" not in jobs
assert 'fottoUploadPending' in jobs
assert 'fottoAccepted' in jobs
assert 'revalidateGallery' in api
assert 'code==429||code==502||code==503||code==504' in api
print('PASS: fila longa Fotto sem limite de 40, sem reupload de unconfirmed e confirmação em lote.')
