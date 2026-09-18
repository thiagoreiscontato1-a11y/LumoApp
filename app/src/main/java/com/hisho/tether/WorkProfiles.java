package com.hisho.tether;
import android.content.*;import org.json.*;import java.util.*;

/** Perfis de trabalho: valores padrão + personalização persistente. */
final class WorkProfiles{
 static final String[] NAMES={"Corrida","Aniversário","Ensaio","Futebol","Personalizado"};
 static JSONObject defaults(String n){
  JSONObject j=new JSONObject();try{
   if("Corrida".equals(n)){j.put("curationSensitivity",1);j.put("autoStrength",.85);j.put("backfill",true);j.put("autoUpload",true);}
   else if("Aniversário".equals(n)){j.put("curationSensitivity",1);j.put("autoStrength",.75);j.put("backfill",true);j.put("autoUpload",true);}
   else if("Ensaio".equals(n)){j.put("curationSensitivity",2);j.put("autoStrength",.55);j.put("backfill",true);j.put("autoUpload",false);}
   else if("Futebol".equals(n)){j.put("curationSensitivity",1);j.put("autoStrength",.9);j.put("backfill",true);j.put("autoUpload",true);}
   else{j.put("curationSensitivity",1);j.put("autoStrength",1.0);j.put("backfill",true);j.put("autoUpload",false);}
   j.put("curation",true);j.put("auto",true);j.put("presetEnabled",true);
  }catch(Exception ignored){}return j;
 }
 static JSONObject stored(Context c,String name){try{JSONObject root=new JSONObject(c.getSharedPreferences("hisho",0).getString("workProfiles","{}"));JSONObject x=root.optJSONObject(name);return x==null?defaults(name):x;}catch(Exception e){return defaults(name);}}
 static void saveCurrent(Context c,String name){
  SharedPreferences p=c.getSharedPreferences("hisho",0);JSONObject root;try{root=new JSONObject(p.getString("workProfiles","{}"));}catch(Exception e){root=new JSONObject();}
  JSONObject j=new JSONObject();try{j.put("curationSensitivity",p.getInt("curationSensitivity",1));j.put("autoStrength",Double.longBitsToDouble(p.getLong("autoStrengthBits",Double.doubleToLongBits(1.0))));j.put("backfill",p.getBoolean("backfillEnabled",true));j.put("autoUpload",p.getBoolean("fottoAuto",false));j.put("curation",p.getBoolean("curationEnabled",true));j.put("auto",p.getBoolean("autoEnabled",true));j.put("presetEnabled",p.getBoolean("presetEnabled",true));j.put("presetName",p.getString("presetName","Vibrant"));j.put("preset",p.getString("preset","{}"));j.put("exportTreeUri",p.getString("exportTreeUri",""));j.put("fottoGalleryId",p.getString("fottoGalleryId",""));j.put("fottoGalleryTitle",p.getString("fottoGalleryTitle",""));root.put(name,j);p.edit().putString("workProfiles",root.toString()).apply();}catch(Exception ignored){}
 }
 static void apply(Context c,String name){
  SharedPreferences p=c.getSharedPreferences("hisho",0);JSONObject j=stored(c,name);SharedPreferences.Editor e=p.edit().putString("workProfile",name)
   .putInt("curationSensitivity",j.optInt("curationSensitivity",1))
   .putLong("autoStrengthBits",Double.doubleToLongBits(j.optDouble("autoStrength",1)))
   .putBoolean("backfillEnabled",j.optBoolean("backfill",true))
   .putBoolean("curationEnabled",j.optBoolean("curation",true))
   .putBoolean("autoEnabled",j.optBoolean("auto",true))
   .putBoolean("presetEnabled",j.optBoolean("presetEnabled",true));
  String pn=j.optString("presetName","");String pd=j.optString("preset","");if(!pn.isEmpty())e.putString("presetName",pn);if(pd!=null&&!pd.isEmpty()&&!"{}".equals(pd))e.putString("preset",pd);
  String tree=j.optString("exportTreeUri","");if(!tree.isEmpty())e.putString("exportTreeUri",tree);
  String gid=j.optString("fottoGalleryId","");String gtitle=j.optString("fottoGalleryTitle","");if(!gid.isEmpty()){e.putString("fottoGalleryId",gid);e.putString("fottoGalleryTitle",gtitle);}
  e.putBoolean("fottoAuto",j.optBoolean("autoUpload",false)&&!FottoApi.token(c).isEmpty()&&!gid.isEmpty());
  e.apply();
 }
 static double autoStrength(Context c){return Double.longBitsToDouble(c.getSharedPreferences("hisho",0).getLong("autoStrengthBits",Double.doubleToLongBits(1.0)));}
 private WorkProfiles(){}
}
