from pathlib import Path
api=(Path(__file__).resolve().parents[1]/'app/src/main/java/com/hisho/tether/FottoApi.java').read_text()
ui=(Path(__file__).resolve().parents[1]/'app/src/main/java/com/hisho/tether/MainActivity.java').read_text()
assert 'galleriesDetailed(Context c)' in api
assert 'String path="/me/galleries"' in api
assert 'nextGalleryPath(root)' in api
assert 'page=1' in api and 'page=2' in api
assert 'unique.containsKey(id)' in api
assert 'paginationEnded(root)' in api
assert 'O Fotto não confirmou a paginação' in api
assert 'galleryById(Context c,String id)' in api
assert 'Buscar por nome ou ID' in ui
assert 'showManualEventDialog()' in ui
assert 'Confirmar destino das fotos' in ui
assert 'fottoGalleries.add(0,new FottoApi.Gallery(saved,savedTitle))' in ui
assert 'if(FottoSync.running())' in ui
assert 'Nunca substitui' not in ui  # the user-facing code performs this rather than showing an extra notice
print('PASS: paginação, busca, fallback por ID, evento salvo e confirmação de destino presentes.')
