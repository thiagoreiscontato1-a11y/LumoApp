package com.hisho.tether;
import org.xmlpull.v1.XmlPullParser;import java.io.IOException;import java.util.*;

/**
 * Motor de revelação do Lumo. Modela o pipeline do Lightroom/Camera Raw (Process Version 2012+)
 * aplicado a JPEG, na mesma ordem do Lightroom:
 * calibração → balanço de branco → exposição → dehaze → tons locais (realces, sombras, claridade,
 * textura) → curva de tons (contraste, brancos, pretos) → curva paramétrica e de pontos →
 * vibração/saturação → HSL ou mistura P&B → color grading → nitidez → vinheta → granulação.
 * Ajustes zerados devolvem a foto idêntica. Não depende do Android (testável no desktop);
 * ios/LumoCore/Sources/LumoCore/Develop.swift é uma porta linha a linha.
 */
final class Develop{
 static final String NS="http://ns.adobe.com/camera-raw-settings/1.0/";
 static final String[] COLORS={"Red","Orange","Yellow","Green","Aqua","Blue","Purple","Magenta"};
 static final double[] CENTERS={0,30,60,120,180,240,270,300};
 /** Curvas de pontos ficam no preset como chaves planas: Nome.0=x0, Nome.1=y0, Nome.2=x1… (0–255). */
 static final String[] CURVES={"ToneCurvePV2012","ToneCurvePV2012Red","ToneCurvePV2012Green","ToneCurvePV2012Blue"};
 static final String[] LEGACY_CURVES={"ToneCurve","ToneCurveRed","ToneCurveGreen","ToneCurveBlue"};
 /** Controles do editor manual, na ordem de {@link Settings#basic()}. */
 static final String[] BASIC={"exposure","contrast","highlights","shadows","whites","blacks","temperature","tint","vibrance","saturation"};
 static final String[] BASIC_XMP={"Exposure2012","Contrast2012","Highlights2012","Shadows2012","Whites2012","Blacks2012","IncrementalTemperature","IncrementalTint","Vibrance","Saturation"};
 static final Map<String,double[]> RANGES=new HashMap<>();
 static void range(double lo,double hi,String... keys){for(String k:keys)RANGES.put(k,new double[]{lo,hi});}
 static{
  range(-5,5,"Exposure2012");
  range(-100,100,"IncrementalTemperature","IncrementalTint","Contrast2012","Highlights2012","Shadows2012","Whites2012","Blacks2012","Texture","Clarity2012","Dehaze","Vibrance","Saturation",
   "ParametricShadows","ParametricDarks","ParametricLights","ParametricHighlights","SplitToningBalance","ColorGradeShadowLum","ColorGradeMidtoneLum","ColorGradeHighlightLum","ColorGradeGlobalLum",
   "ShadowTint","RedHue","RedSaturation","GreenHue","GreenSaturation","BlueHue","BlueSaturation","PostCropVignetteAmount","PostCropVignetteRoundness");
  range(0,100,"ParametricShadowSplit","ParametricMidtoneSplit","ParametricHighlightSplit","SplitToningShadowSaturation","SplitToningHighlightSaturation","ColorGradeMidtoneSat","ColorGradeGlobalSat",
   "ColorGradeBlending","SharpenDetail","SharpenEdgeMasking","PostCropVignetteMidpoint","PostCropVignetteFeather","PostCropVignetteHighlightContrast","GrainAmount","GrainSize","GrainFrequency");
  range(0,360,"SplitToningShadowHue","SplitToningHighlightHue","ColorGradeMidtoneHue","ColorGradeGlobalHue");
  range(0,150,"Sharpness");range(0.5,3,"SharpenRadius");range(1,3,"PostCropVignetteStyle");range(0,1,"ConvertToGrayscale");
  for(String c:COLORS)range(-100,100,"HueAdjustment"+c,"SaturationAdjustment"+c,"LuminanceAdjustment"+c,"GrayMixer"+c);
 }

 // ---------------------------------------------------------------- XMP

 /** Lê um preset .xmp do Lightroom/Camera Raw (atributos ou elementos, incluindo curvas rdf:Seq). */
 static Map<String,Double> parse(XmlPullParser p)throws Exception{
  Map<String,Double> out=new TreeMap<>();Map<String,List<double[]>> curves=new HashMap<>();
  for(int e=p.getEventType();e!=XmlPullParser.END_DOCUMENT;e=p.next()){
   if(e!=XmlPullParser.START_TAG)continue;
   for(int i=0;i<p.getAttributeCount();i++)if(NS.equals(p.getAttributeNamespace(i)))store(out,p.getAttributeName(i),p.getAttributeValue(i));
   if(!NS.equals(p.getNamespace()))continue;
   String name=p.getName();
   if(isCurve(name))curves.put(name,readCurve(p));else if(RANGES.containsKey(name))store(out,name,p.nextText());
  }
  for(int i=0;i<4;i++){List<double[]> c=curves.containsKey(CURVES[i])?curves.get(CURVES[i]):curves.get(LEGACY_CURVES[i]);if(c==null||c.size()<2)continue;for(int j=0;j<c.size();j++){out.put(CURVES[i]+"."+(2*j),c.get(j)[0]);out.put(CURVES[i]+"."+(2*j+1),c.get(j)[1]);}}
  if(out.isEmpty())throw new IOException("Nenhum ajuste compatível encontrado no XMP.");
  return out;
 }
 static boolean isCurve(String name){for(int i=0;i<4;i++)if(CURVES[i].equals(name)||LEGACY_CURVES[i].equals(name))return true;return false;}
 static List<double[]> readCurve(XmlPullParser p)throws Exception{
  List<double[]> points=new ArrayList<>();int depth=p.getDepth();
  for(int e=p.next();!(e==XmlPullParser.END_TAG&&p.getDepth()==depth);e=p.next()){
   if(e==XmlPullParser.END_DOCUMENT)throw new IOException("XMP inválido.");
   if(e==XmlPullParser.START_TAG&&"li".equals(p.getName())){double[] xy=point(p.nextText());if(points.size()<64)points.add(xy);}
  }
  return points;
 }
 static double[] point(String text)throws IOException{
  String[] xy=text.split(",");if(xy.length!=2)throw new IOException("Curva inválida no XMP.");
  double x=number(xy[0]),y=number(xy[1]);if(!(x>=0&&x<=255&&y>=0&&y<=255))throw new IOException("Curva inválida no XMP.");return new double[]{x,y};
 }
 static void store(Map<String,Double> out,String key,String text)throws IOException{
  double[] r=RANGES.get(key);if(r==null)return;String t=text.trim();
  double v=t.equalsIgnoreCase("true")?1:t.equalsIgnoreCase("false")?0:number(t);
  if(!(v>=r[0]&&v<=r[1]))throw new IOException("Valor inválido: "+key);out.put(key,v);
 }
 static double number(String s)throws IOException{try{return Double.parseDouble(s.trim());}catch(NumberFormatException e){throw new IOException("Valor inválido: "+s.trim());}}

