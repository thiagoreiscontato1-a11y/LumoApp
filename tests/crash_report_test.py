#!/usr/bin/env python3
"""Executa o CrashReporter real contra stubs do Android e um servidor local que imita o endpoint de envelopes do Sentry.

Confere: formato do evento (cadeia de causas, frames, mecanismo), máscara de dados do cliente, fila local, envio
(200 apaga, 503 guarda, 400 descarta), envio desligado, build sem DSN e o handler de exceções não tratadas.
org.json não existe no JDK: o jar é baixado do Maven Central (SHA-256 fixo) e guardado em ~/.cache/lumo-tests.
"""
import hashlib, os, pathlib, subprocess, sys, tempfile, urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
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


STUBS = {
    "android/content/SharedPreferences.java": """package android.content; import java.util.*;
public class SharedPreferences { Map<String,Object> m=new HashMap<>();
 public boolean getBoolean(String k,boolean d){return (Boolean)m.getOrDefault(k,d);} public long getLong(String k,long d){return (Long)m.getOrDefault(k,d);}
 public String getString(String k,String d){return (String)m.getOrDefault(k,d);} public Editor edit(){return new Editor();}
 public class Editor { public Editor putBoolean(String k,boolean v){m.put(k,v);return this;} public Editor putLong(String k,long v){m.put(k,v);return this;}
  public Editor putString(String k,String v){m.put(k,v);return this;} public void apply(){} public boolean commit(){return true;} } }""",
    "android/content/res/AssetManager.java": """package android.content.res; import java.io.*; import java.util.*;
public class AssetManager { public Map<String,String> files=new HashMap<>();
 public InputStream open(String n) throws IOException { String s=files.get(n); if(s==null) throw new FileNotFoundException(n); return new ByteArrayInputStream(s.getBytes("UTF-8")); } }""",
    "android/content/pm/PackageInfo.java": """package android.content.pm; public class PackageInfo { public String versionName="0.19.0"; public long getLongVersionCode(){return 55;} }""",
    "android/content/pm/PackageManager.java": """package android.content.pm; public class PackageManager { public PackageInfo getPackageInfo(String n,int f) throws Exception {return new PackageInfo();} }""",
    "android/content/Context.java": """package android.content; import java.io.File;
public class Context { public static final String ACTIVITY_SERVICE="activity"; public static File files; public static android.content.res.AssetManager assets=new android.content.res.AssetManager();
 SharedPreferences p=new SharedPreferences(); public String getPackageName(){return "com.hisho.tether";} public File getFilesDir(){return files;}
 public android.content.res.AssetManager getAssets(){return assets;} public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}
 public SharedPreferences getSharedPreferences(String n,int m){return p;} public Object getSystemService(String s){return null;} }""",
    "android/app/Application.java": """package android.app; public class Application extends android.content.Context {}""",
    "android/app/ApplicationExitInfo.java": """package android.app; public class ApplicationExitInfo { public static final int REASON_CRASH_NATIVE=5, REASON_ANR=6;
 public long getTimestamp(){return 0;} public int getReason(){return 0;} public String getDescription(){return null;} public int getImportance(){return 0;}
 public java.io.InputStream getTraceInputStream() throws java.io.IOException {return null;} }""",
    "android/app/ActivityManager.java": """package android.app; public class ActivityManager { public java.util.List<ApplicationExitInfo> getHistoricalProcessExitReasons(String p,int pid,int max){return new java.util.ArrayList<>();} }""",
    "android/os/Build.java": """package android.os; public class Build { public static String MANUFACTURER="Samsung", BRAND="samsung", MODEL="SM-S918B", DEVICE="dm3q", DISPLAY="UP1A";
 public static String[] SUPPORTED_ABIS={"arm64-v8a"}; public static class VERSION { public static int SDK_INT=29; public static String RELEASE="10"; } }""",
    "android/os/Process.java": """package android.os; public class Process { public static int myPid(){return 1;} public static void killProcess(int p){} }""",
    "android/os/Looper.java": """package android.os; public class Looper { public static Looper getMainLooper(){return new Looper();} }""",
    "android/os/Handler.java": """package android.os; public class Handler { public Handler(Looper l){} public boolean post(Runnable r){r.run();return true;} }""",
    "com/hisho/tether/CaptureService.java": """package com.hisho.tether; class CaptureService { static Object active; }""",
}

