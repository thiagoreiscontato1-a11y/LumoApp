package com.hisho.tether;
import java.text.*;import java.util.*;
final class PhotoTime{
 static long parsePtp(String s){if(s==null)s="";String t=s.trim();for(String pattern:new String[]{"yyyyMMdd'T'HHmmss","yyyyMMddHHmmss","yyyy:MM:dd HH:mm:ss"})try{SimpleDateFormat f=new SimpleDateFormat(pattern,Locale.ROOT);f.setLenient(false);Date d=f.parse(t);if(d!=null)return d.getTime();}catch(Exception ignored){}return System.currentTimeMillis();}
 private PhotoTime(){}
}
