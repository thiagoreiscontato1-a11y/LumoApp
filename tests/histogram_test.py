import pathlib,subprocess,tempfile
root=pathlib.Path(__file__).resolve().parents[1]
src=root/'app/src/main/java/com/hisho/tether'
with tempfile.TemporaryDirectory() as out:
    sources=[root/'tests/stubs/org/xmlpull/v1/XmlPullParser.java',src/'Develop.java',src/'Histogram.java',root/'tests/com/hisho/tether/HistogramTest.java']
    subprocess.run(['javac','-encoding','UTF-8','-source','8','-target','8','-nowarn','-d',out,*map(str,sources)],check=True)
    subprocess.run(['java','-cp',out,'com.hisho.tether.HistogramTest'],check=True)

# Editor do Android: mesmos grupos do iPhone e histograma ligado à prévia.
editor=(src/'ManualEditorActivity.java').read_text(encoding='utf-8')
missing=[t for t in ('"Luz","Cor","Efeitos","Detalhe"','"PostCropVignetteAmount"','"GrainAmount"','"Sharpness"','"Clarity2012"','"HueAdjustment"+c','"RedHue"','new HistogramView(this)','Histogram.of(px)','Histogram.clipping(','options.put("extra",extra)','settings.put("extra"') if t not in editor]
if missing:raise SystemExit('FAIL: editor do Android sem: '+', '.join(missing))
if 'merged(preset,options.optJSONObject("extra"))' not in (src/'PhotoEditor.java').read_text(encoding='utf-8'):raise SystemExit('FAIL: PhotoEditor não aplica os ajustes extras')
print('PASS: editor do Android com grupos Luz/Cor/Efeitos/Detalhe, ajustes extras e histograma')
