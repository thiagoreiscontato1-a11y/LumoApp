package com.hisho.tether;
import org.xmlpull.v1.XmlPullParser;import javax.xml.stream.*;import java.io.*;import java.util.*;

/** Testes do motor de revelação no desktop (sem Android). Rodado por tests/develop_contract.py. */
public class DevelopTest{
 static int checks=0;
 static void check(boolean b,String what){checks++;if(!b)throw new AssertionError(what);}
 static int r(int v){return (v>>16)&255;}static int g(int v){return (v>>8)&255;}static int b(int v){return v&255;}
 static int rgb(int r,int g,int b){return 0xff000000|r<<16|g<<8|b;}
 static Develop.Settings preset(Object... kv){Map<String,Double> m=new HashMap<>();for(int i=0;i<kv.length;i+=2)m.put((String)kv[i],((Number)kv[i+1]).doubleValue());return Develop.Settings.of(m);}
 static int[] solid(int n,int color){int[] p=new int[n];Arrays.fill(p,color);return p;}
 static int one(int color,Develop.Settings s){return new Develop.Source(new int[]{color},1,1).render(s)[0];}
 static double meanLuma(int[] p){double s=0;for(int v:p)s+=.2126*r(v)+.7152*g(v)+.0722*b(v);return s/p.length;}
 /** Imagem sintética fixa: gradientes, céu, pele, folhagem e bordas (também usada na paridade com o iOS). */
 static int[] scene(int w,int h){
  int[] p=new int[w*h];
  for(int y=0;y<h;y++)for(int x=0;x<w;x++){
   double u=x/(double)(w-1),v=y/(double)(h-1);int R,G,B;
   if(v<.33){R=(int)(60+90*u);G=(int)(120+80*u);B=(int)(190+50*u);}
   else if(v<.66){boolean skin=((x/6)+(y/6))%2==0;R=skin?(int)(200+40*u):(int)(40+30*u);G=skin?(int)(150+30*u):(int)(110+60*u);B=skin?(int)(120+20*u):(int)(40+20*u);}
   else{int k=(int)(255*u);R=G=B=k;if(x%9==0)R=G=B=255-k;}
   p[y*w+x]=rgb(R,G,B);
  }
  return p;
 }

