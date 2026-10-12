package com.hisho.tether;
import java.io.*;import java.net.*;import java.nio.*;import java.util.concurrent.*;
public class WifiTransportTest {
 static ByteBuffer b(int n){return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);}
 static void require(boolean v){if(!v)throw new AssertionError();}
 public static void main(String[] args)throws Exception{
  ServerSocket server=new ServerSocket(15740,2,InetAddress.getByName("127.0.0.1"));server.setSoTimeout(5000);ExecutorService ex=Executors.newSingleThreadExecutor();
  Future<?> camera=ex.submit(()->{try(Socket command=server.accept()){command.setSoTimeout(5000);InputStream in=command.getInputStream();OutputStream out=command.getOutputStream();int[] h=PtpIpWire.header(in);require(h[1]==1);PtpIpWire.read(in,h[0]);PtpIpWire.write(out,2,b(26).putInt(9).put(new byte[16]).putShort((short)0).putInt(0x10000).array());try(Socket events=server.accept()){int[] eh=PtpIpWire.header(events.getInputStream());require(eh[1]==3&&PtpIpWire.le(PtpIpWire.read(events.getInputStream(),eh[0])).getInt()==9);PtpIpWire.write(events.getOutputStream(),4,new byte[0]);
   for(int op:new int[]{0x1001,0x1002,0x1009,0x1003}){h=PtpIpWire.header(in);require(h[1]==6);ByteBuffer cmd=PtpIpWire.le(PtpIpWire.read(in,h[0]));require((cmd.getShort(4)&65535)==op);int tx=cmd.getInt(6);if(op==0x1009){PtpIpWire.write(out,9,b(12).putInt(tx).putLong(4).array());PtpIpWire.write(out,12,b(8).putInt(tx).put(new byte[]{1,2,3,4}).array());}PtpIpWire.write(out,7,b(6).putShort((short)0x2001).putInt(tx).array());}
  }}catch(Exception e){throw new RuntimeException(e);}});
  WifiPtp client=new WifiPtp(new android.net.Network(),"127.0.0.1","12345678-1234-1234-1234-123456789012");File file=File.createTempFile("wifi-test-",".jpg");try{client.open();require(client.session);client.download(22,file,4);require(file.length()==4);client.close();camera.get(6,TimeUnit.SECONDS);System.out.println("PASS: TCP command/event handshake, PTP session, streamed download and graceful close against simulated camera.");}finally{client.close();file.delete();server.close();ex.shutdownNow();}
 }
}
