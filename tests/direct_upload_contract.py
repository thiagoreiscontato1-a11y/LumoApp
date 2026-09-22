from pathlib import Path

root = Path(__file__).resolve().parents[1]
api = (root / 'app/src/main/java/com/hisho/tether/FottoApi.java').read_text(encoding='utf-8')
main = (root / 'app/src/main/java/com/hisho/tether/MainActivity.java').read_text(encoding='utf-8')
capture = (root / 'app/src/main/java/com/hisho/tether/CaptureService.java').read_text(encoding='utf-8')

assert '"/me/galleries/"+Uri.encode(galleryId)+"/medias"' in api
assert 'item.put("originalFileName",name)' in api
assert 'item.put("mediaSize",size)' in api
assert 'item.put("mediaType","photo")' not in api
assert 'optString("signedUrl","")' in api
assert 'setRequestMethod("PUT")' in api
assert 'm.optBoolean("processed",false)' in api
assert 'callWithBearerToken(method,path,body,access)' in api
assert 'Envio direto automático' in main
assert 'FottoSync.sendExisting' in main
assert 'FottoSync.kick(this)' in capture
print('PASS: contrato do upload direto Fotto/S3 está presente.')
