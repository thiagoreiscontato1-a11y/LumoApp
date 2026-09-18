package com.hisho.tether;
import android.graphics.*;import java.io.*;import java.security.*;import java.util.*;

final class PhotoHash{
 static String sha256(File f)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[131072];for(int n;(n=in.read(b))!=-1;)d.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte x:d.digest())s.append(String.format(Locale.ROOT,"%02x",x));return s.toString();}
 static String perceptualHash(File f)throws IOException{
  Bitmap src=null,small=null;try{src=BitmapFactory.decodeFile(f.toString());if(src==null)throw new IOException("JPEG inválido para hash perceptual.");small=Bitmap.createScaledBitmap(src,9,8,true);long bits=0;int bit=0;for(int y=0;y<8;y++)for(int x=0;x<8;x++){int a=small.getPixel(x,y),b=small.getPixel(x+1,y);double la=.2126*((a>>16)&255)+.7152*((a>>8)&255)+.0722*(a&255),lb=.2126*((b>>16)&255)+.7152*((b>>8)&255)+.0722*(b&255);if(la>lb)bits|=(1L<<bit);bit++;}return String.format(Locale.ROOT,"%016x",bits);
  }finally{if(small!=null&&small!=src)small.recycle();if(src!=null)src.recycle();}
 }
 static int hammingHex(String a,String b){if(a==null||b==null||a.length()!=16||b.length()!=16)return 64;try{return Long.bitCount(Long.parseUnsignedLong(a,16)^Long.parseUnsignedLong(b,16));}catch(Exception e){return 64;}}
 private PhotoHash(){}
}
