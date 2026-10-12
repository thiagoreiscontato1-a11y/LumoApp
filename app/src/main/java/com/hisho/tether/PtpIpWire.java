package com.hisho.tether;
import java.io.*;import java.nio.*;
/** PTP/IP framing; no Android dependencies. Image data is streamed, never buffered in full. */
final class PtpIpWire {
 static ByteBuffer le(byte[] b){return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);}
 static byte[] read(InputStream in,int size)throws IOException{byte[] b=new byte[size];int at=0;while(at<size){int n=in.read(b,at,size-at);if(n<0)throw new EOFException("Conexão Wi-Fi encerrada pela câmera.");if(n==0)continue;at+=n;}return b;}
 static int[] header(InputStream in)throws IOException{ByteBuffer h=le(read(in,8));int size=h.getInt(),type=h.getInt();if(size<8||size>150*1024*1024)throw new IOException("Pacote PTP/IP inválido.");return new int[]{size-8,type};}
 static void write(OutputStream out,int type,byte[] body)throws IOException{ByteBuffer h=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);h.putInt(body.length+8).putInt(type);out.write(h.array());out.write(body);out.flush();}
 static byte[] command(int op,int tx,int...params){ByteBuffer b=ByteBuffer.allocate(10+4*params.length).order(ByteOrder.LITTLE_ENDIAN);b.putInt(1).putShort((short)op).putInt(tx);for(int p:params)b.putInt(p);return b.array();}
 static class Response {int code;byte[] data;Response(int c,byte[] d){code=c;data=d;}}
 static Response response(InputStream in,OutputStream socketOut,OutputStream target,int tx,Runnable progress)throws IOException{
  ByteArrayOutputStream memory=new ByteArrayOutputStream();OutputStream sink=target==null?memory:target;long expected=-1,received=0;boolean ended=false;
  for(int packets=0;packets<200000;packets++){
   int[] h=header(in);int left=h[0],type=h[1];
   if(type==13&&left==0){write(socketOut,14,new byte[0]);continue;}
   if(type==9){if(left!=12||expected!=-1)throw new IOException("Início de dados Wi-Fi inválido.");ByteBuffer b=le(read(in,12));if(b.getInt()!=tx)throw new IOException("Transação Wi-Fi fora de sequência.");expected=b.getLong();if(expected<0||expected>150*1024*1024||target==null&&expected>16*1024*1024)throw new IOException("Resposta Wi-Fi excede o limite.");}
   else if(type==10||type==12){if(left<4||expected<0||ended)throw new IOException("Dados Wi-Fi inesperados.");if(le(read(in,4)).getInt()!=tx)throw new IOException("Transação Wi-Fi fora de sequência.");left-=4;if(received+left>expected)throw new IOException("Dados Wi-Fi excedem tamanho anunciado.");byte[] buffer=new byte[16384];while(left>0){int n=in.read(buffer,0,Math.min(left,buffer.length));if(n<0)throw new EOFException("Download Wi-Fi interrompido.");if(n==0)continue;sink.write(buffer,0,n);left-=n;received+=n;progress.run();}if(type==12)ended=true;}
   else if(type==7){if(left<6||left>26)throw new IOException("Resposta Wi-Fi inválida.");ByteBuffer b=le(read(in,left));int code=b.getShort()&65535;if(b.getInt()!=tx)throw new IOException("Transação Wi-Fi fora de sequência.");if(code==0x2001&&expected>=0&&(!ended||received!=expected))throw new IOException("Foto Wi-Fi incompleta.");progress.run();return new Response(code,memory.toByteArray());}
   else throw new IOException("Pacote Wi-Fi inesperado: "+type);
  }throw new IOException("Excesso de pacotes Wi-Fi.");
 }
}