 public static void main(String[] args)throws Exception{
  // Ajustes zerados: foto idêntica, pixel a pixel.
  int[] img=scene(64,48);check(Arrays.equals(new Develop.Source(img,64,48).render(new Develop.Settings()),img),"identidade");
  Random rnd=new Random(7);int[] noise=new int[4096];for(int i=0;i<noise.length;i++)noise[i]=0xff000000|rnd.nextInt(1<<24);
  check(Arrays.equals(new Develop.Source(noise,64,64).render(new Develop.Settings()),noise),"identidade em ruído");

  // Automático: neutros extremos ficam iguais; foto escura sobe com moderação.
  for(int color:new int[]{0xff000000,0xffffffff,0xff808080}){int[] p=solid(1000,color);PhotoEditorAuto.auto(p);check(p[0]==color,"auto neutro "+Integer.toHexString(color));}
  int[] dark=solid(1000,0xff303030);PhotoEditorAuto.auto(dark);check(b(dark[0])>48&&b(dark[0])<90,"auto escuro "+b(dark[0]));
  int[] zero=img.clone();PhotoEditorAuto.auto(zero,0);check(Arrays.equals(zero,img),"auto intensidade 0");

  // Exposição atua em luz linear: +1 EV dobra a luminância linear de um meio-tom.
  int mid=one(0xff767676,preset("Exposure2012",1));double lin=Develop.decodeExact(0x76/255.)*2;check(Math.abs(g(mid)-Develop.encodeExact(lin)*255)<1.5,"exposição +1 "+g(mid));
  check(g(one(0xff767676,preset("Exposure2012",-1)))<0x60,"exposição -1");
  // Exposição alta comprime os realces sem inverter a ordem dos tons.
  int[] ramp=new int[256];for(int i=0;i<256;i++)ramp[i]=rgb(i,i,i);
  for(Object[] kv:new Object[][]{{"Exposure2012",2},{"Contrast2012",100},{"Contrast2012",-100},{"Whites2012",100},{"Whites2012",-100},{"Blacks2012",100},{"Blacks2012",-100},{"ParametricShadows",100},{"ParametricHighlights",-100},{"ParametricDarks",80}}){
   int[] out=new Develop.Source(ramp,256,1).render(preset(kv));for(int i=1;i<256;i++)check(g(out[i])>=g(out[i-1]),"monótono "+kv[0]+"="+kv[1]+" em "+i);
  }
  int[] ce=new Develop.Source(ramp,256,1).render(preset("Contrast2012",60));check(g(ce[40])<40&&g(ce[215])>215,"contraste em S");
  check(g(new Develop.Source(ramp,256,1).render(preset("Blacks2012",100))[0])>15,"pretos +100 levanta o preto");
  check(g(new Develop.Source(ramp,256,1).render(preset("Whites2012",-100))[255])<235,"brancos -100 baixa o branco");

  // Balanço de branco incremental.
  int warm=one(0xff808080,preset("IncrementalTemperature",50));check(r(warm)>b(warm)+20,"temperatura quente");
  int magenta=one(0xff808080,preset("IncrementalTint",50));check(g(magenta)<r(magenta)&&g(magenta)<b(magenta),"matiz magenta");

  // Sombras clareiam a área escura sem mexer na clara; realces fazem o oposto.
  int[] split=new int[64*64];for(int i=0;i<split.length;i++)split[i]=(i%64)<32?0xff202020:0xffd0d0d0;
  int[] shOut=new Develop.Source(split,64,64).render(preset("Shadows2012",100));check(g(shOut[64*32+5])>0x20+20&&Math.abs(g(shOut[64*32+60])-0xd0)<6,"sombras +100");
  int[] hlOut=new Develop.Source(split,64,64).render(preset("Highlights2012",-100));check(g(hlOut[64*32+60])<0xd0-15&&Math.abs(g(hlOut[64*32+5])-0x20)<4,"realces -100");

  // Claridade e textura aumentam contraste local; dehaze escurece/satura a névoa.
  double before=stdev(img),after=stdev(new Develop.Source(img,64,48).render(preset("Clarity2012",100)));check(after>before,"claridade");
  check(stdev(new Develop.Source(img,64,48).render(preset("Texture",100)))>before,"textura");
  int haze=one(0xffb0b4b8,preset("Dehaze",80));check(g(haze)<0xb4,"dehaze");

  // Vibração/saturação.
  int dull=rgb(150,120,110);check(chroma(one(dull,preset("Saturation",-100)))<=1,"saturação -100 vira cinza");
  check(chroma(one(dull,preset("Saturation",60)))>chroma(dull),"saturação +");
  check(chroma(one(dull,preset("Vibrance",80)))>chroma(dull),"vibração +");

  // HSL: azul sem saturação, vermelho puxado para laranja, luminância do azul.
  int blue=rgb(40,80,200);check(chroma(one(blue,preset("SaturationAdjustmentBlue",-100)))<8,"HSL saturação azul");
  int red=rgb(200,40,40);check(g(one(red,preset("HueAdjustmentRed",100)))>g(red)+20,"HSL matiz vermelho");
  check(lum(one(blue,preset("LuminanceAdjustmentBlue",-100)))<lum(blue)-10,"HSL luminância azul");
  check(one(red,preset("SaturationAdjustmentBlue",-100))==red,"HSL não afeta outras cores");

  // P&B com mistura.
  int bw=one(blue,preset("ConvertToGrayscale",1));check(r(bw)==g(bw)&&g(bw)==b(bw),"P&B");
  check(g(one(blue,preset("ConvertToGrayscale",1,"GrayMixerBlue",-80)))<g(bw),"mistura P&B azul");

  // Color grading: sombras azuladas, realces quentes.
  int shade=one(0xff303030,preset("SplitToningShadowHue",220,"SplitToningShadowSaturation",60));check(b(shade)>r(shade)+8,"color grading sombras");
  int light=one(0xffd0d0d0,preset("SplitToningHighlightHue",40,"SplitToningHighlightSaturation",60));check(r(light)>b(light)+8,"color grading realces");
  int global=one(0xff808080,preset("ColorGradeGlobalHue",120,"ColorGradeGlobalSat",50));check(g(global)>r(global),"color grading global");

  // Calibração: saturação do azul muda as cores, branco continua neutro.
  check(one(0xffffffff,preset("BlueSaturation",100,"RedHue",40))==0xffffffff,"calibração mantém branco");
  check(one(0xff808080,preset("BlueSaturation",100,"RedHue",40,"GreenSaturation",-50))==0xff808080,"calibração mantém cinza");
  int muted=rgb(90,110,160);check(chroma(one(muted,preset("BlueSaturation",100)))>chroma(muted)+10,"calibração azul +");
  check(one(rgb(200,120,60),preset("BlueSaturation",-100))==one(rgb(200,120,60),new Develop.Settings()),"calibração azul não afeta laranja");
  int leaf=rgb(60,160,60),teal=one(leaf,preset("GreenHue",100));check(b(teal)>b(leaf)+30,"calibração verde gira para o ciano");
  // Preset real (calibração forte + HSL): pele e rosa não podem virar laranja.
  Develop.Settings real=preset("RedHue",54,"RedSaturation",47,"GreenHue",53,"BlueHue",-61,"BlueSaturation",-70,"HueAdjustmentRed",-53,"HueAdjustmentOrange",13,"HueAdjustmentYellow",-63,"HueAdjustmentAqua",70);
  int pink=one(rgb(251,112,152),real);check(b(pink)>g(pink)+20,"rosa continua rosa "+Integer.toHexString(pink));
  int face=one(rgb(205,134,122),real);check(b(face)>70&&r(face)-b(face)<120,"pele não amarela "+Integer.toHexString(face));
  // Exposição + realces/brancos negativos: a camisa branca continua branca, não cinza.
  int shirt=one(rgb(235,225,249),preset("Exposure2012",.52,"Highlights2012",-100,"Whites2012",-25));check(g(shirt)>235,"branco recuperado "+Integer.toHexString(shirt));

  // Curvas: fade (preto em 30) e curva vermelha.
  int faded=one(0xff000000,preset("ToneCurvePV2012.0",0,"ToneCurvePV2012.1",30,"ToneCurvePV2012.2",255,"ToneCurvePV2012.3",255));check(Math.abs(g(faded)-30)<=1,"curva fade "+g(faded));
  int redCurve=one(0xff808080,preset("ToneCurvePV2012Red.0",0,"ToneCurvePV2012Red.1",0,"ToneCurvePV2012Red.2",128,"ToneCurvePV2012Red.3",160,"ToneCurvePV2012Red.4",255,"ToneCurvePV2012Red.5",255));check(r(redCurve)>150&&g(redCurve)==128,"curva vermelha");
  int sCurve=one(0xff404040,preset("ToneCurvePV2012.0",0,"ToneCurvePV2012.1",0,"ToneCurvePV2012.2",64,"ToneCurvePV2012.3",48,"ToneCurvePV2012.4",192,"ToneCurvePV2012.5",208,"ToneCurvePV2012.6",255,"ToneCurvePV2012.7",255));check(Math.abs(g(sCurve)-48)<=1,"curva passa pelos pontos "+g(sCurve));

  // Vinheta escurece cantos, não o centro; granulação e nitidez são determinísticas.
  int[] gray=solid(80*60,0xff909090);int[] vg=new Develop.Source(gray,80,60).render(preset("PostCropVignetteAmount",-60));check(g(vg[0])<0x90-20&&g(vg[30*80+40])==0x90,"vinheta");
  Develop.Settings fx=preset("GrainAmount",40,"Sharpness",80,"Clarity2012",30);int[] a1=new Develop.Source(img,64,48).render(fx),a2=new Develop.Source(img,64,48).render(fx);check(Arrays.equals(a1,a2)&&!Arrays.equals(a1,img),"determinístico");
  check(stdev(new Develop.Source(gray,80,60).render(preset("GrainAmount",50)))>1,"granulação");
  check(stdev(new Develop.Source(img,64,48).render(preset("Sharpness",120)))>before,"nitidez");

  // Fonte reaproveitada: renderizar de novo dá o mesmo resultado (cache dos campos).
  Develop.Source cached=new Develop.Source(img,64,48);Develop.Settings full=fullPreset();int[] f1=cached.render(full),f2=cached.render(full);check(Arrays.equals(f1,f2),"cache");

  // XMP: atributos, elementos, curvas rdf:Seq, booleanos e validação.
  Map<String,Double> x=parse(XMP);
  check(x.get("Exposure2012")==.35&&x.get("Contrast2012")==-12&&x.get("SaturationAdjustmentBlue")==-20,"xmp atributos");
  check(x.get("Vibrance")==15&&x.get("ConvertToGrayscale")==0&&x.get("SplitToningShadowHue")==220,"xmp elementos");
  check(x.get("ToneCurvePV2012.0")==0&&x.get("ToneCurvePV2012.1")==22&&x.get("ToneCurvePV2012.4")==255&&!x.containsKey("ToneCurvePV2012.6"),"xmp curva");
  check(x.get("ToneCurvePV2012Red.3")==70,"xmp curva vermelha");
  check(!x.containsKey("ProcessVersion")&&!x.containsKey("Temperature"),"xmp ignora chaves sem suporte");
  boolean rejected=false;try{parse(XMP.replace("+0.35","9"));}catch(IOException e){rejected=true;}check(rejected,"xmp fora do intervalo");
  rejected=false;try{parse("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>");}catch(IOException e){rejected=e.getMessage().startsWith("Nenhum");}check(rejected,"xmp vazio");

  // Vetores de paridade com o iOS (Develop.swift): mesma cena, mesmo preset.
  int[] parity=new Develop.Source(scene(48,36),48,36).render(fullPreset());
  StringBuilder sb=new StringBuilder();long sum=0;for(int v:parity)sum+=r(v)+g(v)+b(v);
  for(int i=0;i<parity.length;i+=97)sb.append(String.format("0x%08X, ",parity[i]));
  System.out.println("PARITY sum="+sum+" samples=["+sb+"]");
  System.out.println("PASS: "+checks+" verificações do motor de revelação (identidade, automático, tons, cor, curvas, efeitos, XMP).");
 }

