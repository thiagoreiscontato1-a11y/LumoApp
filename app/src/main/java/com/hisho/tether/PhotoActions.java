package com.hisho.tether;

import android.content.*;
import android.net.Uri;
import java.io.*;

/** Ações sobre uma foto compartilhadas pela tela principal e pelo visualizador. */
final class PhotoActions {
 /** Move a foto da Revisão para Editadas e libera a entrega. Executar fora da thread principal. */
 static void approve(Context c,Jobs database,String id,String name,String reviewUri)throws Exception{
  File tmp=new File(c.getCacheDir(),"approve_"+id+".jpg");boolean cleared=false;
  try(InputStream in=c.getContentResolver().openInputStream(Uri.parse(reviewUri));OutputStream out=new FileOutputStream(tmp)){
   if(in==null)throw new IOException("Foto de revisão indisponível.");
   Jobs.copy(in,out);database.clearEdited(id);cleared=true;
   database.save(tmp,"Editadas",name.replaceFirst("(?i)\\.jpg$","_Editada.jpg"),id,"edited");
   database.set(id,"done","error",null);
   database.history(id,System.currentTimeMillis(),"Aprovada","Aprovação manual na Revisão");
   try{c.getContentResolver().delete(Uri.parse(reviewUri),null,null);}catch(Exception ignored){}
   FottoSync.kick(c);
  }catch(Exception e){if(cleared)database.set(id,"review","edited",reviewUri);throw e;}
  finally{tmp.delete();}
 }

 /** Mensagem de bloqueio, ou vazio quando o envio ao Fotto está pronto. */
 static String fottoBlocker(Context c){
  if(FottoApi.token(c).isEmpty())return "Conecte sua conta Fotto em Entrega.";
  if(c.getSharedPreferences("hisho",0).getString("fottoGalleryId","").isEmpty())return "Escolha um evento Fotto em Entrega.";
  return "";
 }

 /** Mensagem de bloqueio, ou vazio quando é seguro excluir fotos. */
 static String deletionBlocker(Context c){
  if(CaptureService.active!=null||FottoSync.running()||c.getSharedPreferences("hisho",0).getBoolean("fottoAuto",false))
   return "Pare a captura e desative o envio automático ao Fotto antes de excluir fotos.";
  return "";
 }

 static Intent share(Uri uri){
  Intent send=new Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
  send.setClipData(ClipData.newRawUri("Lumo",uri));
  return Intent.createChooser(send,"Compartilhar foto");
 }

 private PhotoActions(){}
}
