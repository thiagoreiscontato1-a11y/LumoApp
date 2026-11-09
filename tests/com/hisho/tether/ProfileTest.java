package com.hisho.tether;
import java.io.*;import java.util.*;import java.util.zip.*;

/** Perfis criativos (tabelas do Lightroom), leitura do crs:Look no XMP e recorte/endireitar. Rodado por tests/profile_crop_test.py. */
public class ProfileTest{
 static int checks=0;
 static void check(boolean b,String what){checks++;if(!b)throw new AssertionError(what);}
 static int rgb(int r,int g,int b){return 0xff000000|r<<16|g<<8|b;}
 static int r(int v){return (v>>16)&255;}static int g(int v){return (v>>8)&255;}static int b(int v){return v&255;}
 static boolean near(int a,int e,int tol){return Math.abs(r(a)-r(e))<=tol&&Math.abs(g(a)-g(e))<=tol&&Math.abs(b(a)-b(e))<=tol;}

 // Codificação do DNG SDK (dng_big_table::EncodeAsString), para montar tabelas de teste.
 static final String ENC="0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.-:+=^!/*?`'|()[]{}@%$#";
 static String encode(byte[] data){
  StringBuilder sb=new StringBuilder();byte[] d=Arrays.copyOf(data,data.length+3);int n=data.length;int i=0;
  while(n>0){long x0=(d[i]&255L)|(d[i+1]&255L)<<8|(d[i+2]&255L)<<16|(d[i+3]&255L)<<24;i+=4;long x1=x0/85;sb.append(ENC.charAt((int)(x0-x1*85)));long x2=x1/85;sb.append(ENC.charAt((int)(x1-x2*85)));if(--n==0)break;long x3=x2/85;sb.append(ENC.charAt((int)(x2-x3*85)));if(--n==0)break;long x4=x3/85;sb.append(ENC.charAt((int)(x3-x4*85)));if(--n==0)break;sb.append(ENC.charAt((int)x4));n--;}
  return sb.toString();
 }
 static String table(byte[] raw){
  Deflater df=new Deflater();df.setInput(raw);df.finish();ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] buf=new byte[8192];while(!df.finished())o.write(buf,0,df.deflate(buf));df.end();
  byte[] z=o.toByteArray();byte[] block=new byte[z.length+4];block[0]=(byte)raw.length;block[1]=(byte)(raw.length>>8);block[2]=(byte)(raw.length>>16);block[3]=(byte)(raw.length>>24);System.arraycopy(z,0,block,4,z.length);return encode(block);
 }
 static final class Out{final ByteArrayOutputStream b=new ByteArrayOutputStream();void u32(long v){for(int k=0;k<4;k++)b.write((int)(v>>(8*k)));}void u16(int v){b.write(v);b.write(v>>8);}void f32(float v){u32(Float.floatToIntBits(v)&0xffffffffL);}void f64(double v){long x=Double.doubleToLongBits(v);u32(x&0xffffffffL);u32(x>>>32);}}
 interface Fn{double[] at(double r,double g,double b);}
 /** Tabela 3D (divisions³) com a função pedida, em deltas sobre a identidade, como o SDK grava. */
 static String rgbTable(int div,int primaries,int gamma,Fn f){
  Out o=new Out();o.u32(1);o.u32(1);o.u32(3);o.u32(div);int[] nop=new int[div];for(int i=0;i<div;i++)nop[i]=(int)(((long)i*0xffff+(div>>1))/(div-1));
  for(int ri=0;ri<div;ri++)for(int gi=0;gi<div;gi++)for(int bi=0;bi<div;bi++){double[] v=f.at(ri/(div-1.0),gi/(div-1.0),bi/(div-1.0));o.u16((int)Math.round(v[0]*65535)-nop[ri]);o.u16((int)Math.round(v[1]*65535)-nop[gi]);o.u16((int)Math.round(v[2]*65535)-nop[bi]);}
  o.u32(primaries);o.u32(gamma);o.u32(0);o.f64(0);o.f64(2);return table(o.b.toByteArray());
 }
 static String lookTable(int hue,int sat,int val,float hueShift,float satScale,float valScale){
  Out o=new Out();o.u32(0);o.u32(2);o.u32(hue);o.u32(sat);o.u32(val);for(int i=0;i<hue*sat*val;i++){o.f32(hueShift);o.f32(satScale);o.f32(valScale);}o.u32(1);o.f64(0);o.f64(2);return table(o.b.toByteArray());
 }
 static int[] apply(String text,double amount,int... px)throws Exception{
  Object t=CreativeProfile.decode(text);int[] out=px.clone();
  CreativeProfile.Profile p=t instanceof CreativeProfile.RgbTable?new CreativeProfile.Profile("t"+text.hashCode()+"/"+amount,(CreativeProfile.RgbTable)t,amount,null,0,false):new CreativeProfile.Profile("l"+text.hashCode()+"/"+amount,null,0,(CreativeProfile.LookTable)t,amount,false);
  CreativeProfile.apply(out,out.length,1,p);return out;
 }

 public static void main(String[] args)throws Exception{
  // Base 85 do SDK: ida e volta com sobras de 1 a 3 bytes.
  for(int n=1;n<=13;n++){byte[] d=new byte[n];for(int i=0;i<n;i++)d[i]=(byte)(i*37+n);check(Arrays.equals(CreativeProfile.ascii(encode(d)),d),"base85 "+n);}
  check(Arrays.equals(CreativeProfile.ascii("  "+encode(new byte[]{1,2,3,4,5})+"\n"),new byte[]{1,2,3,4,5}),"base85 ignora espaços");

  int[] px={rgb(200,100,50),rgb(0,0,0),rgb(255,255,255),rgb(30,160,90),rgb(128,128,128)};
  // Identidade em sRGB e em Adobe RGB/gama 2.2 (ida e volta de espaço de cor): foto igual.
  for(int[] space:new int[][]{{0,1},{1,3},{2,0},{3,1},{4,4}}){String id=rgbTable(17,space[0],space[1],(r,g,b)->new double[]{r,g,b});int[] o=apply(id,1,px);for(int i=0;i<px.length;i++)check(near(o[i],px[i],2),"identidade espaço "+space[0]+"/"+space[1]+" pixel "+i+": "+Integer.toHexString(o[i]));}
  // Troca de canais (função linear: a interpolação tetraédrica é exata).
  String swap=rgbTable(9,0,1,(r,g,b)->new double[]{g,b,r});
  int[] o=apply(swap,1,px);check(near(o[0],rgb(100,50,200),2),"troca de canais "+Integer.toHexString(o[0]));check(near(o[3],rgb(160,90,30),2),"troca 2");
  int[] half=apply(swap,.5,px);check(near(half[0],rgb(150,75,125),3),"intensidade 50% "+Integer.toHexString(half[0]));
  int[] none=apply(swap,0,px);check(Arrays.equals(none,px),"intensidade 0 não mexe");
  // Tabela que escurece: preto continua preto, branco vira cinza.
  String dark=rgbTable(5,0,1,(r,g,b)->new double[]{r*.5,g*.5,b*.5});o=apply(dark,1,px);check(near(o[2],rgb(128,128,128),2),"escurecer branco");check(near(o[1],rgb(0,0,0),1),"preto");

  // Tabela de visual (matiz/saturação/valor): identidade não mexe; saturação 0 deixa cinza.
  String lid=lookTable(6,4,1,0,1,1);o=apply(lid,1,px);for(int i=0;i<px.length;i++)check(near(o[i],px[i],2),"visual identidade "+i+" "+Integer.toHexString(o[i]));
  String gray=lookTable(6,4,1,0,0,1);o=apply(gray,1,px);check(r(o[0])==g(o[0])&&g(o[0])==b(o[0]),"visual sem saturação "+Integer.toHexString(o[0]));
  String rot=lookTable(36,8,8,120,1,1);o=apply(rot,1,rgb(220,30,30));check(g(o[0])>r(o[0])&&g(o[0])>b(o[0]),"matiz +120° leva vermelho ao verde "+Integer.toHexString(o[0]));
  check(CreativeProfile.decode(lookTable(36,8,8,0,1,1)) instanceof CreativeProfile.LookTable,"tipo da tabela");

  // Texto inválido não derruba: vira IOException.
  try{CreativeProfile.decode("@@@@@");check(false,"lixo deveria falhar");}catch(IOException e){check(true,"lixo falha");}

  // Armazenamento pelo MD5 e perfil resolvido com intensidade do slider.
  String md5="0123456789ABCDEF0123456789ABCDEF";CreativeProfile.store(md5,swap);check(swap.equals(CreativeProfile.load(md5.toLowerCase(Locale.ROOT))),"guardado pelo MD5");
  CreativeProfile.Profile p=CreativeProfile.resolve(md5,.5,null,100,false);check(p!=null&&Math.abs(p.rgbAmount-.5)<1e-9,"intensidade do RGBTableAmount");
  p=CreativeProfile.resolve(md5,.5,null,200,false);check(Math.abs(p.rgbAmount-1)<1e-9,"slider 200% dobra");
  p=CreativeProfile.resolve(md5,1,null,0,false);check(!p.active(),"slider 0 desliga");
  check(CreativeProfile.resolve("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",1,null,100,false)==null,"tabela ausente = sem perfil");
  try{CreativeProfile.store("nada",swap);check(false,"MD5 inválido");}catch(IOException e){check(true,"MD5 inválido");}

  // XMP: preset com crs:Look (a curva do perfil não vaza para o preset) e perfil com tabela no topo.
  String preset="<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'><rdf:Description xmlns:crs='http://ns.adobe.com/camera-raw-settings/1.0/' crs:Exposure2012='+0.30' crs:Table_"+md5+"='@T@'>"
   +"<crs:Look><rdf:Description crs:Name='Filme 01' crs:Amount='0.8'><crs:Parameters><rdf:Description crs:RGBTable='"+md5+"' crs:RGBTableAmount='0.6' crs:ConvertToGrayscale='False' crs:Exposure2012='+2'>"
   +"<crs:ToneCurvePV2012><rdf:Seq><rdf:li>0, 40</rdf:li><rdf:li>255, 200</rdf:li></rdf:Seq></crs:ToneCurvePV2012></rdf:Description></crs:Parameters></rdf:Description></crs:Look>"
   +"<crs:Contrast2012>+15</crs:Contrast2012></rdf:Description></rdf:RDF></x:xmpmeta>";preset=preset.replace('\'','"').replace("@T@",swap);
  Map<String,String> look=new HashMap<>();Map<String,Double> m=Develop.parse(new DevelopTest.StaxParser(preset),look);
  check(m.get("Exposure2012")==.3,"exposição do preset, não a do perfil: "+m.get("Exposure2012"));check(m.get("Contrast2012")==15,"elemento depois do Look é lido");
  check(!m.containsKey("ToneCurvePV2012.0"),"curva do perfil não vaza");check(Math.abs(m.get("LookAmount")-80)<1e-9,"intensidade do Look");
  check(md5.equals(look.get("RGBTable"))&&"0.6".equals(look.get("RGBTableAmount"))&&"Filme 01".equals(look.get("Name"))&&swap.equals(look.get("Table_"+md5)),"referências do Look");
  String profile="<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'><rdf:Description xmlns:crs='http://ns.adobe.com/camera-raw-settings/1.0/' crs:PresetType='Look' crs:RGBTable='"+md5+"' crs:RGBTableAmount='0.75' crs:Table_"+md5+"='@T@' crs:Saturation='-10'/></rdf:RDF></x:xmpmeta>";profile=profile.replace('\'','"').replace("@T@",swap);
  look.clear();m=Develop.parse(new DevelopTest.StaxParser(profile),look);
  check(m.get("Saturation")==-10&&m.get("LookAmount")==100,"perfil no topo: ajustes e intensidade");check("0.75".equals(look.get("RGBTableAmount"))&&look.containsKey("Table_"+md5),"perfil no topo: tabela");
  m=Develop.parse(new DevelopTest.StaxParser(profile));check(!m.containsKey("RGBTable"),"sem mapa de perfil, só os ajustes");
  check(Develop.value("LookAmount",new HashMap<>())==100,"intensidade padrão 100");
  Map<String,Double> grade=new HashMap<>();check(Develop.value("ColorGradeBlending",grade)==100,"mistura antiga 100");grade.put("ColorGradeMidtoneHue",20.0);check(Develop.value("ColorGradeBlending",grade)==50,"mistura 10+ 50");

  // Recorte e endireitar.
  int w=60,h=40;int[] img=new int[w*h];for(int y=0;y<h;y++)for(int x=0;x<w;x++)img[y*w+x]=rgb(x*4,y*6,77);int[] sz=new int[2];
  check(Crop.none().apply(img,w,h,sz)==img&&sz[0]==w&&sz[1]==h,"sem corte devolve a foto");
  int[] half2=new Crop(0,.5,0,1,.5).apply(img,w,h,sz);check(sz[0]==30&&sz[1]==20,"metade "+sz[0]+"x"+sz[1]);check(half2[0]==img[30],"canto do corte é o pixel da foto");check(half2[sz[0]*sz[1]-1]==img[19*w+59],"outro canto");
  for(double a:new double[]{-30,-7.5,3,12,45}){Crop c=Crop.largest(w,h,a,0);check(Crop.fits(c.left,c.top,c.right,c.bottom,w,h,a),"maior corte cabe "+a);check(c.right-c.left<1||a==0,"girar encolhe "+a);
   Crop big=new Crop(a,0,0,1,1).constrained(w,h);check(Crop.fits(big.left,big.top,big.right,big.bottom,w,h,a),"corte encolhido cabe "+a);
   int[] out=c.apply(img,w,h,sz);check(out.length==sz[0]*sz[1]&&sz[0]>0&&sz[1]>0,"tamanho com ângulo "+a);}
  Crop sq=Crop.largest(w,h,0,1);int[] s2=sq.size(w,h);check(Math.abs(s2[0]-s2[1])<=1&&s2[1]==h,"1:1 ocupa a altura "+s2[0]+"x"+s2[1]);
  int[] solid=new int[w*h];Arrays.fill(solid,rgb(10,200,90));int[] rs=Crop.largest(w,h,20,0).apply(solid,w,h,sz);for(int v:rs)check(v==rgb(10,200,90),"girado sem borda preta");
  int[] canvas=Crop.rotated(solid,w,h,30,0xff000000);check(canvas[0]==0xff000000&&canvas[(h/2)*w+w/2]==rgb(10,200,90),"tela girada com fundo");
  // Ponto girado volta para dentro: o centro é fixo.
  double[] q=Crop.toSource(w/2.0,h/2.0,w,h,33);check(Math.abs(q[0]-w/2.0)<1e-9&&Math.abs(q[1]-h/2.0)<1e-9,"centro fixo");
  check(new Crop(5,.1,.1,.9,.9).equalsCrop(new Crop(5,.1,.1,.9,.9))&&!new Crop(5,.1,.1,.9,.9).equalsCrop(Crop.none()),"igualdade");

  System.out.println("PASS: "+checks+" verificações de perfis criativos, Look no XMP e recorte/endireitar");
 }
}