TEST = r'''package com.hisho.tether;
import com.sun.net.httpserver.*; import java.io.*; import java.net.*; import java.nio.charset.StandardCharsets; import java.nio.file.*; import java.util.*; import org.json.*;
public class CrashReporterTest {
 static int count; static void check(boolean v,String what){count++; if(!v) throw new AssertionError("falhou: "+what);}
 static List<String> bodies=new ArrayList<>(), auth=new ArrayList<>(), paths=new ArrayList<>(); static int status=200;
 static File[] queue(){File[] f=CrashReporter.dir(app).listFiles((d,n)->n.endsWith(".json"));return f==null?new File[0]:f;}
 static android.app.Application app=new android.app.Application();
 public static void main(String[] a) throws Exception {
  File tmp=Files.createTempDirectory("lumo-crash").toFile(); android.content.Context.files=tmp;
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/",x->{paths.add(x.getRequestURI().getPath());auth.add(x.getRequestHeaders().getFirst("X-Sentry-Auth"));
   bodies.add(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));x.sendResponseHeaders(status,-1);x.close();});
  server.start(); String dsn="http://chavepublica@127.0.0.1:"+server.getAddress().getPort()+"/42";

  // Sem DSN: instala, guarda o relatório e não envia nada.
  final List<Throwable> forwarded=new ArrayList<>();
  Thread.setDefaultUncaughtExceptionHandler((t,e)->forwarded.add(e));
  CrashReporter.install(app); Thread.sleep(200);
  check(!CrashReporter.configured(app),"build sem lumo-build.json não está configurado");
  Throwable root=new IllegalStateException("arquivo /storage/emulated/0/Pictures/LUMO/Eventos/Casamento_Ana_e_Joao_x1/Originais/IMG_1.JPG sumiu");
  RuntimeException top=new RuntimeException("falha ao enviar para ana@exemplo.com com token=abc123segredo",root);
  Thread.getDefaultUncaughtExceptionHandler().uncaughtException(new Thread("lumo-edit"),top);
  check(forwarded.size()==1&&forwarded.get(0)==top,"handler anterior recebe a mesma exceção");
  check(queue().length==1,"relatório gravado na fila");
  CrashReporter.sendPending(); check(bodies.isEmpty(),"sem DSN nada é enviado"); check(queue().length==1,"sem DSN a fila é mantida");

  JSONObject e=new JSONObject(new String(Files.readAllBytes(queue()[0].toPath()),StandardCharsets.UTF_8));
  check(e.getString("level").equals("fatal"),"nível fatal");
  check(e.getString("release").equals("lumo-android@0.19.0")&&e.getString("dist").equals("55"),"release e dist");
  check(e.getString("environment").equals("teste"),"ambiente teste sem chave de produção");
  check(e.getString("event_id").matches("[0-9a-f]{32}"),"event_id com 32 hex");
  JSONArray v=e.getJSONObject("exception").getJSONArray("values");
  check(v.length()==2,"cadeia com causa");
  check(v.getJSONObject(0).getString("type").equals("IllegalStateException"),"causa mais interna primeiro");
  check(v.getJSONObject(1).getString("type").equals("RuntimeException")&&v.getJSONObject(1).getString("module").equals("java.lang"),"exceção lançada por último");
  check(v.getJSONObject(1).getJSONObject("mechanism").getBoolean("handled")==false&&!v.getJSONObject(0).has("mechanism"),"mecanismo só na exceção lançada");
  String all=e.toString();
  check(!all.contains("Casamento")&&all.contains("Eventos/<evento>"),"nome do evento mascarado");
  check(!all.contains("ana@exemplo.com")&&all.contains("<email>"),"e-mail mascarado");
  check(!all.contains("abc123segredo"),"token mascarado");
  JSONArray frames=v.getJSONObject(1).getJSONObject("stacktrace").getJSONArray("frames");
  JSONObject lastFrame=frames.getJSONObject(frames.length()-1);
  check(lastFrame.getString("module").equals("com.hisho.tether.CrashReporterTest")&&lastFrame.getBoolean("in_app"),"frame mais recente por último e marcado como do app");
  check(e.getJSONObject("contexts").getJSONObject("device").getString("model").equals("SM-S918B"),"modelo do aparelho");
  check(e.getJSONObject("tags").getString("thread").equals("lumo-edit"),"thread no tag");
  String text=new String(Files.readAllBytes(CrashReporter.lastReport(app).toPath()),StandardCharsets.UTF_8);
  check(text.contains("Causado por: java.lang.IllegalStateException")&&text.contains("SM-S918B")&&!text.contains("Casamento"),"relatório legível para compartilhar");

  // Rastro: mascarado e limitado a 30 linhas.
  for(int i=0;i<40;i++)CrashReporter.note("captura · Original salvo: Eventos/Festa_Maria/IMG_"+i+".JPG");
  JSONObject withTrail=CrashReporter.base("info");JSONArray crumbs=withTrail.getJSONObject("breadcrumbs").getJSONArray("values");
  check(crumbs.length()==30,"rastro com 30 passos");check(!crumbs.toString().contains("Maria")&&crumbs.toString().contains("IMG_39"),"rastro mascarado e com o passo mais recente");

  // Com DSN: 503 guarda, 200 apaga, formato do envelope.
  android.content.Context.assets.files.put("lumo-build.json","{\"sentryDsn\":\""+dsn+"\",\"signing\":\"producao\"}");
  check(CrashReporter.configured(app),"DSN lido do lumo-build.json");
  check(CrashReporter.base("info").getString("environment").equals("producao"),"ambiente produção");
  status=503; CrashReporter.sendPending(); check(bodies.size()==1&&queue().length==1,"503 mantém o relatório para a próxima abertura");
  status=200; CrashReporter.sendPending(); check(bodies.size()==2&&queue().length==0,"200 apaga o relatório enviado");
  check(paths.get(1).equals("/api/42/envelope/"),"endpoint de envelopes do projeto");
  check(auth.get(1).contains("sentry_version=7")&&auth.get(1).contains("sentry_key=chavepublica"),"cabeçalho X-Sentry-Auth");
  String[] lines=bodies.get(1).split("\n");
  check(lines.length==3,"envelope com 3 linhas");
  JSONObject head=new JSONObject(lines[0]),item=new JSONObject(lines[1]),body=new JSONObject(lines[2]);
  check(head.getString("event_id").equals(body.getString("event_id"))&&head.getString("dsn").equals(dsn),"cabeçalho do envelope");
  check(item.getString("type").equals("event")&&item.getInt("length")==lines[2].getBytes(StandardCharsets.UTF_8).length,"tamanho do item em bytes");
  check(CrashReporter.lastReport(app).exists(),"último relatório continua para compartilhar");

  // Evento recusado (400) é descartado; envio desligado não envia.
  CrashReporter.save(CrashReporter.base("error")); status=400; CrashReporter.sendPending(); check(queue().length==0,"400 descarta");
  status=200; CrashReporter.prefs(app).edit().putBoolean("send",false).apply();
  CrashReporter.save(CrashReporter.base("error")); int before=bodies.size(); CrashReporter.sendPending();
  check(bodies.size()==before&&queue().length==1,"envio desligado guarda no aparelho");
  CrashReporter.setEnabled(app,true); Thread.sleep(300); check(queue().length==0,"religar envia o que ficou");

  // Fila limitada.
  CrashReporter.prefs(app).edit().putBoolean("send",false).apply();
  for(int i=0;i<CrashReporter.MAX_PENDING+5;i++)CrashReporter.save(CrashReporter.base("error"));
  check(queue().length==CrashReporter.MAX_PENDING,"fila limitada a "+CrashReporter.MAX_PENDING);

  // DSN com caminho e chave:segredo (formato antigo).
  status=200; int n=paths.size(); CrashReporter.post("http://pub:secreto@127.0.0.1:"+server.getAddress().getPort()+"/sentry/7",CrashReporter.base("info"));
  check(paths.get(n).equals("/sentry/api/7/envelope/")&&auth.get(n).contains("sentry_key=pub,")==false&&auth.get(n).endsWith("sentry_key=pub"),"DSN com prefixo e segredo");
  server.stop(0); System.out.println(count+" verificações do relatório de falhas passaram");
 }
}
'''


def main():
    jar = org_json()
    with tempfile.TemporaryDirectory() as folder:
        p = pathlib.Path(folder)
        for name, text in STUBS.items():
            f = p / "src" / name
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_text(text)
        (p / "src/com/hisho/tether/CrashReporter.java").write_text((ROOT / "app/src/main/java/com/hisho/tether/CrashReporter.java").read_text(encoding="utf-8"))
        (p / "src/com/hisho/tether/CrashReporterTest.java").write_text(TEST)
        sources = [str(x) for x in (p / "src").rglob("*.java")]
        subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-encoding", "UTF-8", "-nowarn", "-cp", str(jar), "-d", str(p / "out"), *sources], check=True)
        subprocess.run(["java", "-cp", f"{p / 'out'}{os.pathsep}{jar}", "com.hisho.tether.CrashReporterTest"], check=True)


main()
