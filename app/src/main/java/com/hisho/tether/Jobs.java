package com.hisho.tether;

import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.DocumentsContract;
import java.io.*;
import java.util.*;

final class Jobs extends SQLiteOpenHelper{
 final Context context;
 Jobs(Context c){super(c,"queue.db",null,6);context=c;}

 public void onCreate(SQLiteDatabase d){
  d.execSQL("CREATE TABLE jobs (id TEXT PRIMARY KEY, file TEXT NOT NULL, name TEXT NOT NULL, settings TEXT NOT NULL, original TEXT, edited TEXT, state TEXT NOT NULL, error TEXT, score INTEGER NOT NULL DEFAULT 0, quality_note TEXT, content_hash TEXT, phash TEXT, file_size INTEGER NOT NULL DEFAULT 0, captured_at INTEGER NOT NULL DEFAULT 0, downloaded_at INTEGER NOT NULL DEFAULT 0, edited_at INTEGER NOT NULL DEFAULT 0, burst_id TEXT, duplicate_of TEXT, semantic_note TEXT)");
  createFotto(d);createHistory(d);
 }
 public void onUpgrade(SQLiteDatabase d,int a,int b){
  if(a<2)createFotto(d);
  if(a<3){
   try{d.execSQL("ALTER TABLE jobs ADD COLUMN score INTEGER NOT NULL DEFAULT 0");}catch(Exception ignored){}
   try{d.execSQL("ALTER TABLE jobs ADD COLUMN quality_note TEXT");}catch(Exception ignored){}
  }
  if(a<4){
   for(String q:new String[]{
    "ALTER TABLE jobs ADD COLUMN content_hash TEXT",
    "ALTER TABLE jobs ADD COLUMN phash TEXT",
    "ALTER TABLE jobs ADD COLUMN file_size INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE jobs ADD COLUMN captured_at INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE jobs ADD COLUMN downloaded_at INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE jobs ADD COLUMN edited_at INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE jobs ADD COLUMN burst_id TEXT",
    "ALTER TABLE jobs ADD COLUMN duplicate_of TEXT",
    "ALTER TABLE jobs ADD COLUMN semantic_note TEXT"
   })try{d.execSQL(q);}catch(Exception ignored){}
  }
  if(a<5)createHistory(d);
  if(a<6){
   try{d.execSQL("CREATE INDEX IF NOT EXISTS idx_jobs_hash ON jobs(content_hash)");}catch(Exception ignored){}
   try{d.execSQL("CREATE INDEX IF NOT EXISTS idx_jobs_burst ON jobs(burst_id)");}catch(Exception ignored){}
   try{d.execSQL("CREATE INDEX IF NOT EXISTS idx_history_job ON history(job_id,ts)");}catch(Exception ignored){}
  }
 }
 static void createFotto(SQLiteDatabase d){d.execSQL("CREATE TABLE IF NOT EXISTS fotto_uploads (job_id TEXT NOT NULL, gallery_id TEXT NOT NULL, state TEXT NOT NULL, media_id TEXT, error TEXT, attempts INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(job_id,gallery_id))");}
 static void createHistory(SQLiteDatabase d){d.execSQL("CREATE TABLE IF NOT EXISTS history (id INTEGER PRIMARY KEY AUTOINCREMENT, job_id TEXT NOT NULL, ts INTEGER NOT NULL, type TEXT NOT NULL, detail TEXT)");}

