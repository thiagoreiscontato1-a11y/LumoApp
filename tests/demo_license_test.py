"""Execute the real license class against deterministic Android preference/clock stubs."""
from pathlib import Path
import tempfile, subprocess, hashlib, re
root=Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory() as folder:
 p=Path(folder)
 def put(name,text):
  f=p/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(text)
 put('android/content/SharedPreferences.java','''package android.content; import java.util.*; public class SharedPreferences {Map<String,Long> m=new HashMap<>(); public long getLong(String k,long d){return m.getOrDefault(k,d);} public Editor edit(){return new Editor();} public class Editor {public Editor putLong(String k,long v){m.put(k,v);return this;}public void apply(){}public boolean commit(){return true;}}}''')
 put('android/content/Context.java','''package android.content; public class Context {public String name="com.hisho.tether.demo";SharedPreferences p=new SharedPreferences(); public String getPackageName(){return name;} public SharedPreferences getSharedPreferences(String s,int mode){return p;}public Object getContentResolver(){return null;}}''')
 put('android/os/SystemClock.java','''package android.os; public class SystemClock {public static long tick=100;public static long elapsedRealtime(){return tick;}}''')
 put('android/provider/Settings.java','''package android.provider;public class Settings {public static class Global {public static int boot=1;public static int getInt(Object c,String k,int d){return boot;}}}''')
 source=(root/'app/src/main/java/com/hisho/tether/DemoLicense.java').read_text()
 source=re.sub(r'equals\("[0-9a-f]{64}"\)',f'equals("{hashlib.sha256(b"TEST-CODE").hexdigest()}")',source)
 put('com/hisho/tether/DemoLicense.java',source)
 put('com/hisho/tether/DemoLicenseTest.java','''package com.hisho.tether;import android.content.*;public class DemoLicenseTest{
 static int count;static void check(boolean v){count++;if(!v)throw new AssertionError("case "+count);}
 public static void main(String[] args){Context c=new Context();check(!DemoLicense.allowed(c));check(!DemoLicense.activate(c,"bad"));check(DemoLicense.activate(c," test-code "));check(DemoLicense.allowed(c));long start=DemoLicense.prefs(c).getLong("start",0);check(DemoLicense.remaining(c)>DemoLicense.DURATION-1000);check(DemoLicense.activate(c,"TEST-CODE"));check(DemoLicense.prefs(c).getLong("start",0)==start);
 android.os.SystemClock.tick+=DemoLicense.DURATION;check(!DemoLicense.allowed(c));check(!DemoLicense.activate(c,"TEST-CODE"));check(DemoLicense.prefs(c).getLong("start",0)==start);android.provider.Settings.Global.boot++;android.os.SystemClock.tick=0;check(!DemoLicense.allowed(c));c.name="com.hisho.tether";check(DemoLicense.allowed(c));System.out.println(count+" license checks passed");}}
''')
 subprocess.run(['java','-m','jdk.compiler/com.sun.tools.javac.Main','-d',str(p/'out'),*[str(x) for x in p.rglob('*.java')]],check=True)
 subprocess.run(['java','-cp',str(p/'out'),'com.hisho.tether.DemoLicenseTest'],check=True)
