package com.hisho.tether;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class FolderWatchService extends Service {
 volatile boolean running=true; Thread worker; Jobs jobs;
 public android.os.IBinder onBind(Intent i){return null;}
 public void onCreate(){super.onCreate();jobs=new Jobs(this);NotificationManager n=getSystemService(NotificationManager.class);n.createNotificationChannel(new NotificationChannel("folderwatch","Pasta monitorada",NotificationManager.IMPORTANCE_LOW));}
 public int onStartCommand(Intent i,int flags,int id){if(i!=null&&"stop".equals(i.getAction())){running=false;getSharedPreferences("hisho",0).edit().putBoolean("folderWatchEnabled",false).apply();stopSelf();return START_NOT_STICKY;}startForeground(7,note("Aguardando novas fotos…"));if(worker==null||!worker.isAlive()){running=true;worker=new Thread(this::loop,"LUMO-FolderWatch");worker.start();}return START_STICKY;}
 Notification note(String s){return new Notification.Builder(this,"folderwatch").setSmallIcon(android.R.drawable.ic_menu_gallery).setContentTitle("LUMO · pasta monitorada").setContentText(s).setOngoing(true).build();}
 void status(String s){getSharedPreferences("hisho",0).edit().putString("folderWatchStatus",s).apply();try{getSystemService(NotificationManager.class).notify(7,note(s));}catch(Exception ignored){}}
 void loop(){while(running&&getSharedPreferences("hisho",0).getBoolean("folderWatchEnabled",false)){try{scan();}catch(Exception e){status("Falha ao ler pasta · "+e.getMessage());}try{Thread.sleep(1200);}catch(InterruptedException e){break;}}stopSelf();}
 void scan()throws Exception{String raw=getSharedPreferences("hisho",0).getString("folderWatchTreeUri","");if(raw.isEmpty())return;Uri tree=Uri.parse(raw);String parent=DocumentsContract.getTreeDocumentId(tree);Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent);String[] proj={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED};ArrayList<Row> rows=new ArrayList<>();try(Cursor c=getContentResolver().query(children,proj,null,null,null)){if(c==null)return;while(c.moveToNext()){String mime=c.getString(2),name=c.getString(1);if(name==null||mime==null)continue;String low=name.toLowerCase(Locale.ROOT);if(!(mime.equals("image/jpeg")||low.endsWith(".jpg")||low.endsWith(".jpeg")))continue;rows.add(new Row(c.getString(0),name,c.getLong(3),c.getLong(4)));}}for(Row r:rows)importOne(tree,r);}
 void importOne(Uri tree,Row r)throws Exception{String marker="fw:"+r.id+":"+r.size+":"+r.modified;String id=sha(marker);if(jobs.exists(id))return;if(r.size<=0)return;Uri doc=DocumentsContract.buildDocumentUriUsingTree(tree,r.id);File dir=new File(getFilesDir(),"queue");dir.mkdirs();File tmp=new File(dir,id+".part"),out=new File(dir,id+".jpg");long copied=0;try(InputStream in=getContentResolver().openInputStream(doc);OutputStream os=new FileOutputStream(tmp)){if(in==null)return;byte[] b=new byte[128*1024];int n;while((n=in.read(b))!=-1){os.write(b,0,n);copied+=n;}}if(copied<=0){tmp.delete();return;}if(!tmp.renameTo(out)){tmp.delete();throw new IOException("Falha ao copiar "+r.name);}String content=PhotoHash.sha256(out);String dup=jobs.duplicateByContent(content);if(!dup.isEmpty()){out.delete();return;}String ph="";try{ph=PhotoHash.perceptualHash(out);}catch(Exception ignored){}String settings=getSharedPreferences("hisho",0).getString("liveSettings","{}");long captured=r.modified>0?r.modified:System.currentTimeMillis();jobs.add(id,out,safe(r.name),settings,captured,content,ph,out.length());jobs.history(id,System.currentTimeMillis(),"Pasta monitorada","Importada automaticamente de "+r.name);status("Nova foto importada · "+r.name);Intent edit=new Intent(this,CaptureService.class).putExtra("settings",settings);startForegroundService(edit);}
 static String safe(String n){String x=n.replaceAll("[^a-zA-Z0-9_.-]","_");return x.toLowerCase(Locale.ROOT).endsWith(".jpg")||x.toLowerCase(Locale.ROOT).endsWith(".jpeg")?x:x+".jpg";}
 static String sha(String s)throws Exception{byte[] d=MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));StringBuilder b=new StringBuilder();for(byte x:d)b.append(String.format(Locale.ROOT,"%02x",x));return b.toString();}
 static class Row{String id,name;long size,modified;Row(String i,String n,long s,long m){id=i;name=n;size=s;modified=m;}}
 public void onDestroy(){running=false;if(worker!=null)worker.interrupt();if(jobs!=null)jobs.close();super.onDestroy();}
}
