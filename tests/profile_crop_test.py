import pathlib,subprocess,tempfile
root=pathlib.Path(__file__).resolve().parents[1]
src=root/'app/src/main/java/com/hisho/tether'
with tempfile.TemporaryDirectory() as out:
    sources=[root/'tests/stubs/org/xmlpull/v1/XmlPullParser.java',src/'Develop.java',src/'CreativeProfile.java',src/'Crop.java',root/'tests/com/hisho/tether/DevelopTest.java',root/'tests/com/hisho/tether/ProfileTest.java']
    subprocess.run(['javac','-encoding','UTF-8','-source','8','-target','8','-nowarn','-d',out,*map(str,sources)],check=True)
    subprocess.run(['java','-cp',out,'com.hisho.tether.ProfileTest'],check=True)

# Exportação e editor do Android: corte e perfil antes da revelação; curva, gradação e corte no editor.
editor=(src/'PhotoEditor.java').read_text(encoding='utf-8')
for t in ('Crop crop=crop(options)','CreativeProfile.Profile look=profile(options)','static JSONObject lookReference(','static boolean curveKey('):
    if t not in editor:raise SystemExit('FAIL: PhotoEditor sem '+t)
if editor.index('Crop crop=crop(options)')>editor.index('PhotoCheck.shrink(pixels'):raise SystemExit('FAIL: a nitidez deve ser medida na foto já recortada')
manual=(src/'ManualEditorActivity.java').read_text(encoding='utf-8')
for t in ('new CurveView(this)','new ColorWheelView(this)','new CropView(this)','"Corte"','"LookAmount","Intensidade"','touchedGrading()','settings.put("crop",c)'):
    if t not in manual:raise SystemExit('FAIL: editor do Android sem '+t)
app=(src/'LumoApplication.java').read_text(encoding='utf-8')
if 'CreativeProfile.dir' not in app:raise SystemExit('FAIL: pasta dos perfis não definida no app')
print('PASS: exportação e editor do Android com corte, perfil, curva e gradação')