 static Develop.Settings fullPreset(){return preset("IncrementalTemperature",-11,"Exposure2012",.15,"Contrast2012",17,"Highlights2012",-70,"Shadows2012",62,"Whites2012",30,"Blacks2012",-20,"Clarity2012",35,"Texture",10,"Dehaze",8,"Vibrance",35,"Saturation",-10,
  "ParametricShadows",10,"ParametricHighlights",-15,"ToneCurvePV2012.0",0,"ToneCurvePV2012.1",18,"ToneCurvePV2012.2",128,"ToneCurvePV2012.3",132,"ToneCurvePV2012.4",255,"ToneCurvePV2012.5",245,
  "HueAdjustmentOrange",-8,"SaturationAdjustmentBlue",-29,"LuminanceAdjustmentBlue",-20,"SaturationAdjustmentGreen",-30,"HueAdjustmentGreen",25,
  "SplitToningShadowHue",210,"SplitToningShadowSaturation",15,"SplitToningHighlightHue",45,"SplitToningHighlightSaturation",12,"ColorGradeBlending",60,
  "BlueSaturation",25,"RedHue",10,"ShadowTint",-5,"Sharpness",40,"PostCropVignetteAmount",-21,"GrainAmount",12);}
 static double stdev(int[] p){double m=meanLuma(p),s=0;for(int v:p){double l=.2126*r(v)+.7152*g(v)+.0722*b(v)-m;s+=l*l;}return Math.sqrt(s/p.length);}
 static int chroma(int v){return Math.max(r(v),Math.max(g(v),b(v)))-Math.min(r(v),Math.min(g(v),b(v)));}
 static double lum(int v){return .2126*r(v)+.7152*g(v)+.0722*b(v);}

