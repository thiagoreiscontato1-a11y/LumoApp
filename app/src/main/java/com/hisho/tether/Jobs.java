package com.hisho.tether;
import android.content.*;import android.database.*;import android.database.sqlite.*;import android.net.Uri;import android.provider.MediaStore;import android.provider.DocumentsContract;import java.io.*;
final class Jobs extends SQLiteOpenHelper{
 final Context context;
 Jobs(Context c){super(c,"queue.db",null,2);context=c;}
 public void onCreate(SQLiteDatabase d){d.execSQL("CREATE TABLE jobs (id TEXT PRIMARY KEY, file TEXT NOT NULL, name TEXT NOT NULL, settings TEXT NOT NULL, original TEXT, edited TEXT, state TEXT NOT NULL, error TEXT)");createFotto(d);}
 public void onUpgrade(SQLiteDatabase d,int a,int b){if(a<2)createFotto(d);}
 static void createFotto(SQLiteDatabase d){d.execSQL("CREATE TABLE IF NOT EXISTS fotto_uploads (job_id TEXT NOT NULL, gallery_id TEXT NOT NULL, state TEXT NOT NULL, media_id TEXT, error TEXT, attempts INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(job_id,gallery_id))");}
 synchronized boolean exists(String id){try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM jobs WHERE id=?",new String[]{id})){return c.moveToFirst();}}
 synchronized void add(String id,File file,String name,String settings){ContentValues v=new ContentValues();v.put("id",id);v.put("file",file.getAbsolutePath());v.put("name",name);v.put("settings",settings);v.put("state","original");getWritableDatabase().insertOrThrow("jobs",null,v);}
 synchronized void set(String id,String state,String field,String value){ContentValues v=new ContentValues();v.put("state",state);if(field!=null)v.put(field,value);getWritableDatabase().update("jobs",v,"id=?",new String[]{id});}
 synchronized String[] next(){try(Cursor c=getReadableDatabase().rawQuery("SELECT id,file,name,settings FROM jobs WHERE state='ready' ORDER BY rowid LIMIT 1",null)){if(!c.moveToFirst())return null;return new String[]{c.getString(0),c.getString(1),c.getString(2),c.getString(3)};}}
 synchronized int pending(){try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM jobs WHERE state NOT IN ('done','review')",null)){c.moveToFirst();return c.getInt(0);}}
 synchronized int reviewCount(){try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM jobs WHERE state='review'",null)){c.moveToFirst();return c.getInt(0);}}
 synchronized void clearEdited(String id){ContentValues v=new ContentValues();v.putNull("edited");getWritableDatabase().update("jobs",v,"id=?",new String[]{id});}
 synchronized void retry(){getWritableDatabase().execSQL("UPDATE jobs SET state='ready',error=NULL WHERE state IN ('error','editing') AND original IS NOT NULL");getWritableDatabase().execSQL("UPDATE jobs SET state='original',error=NULL WHERE state='originalError' OR (state='error' AND original IS NULL)");}
 synchronized String[] originalPending(){try(Cursor c=getReadableDatabase().rawQuery("SELECT id,file,name FROM jobs WHERE state='original' ORDER BY rowid LIMIT 1",null)){if(!c.moveToFirst())return null;return new String[]{c.getString(0),c.getString(1),c.getString(2)};}}
 synchronized long maxRowId(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COALESCE(MAX(rowid),0) FROM jobs",null)){c.moveToFirst();return c.getLong(0);}}
 synchronized String[] nextFotto(String galleryId,long after){String sql="SELECT j.id,j.name,j.edited,j.rowid FROM jobs j LEFT JOIN fotto_uploads f ON f.job_id=j.id AND f.gallery_id=? WHERE j.state='done' AND j.edited IS NOT NULL AND j.rowid>? AND (f.state IS NULL OR (f.state='error' AND f.attempts<3)) ORDER BY j.rowid LIMIT 1";try(Cursor c=getReadableDatabase().rawQuery(sql,new String[]{galleryId,String.valueOf(after)})){if(!c.moveToFirst())return null;return new String[]{c.getString(0),c.getString(1).replaceFirst("(?i)\\.jpg$","_Editada.jpg"),c.getString(2),String.valueOf(c.getLong(3))};}}
 synchronized void fottoSet(String jobId,String galleryId,String state,String mediaId,String error,boolean increment){int attempts=0;try(Cursor c=getReadableDatabase().rawQuery("SELECT attempts FROM fotto_uploads WHERE job_id=? AND gallery_id=?",new String[]{jobId,galleryId})){if(c.moveToFirst())attempts=c.getInt(0);}if(increment)attempts++;ContentValues v=new ContentValues();v.put("job_id",jobId);v.put("gallery_id",galleryId);v.put("state",state);v.put("media_id",mediaId==null?"":mediaId);v.put("error",error==null?"":error);v.put("attempts",attempts);v.put("updated",System.currentTimeMillis());getWritableDatabase().insertWithOnConflict("fotto_uploads",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
 synchronized void fottoRecover(String galleryId){ContentValues v=new ContentValues();v.put("state","error");v.put("error","Envio interrompido antes da confirmação.");v.put("updated",System.currentTimeMillis());getWritableDatabase().update("fotto_uploads",v,"gallery_id=? AND state='uploading'",new String[]{galleryId});}
 synchronized void fottoRetry(String galleryId){ContentValues v=new ContentValues();v.put("attempts",0);v.put("state","error");getWritableDatabase().update("fotto_uploads",v,"gallery_id=? AND state='error'",new String[]{galleryId});}
 synchronized int fottoDone(String galleryId){try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM fotto_uploads WHERE gallery_id=? AND state='done'",new String[]{galleryId})){c.moveToFirst();return c.getInt(0);}}

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
 static void copy(InputStream input,OutputStream output)throws IOException{byte[] bytes=new byte[65536];int n;while((n=input.read(bytes))!=-1)output.write(bytes,0,n);}

}
