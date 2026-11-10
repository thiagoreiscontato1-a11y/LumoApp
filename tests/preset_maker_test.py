#!/usr/bin/env python3
"""Criar preset a partir de uma edição e exportar .xmp (PresetMaker.java).

Confere: básicos = preset + delta manual (sem o automático), extras do editor por cima do preset (curva do editor
substitui a do preset), perfil criativo mantido, e o .xmp gerado (lido aqui com o ElementTree) traz os mesmos valores
e curvas no namespace do Camera Raw, que é o que o Lumo (Android e iPhone) e o Lightroom leem.
"""
import hashlib, os, pathlib, subprocess, sys, tempfile, urllib.request
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
# org.json não existe no JDK: mesmo jar (e SHA-256) do crash_report_test.py, guardado em ~/.cache/lumo-tests.
JSON_URL = "https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar"
JSON_SHA = "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed"


def org_json():
    cache = pathlib.Path(os.environ.get("XDG_CACHE_HOME", pathlib.Path.home() / ".cache")) / "lumo-tests" / "json-20240303.jar"
    if not cache.exists() or hashlib.sha256(cache.read_bytes()).hexdigest() != JSON_SHA:
        cache.parent.mkdir(parents=True, exist_ok=True)
        request = urllib.request.Request(JSON_URL, headers={"User-Agent": "lumo-tests/1.0"})
        data = urllib.request.urlopen(request, timeout=60).read()
        if hashlib.sha256(data).hexdigest() != JSON_SHA:
            sys.exit("org.json baixado com SHA-256 diferente do esperado")
        cache.write_bytes(data)
    return cache


TEST = r'''
package com.hisho.tether;
import org.json.*;
public class PresetMakerTest{
 static int n;static void check(boolean ok,String what){n++;if(!ok)throw new AssertionError(what);}
 public static void main(String[] a)throws Exception{
  JSONObject preset=new JSONObject("{\"Exposure2012\":0.5,\"Contrast2012\":20,\"Clarity2012\":10,\"ToneCurvePV2012.0\":0,\"ToneCurvePV2012.1\":0,\"ToneCurvePV2012.2\":255,\"ToneCurvePV2012.3\":240,\"Look\":{\"name\":\"Filme\",\"rgbTable\":\"ABC\"},\"LookAmount\":80}");
  JSONObject extra=new JSONObject("{\"Clarity2012\":35,\"Texture\":12,\"ToneCurvePV2012.0\":0,\"ToneCurvePV2012.1\":20,\"ToneCurvePV2012.2\":128,\"ToneCurvePV2012.3\":140,\"ToneCurvePV2012.4\":255,\"ToneCurvePV2012.5\":250}");
  JSONObject delta=new JSONObject("{\"exposure\":0.25,\"contrast\":-20,\"shadows\":15}");
  JSONObject made=PresetMaker.fromEdit(preset,extra,delta);
  check(Math.abs(made.getDouble("Exposure2012")-0.75)<1e-9,"exposição = preset + delta");
  check(!made.has("Contrast2012"),"contraste zerado sai do preset");
  check(made.getDouble("Shadows2012")==15,"sombras só do delta");
  check(made.getDouble("Clarity2012")==35,"extra do editor substitui o preset");
  check(made.getDouble("Texture")==12,"textura do editor (extra)");
  check(made.getDouble("ToneCurvePV2012.5")==250&&made.getDouble("ToneCurvePV2012.3")==140,"curva do editor substitui a do preset");
  check(made.getJSONObject("Look").getString("rgbTable").equals("ABC")&&made.getDouble("LookAmount")==80,"perfil criativo e intensidade mantidos");
  check(PresetMaker.fromEdit(null,null,null).length()==0,"sem preset nem edição: preset vazio");
  JSONObject big=PresetMaker.fromEdit(new JSONObject("{\"Exposure2012\":4.5}"),null,new JSONObject("{\"exposure\":2}"));
  check(big.getDouble("Exposure2012")==5,"exposição limitada a +5");
  System.out.print(PresetMaker.xmp(made,"Casamento <Praia> & \"sol\""));
  System.err.println(n+" verificações do PresetMaker");
 }
}
'''

with tempfile.TemporaryDirectory() as d:
    d = pathlib.Path(d)
    (d / "PresetMakerTest.java").write_text(TEST)
    jar = org_json()
    srcs = [ROOT / "tests/stubs/org/xmlpull/v1/XmlPullParser.java", ROOT / "app/src/main/java/com/hisho/tether/Develop.java",
            ROOT / "app/src/main/java/com/hisho/tether/PresetMaker.java", d / "PresetMakerTest.java"]
    subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-encoding", "UTF-8", "-nowarn", "-cp", str(jar),
                    "-d", str(d / "out"), *map(str, srcs)], check=True)
    run = subprocess.run(["java", "-cp", f"{d / 'out'}:{jar}", "com.hisho.tether.PresetMakerTest"], capture_output=True, text=True)
    if run.returncode: sys.exit(run.stdout + run.stderr)
    sys.stderr.write(run.stderr)
    xmp = run.stdout

CRS = "{http://ns.adobe.com/camera-raw-settings/1.0/}"
RDF = "{http://www.w3.org/1999/02/22-rdf-syntax-ns#}"
root = ET.fromstring(xmp)
desc = root.find(f"{RDF}RDF/{RDF}Description")
attrs = {k[len(CRS):]: v for k, v in desc.attrib.items() if k.startswith(CRS)}
assert attrs["Exposure2012"] == "+0.75", attrs
assert attrs["Shadows2012"] == "15" and attrs["Clarity2012"] == "35" and attrs["Texture"] == "12", attrs
assert "Contrast2012" not in attrs and "LookAmount" not in attrs and "Look" not in attrs, attrs
assert attrs["PresetType"] == "Normal" and attrs["HasSettings"] == "True", attrs
assert not any(k.startswith("ToneCurvePV2012.") for k in attrs), "pontos de curva não vão como atributos"
points = [li.text for li in desc.find(f"{CRS}ToneCurvePV2012/{RDF}Seq")]
assert points == ["0, 20", "128, 140", "255, 250"], points
name = desc.find(f"{CRS}Name/{RDF}Alt/{RDF}li").text
assert name == 'Casamento <Praia> & "sol"', name
print("preset_maker_test: ok (.xmp legível, valores e curva conferem)")