 static final String XMP="<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
  +"<rdf:Description rdf:about=\"\" xmlns:crs=\"http://ns.adobe.com/camera-raw-settings/1.0/\" crs:Exposure2012=\"+0.35\" crs:Contrast2012=\"-12\" crs:SaturationAdjustmentBlue=\"-20\" crs:ProcessVersion=\"11.0\" crs:Temperature=\"5500\" crs:ConvertToGrayscale=\"False\">"
  +"<crs:Vibrance>15</crs:Vibrance><crs:SplitToningShadowHue>220</crs:SplitToningShadowHue>"
  +"<crs:ToneCurvePV2012><rdf:Seq><rdf:li>0, 22</rdf:li><rdf:li>128, 128</rdf:li><rdf:li>255, 255</rdf:li></rdf:Seq></crs:ToneCurvePV2012>"
  +"<crs:ToneCurvePV2012Red><rdf:Seq><rdf:li>0, 0</rdf:li><rdf:li>64, 70</rdf:li><rdf:li>255, 255</rdf:li></rdf:Seq></crs:ToneCurvePV2012Red>"
  +"<crs:Look><rdf:Description crs:Name=\"Adobe Color\"/></crs:Look>"
  +"</rdf:Description></rdf:RDF></x:xmpmeta>";
 static Map<String,Double> parse(String xml)throws Exception{return Develop.parse(new StaxParser(xml));}

