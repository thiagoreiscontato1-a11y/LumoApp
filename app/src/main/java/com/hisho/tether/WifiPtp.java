package com.hisho.tether;
import android.net.Network;import android.os.SystemClock;import java.net.*;import java.io.*;import java.nio.*;import java.util.*;
final class WifiPtp extends Ptp {
 private Socket commandSocket,eventSocket;private InputStream input;private OutputStream output;private volatile boolean closed;private Thread eventReader;
 private final Network network;private final String host;private final UUID identity;
 WifiPtp(Network network,String host,String identity){super();this.network=network;this.host=host;this.identity=UUID.fromString(identity);}
 private Socket socket()throws IOException{Socket s=new Socket();try{network.bindSocket(s);s.connect(new InetSocketAddress(host,15740),6000);s.setTcpNoDelay(true);s.setSoTimeout(30000);return s;}catch(IOException e){s.close();throw e;}}
 @Override void open()throws IOException{
  try{
   commandSocket=socket();input=commandSocket.getInputStream();output=commandSocket.getOutputStream();byte[] name="LUMO\0".getBytes("UTF-16LE");ByteBuffer hello=ByteBuffer.allocate(20+name.length).order(ByteOrder.LITTLE_ENDIAN);hello.putLong(identity.getMostSignificantBits()).putLong(identity.getLeastSignificantBits()).put(name).putInt(0x10000);PtpIpWire.write(output,1,hello.array());
   int[] h=PtpIpWire.header(input);if(h[1]==5)throw new IOException("Pareamento recusado. Autorize LUMO na câmera.");if(h[1]!=2||h[0]<24||h[0]>1024)throw new IOException("A câmera não aceitou PTP/IP. Confira o modo Wi-Fi.");byte[] ack=PtpIpWire.read(input,h[0]);int id=PtpIpWire.le(ack).getInt();
   eventSocket=socket();PtpIpWire.write(eventSocket.getOutputStream(),3,ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(id).array());int[] eh=PtpIpWire.header(eventSocket.getInputStream());if(eh[1]!=4||eh[0]!=0)throw new IOException("Canal de eventos Wi-Fi não confirmado.");
   commandSocket.setSoTimeout(15000);eventSocket.setSoTimeout(0);eventReader=new Thread(this::events,"LUMO-WiFi-Events");eventReader.start();super.open();
  }catch(IOException e){closeSockets();throw e;}
 }
 private void events(){try{InputStream in=eventSocket.getInputStream();while(!closed){int[] h=PtpIpWire.header(in);if(h[0]>65536)throw new IOException("Evento Wi-Fi inválido.");PtpIpWire.read(in,h[0]);if(h[1]==13)PtpIpWire.write(eventSocket.getOutputStream(),14,new byte[0]);else if(h[1]!=8&&h[1]!=14)throw new IOException("Evento Wi-Fi inesperado.");}}catch(IOException e){if(!closed){broken=true;try{commandSocket.close();}catch(IOException ignored){}}}}
 @Override byte[] exchange(int code,OutputStream file,int...params)throws IOException{
  if(broken||closed)throw new IOException("Conexão Wi-Fi interrompida.");int tx=session?transaction++:0;
  try{PtpIpWire.write(output,6,PtpIpWire.command(code,tx,params));PtpIpWire.Response r=PtpIpWire.response(input,output,file,tx,()->lastActivity=SystemClock.elapsedRealtime());if(r.code!=0x2001)throw new Failure(r.code);return r.data;}catch(Failure e){throw e;}catch(IOException e){broken=true;throw e;}
 }
 private void closeSockets(){closed=true;for(Socket s:new Socket[]{commandSocket,eventSocket})if(s!=null)try{s.close();}catch(IOException ignored){}}
 @Override public void close(){try{if(commandSocket!=null&&!broken&&!closed){commandSocket.setSoTimeout(1500);if(events)execute(0x9115,0);if(remote)execute(0x9114,0);if(session)execute(0x1003);}}catch(Exception ignored){}finally{closeSockets();}}
}
