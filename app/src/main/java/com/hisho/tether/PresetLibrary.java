package com.hisho.tether;
import android.content.*;import org.json.*;import java.util.*;

final class PresetLibrary{
 static JSONObject all(Context c){
  try{return new JSONObject(c.getSharedPreferences("hisho",0).getString("presetLibrary","{}"));}catch(Exception e){return new JSONObject();}
 }
 static void seed(Context c,String name,JSONObject data){
  if(name==null||name.trim().isEmpty()||data==null)return;JSONObject all=all(c);try{if(!all.has(name))all.put(name,new JSONObject(data.toString()));c.getSharedPreferences("hisho",0).edit().putString("presetLibrary",all.toString()).apply();}catch(Exception ignored){}
 }
 static void put(Context c,String name,JSONObject data){
  if(name==null||name.trim().isEmpty()||data==null)return;JSONObject all=all(c);try{all.put(name,new JSONObject(data.toString()));c.getSharedPreferences("hisho",0).edit().putString("presetLibrary",all.toString()).apply();}catch(Exception ignored){}
 }
 static ArrayList<String> names(Context c){ArrayList<String> n=new ArrayList<>();JSONObject all=all(c);Iterator<String> it=all.keys();while(it.hasNext())n.add(it.next());Collections.sort(n,String.CASE_INSENSITIVE_ORDER);return n;}
 static JSONObject get(Context c,String name){JSONObject all=all(c);JSONObject p=all.optJSONObject(name);try{return p==null?new JSONObject():new JSONObject(p.toString());}catch(Exception e){return new JSONObject();}}
 private PresetLibrary(){}
}