 /** Mesmo caminho do PhotoEditor.auto do Android, sem org.json. */
 static final class PhotoEditorAuto{
  static void auto(int[] d){auto(d,1);}
  static void auto(int[] d,double strength){Develop.Source s=new Develop.Source(d.clone(),d.length,1);double[] a=s.autoControls();double k=Math.max(0,Math.min(1.5,strength));for(int i=0;i<a.length;i++)a[i]*=k;Develop.Settings st=new Develop.Settings();st.setBasic(a);System.arraycopy(s.render(st),0,d,0,d.length);}
 }

 /** XmlPullParser do desktop sobre StAX, com a semântica usada pelo Android. */
 static final class StaxParser implements XmlPullParser{
  final XMLStreamReader r;int event=START_DOCUMENT,depth=0;boolean pendingPop;
  StaxParser(String xml)throws Exception{XMLInputFactory f=XMLInputFactory.newFactory();f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE,true);f.setProperty(XMLInputFactory.SUPPORT_DTD,false);r=f.createXMLStreamReader(new StringReader(xml));}
  public int getEventType(){return event;}
  public int next()throws Exception{
   if(pendingPop){depth--;pendingPop=false;}
   while(r.hasNext()){int e=r.next();
    if(e==XMLStreamConstants.START_ELEMENT){depth++;return event=START_TAG;}
    if(e==XMLStreamConstants.END_ELEMENT){pendingPop=true;return event=END_TAG;}
    if(e==XMLStreamConstants.CHARACTERS||e==XMLStreamConstants.CDATA){if(r.isWhiteSpace())continue;return event=TEXT;}
   }
   return event=END_DOCUMENT;
  }
  public int getDepth(){return depth;}
  public String getNamespace(){String n=r.getNamespaceURI();return n==null?"":n;}
  public String getName(){return r.getLocalName();}
  public int getAttributeCount(){return event==START_TAG?r.getAttributeCount():-1;}
  public String getAttributeNamespace(int i){String n=r.getAttributeNamespace(i);return n==null?"":n;}
  public String getAttributeName(int i){return r.getAttributeLocalName(i);}
  public String getAttributeValue(int i){return r.getAttributeValue(i);}
  public String nextText()throws Exception{StringBuilder sb=new StringBuilder();int e=next();while(e==TEXT){sb.append(r.getText());e=next();}if(e!=END_TAG)throw new IOException("nextText fora de elemento simples");return sb.toString();}
 }
}
