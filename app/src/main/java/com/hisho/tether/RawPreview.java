package com.hisho.tether;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;
import android.graphics.BitmapFactory;

/**
 * Extrai a maior prévia JPEG que a câmera embutiu no RAW; nunca interpreta os pixels do sensor.
 *
 * Cada candidato é lido pela estrutura do JPEG (segmentos com tamanho, dados após SOS até o
 * próximo marcador), e não por "primeiro FFD9 depois de FFD8": assim a miniatura EXIF dentro
 * do APP1 (Fuji RAF, Olympus, Panasonic…) não corta a prévia grande ao meio, e o RAW sem perdas
 * (SOF3, usado nos dados do sensor de CR2/DNG) não é confundido com uma prévia.
 */
final class RawPreview{
 static boolean isRaw(String name){return name!=null&&name.toLowerCase(Locale.ROOT).matches(".*\\.(cr2|cr3|dng|nef|nrw|arw|raf|rw2|orf|pef|srw|raw)$");}

 /** Prévia encontrada: bytes [start,end) do arquivo, com largura e altura do SOF. */
 static final class Found{final long start,end;final int width,height;Found(long s,long e,int w,int h){start=s;end=e;width=w;height=h;}long pixels(){return (long)width*height;}long bytes(){return end-start;}}

 static boolean extract(File source,File target)throws IOException{
  try(FileInputStream in=new FileInputStream(source);FileChannel ch=in.getChannel()){
   List<Found> all=find(ch.map(FileChannel.MapMode.READ_ONLY,0,ch.size()));
   // Maior primeiro; o Android precisa conseguir abrir (confirma com o decodificador do sistema).
   Collections.sort(all,(a,b)->a.pixels()!=b.pixels()?Long.compare(b.pixels(),a.pixels()):Long.compare(b.bytes(),a.bytes()));
   for(Found f:all){
    try(FileOutputStream out=new FileOutputStream(target)){long pos=f.start;while(pos<f.end)pos+=ch.transferTo(pos,f.end-pos,out.getChannel());out.getFD().sync();}
    BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(target.toString(),bounds);
    if(bounds.outWidth>0&&bounds.outHeight>0)return true;
   }
  }
  target.delete();return false;
 }

 /** Todos os JPEGs completos (baseline, estendido ou progressivo) dentro do arquivo. */
 static List<Found> find(ByteBuffer b){
  List<Found> out=new ArrayList<>();int n=b.limit();
  for(int i=0;i+3<n;i++){
   if((b.get(i)&255)!=0xFF||(b.get(i+1)&255)!=0xD8||(b.get(i+2)&255)!=0xFF)continue;
   Found f=parse(b,i);if(f==null)continue;
   if(f.width>=320&&f.height>=240)out.add(f);
   i=(int)f.end-1; // pula o interior (miniaturas aninhadas já ficam de fora)
  }
  return out;
 }

 /** Lê um JPEG a partir de {@code start}; null se estiver incompleto ou não for decodificável. */
 static Found parse(ByteBuffer b,int start){
  int n=b.limit(),p=start+2,w=0,h=0;boolean scan=false,supported=false;
  while(p+1<n){
   if((b.get(p)&255)!=0xFF)return null;
   while(p+1<n&&(b.get(p+1)&255)==0xFF)p++;
   if(p+1>=n)return null;
   int m=b.get(p+1)&255;p+=2;
   if(m==0xD9)return scan&&supported&&w>0&&h>0?new Found(start,p,w,h):null;
   if(m==0x01||(m>=0xD0&&m<=0xD7))continue;
   if(m==0xD8||m==0x00||p+2>n)return null;
   int len=((b.get(p)&255)<<8)|(b.get(p+1)&255);if(len<2||p+len>n)return null;
   if(m>=0xC0&&m<=0xCF&&m!=0xC4&&m!=0xC8&&m!=0xCC){
    if(len<7)return null;
    // Só o que o Android decodifica: baseline (C0), estendido (C1) e progressivo (C2).
    supported=m<=0xC2;h=((b.get(p+3)&255)<<8)|(b.get(p+4)&255);w=((b.get(p+5)&255)<<8)|(b.get(p+6)&255);
    if(!supported)return null;
   }
   p+=len;
   if(m==0xDA){
    if(w==0)return null;scan=true;
    // Dados comprimidos: FF00 (byte escapado), RSTn e FF de preenchimento não encerram o trecho.
    while(p+1<n){
     if((b.get(p)&255)!=0xFF){p++;continue;}
     int next=b.get(p+1)&255;
     if(next==0x00||(next>=0xD0&&next<=0xD7)){p+=2;continue;}
     if(next==0xFF){p++;continue;}
     break;
    }
   }
  }
  return null;
 }
}
