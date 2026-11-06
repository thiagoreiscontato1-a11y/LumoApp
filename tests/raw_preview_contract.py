import pathlib,subprocess,tempfile
root=pathlib.Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory() as out:
    sources=[root/'tests/stubs/android/graphics/BitmapFactory.java',root/'app/src/main/java/com/hisho/tether/RawPreview.java',root/'tests/com/hisho/tether/RawPreviewTest.java']
    subprocess.run(['javac','-encoding','UTF-8','-source','8','-target','8','-nowarn','-d',out,*map(str,sources)],check=True)
    subprocess.run(['java','-Djava.awt.headless=true','-cp',out,'com.hisho.tether.RawPreviewTest'],check=True)
