package com.hisho.tether;

import android.content.*;
import org.json.*;

/**
 * Login do Lumo: a conta Fotto é a entrada do app.
 * Com uma sessão Fotto salva, o Lumo abre direto (inclusive sem internet, no meio de um evento);
 * sem sessão, o MainActivity manda para a LumoLoginActivity. Sair apaga a sessão e os cookies do login.
 */
final class LumoSession {
 static final String PREFS="hisho";
 /** Capturas do CI e apresentação: o modo demonstração entra sem conta. */
 static final String DEMO="lumoDemoSession";
 static final String NAME="fottoAccountName",EMAIL="fottoAccountEmail";

 static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREFS,0);}

 static boolean signedIn(Context c){return !FottoApi.token(c).isEmpty()||prefs(c).getBoolean(DEMO,false);}

 static boolean demo(Context c){return FottoApi.token(c).isEmpty()&&prefs(c).getBoolean(DEMO,false);}

 static void enterDemo(Context c){prefs(c).edit().putBoolean(DEMO,true).apply();}

 /** Nome ou e-mail da conta para mostrar no menu; vazio se o /me ainda não foi lido. */
 static String account(Context c){
  if(demo(c))return "Modo demonstração";
  String name=prefs(c).getString(NAME,""),email=prefs(c).getString(EMAIL,"");
  if(!name.isEmpty()&&!email.isEmpty())return name+" · "+email;
  return !name.isEmpty()?name:email;
 }

 /** Lê nome e e-mail da resposta do /me do Fotto (o formato não é documentado: procura em qualquer nível). */
 static void rememberProfile(Context c,String me){
  try{
   Object root=new JSONTokener(me==null?"":me).nextValue();
   String name=first(root,"name","full_name","fullName","display_name","nome"),email=first(root,"email","mail","login");
   if(!email.contains("@"))email="";
   prefs(c).edit().putString(NAME,name).putString(EMAIL,email).apply();
  }catch(Exception ignored){}
 }

 static String first(Object root,String...keys){
  for(String k:keys){JSONObject o=FottoApi.findObject(root,k);if(o==null)continue;String v=FottoApi.value(o.opt(k)).trim();if(!v.isEmpty()&&!v.equalsIgnoreCase("null")&&v.length()<=120)return v;}
  return "";
 }

 /**
  * Sai da conta: para o envio automático, apaga as chaves do Fotto, o evento de destino e os cookies/armazenamento
  * do WebView (senão o próximo login reaproveitaria a mesma sessão). Fotos, eventos locais e a fila ficam no aparelho.
  * Chamar na thread principal.
  */
 static void signOut(Context c){
  FottoSync.stop(c);
  try{c.stopService(new Intent(c,FottoKeepAliveService.class));}catch(Exception ignored){}
  prefs(c).edit()
   .remove("fottoApiKeyV7").remove("fottoAccessTokenV7").remove("fottoApiKeyV5").remove("fottoAccessTokenV5")
   .remove("fottoApiKeyV4").remove("fottoAccessTokenV4").remove("fottoToken").remove("fottoTokenV2").remove("fottoTokenV3")
   .remove("fottoGalleryId").remove("fottoGalleryTitle").remove(NAME).remove(EMAIL).remove(DEMO)
   .putBoolean("fottoAuto",false).putString("fottoLastStatus","Você saiu da conta Fotto.")
   .apply();
  try{android.webkit.CookieManager cm=android.webkit.CookieManager.getInstance();cm.removeAllCookies(null);cm.flush();}catch(Exception ignored){}
  try{android.webkit.WebStorage.getInstance().deleteAllData();}catch(Exception ignored){}
 }

 private LumoSession(){}
}
