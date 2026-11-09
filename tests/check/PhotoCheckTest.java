package com.hisho.tether;
import java.util.*;

/** Regras da curadoria nova (PhotoCheck): nitidez por regiões, rosto cortado e exposição corrigida depois do preset. */
public class PhotoCheckTest{
 static int checks;static void check(boolean ok,String what){checks++;if(!ok)throw new AssertionError(what);}
 static int[] blur(int[] px,int w,int h,double sigma,boolean onlyOutsideCenter){
  float[][] ch=new float[3][w*h];for(int i=0;i<px.length;i++){ch[0][i]=(px[i]>>16)&255;ch[1][i]=(px[i]>>8)&255;ch[2][i]=px[i]&255;}
  for(int c=0;c<3;c++)ch[c]=Develop.blur(ch[c],w,h,sigma);
  int[] out=px.clone();
  for(int y=0;y<h;y++)for(int x=0;x<w;x++){boolean center=x>w*3/8&&x<w*5/8&&y>h/3&&y<2*h/3;if(onlyOutsideCenter&&center)continue;int i=y*w+x;
   out[i]=0xff000000|Math.round(ch[0][i])<<16|Math.round(ch[1][i])<<8|Math.round(ch[2][i]);}
  return out;
 }
 /** Cena com textura de verdade (folhagem/tecido) e algum ruído de sensor. */
 static int[] scene(int w,int h){
  int[] p=DevelopTest.scene(w,h);Random r=new Random(3);
  for(int i=0;i<p.length;i++){int x=i%w,y=i/w;int t=(int)(18*Math.sin(x*.9+Math.sin(y*.37)*3)*Math.cos(y*.7))+r.nextInt(5)-2;
   int R=Math.max(0,Math.min(255,((p[i]>>16)&255)+t)),G=Math.max(0,Math.min(255,((p[i]>>8)&255)+t)),B=Math.max(0,Math.min(255,(p[i]&255)+t));p[i]=0xff000000|R<<16|G<<8|B;}
  return p;
 }
 public static void main(String[] a){
  // Nitidez: original ~1600 px.
  int w=1600,h=1066;int[] sharp=scene(w,h);
  PhotoCheck.Sharpness s0=PhotoCheck.sharpness(sharp,w,h),s2=PhotoCheck.sharpness(blur(sharp,w,h,2,false),w,h),s3=PhotoCheck.sharpness(blur(sharp,w,h,3,false),w,h),bokeh=PhotoCheck.sharpness(blur(sharp,w,h,4,true),w,h);
  System.out.printf("nitidez: nítida %.2f · desfoque 2 px %.2f · 3 px %.2f · fundo desfocado %.2f%n",s0.best,s2.best,s3.best,bokeh.best);
  for(int sens=0;sens<3;sens++){check(!PhotoCheck.blurry(s0,sens),"nítida passa ("+sens+")");check(!PhotoCheck.blurry(bokeh,sens),"retrato com fundo desfocado passa ("+sens+")");check(PhotoCheck.blurry(s3,sens),"desfoque de 3 px reprova ("+sens+")");}
  check(PhotoCheck.blurry(s2,1)&&PhotoCheck.blurry(s2,2),"desfoque de 2 px reprova na Média e Alta");
  int[] flat=new int[w*h];Arrays.fill(flat,0xff808890);check(!PhotoCheck.sharpness(flat,w,h).known&&!PhotoCheck.blurry(PhotoCheck.sharpness(flat,w,h),2),"foto lisa não é julgada");
  // Ruído não pode fazer uma foto desfocada parecer nítida.
  int[] noisy=blur(sharp,w,h,3,false);Random r=new Random(9);for(int i=0;i<noisy.length;i++){int n=(int)Math.round(r.nextGaussian()*4);int R=Math.max(0,Math.min(255,((noisy[i]>>16)&255)+n)),G=Math.max(0,Math.min(255,((noisy[i]>>8)&255)+n)),B=Math.max(0,Math.min(255,(noisy[i]&255)+n));noisy[i]=0xff000000|R<<16|G<<8|B;}
  check(PhotoCheck.blurry(PhotoCheck.sharpness(noisy,w,h),1),"desfocada com ruído de ISO alto reprova");
  // Redução por média de blocos.
  int[] size=new int[2];int[] small=PhotoCheck.shrink(sharp,w,h,512,size);check(size[0]==400&&size[1]==266&&small.length==size[0]*size[1],"redução por blocos inteiros: "+size[0]+"x"+size[1]);

  // Rosto cortado: só lateral e base contam; testa cortada no alto é enquadramento.
  check(PhotoCheck.cutFraction(800,500,60,1600,1066)==0,"rosto inteiro");
  check(PhotoCheck.cut(PhotoCheck.cutFraction(1600,500,60,1600,1066),1),"metade do rosto fora pela direita");
  check(!PhotoCheck.cut(PhotoCheck.cutFraction(800,30,60,1600,1066),2),"testa cortada no alto não reprova");
  check(PhotoCheck.cut(PhotoCheck.cutFraction(800,1040,60,1600,1066),1),"queixo e boca fora por baixo");
  check(!PhotoCheck.cut(PhotoCheck.cutFraction(1580,500,60,1600,1066),0)||PhotoCheck.cutFraction(1580,500,60,1600,1066)>.40,"sensibilidade Baixa tolera mais");
  check(PhotoCheck.principal(60,1600,1066)&&!PhotoCheck.principal(20,1600,1066),"rosto principal × rosto pequeno do fundo");

  // Exposição depois do preset: prévia 512 px, rosto de pele clara no centro, fundo médio.
  int pw=512,ph=340;int[] photo=new int[pw*ph];
  for(int y=0;y<ph;y++)for(int x=0;x<pw;x++){boolean face=Math.abs(x-256)<40&&y>130&&y<230;int v=90+(x*7+y*3)%40;photo[y*pw+x]=face?0xff000000|205<<16|160<<8|140:0xff000000|v<<16|(v+5)<<8|(v+10);}
  PhotoCheck.Faces finder=(px,fw,fh)->new float[][]{{256,165,50,.8f}};
  Develop.Settings bright=DevelopTest.preset("Exposure2012",1.6);PhotoCheck.Exposure e=PhotoCheck.fixExposure(photo,pw,ph,bright,finder);
  System.out.printf("exposição: preset +1,6 EV → correção %+.2f EV (%s)%n",e.ev,e.note);
  check(e.ev<0&&bright.exposure<1.6&&e.note.contains("rosto"),"preset que estoura o rosto é escurecido");
  PhotoCheck.Light after=PhotoCheck.Light.of(new Develop.Source(photo.clone(),pw,ph).render(bright),pw,ph,PhotoCheck.faceBoxes(finder.find(null,0,0),pw,ph));
  check(after.faceClip<=.12&&after.faceMean<=222,"depois da correção o rosto não estoura ("+after.faceMean+")");
  Develop.Settings normal=DevelopTest.preset("Exposure2012",.2);check(PhotoCheck.fixExposure(photo,pw,ph,normal,finder).ev==0&&normal.exposure==.2,"foto boa não é mexida");
  // Preset claro de propósito sem rosto: só reage se estourar uma área grande que tinha detalhe.
  Develop.Settings airy=DevelopTest.preset("Exposure2012",.5);check(PhotoCheck.fixExposure(photo,pw,ph,airy,(px,fw,fh)->new float[0][]).ev==0,"preset claro sem estourar não é mexido");
  // Rosto escuro demais é clareado.
  int[] dark=photo.clone();for(int i=0;i<dark.length;i++){int x=i%pw,y=i/pw;if(Math.abs(x-256)<40&&y>130&&y<230)dark[i]=0xff000000|40<<16|30<<8|26;}
  Develop.Settings none=DevelopTest.preset("Exposure2012",0);PhotoCheck.Exposure d=PhotoCheck.fixExposure(dark,pw,ph,none,finder);
  System.out.printf("exposição: rosto escuro → correção %+.2f EV%n",d.ev);check(d.ev>0&&d.ev<=1.2,"rosto escuro é clareado");
  // Original já estourada na câmera (fundo branco): não fica perseguindo o branco.
  int[] white=photo.clone();for(int i=0;i<white.length;i++)if(i%pw<160)white[i]=0xffffffff;check(PhotoCheck.fixExposure(white,pw,ph,DevelopTest.preset("Exposure2012",.1),(px,fw,fh)->new float[0][]).ev==0,"fundo branco da câmera não é corrigido");
  int[] black=new int[pw*ph];Arrays.fill(black,0xff050505);check(!PhotoCheck.fixExposure(black,pw,ph,DevelopTest.preset("Exposure2012",0),finder).unrecoverable.isEmpty(),"foto preta vai para Revisão");
  System.out.println(checks+" verificações da curadoria (nitidez, rosto cortado, exposição)");
 }
}
