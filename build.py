import os, pathlib, subprocess, zipfile, shutil
p=pathlib.Path(__file__).resolve().parent
sdk=pathlib.Path(os.environ['ANDROID_SDK_ROOT']);tools=sdk/'build-tools/35.0.0';android=sdk/'platforms/android-35/android.jar';build=p/'build'
demo=os.environ.get('LUMO_DEMO')=='1'
if build.exists():shutil.rmtree(build)
(build/'classes').mkdir(parents=True,exist_ok=True);(build/'dex').mkdir(exist_ok=True)
manifest=p/'app/src/main/AndroidManifest.xml'
if demo:
    source=manifest.read_text().replace('package="com.hisho.tether"','package="com.hisho.tether.demo"').replace('android:name=".','android:name="com.hisho.tether.').replace('android:label="LUMO"','android:label="Lumo Demo"')
    source=source.replace('<activity android:name="com.hisho.tether.DemoActivationActivity" android:exported="false"/>','<activity android:name="com.hisho.tether.MainActivity" android:exported="false"/>')
    source=source.replace('<activity android:name="com.hisho.tether.MainActivity" android:exported="true">','<activity android:name="com.hisho.tether.DemoActivationActivity" android:exported="true">')
    manifest=build/'AndroidManifest.xml';manifest.write_text(source)
def run(*args):subprocess.run([str(a) for a in args],check=True,cwd=p)
run('java','-m','jdk.compiler/com.sun.tools.javac.Main','-encoding','UTF-8','-source','8','-target','8','-classpath',android,'-d',build/'classes',*sorted((p/'app/src/main/java').rglob('*.java')))
run('java','-m','jdk.jartool/sun.tools.jar.Main','--create','--file',build/'classes.jar','-C',build/'classes','.')
run('java','-cp',tools/'lib/d8.jar','com.android.tools.r8.D8','--lib',android,'--min-api','29','--output',build/'dex',build/'classes.jar')
run(tools/'aapt2','compile','--dir',p/'app/src/main/res','-o',build/'resources.zip')
run(tools/'aapt2','link','-I',android,'--manifest',manifest,'-A',p/'app/src/main/assets','--version-code','48','--version-name',('0.15.27-demo' if demo else '0.15.27'),'-o',build/'unsigned.apk',build/'resources.zip')
with zipfile.ZipFile(build/'unsigned.apk','a') as z:z.write(build/'dex/classes.dex','classes.dex')
run(tools/'zipalign','-f','4',build/'unsigned.apk',build/'aligned.apk')
run('java','-jar',tools/'lib/apksigner.jar','sign','--ks',p/'hisho-test.keystore','--ks-key-alias','hisho-test','--ks-pass','pass:android','--key-pass','pass:android','--out',p/('Lumo-Demo.apk' if demo else 'Lumo.apk'),build/'aligned.apk')
run('java','-jar',tools/'lib/apksigner.jar','verify','--verbose',p/('Lumo-Demo.apk' if demo else 'Lumo.apk'))