 // ---------------------------------------------------------------- Ajustes

 /** Ajustes resolvidos, nas unidades do Lightroom. Ausente no preset = valor padrão do Lightroom. */
 static final class Settings{
  double temperature,tint,exposure,contrast,highlights,shadows,whites,blacks,texture,clarity,dehaze,vibrance,saturation;
  double paramShadows,paramDarks,paramLights,paramHighlights,shadowSplit=25,midtoneSplit=50,highlightSplit=75;
  /** Curvas de pontos normalizadas {x0,y0,x1,y1…} (0–1) ou null: geral, vermelho, verde, azul. */
  final double[][] curves=new double[4][];
  final double[] hue=new double[8],sat=new double[8],lum=new double[8],mix=new double[8];
  boolean grayscale;
  double shadowHue,shadowSat,midtoneHue,midtoneSat,highlightHue,highlightSat,globalHue,globalSat,shadowLum,midtoneLum,highlightLum,globalLum,blending=50,balance;
  double shadowTint,redHue,redSat,greenHue,greenSat,blueHue,blueSat;
  double sharpness,sharpenRadius=1,sharpenDetail=25,sharpenMasking;
  double vignette,vignetteMidpoint=50,vignetteRoundness,vignetteFeather=50,vignetteHighlights,vignetteStyle=1;
  double grain,grainSize=25,grainRoughness=50;

  static Settings of(Map<String,Double> p){
   Settings s=new Settings();if(p==null)return s;
   s.temperature=get(p,"IncrementalTemperature",0);s.tint=get(p,"IncrementalTint",0);s.exposure=get(p,"Exposure2012",0);s.contrast=get(p,"Contrast2012",0);
   s.highlights=get(p,"Highlights2012",0);s.shadows=get(p,"Shadows2012",0);s.whites=get(p,"Whites2012",0);s.blacks=get(p,"Blacks2012",0);
   s.texture=get(p,"Texture",0);s.clarity=get(p,"Clarity2012",0);s.dehaze=get(p,"Dehaze",0);s.vibrance=get(p,"Vibrance",0);s.saturation=get(p,"Saturation",0);
   s.paramShadows=get(p,"ParametricShadows",0);s.paramDarks=get(p,"ParametricDarks",0);s.paramLights=get(p,"ParametricLights",0);s.paramHighlights=get(p,"ParametricHighlights",0);
   s.shadowSplit=get(p,"ParametricShadowSplit",25);s.midtoneSplit=get(p,"ParametricMidtoneSplit",50);s.highlightSplit=get(p,"ParametricHighlightSplit",75);
   for(int i=0;i<4;i++)s.curves[i]=curve(p,CURVES[i]);
   for(int i=0;i<8;i++){String c=COLORS[i];s.hue[i]=get(p,"HueAdjustment"+c,0);s.sat[i]=get(p,"SaturationAdjustment"+c,0);s.lum[i]=get(p,"LuminanceAdjustment"+c,0);s.mix[i]=get(p,"GrayMixer"+c,0);}
   s.grayscale=get(p,"ConvertToGrayscale",0)>=.5;
   s.shadowHue=get(p,"SplitToningShadowHue",0);s.shadowSat=get(p,"SplitToningShadowSaturation",0);s.highlightHue=get(p,"SplitToningHighlightHue",0);s.highlightSat=get(p,"SplitToningHighlightSaturation",0);
   s.midtoneHue=get(p,"ColorGradeMidtoneHue",0);s.midtoneSat=get(p,"ColorGradeMidtoneSat",0);s.globalHue=get(p,"ColorGradeGlobalHue",0);s.globalSat=get(p,"ColorGradeGlobalSat",0);
   s.shadowLum=get(p,"ColorGradeShadowLum",0);s.midtoneLum=get(p,"ColorGradeMidtoneLum",0);s.highlightLum=get(p,"ColorGradeHighlightLum",0);s.globalLum=get(p,"ColorGradeGlobalLum",0);
   s.balance=get(p,"SplitToningBalance",0);
   // Presets antigos (só Split Toning) abrem no Lightroom 10+ com mistura 100.
   boolean grade=false;for(String k:p.keySet())if(k.startsWith("ColorGrade"))grade=true;
   s.blending=get(p,"ColorGradeBlending",grade?50:100);
   s.shadowTint=get(p,"ShadowTint",0);s.redHue=get(p,"RedHue",0);s.redSat=get(p,"RedSaturation",0);s.greenHue=get(p,"GreenHue",0);s.greenSat=get(p,"GreenSaturation",0);s.blueHue=get(p,"BlueHue",0);s.blueSat=get(p,"BlueSaturation",0);
   s.sharpness=get(p,"Sharpness",0);s.sharpenRadius=get(p,"SharpenRadius",1);s.sharpenDetail=get(p,"SharpenDetail",25);s.sharpenMasking=get(p,"SharpenEdgeMasking",0);
   s.vignette=get(p,"PostCropVignetteAmount",0);s.vignetteMidpoint=get(p,"PostCropVignetteMidpoint",50);s.vignetteRoundness=get(p,"PostCropVignetteRoundness",0);
   s.vignetteFeather=get(p,"PostCropVignetteFeather",50);s.vignetteHighlights=get(p,"PostCropVignetteHighlightContrast",0);s.vignetteStyle=get(p,"PostCropVignetteStyle",1);
   s.grain=get(p,"GrainAmount",0);s.grainSize=get(p,"GrainSize",25);s.grainRoughness=get(p,"GrainFrequency",50);
   return s;
  }
  static double get(Map<String,Double> p,String k,double def){Double v=p.get(k);if(v==null||v.isNaN()||v.isInfinite())return def;double[] r=RANGES.get(k);return r==null?v:lim(v,r[0],r[1]);}
  static double[] curve(Map<String,Double> p,String name){
   List<Double> v=new ArrayList<>();while(p.containsKey(name+"."+v.size())&&p.containsKey(name+"."+(v.size()+1))&&v.size()<128){v.add(p.get(name+"."+v.size()));v.add(p.get(name+"."+v.size()));}
   if(v.size()<4)return null;boolean identity=true;double[] out=new double[v.size()];
   for(int i=0;i<out.length;i+=2){out[i]=lim(v.get(i)/255,0,1);out[i+1]=lim(v.get(i+1)/255,0,1);if(Math.abs(out[i]-out[i+1])>1e-9)identity=false;}
   return identity?null:out;
  }
  /** exposure, contrast, highlights, shadows, whites, blacks, temperature, tint, vibrance, saturation. */
  double[] basic(){return new double[]{exposure,contrast,highlights,shadows,whites,blacks,temperature,tint,vibrance,saturation};}
  void setBasic(double[] b){exposure=lim(b[0],-5,5);contrast=lim(b[1],-100,100);highlights=lim(b[2],-100,100);shadows=lim(b[3],-100,100);whites=lim(b[4],-100,100);blacks=lim(b[5],-100,100);temperature=lim(b[6],-100,100);tint=lim(b[7],-100,100);vibrance=lim(b[8],-100,100);saturation=lim(b[9],-100,100);}
 }

