package com.hisho.tether;
import org.json.*;import java.util.*;

/**
 * Criar preset a partir de uma edição e exportar presets como .xmp (Lightroom/Camera Raw), sem dependência do Android.
 * Mesmas regras do iOS ({@code PhotoEditor.presetValues} e {@code XMPPreset.write}): o .xmp feito aqui abre no Lumo do
 * iPhone e vice-versa.
 */
final class PresetMaker{
 /** Preset salvo (JSON de chaves Camera Raw) como mapa numérico (o "Look" e outros objetos ficam de fora). */
 static Map<String,Double> values(JSONObject preset){
  Map<String,Double> m=new HashMap<>();if(preset==null)return m;Iterator<String> it=preset.keys();
  while(it.hasNext()){String k=it.next();double v=preset.optDouble(k,Double.NaN);if(!Double.isNaN(v))m.put(k,v);}return m;
 }
 /**
  * Preset com os ajustes extras do editor por cima (só chaves Camera Raw conhecidas). Uma curva de pontos no
  * extra ({@code ToneCurvePV2012.0}, {@code .1}…) substitui a curva inteira do preset.
  */
 static Map<String,Double> merged(JSONObject preset,JSONObject extra){
  Map<String,Double> m=values(preset);if(extra==null)return m;
  for(String c:Develop.CURVES)if(extra.has(c+".0")){Iterator<String> old=new ArrayList<>(m.keySet()).iterator();while(old.hasNext()){String k=old.next();if(k.startsWith(c+"."))m.remove(k);}}
  Iterator<String> it=extra.keys();
  while(it.hasNext()){String k=it.next();double v=extra.optDouble(k,Double.NaN);if(Double.isNaN(v))continue;if(Develop.RANGES.containsKey(k))m.put(k,v);else if(curveKey(k))m.put(k,Math.max(0,Math.min(255,v)));}return m;
 }
 /** Chave de ponto de curva ({@code ToneCurvePV2012Red.4}…). */
 static boolean curveKey(String k){int dot=k.lastIndexOf('.');if(dot<0)return false;String base=k.substring(0,dot),n=k.substring(dot+1);if(n.isEmpty()||n.length()>3)return false;for(char ch:n.toCharArray())if(ch<'0'||ch>'9')return false;for(String c:Develop.CURVES)if(c.equals(base))return true;return false;}

 /**
  * Preset de uma edição: o preset da foto com os ajustes do editor por cima e os básicos = preset + delta manual.
  * O automático fica de fora (é calculado para cada foto) e o recorte também. O perfil criativo ("Look") é mantido.
  * Aplicar o preset novo numa foto sem ajuste manual dá o mesmo resultado que a edição de onde ele saiu.
  */
 static JSONObject fromEdit(JSONObject preset,JSONObject extra,JSONObject delta)throws JSONException{
  Map<String,Double> values=merged(preset,extra);
  JSONObject out=new JSONObject();
  for(Map.Entry<String,Double> e:values.entrySet())out.put(e.getKey(),(double)e.getValue());
  for(int i=0;i<Develop.BASIC.length;i++){
   String key=Develop.BASIC_XMP[i];double v=(preset==null?0:preset.optDouble(key,0))+(delta==null?0:delta.optDouble(Develop.BASIC[i],0));
   double lo=i==0?-5:-100,hi=i==0?5:100;v=Math.max(lo,Math.min(hi,v));
   if(v==0)out.remove(key);else out.put(key,v);
  }
  if(preset!=null&&preset.optJSONObject("Look")!=null)out.put("Look",new JSONObject(preset.getJSONObject("Look").toString()));
  return out;
 }

 /** Preset no formato .xmp do Lightroom/Camera Raw. O perfil criativo (tabelas de cor) não vai no arquivo. */
 static String xmp(JSONObject preset,String name){
  StringBuilder attrs=new StringBuilder();
  TreeSet<String> keys=new TreeSet<>();Iterator<String> it=preset.keys();while(it.hasNext())keys.add(it.next());
  for(String k:keys){
   double[] r=Develop.RANGES.get(k);if(r==null||"LookAmount".equals(k)||curveKey(k))continue;
   double v=preset.optDouble(k,Double.NaN);if(Double.isNaN(v)||Double.isInfinite(v)||v<r[0]||v>r[1])continue;
   attrs.append("\n    crs:").append(k).append("=\"").append(number(v,k)).append('"');
  }
  StringBuilder curves=new StringBuilder();
  for(String c:Develop.CURVES){
   ArrayList<String> points=new ArrayList<>();
   while(points.size()<64&&preset.has(c+"."+(2*points.size()))&&preset.has(c+"."+(2*points.size()+1)))
    points.add(Math.round(preset.optDouble(c+"."+(2*points.size()),0))+", "+Math.round(preset.optDouble(c+"."+(2*points.size()+1),0)));
   if(points.size()<2)continue;
   curves.append("\n   <crs:").append(c).append(">\n    <rdf:Seq>\n");for(String p:points)curves.append("     <rdf:li>").append(p).append("</rdf:li>\n");
   curves.append("    </rdf:Seq>\n   </crs:").append(c).append('>');
  }
  String uuid=UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
  return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Lumo\">\n"
   +" <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
   +"  <rdf:Description rdf:about=\"\"\n"
   +"    xmlns:crs=\"http://ns.adobe.com/camera-raw-settings/1.0/\"\n"
   +"    crs:PresetType=\"Normal\" crs:UUID=\""+uuid+"\" crs:SupportsAmount=\"False\"\n"
   +"    crs:SupportsColor=\"True\" crs:SupportsMonochrome=\"True\" crs:SupportsHighDynamicRange=\"True\" crs:SupportsNormalDynamicRange=\"True\"\n"
   +"    crs:SupportsSceneReferred=\"True\" crs:SupportsOutputReferred=\"True\" crs:CameraModelRestriction=\"\" crs:Copyright=\"\" crs:ContactInfo=\"\"\n"
   +"    crs:Version=\"15.0\" crs:ProcessVersion=\"11.0\" crs:HasSettings=\"True\""+attrs+">\n"
   +"   <crs:Name>\n    <rdf:Alt>\n     <rdf:li xml:lang=\"x-default\">"+escape(name)+"</rdf:li>\n    </rdf:Alt>\n   </crs:Name>"+curves+"\n"
   +"  </rdf:Description>\n </rdf:RDF>\n</x:xmpmeta>\n";
 }
 static String number(double v,String key){
  if("ConvertToGrayscale".equals(key))return v>=.5?"True":"False";
  String sign=v>0&&"Exposure2012".equals(key)?"+":"";
  if(v==Math.rint(v)&&Math.abs(v)<1e9)return sign+(long)v;
  String t=String.format(Locale.ROOT,"%.4f",v);while(t.endsWith("0"))t=t.substring(0,t.length()-1);return sign+t;
 }
 static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
 /** Nome de arquivo seguro para o preset. */
 static String fileName(String name){String n=name.replaceAll("[\\\\/:*?\"<>|]","-").trim();return (n.isEmpty()?"Preset Lumo":n)+".xmp";}
 private PresetMaker(){}
}
