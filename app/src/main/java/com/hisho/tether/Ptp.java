package com.hisho.tether;
import android.hardware.usb.*;
import java.io.*;
import java.nio.*;
import java.util.*;

class Ptp implements AutoCloseable {
 static final int MAX=150*1024*1024;
 final UsbDeviceConnection connection; final UsbInterface iface; final UsbEndpoint in,out;
 volatile long lastActivity=android.os.SystemClock.elapsedRealtime();int transaction=1; boolean session=false,remote=false,events=false;volatile boolean broken=false;
 int timeout=15000;byte[] pending=new byte[0]; int pos;
 static class Failure extends IOException {final int code;Failure(int c){super(String.format("Resposta PTP 0x%04X",c));code=c;}}
 Ptp(){connection=null;iface=null;in=null;out=null;}
 Ptp(UsbManager manager,UsbDevice device)throws IOException{
  UsbInterface found=null;UsbEndpoint input=null,output=null;
  for(int i=0;i<device.getInterfaceCount();i++){
   UsbInterface x=device.getInterface(i);if(x.getInterfaceClass()!=6||x.getInterfaceSubclass()!=1||x.getInterfaceProtocol()!=1)continue;
   UsbEndpoint a=null,b=null;for(int j=0;j<x.getEndpointCount();j++){UsbEndpoint e=x.getEndpoint(j);if(e.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK){if(e.getDirection()==UsbConstants.USB_DIR_IN)a=e;else b=e;}}
   if(a!=null&&b!=null){found=x;input=a;output=b;break;}
  }
  if(found==null)throw new IOException("Interface PTP não encontrada.");
  iface=found;in=input;out=output;connection=manager.openDevice(device);
  if(connection==null)throw new IOException("Sem acesso USB. Autorize novamente.");
  if(!connection.claimInterface(iface,true)){connection.close();throw new IOException("Câmera ocupada por outro aplicativo.");}
 }
 static ByteBuffer le(byte[] a){return ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN);}
 void readFully(byte[] dest,int off,int len)throws IOException{
  int empty=0;while(len>0){
   if(pos==pending.length){byte[] chunk=new byte[16384];int n=connection.bulkTransfer(in,chunk,chunk.length,timeout);if(n<0){broken=true;throw new IOException("Falha USB ao receber. Reconecte a câmera.");}if(n==0){if(++empty>8){broken=true;throw new IOException("Pacotes USB vazios em excesso.");}continue;}empty=0;lastActivity=android.os.SystemClock.elapsedRealtime();pending=Arrays.copyOf(chunk,n);pos=0;}
   int n=Math.min(len,pending.length-pos);System.arraycopy(pending,pos,dest,off,n);pos+=n;off+=n;len-=n;
  }
 }
 byte[] execute(int code,int...params)throws IOException{return exchange(code,null,params);}
 byte[] exchange(int code,OutputStream file,int...params)throws IOException{
  if(broken)throw new IOException("Conexão USB interrompida.");
  int tx=session?transaction++:0;ByteBuffer cmd=ByteBuffer.allocate(12+4*params.length).order(ByteOrder.LITTLE_ENDIAN);
  cmd.putInt(cmd.capacity()).putShort((short)1).putShort((short)code).putInt(tx);for(int p:params)cmd.putInt(p);
  if(connection.bulkTransfer(out,cmd.array(),cmd.capacity(),timeout)!=cmd.capacity()){broken=true;throw new IOException("Falha USB ao enviar comando.");}
  byte[] result=new byte[0];
  try{
   for(int packets=0;packets<64;packets++){
    byte[] h=new byte[12];readFully(h,0,12);ByteBuffer b=le(h);int length=b.getInt(),type=b.getShort()&65535,op=b.getShort()&65535,id=b.getInt();
    if(length<12||length>MAX)throw new IOException("Tamanho PTP inválido.");
    int left=length-12;
    if(id!=tx)throw new IOException("Transação USB fora de sequência.");
    if(type==2&&op!=code)throw new IOException("Comando PTP inesperado.");
    if(type==2&&file!=null){byte[] part=new byte[16384];while(left>0){int n=Math.min(left,part.length);readFully(part,0,n);file.write(part,0,n);left-=n;}}
    else{if(left>16*1024*1024)throw new IOException("Resposta PTP muito grande.");byte[] bytes=new byte[left];readFully(bytes,0,left);if(type==2)result=bytes;}
    if(type==3){if(op!=0x2001)throw new Failure(op);return result;}
   }throw new IOException("Excesso de respostas PTP.");
  }catch(Failure e){throw e;}catch(IOException e){broken=true;throw e;}
 }
 void open()throws IOException{execute(0x1001);execute(0x1002,1);session=true;}
 void captureMode()throws IOException{execute(0x9114,1);remote=true;execute(0x9115,1);events=true;execute(0x9116);}
 void drain()throws IOException{if(events)execute(0x9116);}
 static int[] array(byte[] bytes)throws IOException{if(bytes.length<4)throw new IOException("Lista PTP incompleta.");ByteBuffer b=le(bytes);int n=b.getInt();if(n<0||n>(bytes.length-4)/4)throw new IOException("Lista PTP inválida.");int[] a=new int[n];for(int i=0;i<n;i++)a[i]=b.getInt();return a;}
 List<int[]> objects()throws IOException{List<int[]> all=new ArrayList<>();for(int storage:array(execute(0x1004)))for(int handle:array(execute(0x1007,storage,0x3801,0)))all.add(new int[]{storage,handle});return all;}
 static String string(ByteBuffer b)throws IOException{if(!b.hasRemaining())return "";int n=b.get()&255;if(b.remaining()<n*2)throw new IOException("Texto PTP incompleto.");StringBuilder s=new StringBuilder();for(int i=0;i<n;i++){char c=b.getChar();if(c!=0)s.append(c);}return s.toString();}
 static class Info {String name,date;int size;}
 Info info(int handle)throws IOException{byte[] a=execute(0x1008,handle);if(a.length<53)throw new IOException("Dados da foto incompletos.");ByteBuffer b=le(a);if((b.getShort(4)&65535)!=0x3801)throw new IOException("Arquivo não JPEG.");Info i=new Info();i.size=b.getInt(8);if(i.size<=0||i.size>MAX-12)throw new IOException("Foto excede 150 MB.");b.position(52);i.name=string(b);i.date=string(b);return i;}
 void download(int handle,File dest,int size)throws IOException{try(FileOutputStream f=new FileOutputStream(dest)){exchange(0x1009,f,handle);f.getFD().sync();}if(dest.length()!=size)throw new IOException("JPEG incompleto.");}
 public void close(){timeout=1500;try{if(!broken){try{if(events)execute(0x9115,0);if(remote)execute(0x9114,0);if(session)execute(0x1003);}catch(Exception ignored){}}}finally{try{connection.releaseInterface(iface);}finally{connection.close();}}}
}
