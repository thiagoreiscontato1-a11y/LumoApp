import base64, json, os, pathlib, subprocess, sys, zipfile, shutil
p=pathlib.Path(__file__).resolve().parent
sdk=pathlib.Path(os.environ['ANDROID_SDK_ROOT']);tools=sdk/'build-tools/35.0.0';android=sdk/'platforms/android-35/android.jar';build=p/'build'
demo=os.environ.get('LUMO_DEMO')=='1'
output=p/('Lumo-Demo.apk' if demo else 'Lumo.apk')

# Assinatura. Produção: a chave vem do ambiente (no GitHub, dos Secrets; veja ASSINATURA.md).
#   LUMO_KEYSTORE_BASE64 (o arquivo .jks/.p12 em base64) ou LUMO_KEYSTORE (caminho do arquivo),
#   LUMO_KEYSTORE_PASSWORD, LUMO_KEY_ALIAS e, se diferente da senha do arquivo, LUMO_KEY_PASSWORD.
# Sem essas variáveis, assina com hisho-test.keystore (chave de TESTE, pública, só para desenvolvimento).
# LUMO_REQUIRE_RELEASE_KEY=1 faz o build falhar em vez de cair na chave de teste.
release_vars=('LUMO_KEYSTORE_BASE64','LUMO_KEYSTORE','LUMO_KEYSTORE_PASSWORD','LUMO_KEY_ALIAS','LUMO_KEY_PASSWORD')
release=any(os.environ.get(v) for v in release_vars)
if release:
    missing=[v for v in ('LUMO_KEYSTORE_PASSWORD','LUMO_KEY_ALIAS') if not os.environ.get(v)]
    if not (os.environ.get('LUMO_KEYSTORE_BASE64') or os.environ.get('LUMO_KEYSTORE')):missing.insert(0,'LUMO_KEYSTORE_BASE64 (ou LUMO_KEYSTORE)')
    if missing:sys.exit('Chave de produção incompleta. Falta: '+', '.join(missing))
elif os.environ.get('LUMO_REQUIRE_RELEASE_KEY')=='1':
    sys.exit('LUMO_REQUIRE_RELEASE_KEY=1, mas a chave de produção não foi informada (veja ASSINATURA.md).')
else:
    print('AVISO: assinando com a chave de TESTE (hisho-test.keystore). Não distribua este APK a clientes.',flush=True)

if build.exists():shutil.rmtree(build)
(build/'classes').mkdir(parents=True,exist_ok=True);(build/'dex').mkdir(exist_ok=True)
manifest=p/'app/src/main/AndroidManifest.xml'
if demo:
    source=manifest.read_text().replace('package="com.hisho.tether"','package="com.hisho.tether.demo"').replace('android:name=".','android:name="com.hisho.tether.').replace('android:label="LUMO"','android:label="Lumo Demo"')
    source=source.replace('<activity android:name="com.hisho.tether.DemoActivationActivity" android:exported="false"/>','<activity android:name="com.hisho.tether.MainActivity" android:exported="false"/>')
    source=source.replace('<activity android:name="com.hisho.tether.MainActivity" android:exported="true">','<activity android:name="com.hisho.tether.DemoActivationActivity" android:exported="true">')
    manifest=build/'AndroidManifest.xml';manifest.write_text(source)

# Assets + lumo-build.json (lido por CrashReporter): DSN do Sentry e o tipo de chave, para separar testes de clientes.
assets=build/'assets';shutil.copytree(p/'app/src/main/assets',assets)
(assets/'lumo-build.json').write_text(json.dumps({'sentryDsn':os.environ.get('SENTRY_DSN','').strip(),'signing':'producao' if release else 'teste'}))

def run(*args,**kw):subprocess.run([str(a) for a in args],check=True,cwd=p,**kw)
run('java','-m','jdk.compiler/com.sun.tools.javac.Main','-encoding','UTF-8','-source','8','-target','8','-classpath',android,'-d',build/'classes',*sorted((p/'app/src/main/java').rglob('*.java')))
run('java','-m','jdk.jartool/sun.tools.jar.Main','--create','--file',build/'classes.jar','-C',build/'classes','.')
run('java','-cp',tools/'lib/d8.jar','com.android.tools.r8.D8','--lib',android,'--min-api','29','--output',build/'dex',build/'classes.jar')
run(tools/'aapt2','compile','--dir',p/'app/src/main/res','-o',build/'resources.zip')
run(tools/'aapt2','link','-I',android,'--manifest',manifest,'-A',assets,'--version-code','56','--version-name',('0.20.0-demo' if demo else '0.20.0'),'-o',build/'unsigned.apk',build/'resources.zip')
with zipfile.ZipFile(build/'unsigned.apk','a') as z:z.write(build/'dex/classes.dex','classes.dex')
run(tools/'zipalign','-f','4',build/'unsigned.apk',build/'aligned.apk')

# As senhas vão por variável de ambiente (env:), nunca na linha de comando.
env=dict(os.environ)
if release:
    keystore=os.environ.get('LUMO_KEYSTORE')
    if os.environ.get('LUMO_KEYSTORE_BASE64'):
        keystore=build/'release.keystore'
        keystore.write_bytes(base64.b64decode(''.join(os.environ['LUMO_KEYSTORE_BASE64'].split())))
        keystore.chmod(0o600)
    env.setdefault('LUMO_KEY_PASSWORD','')
    if not env['LUMO_KEY_PASSWORD']:env['LUMO_KEY_PASSWORD']=env['LUMO_KEYSTORE_PASSWORD']
    sign=['--ks',keystore,'--ks-key-alias',os.environ['LUMO_KEY_ALIAS'],'--ks-pass','env:LUMO_KEYSTORE_PASSWORD','--key-pass','env:LUMO_KEY_PASSWORD']
else:
    env['LUMO_TEST_PASS']='android'
    sign=['--ks',p/'hisho-test.keystore','--ks-key-alias','hisho-test','--ks-pass','env:LUMO_TEST_PASS','--key-pass','env:LUMO_TEST_PASS']
try:
    run('java','-jar',tools/'lib/apksigner.jar','sign',*sign,'--out',output,build/'aligned.apk',env=env)
finally:
    if (build/'release.keystore').exists():(build/'release.keystore').unlink()
run('java','-jar',tools/'lib/apksigner.jar','verify','--verbose','--print-certs',output)
print(('Assinado com a chave de PRODUÇÃO: ' if release else 'Assinado com a chave de TESTE: ')+output.name)
