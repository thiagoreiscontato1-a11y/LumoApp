package com.hisho.tether;
import java.io.*;
import java.util.*;
import android.graphics.BitmapFactory;
/** Extract embedded JPEG; never interprets sensor RAW pixels. */
final class RawPreview{
 static boolean isRaw(String name){return name!=null&&name.toLowerCase(Locale.ROOT).matches(".*\\.(cr2|cr3|dng|nef|nrw|arw|raf|rw2|orf|pef|srw|raw)$");}
 static boolean extract(File source,File target)throws IOException{
  File candidate=new File(target+".candidate");long best=0,current=0;OutputStream out=null;int previous=-1;
  try(InputStream in=new BufferedInputStream(new FileInputStream(source),65536)){
   int value;while((value=in.read())!=-1){
    if(out==null&&previous==255&&value==216){out=new BufferedOutputStream(new FileOutputStream(candidate));out.write(255);out.write(216);current=2;}
    else if(out!=null){out.write(value);current++;if(current>64L*1024*1024){out.close();out=null;candidate.delete();}else if(previous==255&&value==217){out.close();out=null;BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(candidate.toString(),bounds);long pixels=(long)bounds.outWidth*bounds.outHeight;if(bounds.outWidth>0&&bounds.outHeight>0&&pixels>best){try(InputStream src=new FileInputStream(candidate);OutputStream dst=new FileOutputStream(target)){byte[] buf=new byte[65536];int n;while((n=src.read(buf))!=-1)dst.write(buf,0,n);}best=pixels;}candidate.delete();}}
    previous=value;
   }
  }finally{if(out!=null)out.close();candidate.delete();}
  return best>0;
 }
}
