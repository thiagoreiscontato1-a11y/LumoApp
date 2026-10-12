package com.hisho.tether;
import java.nio.*;import java.util.*;
public class CoreTest{
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args)throws Exception{
  byte[] a=ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(2).putInt(19).putInt(-1).array();check(Arrays.equals(Ptp.array(a),new int[]{19,-1}));
  boolean rejected=false;try{Ptp.array(new byte[]{2,0,0,0});}catch(java.io.IOException e){rejected=true;}check(rejected);
  ByteBuffer s=ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN).put((byte)3).putChar('A').putChar('B').putChar('\0');s.flip();check(Ptp.string(s).equals("AB"));
  for(int color:new int[]{0xff000000,0xffffffff,0xff808080}){int[] pixels=new int[1000];Arrays.fill(pixels,color);PhotoEditor.auto(pixels);check(pixels[0]==color);}
  int[] dark=new int[1000];Arrays.fill(dark,0xff303030);PhotoEditor.auto(dark);check((dark[0]&255)>48);check((dark[0]&255)<90);
  System.out.println("PASS: PTP arrays/text, truncated packets, neutral/black/white identity, conservative dark correction.");
 }
}