 // ---------------------------------------------------------------- Automático

 static double percentile(int[] hist,int count,double fraction){int total=0;for(int i=0;i<256;i++){total+=hist[i];if(total>=count*fraction)return i/255.;}return 1;}
 /** Valores sugeridos pelo automático, nas unidades dos controles (mesma ordem de {@link #BASIC}). */
 static double[] autoControls(int[] data){
  double[] out=new double[10];if(data==null||data.length==0)return out;
  int[] hist=new int[256];int stride=Math.max(1,data.length/65536),count=0,neutral=0;double saturation=0,rs=0,gs=0,bs=0;
  for(int i=0;i<data.length;i+=stride){int v=data[i];double r=((v>>16)&255)/255.,g=((v>>8)&255)/255.,b=(v&255)/255.,l=.2126*r+.7152*g+.0722*b,max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b)),sat=max==0?0:(max-min)/max;hist[(int)Math.round(l*255)]++;count++;saturation+=sat;if(l>.18&&l<.82&&sat<.18){neutral++;rs+=r;gs+=g;bs+=b;}}
  double lo=percentile(hist,count,.1),mid=percentile(hist,count,.5),hi=percentile(hist,count,.9);
  if(mid>.02&&mid<.38)out[0]=lim(Math.log(.40/mid)/Math.log(2)*.35,0,.45);else if(mid>.65&&mid<.98)out[0]=lim(Math.log(.60/mid)/Math.log(2)*.28,-.35,0);
  out[1]=hi-lo<.35&&hi-lo>.06?4:0;out[9]=saturation/count<.3?4:0;
  if(neutral>=Math.max(32,count*.03)){double avg=(rs+gs+bs)/3;double gr=lim(1+.35*(avg/rs-1),.97,1.03),gg=lim(1+.35*(avg/gs-1),.97,1.03),gb=lim(1+.35*(avg/bs-1),.97,1.03);out[6]=lim((gr-gb)/.32*100,-20,20);double rb=(gr+gb)/2;out[7]=lim((1-gg/Math.max(.001,rb))/.12*100,-20,20);}
  return out;
 }

 // ---------------------------------------------------------------- Funções de cor

 static final double[] LIN=new double[256];
 static final int TABLE=8192;
 static final double[] ENCODE=new double[TABLE+1],DECODE=new double[TABLE+1];
 static{for(int i=0;i<256;i++)LIN[i]=decodeExact(i/255.);for(int i=0;i<=TABLE;i++){ENCODE[i]=encodeExact(i/(double)TABLE);DECODE[i]=decodeExact(i/(double)TABLE);}}
 static double decodeExact(double v){return v<=.04045?v/12.92:Math.pow((v+.055)/1.055,2.4);}
 static double encodeExact(double x){return x<=.0031308?x*12.92:1.055*Math.pow(x,1/2.4)-.055;}
 /** Tabela com interpolação linear sobre [0,1]. */
 static double table(double[] t,double x){if(!(x>0))return t[0];if(x>=1)return t[t.length-1];double f=x*(t.length-1);int i=(int)f;return t[i]+(t[i+1]-t[i])*(f-i);}
 /** Linear → sRGB (0–1). */
 static double encode(double x){return table(ENCODE,x);}
 /** sRGB → linear (0–1). */
 static double decode(double v){return table(DECODE,v);}
 static double lim(double v,double lo,double hi){return v<lo?lo:v>hi?hi:v;}
 static double clamp01(double v){return v<0?0:v>1?1:v;}
 static double smooth(double a,double b,double x){if(x<=a)return 0;if(x>=b)return 1;double t=(x-a)/(b-a);return t*t*(3-2*t);}
 static double luma(double r,double g,double b){return .2126*r+.7152*g+.0722*b;}
 static final double LN2=Math.log(2);
 static double log2(double x){return Math.log(x)/LN2;}
 /** Limita suavemente {@code d} a ±limit (quase linear perto de zero). */
 static double soft(double d,double limit){double q=d/limit;return d/Math.sqrt(1+q*q);}
 static final double[] EXP2=new double[32*256+1];
 static{for(int i=0;i<EXP2.length;i++)EXP2[i]=Math.pow(2,i/256.-24);}
 /** 2^x por tabela (−24…8), igual nas duas plataformas. */
 static double exp2(double x){double f=(lim(x,-24,8)+24)*256;int i=(int)f;if(i>=EXP2.length-1)return EXP2[EXP2.length-1];return EXP2[i]+(EXP2[i+1]-EXP2[i])*(f-i);}
 static int pack(double r,double g,double b){return 0xff000000|((int)Math.round(clamp01(r)*255)<<16)|((int)Math.round(clamp01(g)*255)<<8)|(int)Math.round(clamp01(b)*255);}
 /** Matiz HSV (0–360). */
 static double hue(double r,double g,double b,double max,double min){double d=max-min;if(d<=0)return 0;double h=max==r?(g-b)/d:max==g?(b-r)/d+2:(r-g)/d+4;h*=60;return h<0?h+360:h;}
 /** Cor HSV de saturação e valor máximos. */
 static double[] hueColor(double h){h=((h%360)+360)%360/60;double x=1-Math.abs(h%2-1);int s=(int)h;switch(s){case 0:return new double[]{1,x,0};case 1:return new double[]{x,1,0};case 2:return new double[]{0,1,x};case 3:return new double[]{0,x,1};case 4:return new double[]{x,0,1};default:return new double[]{1,0,x};}}
 /** Direção de cor neutra em luminância para tingir (color grading, tom de sombra). */
 static double[] tint(double hue,double amount){double[] c=hueColor(hue);double y=luma(c[0],c[1],c[2]);return new double[]{(c[0]-y)*amount,(c[1]-y)*amount,(c[2]-y)*amount};}
 /**
  * Encaixa a cor no intervalo 0–1 reduzindo a saturação e mantendo a luminância {@code y},
  * como o Lightroom faz ao estourar canais. Atua em {@code c} = {r,g,b}.
  */
 static void fit(double[] c,double y){
  double max=Math.max(c[0],Math.max(c[1],c[2])),min=Math.min(c[0],Math.min(c[1],c[2]));if(max<=1&&min>=0)return;
  if(y>=1){c[0]=c[1]=c[2]=1;return;}if(y<=0){c[0]=c[1]=c[2]=0;return;}
  double t=1;if(max>1)t=Math.min(t,(1-y)/(max-y));if(min<0)t=Math.min(t,y/(y-min));
  for(int i=0;i<3;i++)c[i]=clamp01(y+(c[i]-y)*t);
 }
 /** Gira a cor em torno do eixo neutro (sentido vermelho → amarelo → verde). */
 static void rotate(double[] c,double degrees){
  double mean=(c[0]+c[1]+c[2])/3,r=c[0]-mean,g=c[1]-mean,b=c[2]-mean,a=Math.toRadians(degrees),ca=Math.cos(a),sa=Math.sin(a)/Math.sqrt(3);
  c[0]=mean+r*ca+(b-g)*sa;c[1]=mean+g*ca+(r-b)*sa;c[2]=mean+b*ca+(g-r)*sa;
 }
 /** Faixa HSL de uma matiz e peso da faixa seguinte (cada cor tem um platô em volta do centro). */
 static int band(double h){for(int k=0;k<7;k++)if(h<CENTERS[k+1])return k;return 7;}
 static double bandMix(double h,int k){double c0=CENTERS[k],c1=k==7?360:CENTERS[k+1];return smooth(.2,.8,(h-c0)/(c1-c0));}

 /** Matriz de calibração: gira e satura os primários e mantém o branco neutro. */
 static double[] calibration(Settings s){
  if(s.redHue==0&&s.redSat==0&&s.greenHue==0&&s.greenSat==0&&s.blueHue==0&&s.blueSat==0)return null;
  double[] hues={s.redHue,s.greenHue,s.blueHue},sats={s.redSat,s.greenSat,s.blueSat},m=new double[9],c=new double[3];
  for(int j=0;j<3;j++){c[0]=c[1]=c[2]=0;c[j]=1;rotate(c,hues[j]*.3);double f=Math.max(0,1+sats[j]/100*.75),mean=1/3.;for(int i=0;i<3;i++)m[i*3+j]=mean+(c[i]-mean)*f;}
  for(int i=0;i<3;i++){double sum=m[i*3]+m[i*3+1]+m[i*3+2];for(int j=0;j<3;j++)m[i*3+j]/=sum;}
  return m;
 }
 /** Curva de tons global (contraste, brancos, pretos) sobre a luminância em sRGB. */
 static double[] toneTable(Settings s){
  if(s.contrast==0&&s.whites==0&&s.blacks==0)return null;
  double c=s.contrast/100,w=s.whites/100,k=s.blacks/100,g=c>=0?1+.8*c:1/(1-.8*c),p=.5;double[] t=new double[1025];
  for(int i=0;i<=1024;i++){double l=i/1024.;
   if(c!=0)l=l<p?p*Math.pow(l/p,g):1-(1-p)*Math.pow((1-l)/(1-p),g);
   if(w!=0)l=l*(1+.25*w*l*l);
   if(k!=0){double u=1-l;u=u*(1-(k>0?.18:.30)*k*u*u);l=1-u;}
   t[i]=clamp01(l);}
  return t;
 }
 static double hump(double x,double a,double c,double b){if(x<=a||x>=b)return 0;return x<c?smooth(a,c,x):1-smooth(c,b,x);}
 /** Tabelas das curvas (paramétrica + pontos) por canal; null quando todas são neutras. */
 static double[][] curveTables(Settings s){
  boolean param=s.paramShadows!=0||s.paramDarks!=0||s.paramLights!=0||s.paramHighlights!=0;
  if(!param&&s.curves[0]==null&&s.curves[1]==null&&s.curves[2]==null&&s.curves[3]==null)return null;
  double s1=s.shadowSplit/100,s2=Math.max(s1,s.midtoneSplit/100),s3=Math.max(s2,s.highlightSplit/100),c0=s1/2,c1=(s1+s2)/2,c2=(s2+s3)/2,c3=(s3+1)/2;
  double[] master=new double[1025];double prev=0;
  for(int i=0;i<=1024;i++){double x=i/1024.,y=x;
   if(param)y=x+.12*(s.paramShadows/100*hump(x,0,c0,c1)+s.paramDarks/100*hump(x,c0,c1,c2)+s.paramLights/100*hump(x,c1,c2,c3)+s.paramHighlights/100*hump(x,c2,c3,1));
   y=clamp01(y);if(y<prev)y=prev;prev=y;
   if(s.curves[0]!=null)y=spline(s.curves[0],y);
   master[i]=y;}
  double[][] t=new double[3][1025];
  for(int ch=0;ch<3;ch++)for(int i=0;i<=1024;i++)t[ch][i]=s.curves[ch+1]==null?master[i]:spline(s.curves[ch+1],master[i]);
  return t;
 }
 /** Spline cúbica natural pelos pontos (como as curvas do Lightroom/Photoshop), constante fora deles. */
 static double spline(double[] pts,double x){
  int n=pts.length/2;double[] xs=new double[n],ys=new double[n];int m=0;
  for(int i=0;i<n;i++){double px=pts[2*i],py=pts[2*i+1];if(m>0&&px<=xs[m-1])continue;xs[m]=px;ys[m]=py;m++;}
  if(m==0)return x;if(m==1||x<=xs[0])return ys[0];if(x>=xs[m-1])return ys[m-1];
  double[] d2=new double[m],u=new double[m];
  for(int i=1;i<m-1;i++){double sig=(xs[i]-xs[i-1])/(xs[i+1]-xs[i-1]),p=sig*d2[i-1]+2;d2[i]=(sig-1)/p;double dd=(ys[i+1]-ys[i])/(xs[i+1]-xs[i])-(ys[i]-ys[i-1])/(xs[i]-xs[i-1]);u[i]=(6*dd/(xs[i+1]-xs[i-1])-sig*u[i-1])/p;}
  d2[m-1]=0;for(int i=m-2;i>=0;i--)d2[i]=d2[i]*d2[i+1]+u[i];
  int k=0;while(k<m-2&&x>xs[k+1])k++;
  double h=xs[k+1]-xs[k],a=(xs[k+1]-x)/h,b=(x-xs[k])/h;
  return clamp01(a*ys[k]+b*ys[k+1]+((a*a*a-a)*d2[k]+(b*b*b-b)*d2[k+1])*h*h/6);
 }
 static float[] blur(float[] src,int w,int h,double sigma){
  if(sigma<.3)return src.clone();int r=(int)Math.ceil(sigma*3);double[] k=new double[2*r+1];double sum=0;
  for(int i=-r;i<=r;i++){k[i+r]=Math.exp(-i*i/(2*sigma*sigma));sum+=k[i+r];}for(int i=0;i<k.length;i++)k[i]/=sum;
  float[] tmp=new float[w*h],out=new float[w*h];
  for(int y=0;y<h;y++){int row=y*w;for(int x=0;x<w;x++){double a=0;for(int i=-r;i<=r;i++){int xx=x+i;xx=xx<0?0:xx>=w?w-1:xx;a+=k[i+r]*src[row+xx];}tmp[row+x]=(float)a;}}
  for(int y=0;y<h;y++)for(int x=0;x<w;x++){double a=0;for(int i=-r;i<=r;i++){int yy=y+i;yy=yy<0?0:yy>=h?h-1:yy;a+=k[i+r]*tmp[yy*w+x];}out[y*w+x]=(float)a;}
  return out;
 }
 /** Ruído de valor determinístico (−1…1) para a granulação. */
 static double noise(double fx,double fy,int seed){
  double ix=Math.floor(fx),iy=Math.floor(fy),tx=fx-ix,ty=fy-iy;int x=(int)ix,y=(int)iy;tx=tx*tx*(3-2*tx);ty=ty*ty*(3-2*ty);
  double a=hash(x,y,seed),b=hash(x+1,y,seed),c=hash(x,y+1,seed),d=hash(x+1,y+1,seed);
  return (a+(b-a)*tx)*(1-ty)+(c+(d-c)*tx)*ty;
 }
 static double hash(int x,int y,int seed){int h=x*0x27d4eb2d^y*0x165667b1^seed;h^=h>>>15;h*=0x85ebca6b;h^=h>>>13;h*=0xc2b2ae35;h^=h>>>16;return (h&0xffffff)/16777215.*2-1;}

 // ---------------------------------------------------------------- Imagem de origem

 /**
  * Campo suave em grade reduzida, amostrado com interpolação bilinear. Com {@code scale}
  * é um filtro guiado (valor = A·I + B): segue as bordas fortes da própria foto, então
  * realces/sombras e claridade não criam halos, como no Lightroom.
  */
 static final class Field{final float[] offset,scale;final int gw,gh,cell;Field(float[] offset,float[] scale,int gw,int gh,int cell){this.offset=offset;this.scale=scale;this.gw=gw;this.gh=gh;this.cell=cell;}
  double at(int x,int y,double guide){
   if(cell==1){int i=y*gw+x;return scale==null?offset[i]:scale[i]*guide+offset[i];}
   double gx=lim((x+.5)/cell-.5,0,gw-1),gy=lim((y+.5)/cell-.5,0,gh-1);int x0=(int)gx,y0=(int)gy,x1=Math.min(gw-1,x0+1),y1=Math.min(gh-1,y0+1);double fx=gx-x0,fy=gy-y0;
   double o=bilinear(offset,x0,y0,x1,y1,fx,fy);return scale==null?o:bilinear(scale,x0,y0,x1,y1,fx,fy)*guide+o;}
  double bilinear(float[] d,int x0,int y0,int x1,int y1,double fx,double fy){return (d[y0*gw+x0]*(1-fx)+d[y0*gw+x1]*fx)*(1-fy)+(d[y1*gw+x0]*(1-fx)+d[y1*gw+x1]*fx)*fy;}}

 /**
  * Foto decodificada (ARGB) com os campos que os ajustes locais precisam, calculados uma vez
  * e reaproveitados a cada renderização (prévia ao vivo do editor).
  */
 static final class Source{
  static final int TONE=0,CLARITY=1,TEXTURE=2,HAZE=3;
  final int w,h;final int[] px;private float[] logY;private final Field[] fields=new Field[4];private double[] auto;
  Source(int[] px,int w,int h){if(w<=0||h<=0||px.length!=w*h)throw new IllegalArgumentException("Dimensões inconsistentes");this.px=px;this.w=w;this.h=h;}
  synchronized double[] autoControls(){if(auto==null)auto=Develop.autoControls(px);return auto.clone();}
  /** log2 da luminância linear de cada pixel. */
  synchronized float[] logY(){
   if(logY==null){logY=new float[w*h];for(int i=0;i<px.length;i++){int v=px[i];logY[i]=(float)log2(Math.max(1/4096.,luma(LIN[(v>>16)&255],LIN[(v>>8)&255],LIN[v&255])));}}
   return logY;
  }
  /** Raios relativos ao maior lado, para a prévia e a exportação ficarem iguais. */
  double sigma(int kind){double edge=Math.max(w,h);return kind==TONE?edge/40:kind==CLARITY?edge/120:kind==TEXTURE?Math.max(.8,edge/600):edge/50;}
  synchronized Field field(int kind){
   if(fields[kind]!=null)return fields[kind];
   double sigma=sigma(kind),eps=kind==TONE?.25:kind==CLARITY?.12:0;int cell=Math.max(1,(int)(sigma/3)),gw=(w+cell-1)/cell,gh=(h+cell-1)/cell,cells=gw*gh;
   float[] mean=new float[cells],square=new float[cells];int[] count=new int[cells];float[] log=kind==HAZE?null:logY();
   for(int y=0;y<h;y++)for(int x=0;x<w;x++){int i=y*w+x,g=(y/cell)*gw+x/cell;double v;if(log!=null)v=log[i];else{int p=px[i];v=Math.min(LIN[(p>>16)&255],Math.min(LIN[(p>>8)&255],LIN[p&255]));}mean[g]+=v;square[g]+=v*v;count[g]++;}
   for(int i=0;i<cells;i++){mean[i]/=count[i];square[i]/=count[i];}
   double s=sigma/cell;mean=blur(mean,gw,gh,s);
   if(eps<=0)return fields[kind]=new Field(mean,null,gw,gh,cell);
   // Filtro guiado: A = var/(var+eps), B = média·(1−A), ambos suavizados.
   square=blur(square,gw,gh,s);float[] a=new float[cells],b=new float[cells];
   for(int i=0;i<cells;i++){double m=mean[i],var=Math.max(0,square[i]-m*m),k=var/(var+eps);a[i]=(float)k;b[i]=(float)(m-k*m);}
   return fields[kind]=new Field(blur(b,gw,gh,s),blur(a,gw,gh,s),gw,gh,cell);
  }

  /** Renderiza a foto com os ajustes; devolve pixels ARGB novos. As linhas são divididas entre os núcleos. */
  int[] render(Settings s){
   final int[] out=new int[w*h];final Plan plan=new Plan(this,s);
   if(plan.identity){System.arraycopy(px,0,out,0,out.length);return out;}
   parallel(h,(y0,y1)->plan.rows(y0,y1,out));
   if(plan.sharpen){
    final float[] l=new float[w*h];for(int i=0;i<l.length;i++){int v=out[i];l[i]=(float)(luma((v>>16)&255,(v>>8)&255,v&255)/255);}
    final float[] bl=blur(l,w,h,Math.max(.5,s.sharpenRadius*.8));
    parallel(h,(y0,y1)->plan.sharpenRows(y0,y1,out,l,bl));
   }
   return out;
  }
 }

 /** Constantes de uma renderização e o pipeline por pixel. */
 static final class Plan{
  final Source src;final Settings s;final int w;
  final double[] cal,tone,tS,tM,tH,tG,tShadow;final double[][] curves;
  final double gr,gg,gb,expo,knee=.6,rollPower,dehaze,sh,hl,clarity,texture,sat,vib,mid,width,amount,limit,mask;
  final boolean local,tonal,hsl,mixing,grade,color,sharpen,identity;
  final float[] logY;final Field toneF,clarityF,textureF,hazeF;final Effects fx;
  Plan(Source src,Settings s){
   this.src=src;this.s=s;w=src.w;
   cal=calibration(s);
   // Temperatura/matiz incrementais (JPEG): ganhos por canal que preservam o brilho.
   double t=s.temperature/100,ti=s.tint/100,e=Math.pow(2,s.exposure),r=e,g=e,b=e;
   if(t!=0||ti!=0){double r0=Math.pow(2,.5*t+.12*ti),g0=Math.pow(2,.04*t-.42*ti),b0=Math.pow(2,-.76*t+.12*ti),norm=luma(r0,g0,b0);r=r0*e/norm;g=g0*e/norm;b=b0*e/norm;}
   expo=e;gr=r;gg=g;gb=b;rollPower=e>1?(e-knee)/(1-knee):1;
   dehaze=s.dehaze/100;sh=s.shadows/100;hl=s.highlights/100;clarity=s.clarity/100;texture=s.texture/100;
   local=sh!=0||hl!=0||clarity!=0||texture!=0;
   tone=toneTable(s);curves=curveTables(s);
   tonal=rollPower>1||local||tone!=null;
   sat=s.saturation/100;vib=s.vibrance/100;
   boolean anyHsl=false,anyMix=false;for(int i=0;i<8;i++){if(s.hue[i]!=0||s.sat[i]!=0||s.lum[i]!=0)anyHsl=true;if(s.mix[i]!=0)anyMix=true;}hsl=anyHsl;mixing=anyMix;
   grade=s.shadowSat!=0||s.midtoneSat!=0||s.highlightSat!=0||s.globalSat!=0||s.shadowLum!=0||s.midtoneLum!=0||s.highlightLum!=0||s.globalLum!=0||s.shadowTint!=0;
   tS=tint(s.shadowHue,s.shadowSat/100*.3);tM=tint(s.midtoneHue,s.midtoneSat/100*.3);tH=tint(s.highlightHue,s.highlightSat/100*.3);tG=tint(s.globalHue,s.globalSat/100*.3);
   mid=.5-.3*s.balance/100;width=.1+.6*s.blending/100;
   tShadow=tint(s.shadowTint>0?300:120,Math.abs(s.shadowTint)/100*.1);
   color=sat!=0||vib!=0||hsl||s.grayscale||grade;
   sharpen=s.sharpness>0;amount=s.sharpness/100*1.2;limit=.03+.3*s.sharpenDetail/100;mask=s.sharpenMasking/100*.05;
   fx=new Effects(s,src.w,src.h);
   identity=cal==null&&gr==1&&gg==1&&gb==1&&dehaze==0&&!tonal&&curves==null&&!color&&!sharpen&&!fx.active;
   logY=local&&!identity?src.logY():null;
   toneF=(sh!=0||hl!=0)&&!identity?src.field(Source.TONE):null;clarityF=clarity!=0&&!identity?src.field(Source.CLARITY):null;
   textureF=texture!=0&&!identity?src.field(Source.TEXTURE):null;hazeF=dehaze>0&&!identity?src.field(Source.HAZE):null;
  }

  void rows(int y0,int y1,int[] out){
   double[] c=new double[3];int[] px=src.px;
   for(int y=y0;y<y1;y++)for(int x=0;x<w;x++){
    int i=y*w+x,v=px[i];
    double r=LIN[(v>>16)&255],g=LIN[(v>>8)&255],b=LIN[v&255];
    // Perfil (calibração), balanço de branco e exposição em luz linear.
    if(cal!=null){double rr=cal[0]*r+cal[1]*g+cal[2]*b,g2=cal[3]*r+cal[4]*g+cal[5]*b,b2=cal[6]*r+cal[7]*g+cal[8]*b;r=Math.max(0,rr);g=Math.max(0,g2);b=Math.max(0,b2);}
    r*=gr;g*=gg;b*=gb;
    // Dehaze: modelo de névoa atmosférica com canal escuro borrado.
    if(dehaze>0){double dark=Math.min(Math.min(r,Math.min(g,b)),hazeF.at(x,y,0)*expo),tr=Math.max(.15,1-.85*dehaze*Math.min(1,dark)),a=1-tr;r=(r-a)/tr;g=(g-a)/tr;b=(b-a)/tr;}
    else if(dehaze<0){double k=-dehaze*.45;r+=(.85-r)*k;g+=(.85-g)*k;b+=(.85-b)*k;}
    if(tonal){
     double lum=luma(r,g,b),yt=lum;
     // Exposição positiva comprime os realces suavemente em vez de estourar.
     if(rollPower>1&&yt>knee){double q=(yt-knee)/(expo-knee);yt=q>=1?1:knee+(1-knee)*(1-Math.pow(1-q,rollPower));}
     if(local){
      double ly=log2(Math.max(yt,1e-6)),l0=logY[i],ev=0,lp=encode(Math.min(1,yt));
      if(toneF!=null){double base=ly-soft(l0-toneF.at(x,y,l0),1),bl=encode(Math.min(1,exp2(base)));
       if(sh!=0)ev+=sh*(sh>0?1.35:1)*(1-smooth(.2,.7,bl))*(.55+.45*smooth(0,.15,bl));
       if(hl!=0)ev+=hl*(hl<0?1.25:.9)*smooth(.4,.9,bl);}
      if(clarityF!=null)ev+=clarity*.65*soft(l0-clarityF.at(x,y,l0),1.5)*4*lp*(1-lp);
      if(textureF!=null)ev+=texture*.6*soft(l0-textureF.at(x,y,l0),1)*(1-smooth(.85,1,lp));
      if(ev!=0)yt*=exp2(ev);
     }
     if(tone!=null)yt=decode(table(tone,encode(Math.min(1,yt))));
     if(lum>1e-9){double k=yt/lum;r*=k;g*=k;b*=k;}else r=g=b=yt;
    }
    c[0]=r;c[1]=g;c[2]=b;fit(c,luma(r,g,b));
    double R=encode(c[0]),G=encode(c[1]),B=encode(c[2]);
    if(curves!=null){R=table(curves[0],R);G=table(curves[1],G);B=table(curves[2],B);}
    if(color){
     double yg=luma(R,G,B);
     if(sat!=0||vib!=0){double max=Math.max(R,Math.max(G,B)),min=Math.min(R,Math.min(G,B)),sv=max<=0?0:(max-min)/max,f=1+sat;
      if(vib>0){double hh=hue(R,G,B,max,min),z=(hh-25)/20,skin=Math.exp(-z*z);f*=1+vib*.9*(1-sv)*Math.sqrt(1-sv)*(1-.6*skin);}else if(vib<0)f*=1+vib*.8*(.3+.7*sv);
      f=Math.max(0,f);c[0]=yg+(R-yg)*f;c[1]=yg+(G-yg)*f;c[2]=yg+(B-yg)*f;fit(c,yg);R=c[0];G=c[1];B=c[2];}
     double max=Math.max(R,Math.max(G,B)),min=Math.min(R,Math.min(G,B)),chroma=max-min;
     if(s.grayscale){
      double m=0;if(mixing&&chroma>1e-6){double hh=hue(R,G,B,max,min);int k=band(hh),k2=(k+1)%8;double q=bandMix(hh,k);m=(s.mix[k]*(1-q)+s.mix[k2]*q)/100*smooth(0,.35,chroma);}
      R=G=B=clamp01(yg*exp2(m*.8));
     }else if(hsl&&chroma>1e-6){
      double hh=hue(R,G,B,max,min);int k=band(hh),k2=(k+1)%8;double q=bandMix(hh,k);
      double dh=(s.hue[k]*(1-q)+s.hue[k2]*q)*.3,ds=(s.sat[k]*(1-q)+s.sat[k2]*q)/100,dl=(s.lum[k]*(1-q)+s.lum[k2]*q)/100;
      c[0]=R;c[1]=G;c[2]=B;
      if(dh!=0)rotate(c,dh);
      if(ds!=0){double mean=(c[0]+c[1]+c[2])/3,f=Math.max(0,1+ds);for(int j=0;j<3;j++)c[j]=mean+(c[j]-mean)*f;}
      double fix=yg-luma(c[0],c[1],c[2]);for(int j=0;j<3;j++)c[j]+=fix;
      double target=yg;if(dl!=0){double f=exp2(dl*.6*smooth(0,.35,chroma));for(int j=0;j<3;j++)c[j]*=f;target=yg*f;}
      fit(c,target);R=c[0];G=c[1];B=c[2];
     }
     if(grade){
      double l=luma(R,G,B),ws=1-smooth(mid-width,mid,l),wh=smooth(mid,mid+width,l),wm=1-ws-wh,edge=Math.min(1,Math.min(l,1-l)*12);
      double dl=(s.shadowLum*ws+s.midtoneLum*wm+s.highlightLum*wh+s.globalLum)/100*.2,shadowW=(1-l)*(1-l);
      c[0]=R+(tS[0]*ws+tM[0]*wm+tH[0]*wh+tG[0])*edge+tShadow[0]*shadowW+dl;
      c[1]=G+(tS[1]*ws+tM[1]*wm+tH[1]*wh+tG[1])*edge+tShadow[1]*shadowW+dl;
      c[2]=B+(tS[2]*ws+tM[2]*wm+tH[2]*wh+tG[2])*edge+tShadow[2]*shadowW+dl;
      fit(c,clamp01(l+dl));R=c[0];G=c[1];B=c[2];
     }
    }
    out[i]=sharpen?pack(R,G,B):fx.apply(R,G,B,x,y);
   }
  }

  /** Nitidez (máscara de nitidez na luminância), depois vinheta e granulação. */
  void sharpenRows(int y0,int y1,int[] out,float[] l,float[] bl){
   for(int y=y0;y<y1;y++)for(int x=0;x<w;x++){int i=y*w+x,v=out[i];double d=l[i]-bl[i],weight=mask>0?smooth(mask*.5,mask*1.5,Math.abs(d)):1,add=amount*lim(d,-limit,limit)*weight;
    out[i]=fx.apply(((v>>16)&255)/255.+add,((v>>8)&255)/255.+add,(v&255)/255.+add,x,y);}
  }
 }

 interface Rows{void run(int y0,int y1);}
 static final int THREADS=Math.max(1,Math.min(8,Runtime.getRuntime().availableProcessors()));
 static java.util.concurrent.ExecutorService pool;
 static synchronized java.util.concurrent.ExecutorService pool(){
  if(pool==null)pool=java.util.concurrent.Executors.newFixedThreadPool(THREADS,r->{Thread t=new Thread(r,"lumo-develop");t.setDaemon(true);t.setPriority(Thread.NORM_PRIORITY-1);return t;});
  return pool;
 }
 /** Executa faixas de linhas em paralelo e espera todas. */
 static void parallel(int h,Rows task){
  int chunks=Math.min(h,THREADS*4);if(chunks<=1||THREADS==1){task.run(0,h);return;}
  java.util.List<java.util.concurrent.Future<?>> jobs=new java.util.ArrayList<>();
  for(int k=0;k<chunks;k++){final int y0=h*k/chunks,y1=h*(k+1)/chunks;jobs.add(pool().submit(()->task.run(y0,y1)));}
  try{for(java.util.concurrent.Future<?> f:jobs)f.get();}
  catch(java.util.concurrent.ExecutionException e){Throwable c=e.getCause();if(c instanceof RuntimeException)throw (RuntimeException)c;if(c instanceof Error)throw (Error)c;throw new RuntimeException(c);}
  catch(InterruptedException e){Thread.currentThread().interrupt();for(java.util.concurrent.Future<?> f:jobs)f.cancel(true);throw new RuntimeException(e);}
 }

 /** Vinheta pós-corte e granulação. */
 static final class Effects{
  final boolean active,vignette,grain;final double amount,inner,width,roundness,power,highlights,cx,cy,hw,hh,hc,grainAmount,cell,rough;final int style;
  Effects(Settings s,int w,int h){
   amount=s.vignette/100;vignette=amount!=0;style=(int)Math.round(s.vignetteStyle);
   inner=.2+s.vignetteMidpoint/100*.95;width=.08+s.vignetteFeather/100*1.1;roundness=s.vignetteRoundness/100;power=roundness<0?2-roundness*6:2;highlights=s.vignetteHighlights/100;
   cx=w/2.;cy=h/2.;hw=w/2.;hh=h/2.;hc=Math.sqrt((double)w*h)/2;
   grainAmount=s.grain/100*.1;grain=grainAmount>0;cell=Math.max(1,(1+s.grainSize/100*3)*Math.max(w,h)/2560.);rough=s.grainRoughness/100;
   active=vignette||grain;
  }
  int apply(double r,double g,double b,int x,int y){
   if(vignette){
    double u=(x+.5-cx)/hw,v=(y+.5-cy)/hh;if(roundness>0){u+=((x+.5-cx)/hc-u)*roundness;v+=((y+.5-cy)/hc-v)*roundness;}
    double d=power==2?Math.sqrt(u*u+v*v):Math.pow(Math.pow(Math.abs(u),power)+Math.pow(Math.abs(v),power),1/power),m=smooth(inner,inner+width,d);
    if(m>0){
     if(amount<0){
      if(style==3){double f=1+amount*m*.9;r*=f;g*=f;b*=f;}
      else{double l=clamp01(luma(r,g,b)),f=exp2(amount*m*.75);f=1-(1-f)*(1-highlights*smooth(.5,1,l));r*=f;g*=f;b*=f;}
     }else{double k=amount*m*(style==3?.9:.75);r+=(1-r)*k;g+=(1-g)*k;b+=(1-b)*k;}
    }
   }
   if(grain){
    double l=clamp01(luma(r,g,b)),nz=noise(x/cell,y/cell,0x5eed)*(1-.5*rough)+noise(x/(cell*.5),y/(cell*.5),0x1ced)*.5*rough,a=grainAmount*nz*(.35+.65*4*l*(1-l));
    r+=a;g+=a;b+=a;
   }
   return pack(r,g,b);
  }
 }
 private Develop(){}
}
