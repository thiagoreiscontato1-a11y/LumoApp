package com.hisho.tether;
import javax.imageio.*;import javax.imageio.stream.*;import java.awt.image.BufferedImage;import java.io.*;import java.nio.ByteBuffer;import java.util.*;

/** Extrator de prévia RAW no desktop. Rodado por tests/raw_preview_contract.py. */
public class RawPreviewTest{
 static int checks=0;
 static void check(boolean b,String what){checks++;if(!b)throw new AssertionError(what);}
 static byte[] jpeg(int w,int h,boolean progressive)throws IOException{
  BufferedImage img=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);for(int y=0;y<h;y++)for(int x=0;x<w;x++)img.setRGB(x,y,((x*7)&255)<<16|((y*3)&255)<<8|((x^y)&255));
  ImageWriter wr=ImageIO.getImageWritersByFormatName("jpeg").next();ImageWriteParam p=wr.getDefaultWriteParam();
  if(progressive)p.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);else{p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);p.setCompressionQuality(.9f);}
  ByteArrayOutputStream out=new ByteArrayOutputStream();try(ImageOutputStream io=ImageIO.createImageOutputStream(out)){wr.setOutput(io);wr.write(null,new IIOImage(img,null,null),p);}wr.dispose();return out.toByteArray();
 }
 /** JPEG com uma miniatura JPEG completa dentro do APP1, como a prévia de um Fuji RAF. */
 static byte[] nested(byte[] full,byte[] thumb){
  ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] head="Exif\0\0II*\0".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);int len=2+head.length+thumb.length;
  o.write(full,0,2);o.write(0xFF);o.write(0xE1);o.write(len>>8);o.write(len&255);o.write(head,0,head.length);o.write(thumb,0,thumb.length);o.write(full,2,full.length-2);return o.toByteArray();
 }
 /** Cabeçalho de um JPEG sem perdas (SOF3), como os dados do sensor de um CR2. */
 static byte[] lossless(){return new byte[]{(byte)0xFF,(byte)0xD8,(byte)0xFF,(byte)0xC3,0,11,14,0x0F,(byte)0xA0,0x17,0x70,1,1,0x11,0,(byte)0xFF,(byte)0xDA,0,8,1,1,0,1,0,0,0x12,0x34,(byte)0xFF,0,0x56,(byte)0xFF,(byte)0xD9};}
 static byte[] junk(int n,int seed){byte[] b=new byte[n];new Random(seed).nextBytes(b);for(int i=0;i+1<n;i++)if((b[i]&255)==0xFF)b[i]=0x7F;return b;}
 static List<RawPreview.Found> find(byte[]... parts){ByteArrayOutputStream o=new ByteArrayOutputStream();for(byte[] p:parts)o.write(p,0,p.length);return RawPreview.find(ByteBuffer.wrap(o.toByteArray()));}

 public static void main(String[] a)throws Exception{
  byte[] full=jpeg(1600,1067,false),thumb=jpeg(160,120,false),prog=jpeg(1024,683,true),small=jpeg(1620/4,1080/4,false);
  // Prévia com miniatura EXIF aninhada: inteira, do primeiro ao último byte.
  byte[] raf=nested(full,thumb);List<RawPreview.Found> f=find(junk(4096,1),raf,junk(999,2));
  check(f.size()==1,"uma prévia em RAF: "+f.size());check(f.get(0).start==4096&&f.get(0).bytes()==raf.length,"prévia aninhada inteira: "+f.get(0).bytes()+" de "+raf.length);
  check(f.get(0).width==1600&&f.get(0).height==1067,"tamanho do SOF "+f.get(0).width+"x"+f.get(0).height);
  // Várias prévias (CR3: THMB, PRVW e a grande) + dados sem perdas do sensor, que não são prévia.
  f=find(junk(500,3),thumb,junk(77,4),small,lossless(),junk(300,5),prog,junk(64,6),full,junk(10,7));
  long best=0;for(RawPreview.Found x:f)best=Math.max(best,x.pixels());
  check(best==1600L*1067,"maior prévia escolhida: "+best);check(f.size()==3,"miniatura <320 px e SOF3 ignorados; progressivo aceito: "+f.size());
  // Prévia cortada no fim do arquivo não serve.
  check(find(junk(100,8),Arrays.copyOf(full,full.length-500)).isEmpty(),"JPEG truncado rejeitado");
  check(find(junk(2000,9)).isEmpty(),"arquivo sem JPEG");
  System.out.println("PASS: "+checks+" verificações do extrator de prévia RAW (aninhada, várias prévias, SOF3, truncada).");
 }
}
