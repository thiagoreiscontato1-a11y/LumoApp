import pathlib,subprocess,tempfile
root=pathlib.Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory() as out:
    sources=[root/'tests/stubs/org/xmlpull/v1/XmlPullParser.java',root/'app/src/main/java/com/hisho/tether/Develop.java',root/'tests/com/hisho/tether/DevelopTest.java']
    subprocess.run(['javac','-encoding','UTF-8','-source','8','-target','8','-nowarn','-d',out,*map(str,sources)],check=True)
    subprocess.run(['java','-cp',out,'com.hisho.tether.DevelopTest'],check=True)
