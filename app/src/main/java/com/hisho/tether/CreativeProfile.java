package com.hisho.tether;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Perfis criativos do Lightroom (crs:Look) embutidos nos presets .xmp: a tabela 3D de cor (RGBTable) e a
 * tabela matiz/saturação/valor (LookTable), no formato do DNG SDK da Adobe (dng_big_table): texto num base 85
 * próprio → 4 bytes com o tamanho + zlib → tabela. Na revelação, as tabelas são "assadas" numa tabela 3D no
 * espaço sRGB do JPEG e aplicadas antes do resto do motor, com interpolação tetraédrica como no SDK.
 * Não depende do Android; ios/LumoCore/Sources/LumoCore/CreativeProfile.swift é a mesma conta.
 *
 * <p>No preset fica só a referência {@code "Look":{"name","rgbTable","rgbAmount","lookTable","grayscale"}} e a
 * intensidade em {@code LookAmount} (0–200, como o slider do Lightroom); as tabelas ficam uma vez em {@link #dir},
 * pelo MD5 que o próprio Lightroom usa no nome ({@code Table_<MD5>}).
 */
final class CreativeProfile{
 static final int LOOK_TABLE=0,RGB_TABLE=1;
 static final int SRGB=0,ADOBE=1,PROPHOTO=2,P3=3,REC2020=4;
 static final int LINEAR=0,GAMMA_SRGB=1,GAMMA_18=2,GAMMA_22=3,GAMMA_REC2020=4;
 /** Lado da tabela assada (pontos por canal no espaço sRGB do JPEG). */
 static final int BAKE=33;
 /** Pasta das tabelas (definida pelo app; nos testes fica só a memória). */
 static volatile File dir;
 static final Map<String,String> MEMORY=Collections.synchronizedMap(new HashMap<>());
 static final Map<String,float[]> BAKED=Collections.synchronizedMap(new LinkedHashMap<String,float[]>(8,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,float[]> e){return size()>4;}});

 // ---------------------------------------------------------------- Texto → binário (dng_big_table::ASCIItoBinary)

 static final int[] DECODE=new int[96];
 static{
  Arrays.fill(DECODE,0xff);
  for(int i=0;i<10;i++)DECODE['0'-32+i]=i;
  for(int i=0;i<26;i++){DECODE['a'-32+i]=10+i;DECODE['A'-32+i]=36+i;}
  String extra=".-:+=^!/*?`'|()[]{}@%$#";for(int i=0;i<extra.length();i++)DECODE[extra.charAt(i)-32]=62+i;
 }
 static byte[] ascii(String s){
  ByteArrayOutputStream out=new ByteArrayOutputStream(s.length()*4/5+4);int phase=0;long value=0;
  for(int j=0;j<s.length();j++){
   int e=s.charAt(j);if(e<32||e>127)continue;int d=DECODE[e-32];if(d>85)continue;phase++;
   if(phase==1)value=d;else if(phase==2)value+=d*85L;else if(phase==3)value+=d*85L*85;else if(phase==4)value+=d*85L*85*85;
   else{value+=d*85L*85*85*85;out.write((int)value);out.write((int)(value>>8));out.write((int)(value>>16));out.write((int)(value>>24));phase=0;}
  }
  // Sobra no fim: até 3 bytes, na ordem do SDK.
  byte[] b=out.toByteArray();int extra=phase>3?3:phase>2?2:phase>1?1:0;if(extra==0)return b;
  byte[] r=Arrays.copyOf(b,b.length+extra);for(int k=0;k<extra;k++)r[b.length+k]=(byte)(value>>(8*k));return r;
 }
 /** Bloco comprimido: tamanho descomprimido (uint32 LE) e zlib. */
 static byte[] inflate(byte[] block)throws IOException{
  if(block.length<5)throw new IOException("Tabela de perfil vazia.");
  long size=(block[0]&255L)|(block[1]&255L)<<8|(block[2]&255L)<<16|(block[3]&255L)<<24;if(size<=0||size>16_000_000)throw new IOException("Tabela de perfil inválida.");
  Inflater inf=new Inflater();inf.setInput(block,4,block.length-4);byte[] out=new byte[(int)size];int n=0;
  try{while(n<out.length&&!inf.finished()){int k=inf.inflate(out,n,out.length-n);if(k==0&&(inf.needsInput()||inf.needsDictionary()))break;n+=k;}}catch(DataFormatException e){throw new IOException("Tabela de perfil corrompida.");}finally{inf.end();}
  if(n!=out.length)throw new IOException("Tabela de perfil incompleta.");return out;
 }

 // ---------------------------------------------------------------- Tabelas

 /** Tabela RGB (1D ou 3D) com as amostras já somadas à identidade, 0–65535, como dng_rgb_table. */
 static final class RgbTable{int dims,divisions,primaries,gamma,gamut;int[] samples;double minAmount,maxAmount;}
 /** Tabela matiz/saturação/valor (dng_hue_sat_map): para cada ponto, desvio de matiz em graus e escalas de saturação e valor. */
 static final class LookTable{int hue,sat,val,encoding;float[] deltas;double minAmount=1,maxAmount=1;}

 static final class Reader{
  final byte[] b;int p;Reader(byte[] b){this.b=b;}
  void need(int n)throws IOException{if(p+n>b.length)throw new IOException("Tabela de perfil truncada.");}
  int u16()throws IOException{need(2);int v=(b[p]&255)|(b[p+1]&255)<<8;p+=2;return v;}
  long u32()throws IOException{need(4);long v=(b[p]&255L)|(b[p+1]&255L)<<8|(b[p+2]&255L)<<16|(b[p+3]&255L)<<24;p+=4;return v;}
  float f32()throws IOException{return Float.intBitsToFloat((int)u32());}
  double f64()throws IOException{long lo=u32(),hi=u32();return Double.longBitsToDouble(hi<<32|lo);}
 }
 /** Lê uma tabela do texto de {@code crs:Table_<MD5>}: devolve {@link RgbTable} ou {@link LookTable}. */
 static Object decode(String text)throws IOException{
  Reader r=new Reader(inflate(ascii(text)));long type=r.u32();
  if(type==RGB_TABLE){
   RgbTable t=new RgbTable();if(r.u32()!=1)throw new IOException("Versão de tabela RGB desconhecida.");
   t.dims=(int)r.u32();t.divisions=(int)r.u32();
   if(t.dims==1){if(t.divisions<2||t.divisions>4096)throw new IOException("Tabela RGB inválida.");}
   else if(t.dims==3){if(t.divisions<2||t.divisions>64)throw new IOException("Tabela RGB inválida.");}
   else throw new IOException("Tabela RGB inválida.");
   int d=t.divisions;int[] nop=new int[d];for(int i=0;i<d;i++)nop[i]=(int)(((long)i*0xffff+(d>>1))/(d-1));
   if(t.dims==1){t.samples=new int[d*3];for(int i=0;i<d;i++)for(int c=0;c<3;c++)t.samples[i*3+c]=(r.u16()+nop[i])&0xffff;}
   else{t.samples=new int[d*d*d*3];int k=0;for(int ri=0;ri<d;ri++)for(int gi=0;gi<d;gi++)for(int bi=0;bi<d;bi++){t.samples[k++]=(r.u16()+nop[ri])&0xffff;t.samples[k++]=(r.u16()+nop[gi])&0xffff;t.samples[k++]=(r.u16()+nop[bi])&0xffff;}}
   t.primaries=(int)r.u32();t.gamma=(int)r.u32();t.gamut=(int)r.u32();if(t.primaries>4||t.gamma>4||t.gamut>1)throw new IOException("Espaço de cor da tabela desconhecido.");
   t.minAmount=r.f64();t.maxAmount=r.f64();if(!(t.minAmount>=0&&t.minAmount<=1&&t.maxAmount>=1&&t.maxAmount<10)){t.minAmount=0;t.maxAmount=2;}
   return t;
  }
  if(type==LOOK_TABLE){
   LookTable t=new LookTable();long version=r.u32();if(version!=1&&version!=2)throw new IOException("Versão de tabela de visual desconhecida.");
   t.hue=(int)r.u32();t.sat=(int)r.u32();t.val=(int)r.u32();
   if(t.hue<1||t.hue>360||t.sat<1||t.sat>256||t.val<1||t.val>256||(long)t.hue*t.sat*t.val>100_000)throw new IOException("Tabela de visual inválida.");
   int n=t.hue*t.sat*t.val;t.deltas=new float[n*3];for(int i=0;i<n*3;i++)t.deltas[i]=r.f32();
   t.encoding=(int)r.u32();if(t.encoding>1)throw new IOException("Codificação da tabela de visual desconhecida.");
   if(version!=1){t.minAmount=r.f64();t.maxAmount=r.f64();if(!(t.minAmount>=0&&t.minAmount<=1&&t.maxAmount>=1&&t.maxAmount<10)){t.minAmount=0;t.maxAmount=2;}}
   return t;
  }
  throw new IOException("Tipo de tabela de perfil desconhecido.");
 }

 // ---------------------------------------------------------------- Armazenamento

 static boolean validKey(String md5){return md5!=null&&md5.matches("[0-9A-Fa-f]{32}");}
 /** Guarda o texto de uma tabela (uma vez por MD5). */
 static void store(String md5,String text)throws IOException{
  if(!validKey(md5))throw new IOException("Tabela de perfil sem identificador.");decode(text);
  String k=md5.toUpperCase(Locale.ROOT);MEMORY.put(k,text);File d=dir;if(d==null)return;
  if(!d.isDirectory()&&!d.mkdirs())throw new IOException("Não foi possível guardar o perfil.");
  File f=new File(d,k+".txt");if(f.isFile()&&f.length()==text.length())return;File tmp=new File(d,k+".tmp");
  try(Writer w=new OutputStreamWriter(new FileOutputStream(tmp),"US-ASCII")){w.write(text);}if(!tmp.renameTo(f)){tmp.delete();throw new IOException("Não foi possível guardar o perfil.");}
 }
 static String load(String md5){
  if(!validKey(md5))return null;String k=md5.toUpperCase(Locale.ROOT);String t=MEMORY.get(k);if(t!=null)return t;File d=dir;if(d==null)return null;File f=new File(d,k+".txt");if(!f.isFile())return null;
  try(InputStream in=new FileInputStream(f)){ByteArrayOutputStream o=new ByteArrayOutputStream((int)f.length());byte[] buf=new byte[65536];int n;while((n=in.read(buf))>0)o.write(buf,0,n);t=o.toString("US-ASCII");MEMORY.put(k,t);return t;}catch(IOException e){return null;}
 }

 // ---------------------------------------------------------------- Espaços de cor

 static final double[][] PRIMARIES={{.64,.33,.30,.60,.15,.06},{.64,.33,.21,.71,.15,.06},{.7347,.2653,.1596,.8404,.0366,.0001},{.68,.32,.265,.69,.15,.06},{.708,.292,.170,.797,.131,.046}};
 static final double[] D65={.95047,1,1.08883},D50={.96422,1,.82521};
 static double[][] toXYZ(int primaries){
  double[] p=PRIMARIES[primaries];double[] w=primaries==PROPHOTO?D50:D65;double[][] m=new double[3][3];
  for(int i=0;i<3;i++){double x=p[2*i],y=p[2*i+1];m[0][i]=x/y;m[1][i]=1;m[2][i]=(1-x-y)/y;}
  double[] s=mul(inv(m),w);for(int r=0;r<3;r++)for(int c=0;c<3;c++)m[r][c]*=s[c];return m;
 }
 /** Bradford de um branco para outro. */
 static double[][] adapt(double[] from,double[] to){
  double[][] b={{.8951,.2664,-.1614},{-.7502,1.7135,.0367},{.0389,-.0685,1.0296}};double[] a=mul(b,from),z=mul(b,to);
  double[][] d={{z[0]/a[0],0,0},{0,z[1]/a[1],0},{0,0,z[2]/a[2]}};return mul(inv(b),mul(d,b));
 }
 /** Matriz linear sRGB → primárias da tabela (com adaptação D65 → D50 no ProPhoto). */
 static double[][] fromSRGB(int primaries){
  if(primaries==SRGB)return new double[][]{{1,0,0},{0,1,0},{0,0,1}};
  double[][] xyz=toXYZ(SRGB);if(primaries==PROPHOTO)xyz=mul(adapt(D65,D50),xyz);return mul(inv(toXYZ(primaries)),xyz);
 }
 static double[] mul(double[][] m,double[] v){return new double[]{m[0][0]*v[0]+m[0][1]*v[1]+m[0][2]*v[2],m[1][0]*v[0]+m[1][1]*v[1]+m[1][2]*v[2],m[2][0]*v[0]+m[2][1]*v[1]+m[2][2]*v[2]};}
 static double[][] mul(double[][] a,double[][] b){double[][] r=new double[3][3];for(int i=0;i<3;i++)for(int j=0;j<3;j++)for(int k=0;k<3;k++)r[i][j]+=a[i][k]*b[k][j];return r;}
 static double[][] inv(double[][] m){
  double a=m[0][0],b=m[0][1],c=m[0][2],d=m[1][0],e=m[1][1],f=m[1][2],g=m[2][0],h=m[2][1],i=m[2][2];double det=a*(e*i-f*h)-b*(d*i-f*g)+c*(d*h-e*g);
  return new double[][]{{(e*i-f*h)/det,(c*h-b*i)/det,(b*f-c*e)/det},{(f*g-d*i)/det,(a*i-c*g)/det,(c*d-a*f)/det},{(d*h-e*g)/det,(b*g-a*h)/det,(a*e-b*d)/det}};
 }
 static double decodeSRGB(double v){return v<=.04045?v/12.92:Math.pow((v+.055)/1.055,2.4);}
 static double encodeSRGB(double v){return v<=.0031308?v*12.92:1.055*Math.pow(v,1/2.4)-.055;}
 static double encode(int gamma,double v){
  v=Math.max(0,Math.min(1,v));
  switch(gamma){case LINEAR:return v;case GAMMA_SRGB:return encodeSRGB(v);case GAMMA_18:return Math.pow(v,1/1.8);case GAMMA_22:return Math.pow(v,1/2.2);default:return v<.0181?4.5*v:1.0993*Math.pow(v,.45)-.0993;}
 }
 static double decode(int gamma,double v){
  v=Math.max(0,Math.min(1,v));
  switch(gamma){case LINEAR:return v;case GAMMA_SRGB:return decodeSRGB(v);case GAMMA_18:return Math.pow(v,1.8);case GAMMA_22:return Math.pow(v,2.2);default:return v<.0814?v/4.5:Math.pow((v+.0993)/1.0993,1/.45);}
 }
 static double clamp(double v){return v<0?0:v>1?1:v;}

 // ---------------------------------------------------------------- Aplicação de cada tabela (linear sRGB in/out)

 /** RefRGBtoRGBTable3D/1D: sRGB linear → primárias e gama da tabela → tabela (com intensidade) → de volta. */
 static void rgb(RgbTable t,double amount,double[][] enc,double[][] dec,double[] c){
  double r=c[0],g=c[1],b=c[2];double[] m=mul(enc,c);double rr=m[0],gg=m[1],bb=m[2];
  double pr=clamp(rr),pg=clamp(gg),pb=clamp(bb);boolean extend=t.gamut==1;double dr=extend?rr-pr:0,dg=extend?gg-pg:0,db=extend?bb-pb:0;
  double er=encode(t.gamma,pr),eg=encode(t.gamma,pg),eb=encode(t.gamma,pb);double or,og,ob;
  if(t.dims==1){double s=t.divisions-1;or=lerp1(t,er*s,0);og=lerp1(t,eg*s,1);ob=lerp1(t,eb*s,2);}
  else{double[] o=tetra(t.samples,t.divisions,er,eg,eb,1/65535.);or=o[0];og=o[1];ob=o[2];}
  or=clamp(er+amount*(or-er));og=clamp(eg+amount*(og-eg));ob=clamp(eb+amount*(ob-eb));
  double[] back=mul(dec,new double[]{decode(t.gamma,or)+dr,decode(t.gamma,og)+dg,decode(t.gamma,ob)+db});
  c[0]=back[0];c[1]=back[1];c[2]=back[2];
 }
 static double lerp1(RgbTable t,double x,int ch){int i=Math.max(0,Math.min(t.divisions-2,(int)x));double f=x-i;return (t.samples[i*3+ch]*(1-f)+t.samples[(i+1)*3+ch]*f)/65535.;}
 /** Interpolação tetraédrica numa grade n³ de trincas (mesma escolha de tetraedro do SDK). */
 static double[] tetra(int[] s,int n,double r,double g,double b,double scale){
  double sc=n-1;double rs=r*sc,gs=g*sc,bs=b*sc;int ri=Math.max(0,Math.min(n-2,(int)rs)),gi=Math.max(0,Math.min(n-2,(int)gs)),bi=Math.max(0,Math.min(n-2,(int)bs));
  double rf=rs-ri,gf=gs-gi,bf=bs-bi;int o001=3,o010=3*n,o100=3*n*n,o011=o010+o001,o101=o100+o001,o110=o100+o010,o111=o110+o001;int o1,o2;double f1,f2,f3;
  if(gf>=rf){if(bf>=gf){o1=o001;o2=o011;f1=bf;f2=gf;f3=rf;}else if(bf>=rf){o1=o010;o2=o011;f1=gf;f2=bf;f3=rf;}else{o1=o010;o2=o110;f1=gf;f2=rf;f3=bf;}}
  else{if(bf>=rf){o1=o001;o2=o101;f1=bf;f2=rf;f3=gf;}else if(bf>=gf){o1=o100;o2=o101;f1=rf;f2=bf;f3=gf;}else{o1=o100;o2=o110;f1=rf;f2=gf;f3=bf;}}
  double w0=1-f1,w1=f1-f2,w2=f2-f3;int base=ri*o100+gi*o010+bi*o001;double[] out=new double[3];
  for(int c=0;c<3;c++)out[c]=(w0*s[base+c]+w1*s[base+o1+c]+w2*s[base+o2+c]+f3*s[base+o111+c])*scale;
  return out;
 }
 static double[] tetra(float[] s,int n,double r,double g,double b){
  double sc=n-1;double rs=r*sc,gs=g*sc,bs=b*sc;int ri=Math.max(0,Math.min(n-2,(int)rs)),gi=Math.max(0,Math.min(n-2,(int)gs)),bi=Math.max(0,Math.min(n-2,(int)bs));
  double rf=rs-ri,gf=gs-gi,bf=bs-bi;int o001=3,o010=3*n,o100=3*n*n,o011=o010+o001,o101=o100+o001,o110=o100+o010,o111=o110+o001;int o1,o2;double f1,f2,f3;
  if(gf>=rf){if(bf>=gf){o1=o001;o2=o011;f1=bf;f2=gf;f3=rf;}else if(bf>=rf){o1=o010;o2=o011;f1=gf;f2=bf;f3=rf;}else{o1=o010;o2=o110;f1=gf;f2=rf;f3=bf;}}
  else{if(bf>=rf){o1=o001;o2=o101;f1=bf;f2=rf;f3=gf;}else if(bf>=gf){o1=o100;o2=o101;f1=rf;f2=bf;f3=gf;}else{o1=o100;o2=o110;f1=rf;f2=gf;f3=bf;}}
  double w0=1-f1,w1=f1-f2,w2=f2-f3;int base=ri*o100+gi*o010+bi*o001;
  return new double[]{w0*s[base]+w1*s[base+o1]+w2*s[base+o2]+f3*s[base+o111],w0*s[base+1]+w1*s[base+o1+1]+w2*s[base+o2+1]+f3*s[base+o111+1],w0*s[base+2]+w1*s[base+o1+2]+w2*s[base+o2+2]+f3*s[base+o111+2]};
 }

 /** RefBaselineHueSatMap no ProPhoto linear (espaço do perfil); intensidade aproxima os desvios da identidade. */
 static void look(LookTable t,double amount,double[][] toPro,double[][] fromPro,double[] c){
  double[] p=mul(toPro,c);double r=Math.max(0,p[0]),g=Math.max(0,p[1]),b=Math.max(0,p[2]);
  double max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b)),v=max,s=max>0?(max-min)/max:0,h=0;
  if(max>min){double d=max-min;if(r==max)h=(g-b)/d;else if(g==max)h=2+(b-r)/d;else h=4+(r-g)/d;if(h<0)h+=6;}
  boolean srgb=t.encoding==1&&t.val>1;double ve=srgb?encodeSRGB(clamp(v)):v;
  double hScale=t.hue<2?0:t.hue/6.0,sScale=t.sat-1,vScale=t.val-1;int maxH=t.hue-1,maxS=Math.max(0,t.sat-2),maxV=Math.max(0,t.val-2);
  int hueStep=t.sat,valStep=t.hue*hueStep;
  double hs=h*hScale,ss=s*sScale,vs=ve*vScale;int h0=Math.max(0,Math.min(maxH,(int)hs)),s0=Math.max(0,Math.min(maxS,(int)ss)),v0=t.val<2?0:Math.max(0,Math.min(maxV,(int)vs));int h1=h0+1;if(h0>=maxH){h0=maxH;h1=0;}
  double hf1=hs-h0,sf1=t.sat<2?0:ss-s0,vf1=t.val<2?0:vs-v0,hf0=1-hf1,sf0=1-sf1,vf0=1-vf1;
  double[] out=new double[3];
  for(int k=0;k<3;k++){
   int e00=(v0*valStep+h0*hueStep+s0)*3+k,e01=(v0*valStep+h1*hueStep+s0)*3+k,sStep=t.sat>1?3:0,vStep=t.val>1?valStep*3:0;
   double a0=vf0*(hf0*t.deltas[e00]+hf1*t.deltas[e01])+vf1*(hf0*t.deltas[e00+vStep]+hf1*t.deltas[e01+vStep]);
   double a1=vf0*(hf0*t.deltas[e00+sStep]+hf1*t.deltas[e01+sStep])+vf1*(hf0*t.deltas[e00+sStep+vStep]+hf1*t.deltas[e01+sStep+vStep]);
   out[k]=sf0*a0+sf1*a1;
  }
  double hueShift=out[0]*amount,satScale=1+(out[1]-1)*amount,valScale=1+(out[2]-1)*amount;
  h+=hueShift*6/360;s=Math.min(s*satScale,1);ve=clamp(ve*valScale);v=srgb?decodeSRGB(ve):ve;
  h%=6;if(h<0)h+=6;int i=(int)h;double f=h-i,pp=v*(1-s),q=v*(1-s*f),tt=v*(1-s*(1-f));double R,G,B;
  switch(i){case 0:R=v;G=tt;B=pp;break;case 1:R=q;G=v;B=pp;break;case 2:R=pp;G=v;B=tt;break;case 3:R=pp;G=q;B=v;break;case 4:R=tt;G=pp;B=v;break;default:R=v;G=pp;B=q;}
  double[] back=mul(fromPro,new double[]{R,G,B});c[0]=back[0];c[1]=back[1];c[2]=back[2];
 }

 // ---------------------------------------------------------------- Perfil resolvido

 /** Perfil pronto para aplicar: tabelas lidas e intensidades finais (0 = sem efeito). */
 static final class Profile{
  final String key;final RgbTable rgb;final LookTable look;final double rgbAmount,lookAmount;final boolean grayscale;
  Profile(String key,RgbTable rgb,double rgbAmount,LookTable look,double lookAmount,boolean grayscale){this.key=key;this.rgb=rgb;this.look=look;this.rgbAmount=rgbAmount;this.lookAmount=lookAmount;this.grayscale=grayscale;}
  boolean active(){return (rgb!=null&&rgbAmount>0)||(look!=null&&lookAmount>0);}
 }
 /**
  * Perfil a partir das referências do preset. {@code amount}: intensidade do slider (100 = como veio, 0–200).
  * Tabela ausente (preset importado antes ou pasta apagada) = perfil ignorado, como um preset sem perfil.
  */
 static Profile resolve(String rgbKey,double rgbAmount,String lookKey,double amount,boolean grayscale){
  RgbTable rgb=null;LookTable look=null;double a=Math.max(0,Math.min(2,amount/100));
  try{String t=load(rgbKey);if(t!=null){Object o=decode(t);if(o instanceof RgbTable)rgb=(RgbTable)o;}}catch(IOException ignored){}
  try{String t=load(lookKey);if(t!=null){Object o=decode(t);if(o instanceof LookTable)look=(LookTable)o;}}catch(IOException ignored){}
  if(rgb==null&&look==null)return null;
  double ra=rgb==null?0:Math.max(rgb.minAmount,Math.min(rgb.maxAmount,rgbAmount*a)),la=look==null?0:Math.max(look.minAmount,Math.min(look.maxAmount,a));
  if(a==0){ra=0;la=0;}
  String key=(rgb==null?"-":rgbKey.toUpperCase(Locale.ROOT))+"/"+(look==null?"-":lookKey.toUpperCase(Locale.ROOT))+"/"+Math.round(ra*1000)+"/"+Math.round(la*1000);
  return new Profile(key,rgb,ra,look,la,grayscale);
 }

 /** Tabela {@link #BAKE}³ no sRGB do JPEG (entrada e saída 0–1): perfil inteiro numa consulta por pixel. */
 static float[] bake(Profile p){
  float[] cached=BAKED.get(p.key);if(cached!=null)return cached;
  int n=BAKE;float[] t=new float[n*n*n*3];double[][] enc=p.rgb==null?null:fromSRGB(p.rgb.primaries),dec=enc==null?null:inv(enc);
  double[][] toPro=fromSRGB(PROPHOTO),fromPro=inv(toPro);double[] c=new double[3];int k=0;
  for(int ri=0;ri<n;ri++)for(int gi=0;gi<n;gi++)for(int bi=0;bi<n;bi++){
   c[0]=decodeSRGB(ri/(n-1.0));c[1]=decodeSRGB(gi/(n-1.0));c[2]=decodeSRGB(bi/(n-1.0));
   if(p.look!=null&&p.lookAmount>0)look(p.look,p.lookAmount,toPro,fromPro,c);
   if(p.rgb!=null&&p.rgbAmount>0)rgb(p.rgb,p.rgbAmount,enc,dec,c);
   for(int j=0;j<3;j++)t[k++]=(float)encodeSRGB(clamp(c[j]));
  }
  BAKED.put(p.key,t);return t;
 }

 /** Aplica o perfil nos pixels ARGB (no lugar), em paralelo por faixas. */
 static void apply(int[] px,int w,int h,Profile p){
  if(p==null||!p.active()||px.length==0)return;final float[] t=bake(p);final int n=BAKE;
  Develop.parallel(h,(y0,y1)->{for(int i=y0*w,end=Math.min(px.length,y1*w);i<end;i++){int v=px[i];
   double[] o=tetra(t,n,((v>>16)&255)/255.,((v>>8)&255)/255.,(v&255)/255.);
   px[i]=(v&0xff000000)|to8(o[0])<<16|to8(o[1])<<8|to8(o[2]);}});
 }
 static int to8(double v){int x=(int)Math.round(v*255);return x<0?0:x>255?255:x;}
 private CreativeProfile(){}
}
