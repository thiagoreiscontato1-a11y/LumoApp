package com.hisho.tether;
import java.io.*;import java.nio.*;import java.util.*;
public class WifiWireTest {
 static byte[] b(int size){return new byte[size];}
 static ByteBuffer le(int n){return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);}
 static void check(boolean v){if(!v)throw new AssertionError();}
 static byte[] transfer(int tx,byte[] data)throws Exception{ByteArrayOutputStream wire=new ByteArrayOutputStream();PtpIpWire.write(wire,13,b(0));PtpIpWire.write(wire,9,le(12).putInt(tx).putLong(data.length).array());PtpIpWire.write(wire,10,le(4+data.length/2).putInt(tx).put(data,0,data.length/2).array());PtpIpWire.write(wire,12,le(4+data.length-data.length/2).putInt(tx).put(data,data.length/2,data.length-data.length/2).array());PtpIpWire.write(wire,7,le(6).putShort((short)0x2001).putInt(tx).array());return wire.toByteArray();}
 static void reject(byte[] data,int tx)throws Exception{try{PtpIpWire.response(new ByteArrayInputStream(data),new ByteArrayOutputStream(),null,tx,()->{});throw new AssertionError("Expected rejected frame");}catch(IOException expected){}}
 public static void main(String[] args)throws Exception{
  byte[] data=new byte[250003];new Random(3).nextBytes(data);byte[] wire=transfer(42,data);InputStream fragmented=new ByteArrayInputStream(wire){public synchronized int read(byte[] b,int off,int len){return super.read(b,off,Math.min(len,3));}};ByteArrayOutputStream saved=new ByteArrayOutputStream(),replies=new ByteArrayOutputStream();PtpIpWire.Response r=PtpIpWire.response(fragmented,replies,saved,42,()->{});check(r.code==0x2001&&r.data.length==0&&Arrays.equals(data,saved.toByteArray()));check(PtpIpWire.le(replies.toByteArray()).getInt(4)==14);
  reject(wire,43);reject(Arrays.copyOf(wire,wire.length-2),42);reject(le(8).putInt(Integer.MAX_VALUE).putInt(9).array(),42);
  ByteArrayOutputStream incomplete=new ByteArrayOutputStream();PtpIpWire.write(incomplete,9,le(12).putInt(42).putLong(5).array());PtpIpWire.write(incomplete,7,le(6).putShort((short)0x2001).putInt(42).array());reject(incomplete.toByteArray(),42);
  ByteArrayOutputStream failure=new ByteArrayOutputStream();PtpIpWire.write(failure,7,le(6).putShort((short)0x2019).putInt(42).array());check(PtpIpWire.response(new ByteArrayInputStream(failure.toByteArray()),new ByteArrayOutputStream(),null,42,()->{}).code==0x2019);
  byte[] command=PtpIpWire.command(0x1009,42,123);check(PtpIpWire.le(command).getInt()==1&&PtpIpWire.le(command).getInt(6)==42&&PtpIpWire.le(command).getInt(10)==123);
  System.out.println("PASS: fragmented TCP, streaming JPEG, ping/pong, transaction check, truncated/oversized/incomplete frames, busy response and operation encoding.");
 }
}