 synchronized boolean exists(String id){try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM jobs WHERE id=?",new String[]{id})){return c.moveToFirst();}}
 synchronized void add(String id,File file,String name,String settings){add(id,file,name,settings,System.currentTimeMillis(),"","",file.length());}
 synchronized void add(String id,File file,String name,String settings,long capturedAt,String contentHash,String pHash,long fileSize){
  String burst=burstFor(capturedAt);
  ContentValues v=new ContentValues();v.put("id",id);v.put("file",file.getAbsolutePath());v.put("name",name);v.put("settings",settings);v.put("state","original");v.put("content_hash",contentHash);v.put("phash",pHash);v.put("file_size",fileSize);v.put("captured_at",capturedAt);v.put("downloaded_at",System.currentTimeMillis());v.put("burst_id",burst);getWritableDatabase().insertOrThrow("jobs",null,v);
  history(id,capturedAt>0?capturedAt:System.currentTimeMillis(),"Capturada","Registrada pela câmera");
  history(id,System.currentTimeMillis(),"Baixada","Original recebido e preservado");
 }
 synchronized String burstFor(long capturedAt){
  if(capturedAt<=0)capturedAt=System.currentTimeMillis();
  try(Cursor c=getReadableDatabase().rawQuery("SELECT burst_id,captured_at FROM jobs WHERE captured_at>0 ORDER BY captured_at DESC LIMIT 1",null)){
   if(c.moveToFirst()){long prev=c.getLong(1);String b=c.getString(0);if(Math.abs(capturedAt-prev)<=2200&&b!=null&&!b.isEmpty())return b;}
  }
  return "R"+capturedAt;
 }
 synchronized String duplicateByContent(String hash){
  if(hash==null||hash.isEmpty())return "";
  try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM jobs WHERE content_hash=? LIMIT 1",new String[]{hash})){return c.moveToFirst()?c.getString(0):"";}
 }
 synchronized String visualDuplicate(String id,String pHash,long size){
  if(pHash==null||pHash.length()!=16)return "";
  try(Cursor c=getReadableDatabase().rawQuery("SELECT id,phash,file_size,burst_id FROM jobs WHERE id<>? AND phash IS NOT NULL AND phash<>'' ORDER BY downloaded_at DESC LIMIT 250",new String[]{id})){
   String myBurst=burstId(id);
   while(c.moveToNext()){
    String other=c.getString(0),h=c.getString(1),burst=c.getString(3);long os=c.getLong(2);
    if(myBurst!=null&&myBurst.equals(burst))continue; // rajada legítima não é bloqueada como duplicata
    if(size>0&&os>0){double ratio=Math.abs(size-os)/(double)Math.max(size,os);if(ratio>.04)continue;}
    if(PhotoHash.hammingHex(pHash,h)<=2)return other;
   }
  }
  return "";
 }
 synchronized void setIntelligence(String id,String pHash,String duplicateOf,String semanticNote){
  ContentValues v=new ContentValues();if(pHash!=null)v.put("phash",pHash);if(duplicateOf==null||duplicateOf.isEmpty())v.putNull("duplicate_of");else v.put("duplicate_of",duplicateOf);if(semanticNote==null)v.putNull("semantic_note");else v.put("semantic_note",semanticNote);getWritableDatabase().update("jobs",v,"id=?",new String[]{id});
 }
 synchronized String burstId(String id){try(Cursor c=getReadableDatabase().rawQuery("SELECT burst_id FROM jobs WHERE id=?",new String[]{id})){return c.moveToFirst()&&!c.isNull(0)?c.getString(0):"";}}
 synchronized String[] burstSummary(String id){
  String b=burstId(id);if(b.isEmpty())return new String[0];ArrayList<String> out=new ArrayList<>();
  try(Cursor c=getReadableDatabase().rawQuery("SELECT id,name,score,state FROM jobs WHERE burst_id=? ORDER BY score DESC,captured_at ASC",new String[]{b})){while(c.moveToNext())out.add(c.getString(0)+"|"+c.getString(1)+"|"+c.getInt(2)+"|"+c.getString(3));}
  return out.toArray(new String[0]);
 }

 synchronized void set(String id,String state,String field,String value){ContentValues v=new ContentValues();v.put("state",state);if(field!=null){if(value==null)v.putNull(field);else v.put(field,value);}getWritableDatabase().update("jobs",v,"id=?",new String[]{id});}
 synchronized void setQuality(String id,int score,String note){ContentValues v=new ContentValues();v.put("score",Math.max(0,Math.min(10,score)));if(note==null)v.putNull("quality_note");else v.put("quality_note",note);getWritableDatabase().update("jobs",v,"id=?",new String[]{id});history(id,System.currentTimeMillis(),"Curadoria","Nota "+Math.max(1,Math.min(10,score))+"/10"+(note==null?"":" · "+note));}
 synchronized void markEdited(String id,String presetName){ContentValues v=new ContentValues();v.put("edited_at",System.currentTimeMillis());getWritableDatabase().update("jobs",v,"id=?",new String[]{id});history(id,System.currentTimeMillis(),"Editada","Automático + predefinição "+(presetName==null||presetName.isEmpty()?"sem nome":presetName));}
 synchronized int[] quality(String id){try(Cursor c=getReadableDatabase().rawQuery("SELECT score FROM jobs WHERE id=?",new String[]{id})){return c.moveToFirst()?new int[]{c.getInt(0)}:new int[]{0};}}
 synchronized String qualityNote(String id){try(Cursor c=getReadableDatabase().rawQuery("SELECT quality_note FROM jobs WHERE id=?",new String[]{id})){return c.moveToFirst()&&!c.isNull(0)?c.getString(0):"";}}
 synchronized String semanticNote(String id){try(Cursor c=getReadableDatabase().rawQuery("SELECT semantic_note FROM jobs WHERE id=?",new String[]{id})){return c.moveToFirst()&&!c.isNull(0)?c.getString(0):"";}}
 synchronized String[] next(){try(Cursor c=getReadableDatabase().rawQuery("SELECT id,file,name,settings FROM jobs WHERE state='ready' ORDER BY rowid LIMIT 1",null)){if(!c.moveToFirst())return null;return new String[]{c.getString(0),c.getString(1),c.getString(2),c.getString(3)};}}
 synchronized int pending(){try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM jobs WHERE state NOT IN ('done','review')",null)){c.moveToFirst();return c.getInt(0);}}
 synchronized int processingCount(){try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM jobs WHERE state IN ('original','ready','editing','originalError','error')",null)){c.moveToFirst();return c.getInt(0);}}
 synchronized int reviewCount(){try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM jobs WHERE state='review'",null)){c.moveToFirst();return c.getInt(0);}}
 synchronized int doneCount(){try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM jobs WHERE state='done'",null)){c.moveToFirst();return c.getInt(0);}}
 synchronized long lastDownloadedAt(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COALESCE(MAX(downloaded_at),0) FROM jobs",null)){c.moveToFirst();return c.getLong(0);}}
 synchronized long lastEditedAt(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COALESCE(MAX(edited_at),0) FROM jobs",null)){c.moveToFirst();return c.getLong(0);}}
 synchronized void clearEdited(String id){ContentValues v=new ContentValues();v.putNull("edited");getWritableDatabase().update("jobs",v,"id=?",new String[]{id});}
 synchronized void retry(){getWritableDatabase().execSQL("UPDATE jobs SET state='ready',error=NULL WHERE state IN ('error','editing') AND original IS NOT NULL");getWritableDatabase().execSQL("UPDATE jobs SET state='original',error=NULL WHERE state='originalError' OR (state='error' AND original IS NULL)");}
 synchronized String[] originalPending(){try(Cursor c=getReadableDatabase().rawQuery("SELECT id,file,name FROM jobs WHERE state='original' ORDER BY rowid LIMIT 1",null)){if(!c.moveToFirst())return null;return new String[]{c.getString(0),c.getString(1),c.getString(2)};}}
 synchronized long maxRowId(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COALESCE(MAX(rowid),0) FROM jobs",null)){c.moveToFirst();return c.getLong(0);}}

 synchronized String[] nextFotto(String galleryId,long after){
  String sql="SELECT j.id,j.name,j.edited,j.rowid FROM jobs j LEFT JOIN fotto_uploads f ON f.job_id=j.id AND f.gallery_id=? WHERE j.state='done' AND j.edited IS NOT NULL AND j.rowid>? AND (f.state IS NULL OR (f.state='error' AND f.attempts<4)) ORDER BY j.rowid LIMIT 1";
  try(Cursor c=getReadableDatabase().rawQuery(sql,new String[]{galleryId,String.valueOf(after)})){if(!c.moveToFirst())return null;return new String[]{c.getString(0),c.getString(1).replaceFirst("(?i)\\.jpg$","_Editada.jpg"),c.getString(2),String.valueOf(c.getLong(3))};}
 }
 synchronized int fottoPending(String galleryId){if(galleryId==null||galleryId.isEmpty())return 0;String sql="SELECT COUNT(*) FROM jobs j LEFT JOIN fotto_uploads f ON f.job_id=j.id AND f.gallery_id=? WHERE j.state='done' AND j.edited IS NOT NULL AND (f.state IS NULL OR f.state<>'confirmed')";try(Cursor c=getReadableDatabase().rawQuery(sql,new String[]{galleryId})){c.moveToFirst();return c.getInt(0);}}
 synchronized void fottoSet(String jobId,String galleryId,String state,String mediaId,String error,boolean increment){int attempts=0;try(Cursor c=getReadableDatabase().rawQuery("SELECT attempts FROM fotto_uploads WHERE job_id=? AND gallery_id=?",new String[]{jobId,galleryId})){if(c.moveToFirst())attempts=c.getInt(0);}if(increment)attempts++;ContentValues v=new ContentValues();v.put("job_id",jobId);v.put("gallery_id",galleryId);v.put("state",state);v.put("media_id",mediaId==null?"":mediaId);v.put("error",error==null?"":error);v.put("attempts",attempts);v.put("updated",System.currentTimeMillis());getWritableDatabase().insertWithOnConflict("fotto_uploads",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
 synchronized String[] fottoState(String jobId,String galleryId){try(Cursor c=getReadableDatabase().rawQuery("SELECT state,media_id,error,updated FROM fotto_uploads WHERE job_id=? AND gallery_id=?",new String[]{jobId,galleryId})){if(!c.moveToFirst())return new String[]{"","","","0"};return new String[]{c.getString(0),c.getString(1),c.getString(2),String.valueOf(c.getLong(3))};}}
 synchronized ArrayList<String[]> fottoNeedsConfirmation(String galleryId){ArrayList<String[]> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT f.job_id,j.name,f.media_id FROM fotto_uploads f JOIN jobs j ON j.id=f.job_id WHERE f.gallery_id=? AND f.state IN ('uploaded','unconfirmed') ORDER BY f.updated LIMIT 500",new String[]{galleryId})){while(c.moveToNext())out.add(new String[]{c.getString(0),c.getString(1).replaceFirst("(?i)\\.jpg$","_Editada.jpg"),c.getString(2)});}return out;}
 synchronized void fottoRecover(String galleryId){ContentValues v=new ContentValues();v.put("state","error");v.put("error","Envio interrompido antes da confirmação.");v.put("updated",System.currentTimeMillis());getWritableDatabase().update("fotto_uploads",v,"gallery_id=? AND state='uploading'",new String[]{galleryId});ContentValues accepted=new ContentValues();accepted.put("state","uploaded");accepted.put("error","Aguardando confirmação no evento.");accepted.put("updated",System.currentTimeMillis());getWritableDatabase().update("fotto_uploads",accepted,"gallery_id=? AND state='unconfirmed' AND media_id IS NOT NULL AND media_id<>''",new String[]{galleryId});}
 synchronized void fottoRetry(String galleryId){ContentValues v=new ContentValues();v.put("attempts",0);v.put("state","error");getWritableDatabase().update("fotto_uploads",v,"gallery_id=? AND state='error'",new String[]{galleryId});}

 synchronized int fottoUploadPending(String galleryId,long after){if(galleryId==null||galleryId.isEmpty())return 0;String sql="SELECT COUNT(*) FROM jobs j LEFT JOIN fotto_uploads f ON f.job_id=j.id AND f.gallery_id=? WHERE j.state='done' AND j.edited IS NOT NULL AND j.rowid>? AND (f.state IS NULL OR (f.state='error' AND f.attempts<4))";try(Cursor c=getReadableDatabase().rawQuery(sql,new String[]{galleryId,String.valueOf(after)})){c.moveToFirst();return c.getInt(0);}}
 synchronized int fottoErrorCount(String galleryId){if(galleryId==null||galleryId.isEmpty())return 0;try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM fotto_uploads WHERE gallery_id=? AND state='error'",new String[]{galleryId})){c.moveToFirst();return c.getInt(0);}}
 synchronized long fottoLastStateAt(String galleryId){if(galleryId==null||galleryId.isEmpty())return 0;try(Cursor c=getReadableDatabase().rawQuery("SELECT COALESCE(MAX(updated),0) FROM fotto_uploads WHERE gallery_id=?",new String[]{galleryId})){c.moveToFirst();return c.getLong(0);}}
 synchronized int fottoAccepted(String galleryId){if(galleryId==null||galleryId.isEmpty())return 0;try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM fotto_uploads WHERE gallery_id=? AND state IN ('uploaded','unconfirmed','confirmed')",new String[]{galleryId})){c.moveToFirst();return c.getInt(0);}}
 synchronized int fottoDone(String galleryId){if(galleryId==null||galleryId.isEmpty())return 0;try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM fotto_uploads WHERE gallery_id=? AND state='confirmed'",new String[]{galleryId})){c.moveToFirst();return c.getInt(0);}}
 synchronized int fottoProcessing(String galleryId){if(galleryId==null||galleryId.isEmpty())return 0;try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM fotto_uploads WHERE gallery_id=? AND state IN ('uploading','uploaded','unconfirmed')",new String[]{galleryId})){c.moveToFirst();return c.getInt(0);}}
 synchronized String fottoLastError(String galleryId){if(galleryId==null||galleryId.isEmpty())return "";try(Cursor c=getReadableDatabase().rawQuery("SELECT error FROM fotto_uploads WHERE gallery_id=? AND state IN ('error','unconfirmed') AND error IS NOT NULL AND error<>'' ORDER BY updated DESC LIMIT 1",new String[]{galleryId})){return c.moveToFirst()?c.getString(0):"";}}
 synchronized String[] editorJob(String id){try(Cursor c=getReadableDatabase().rawQuery("SELECT id,name,settings,original,edited,state FROM jobs WHERE id=?",new String[]{id})){if(!c.moveToFirst())return null;return new String[]{c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5)};}}
 synchronized String idForEdited(String uri){try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM jobs WHERE edited=? LIMIT 1",new String[]{uri})){return c.moveToFirst()?c.getString(0):"";}}
 synchronized void updateSettings(String id,String settings){ContentValues v=new ContentValues();v.put("settings",settings);getWritableDatabase().update("jobs",v,"id=?",new String[]{id});}
 synchronized boolean fottoWasSent(String jobId){try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM fotto_uploads WHERE job_id=? AND state='confirmed' LIMIT 1",new String[]{jobId})){return c.moveToFirst();}}
 synchronized boolean fottoWasSent(String jobId,String galleryId){try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM fotto_uploads WHERE job_id=? AND gallery_id=? AND state='confirmed' LIMIT 1",new String[]{jobId,galleryId})){return c.moveToFirst();}}
 synchronized void fottoDiscardUnsent(String jobId){getWritableDatabase().delete("fotto_uploads","job_id=? AND state<>'confirmed'",new String[]{jobId});}

 synchronized void history(String jobId,long ts,String type,String detail){if(jobId==null||jobId.isEmpty())return;ContentValues v=new ContentValues();v.put("job_id",jobId);v.put("ts",ts<=0?System.currentTimeMillis():ts);v.put("type",type);v.put("detail",detail==null?"":detail);getWritableDatabase().insert("history",null,v);}
 synchronized ArrayList<String[]> historyRows(String jobId){ArrayList<String[]> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT ts,type,detail FROM history WHERE job_id=? ORDER BY ts",new String[]{jobId})){while(c.moveToNext())out.add(new String[]{String.valueOf(c.getLong(0)),c.getString(1),c.getString(2)});}return out;}
 synchronized String[] photoMeta(String id){try(Cursor c=getReadableDatabase().rawQuery("SELECT name,state,score,quality_note,semantic_note,burst_id,duplicate_of,captured_at,downloaded_at,edited_at FROM jobs WHERE id=?",new String[]{id})){if(!c.moveToFirst())return null;String[] r=new String[10];for(int i=0;i<7;i++)r[i]=c.isNull(i)?"":c.getString(i);r[7]=String.valueOf(c.getLong(7));r[8]=String.valueOf(c.getLong(8));r[9]=String.valueOf(c.getLong(9));return r;}}

 void overwrite(File source,String rawUri)throws IOException{if(rawUri==null||rawUri.isEmpty())throw new IOException("Destino editado indisponível.");ContentResolver r=context.getContentResolver();try(InputStream input=new FileInputStream(source);OutputStream output=r.openOutputStream(Uri.parse(rawUri),"wt")){if(output==null)throw new IOException("Não foi possível regravar a foto editada.");copy(input,output);}}

 Uri save(File source,String folder,String name,String id,String column)throws IOException{
  String tree=context.getSharedPreferences("hisho",0).getString("exportTreeUri","");
  if(!tree.isEmpty())return saveTree(source,Uri.parse(tree),folder,name,id,column);
  return saveMediaStore(source,folder,name,id,column);
 }
 Uri saveTree(File source,Uri tree,String folder,String name,String id,String column)throws IOException{
  ContentResolver r=context.getContentResolver();Uri root;
  try{String rootId=DocumentsContract.getTreeDocumentId(tree);root=DocumentsContract.buildDocumentUriUsingTree(tree,rootId);}catch(Exception e){throw new IOException("Pasta de exportação inválida. Escolha a pasta novamente.",e);}
  Uri dir=findChild(r,root,folder,true);if(dir==null)throw new IOException("Não foi possível criar a pasta "+folder+" no destino escolhido.");
  Uri uri=null;
  try{uri=DocumentsContract.createDocument(r,dir,"image/jpeg",name);}catch(Exception e){throw new IOException("Não foi possível criar "+name+" na pasta escolhida.",e);}
  if(uri==null)throw new IOException("Não foi possível criar o arquivo na pasta escolhida.");
  try(InputStream input=new FileInputStream(source);OutputStream output=r.openOutputStream(uri,"w")){if(output==null)throw new IOException("Pasta de exportação indisponível.");copy(input,output);}catch(Exception e){try{DocumentsContract.deleteDocument(r,uri);}catch(Exception ignored){}throw e instanceof IOException?(IOException)e:new IOException(e);}
  ContentValues u=new ContentValues();u.put(column,uri.toString());getWritableDatabase().update("jobs",u,"id=?",new String[]{id});return uri;
 }
 Uri findChild(ContentResolver r,Uri parent,String name,boolean createDirectory)throws IOException{
  try{String parentId=DocumentsContract.getDocumentId(parent);Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(parent,parentId);try(Cursor c=r.query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){if(c!=null)while(c.moveToNext())if(name.equals(c.getString(1))&&DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2)))return DocumentsContract.buildDocumentUriUsingTree(parent,c.getString(0));}
   if(!createDirectory)return null;return DocumentsContract.createDocument(r,parent,DocumentsContract.Document.MIME_TYPE_DIR,name);
  }catch(Exception e){throw new IOException("Sem acesso à pasta escolhida. Selecione-a novamente.",e);}
 }
 Uri saveMediaStore(File source,String folder,String name,String id,String column)throws IOException{
  Uri uri=null;try(Cursor c=getReadableDatabase().rawQuery("SELECT "+column+" FROM jobs WHERE id=?",new String[]{id})){if(c.moveToFirst()&&!c.isNull(0))uri=Uri.parse(c.getString(0));}
  ContentResolver r=context.getContentResolver();
  if(uri!=null){try(Cursor c=r.query(uri,new String[]{MediaStore.Images.Media.IS_PENDING},null,null,null)){if(c==null||!c.moveToFirst())uri=null;else if(c.getInt(0)==0)return uri;}}
  if(uri==null){ContentValues v=new ContentValues();v.put(MediaStore.Images.Media.DISPLAY_NAME,name);v.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");v.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/LUMO/"+folder);v.put(MediaStore.Images.Media.IS_PENDING,1);uri=r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v);if(uri==null)throw new IOException("Não foi possível criar o arquivo na galeria.");ContentValues u=new ContentValues();u.put(column,uri.toString());getWritableDatabase().update("jobs",u,"id=?",new String[]{id});}
  try(InputStream input=new FileInputStream(source);OutputStream output=r.openOutputStream(uri,"wt")){if(output==null)throw new IOException("Pasta indisponível.");copy(input,output);}
  ContentValues v=new ContentValues();v.put(MediaStore.Images.Media.IS_PENDING,0);if(r.update(uri,v,null,null)!=1)throw new IOException("Falha ao concluir gravação.");return uri;
 }

 int clearEditedFolder()throws IOException{
  int deleted=0;
  String tree=context.getSharedPreferences("hisho",0).getString("exportTreeUri","");
  if(!tree.isEmpty())deleted=clearTreeFolder(Uri.parse(tree),"Editadas");
  else deleted=clearMediaStoreFolder("Editadas");

  // As cópias editadas foram removidas. Preservamos originais, histórico e notas.
  // A Galeria volta a exibir o original para fotos cuja saída editada foi limpa.
  ContentValues v=new ContentValues();v.putNull("edited");
  getWritableDatabase().update("jobs",v,"state<>'review'",null);
  return deleted;
 }

 int clearTreeFolder(Uri tree,String folder)throws IOException{
  ContentResolver r=context.getContentResolver();
  Uri root;
  try{
   String rootId=DocumentsContract.getTreeDocumentId(tree);
   root=DocumentsContract.buildDocumentUriUsingTree(tree,rootId);
  }catch(Exception e){throw new IOException("Pasta de exportação inválida. Escolha a pasta novamente.",e);}

  Uri dir=findChild(r,root,folder,false);
  if(dir==null)return 0;

  int deleted=0;
  Cursor c=null;
  try{
   String dirId=DocumentsContract.getDocumentId(dir);
   Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(dir,dirId);
   c=r.query(children,new String[]{
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_MIME_TYPE
   },null,null,null);
   if(c!=null){
    ArrayList<Uri> targets=new ArrayList<>();
    while(c.moveToNext()){
     String id=c.getString(0),mime=c.getString(1);
     if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime))continue;
     targets.add(DocumentsContract.buildDocumentUriUsingTree(dir,id));
    }
    c.close();c=null;
    for(Uri u:targets){
     try{if(DocumentsContract.deleteDocument(r,u))deleted++;}catch(Exception ignored){}
    }
   }
  }catch(Exception e){throw new IOException("Não foi possível limpar a pasta Editadas.",e);}
  finally{if(c!=null)c.close();}
  return deleted;
 }

 int clearMediaStoreFolder(String folder)throws IOException{
  ContentResolver r=context.getContentResolver();
  String relative="Pictures/LUMO/"+folder+"/";
  try{
   return r.delete(
    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
    MediaStore.Images.Media.RELATIVE_PATH+"=?",
    new String[]{relative}
   );
  }catch(Exception e){
   throw new IOException("Não foi possível limpar Pictures/LUMO/"+folder+".",e);
  }
 }

 static void copy(InputStream input,OutputStream output)throws IOException{byte[] bytes=new byte[65536];int n;while((n=input.read(bytes))!=-1)output.write(bytes,0,n);}
}
