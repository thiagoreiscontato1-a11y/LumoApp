#!/usr/bin/env python3
"""Curadoria nova (PhotoCheck): nitidez por regiões na original, rosto cortado e exposição corrigida depois do preset."""
import pathlib, subprocess, tempfile
ROOT = pathlib.Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory() as d:
    srcs = [ROOT / "tests/stubs/org/xmlpull/v1/XmlPullParser.java", ROOT / "app/src/main/java/com/hisho/tether/Develop.java",
            ROOT / "app/src/main/java/com/hisho/tether/PhotoCheck.java", ROOT / "tests/com/hisho/tether/DevelopTest.java", ROOT / "tests/check/PhotoCheckTest.java"]
    subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-encoding", "UTF-8", "-nowarn", "-d", d, *map(str, srcs)], check=True)
    subprocess.run(["java", "-cp", d, "com.hisho.tether.PhotoCheckTest"], check=True)
