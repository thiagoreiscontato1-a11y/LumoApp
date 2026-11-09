package com.hisho.tether;
import java.util.*;

/**
 * Verificações da curadoria e correção de exposição, sem dependência do Android (testadas no desktop).
 *
 * Regras da curadoria (0.19.0):
 * - Muito clara ou escura depois do preset: o Lumo corrige a exposição, não recusa.
 * - Desfocada: vai para Revisão. A nitidez é medida na foto ORIGINAL (antes de preset, nitidez e grão), região por
 *   região, e basta uma região nítida (o assunto) para passar. Fundo desfocado de retrato não reprova.
 * - Cortada: vai para Revisão quando um rosto principal sai do quadro pela lateral ou por baixo (SemanticCurator).
 * - Foto praticamente preta ou toda branca: Revisão (não há o que corrigir).
 */
final class PhotoCheck{
 /** Lado da versão usada para medir a nitidez: perto do que o convidado vê no celular ou na galeria. */
 static final int DETAIL_EDGE=1600;
 /** Lado da prévia usada para ajustar a exposição. */
 static final int PREVIEW_EDGE=512;

 static double luma(int c){return .2126*((c>>16)&255)+.7152*((c>>8)&255)+.0722*(c&255);}

 /** Reduz pela média de blocos k×k (k inteiro) até o maior lado caber em maxEdge. size recebe a largura e a altura. */
 static int[] shrink(final int[] px,final int w,final int h,int maxEdge,int[] size){
  final int k=Math.max(1,(int)Math.ceil(Math.max(w,h)/(double)maxEdge));
  if(k==1){size[0]=w;size[1]=h;return px.clone();}
  final int ow=Math.max(1,w/k),oh=Math.max(1,h/k),n=k*k;final int[] out=new int[ow*oh];
  Develop.parallel(oh,(y0,y1)->{
   for(int oy=y0;oy<y1;oy++)for(int ox=0;ox<ow;ox++){int r=0,g=0,b=0;
    for(int dy=0;dy<k;dy++){int row=(oy*k+dy)*w+ox*k;for(int dx=0;dx<k;dx++){int c=px[row+dx];r+=(c>>16)&255;g+=(c>>8)&255;b+=c&255;}}
    out[oy*ow+ox]=0xff000000|((r+n/2)/n)<<16|((g+n/2)/n)<<8|((b+n/2)/n);}
  });
  size[0]=ow;size[1]=oh;return out;
 }

 // ---------------------------------------------------------------- Nitidez

 /**
  * Nitidez por região. Em cada região compara a energia das diferenças finas (2 px) com a das grossas (4 px), em
  * cada direção: borda nítida dá 2, borda borrada tende a 1, textura fina passa de 2. Não depende de contraste nem
  * de brilho. Tremido numa direção derruba aquela direção, então vale a pior das duas. O ruído (ISO alto) é estimado
  * na região mais lisa e descontado, para não parecer detalhe. Regiões lisas (céu, parede) não contam.
  * {@code best} é a região mais nítida; {@code known} é falso quando não há detalhe para julgar (aí nunca reprova).
  */
 static final class Sharpness{
  final double best,noise;final int regions;final boolean known;
  Sharpness(double best,int regions,double noise){this.best=best;this.regions=regions;this.noise=noise;known=regions>0;}
 }
 /** Energia mínima (níveis², por pixel e direção, nas diferenças de 4 px) para uma região ter detalhe de verdade. */
 static final double MIN_ENERGY=6;
 static Sharpness sharpness(int[] px,int w,int h){
  if(w<16||h<16)return new Sharpness(0,0,0);
  float[] l=new float[w*h];for(int i=0;i<l.length;i++)l[i]=(float)luma(px[i]);
  int tile=Math.max(24,Math.max(w,h)/8),cols=(w+tile-1)/tile,rows=(h+tile-1)/tile;
  double[][] e=new double[cols*rows][];double minFine=Double.MAX_VALUE;
  for(int ty=0;ty<rows;ty++)for(int tx=0;tx<cols;tx++){
   int x0=Math.max(2,tx*tile),x1=Math.min(w-2,tx*tile+tile),y0=Math.max(2,ty*tile),y1=Math.min(h-2,ty*tile+tile);if(x1-x0<8||y1-y0<8)continue;
   double fx2=0,fy2=0,cx2=0,cy2=0;long n=0;
   for(int y=y0;y<y1;y++){int row=y*w;for(int x=x0;x<x1;x++){int i=row+x;
    double fx=l[i+1]-l[i-1],fy=l[i+w]-l[i-w],cx=(l[i+2]-l[i-2])*.5,cy=(l[i+2*w]-l[i-2*w])*.5;
    fx2+=fx*fx;fy2+=fy*fy;cx2+=cx*cx;cy2+=cy*cy;n++;}}
   e[ty*cols+tx]=new double[]{fx2/n,fy2/n,cx2/n,cy2/n};minFine=Math.min(minFine,(fx2+fy2)/n);
  }
  // Ruído branco de variância v soma 2v às diferenças finas e v/2 às grossas, em cada direção.
  double v=minFine==Double.MAX_VALUE?0:minFine/4;
  // Primeiro só regiões com detalhe nas duas direções: uma região só de linhas horizontais continua "nítida" num
  // tremido horizontal. Se a foto não tiver nenhuma assim, usa as de uma direção só.
  for(int pass=0;pass<2;pass++){double best=0;int regions=0;
   for(double[] t:e){if(t==null)continue;
    double fx=t[0]-2*v,fy=t[1]-2*v,cx=t[2]-v/2,cy=t[3]-v/2;boolean ex=cx>=MIN_ENERGY,ey=cy>=MIN_ENERGY;
    if(pass==0?!(ex&&ey):!(ex||ey))continue;
    double r=Double.MAX_VALUE;if(ex)r=Math.min(r,Math.max(0,fx)/cx);if(ey)r=Math.min(r,Math.max(0,fy)/cy);
    regions++;best=Math.max(best,r);}
   if(regions>0)return new Sharpness(best,regions,Math.sqrt(v));
  }
  return new Sharpness(0,0,Math.sqrt(v));
 }
 /** Abaixo disto a foto inteira está desfocada (Baixa, Média, Alta). Calibrado em fotos reais com desfoque conhecido. */
 static final double[] BLUR_LIMIT={1.25,1.40,1.60};
 static boolean blurry(Sharpness s,int sensitivity){return s.known&&s.best<BLUR_LIMIT[Math.max(0,Math.min(2,sensitivity))];}

 // ---------------------------------------------------------------- Rosto cortado

 /** Rosto principal: olhos separados por pelo menos 2,5% do maior lado (quem está em destaque, não o fundo de um grupo). */
 static boolean principal(float eye,int w,int h){return eye>=.025f*Math.max(w,h);}
 /**
  * Fração do rosto (da testa ao queixo, de orelha a orelha) que ficou fora do quadro pela lateral ou por baixo.
  * Cortar o alto da cabeça é enquadramento comum em retrato fechado e não conta.
  */
 static double cutFraction(float cx,float cy,float eye,int w,int h){
  double x0=cx-eye*1.25,x1=cx+eye*1.25,y0=cy-eye*1.2,y1=cy+eye*2.0,area=(x1-x0)*(y1-y0);if(area<=0)return 0;
  double vx=Math.max(0,Math.min(w,x1)-Math.max(0,x0)),vy=Math.max(0,Math.min(h,y1)-y0);
  return Math.max(0,1-vx*vy/area);
 }
 /** Fração cortada a partir da qual vai para Revisão (Baixa, Média, Alta). */
 static final double[] CUT_LIMIT={.40,.25,.15};
 static boolean cut(double fraction,int sensitivity){return fraction>CUT_LIMIT[Math.max(0,Math.min(2,sensitivity))];}

 // ---------------------------------------------------------------- Exposição depois do preset

 /** Rostos encontrados na prévia: {centro x, centro y, distância entre os olhos, confiança}. */
 interface Faces{float[][] find(int[] argb,int w,int h);}

 static final class Exposure{
  double ev;String note="";String unrecoverable="";
 }

 /** Medidas da prévia revelada: luz estourada na foto e nos rostos, e brilho médio dos rostos. */
 static final class Light{
  double clip,faceClip,faceMean=-1;
  static Light of(int[] px,int w,int h,int[][] boxes){
   Light m=new Light();long clipped=0;for(int c:px)if(luma(c)>=250)clipped++;m.clip=clipped/(double)px.length;
   double sum=0,area=0;long fc=0,fn=0;
   for(int[] b:boxes){double s=0;long n=0,k=0;for(int y=b[1];y<b[3];y++)for(int x=b[0];x<b[2];x++){double v=luma(px[y*w+x]);s+=v;n++;if(v>=245)k++;}
    if(n==0)continue;sum+=s;area+=n;fc+=k;fn+=n;}
   if(area>0){m.faceMean=sum/area;m.faceClip=fc/(double)fn;}
   return m;
  }
 }

 /** Caixas da parte central do rosto (sem cabelo nem fundo), dentro da imagem. */
 static int[][] faceBoxes(float[][] faces,int w,int h){
  List<int[]> boxes=new ArrayList<>();
  if(faces!=null)for(float[] f:faces){if(f==null||f[3]<.3f)continue;float e=Math.max(2,f[2]);
   int x0=Math.max(0,(int)(f[0]-e*.8f)),x1=Math.min(w,(int)(f[0]+e*.8f)),y0=Math.max(0,(int)(f[1]-e*.3f)),y1=Math.min(h,(int)(f[1]+e*1.3f));
   if(x1-x0>=3&&y1-y0>=3)boxes.add(new int[]{x0,y0,x1,y1});}
  return boxes.toArray(new int[0][]);
 }

 static double percentile(int[] px,double p){int[] hist=new int[256];for(int c:px)hist[Math.min(255,(int)Math.round(luma(c)))]++;long need=(long)Math.ceil(px.length*p),acc=0;for(int i=0;i<256;i++){acc+=hist[i];if(acc>=need)return i;}return 255;}

 /**
  * Ajusta {@code s.exposure} olhando a foto JÁ COM o preset (prévia pequena), não a original:
  * - Clara demais: rosto estourado ou muita área que estava com detalhe e estourou. Escurece o mínimo necessário.
  *   Um preset claro de propósito ("airy") continua claro; só não deixa perder detalhe no rosto.
  * - Escura demais: rosto muito escuro. Clareia até o rosto ficar visível, sem estourar nada.
  * Foto praticamente preta ou toda branca não tem correção: volta em {@code unrecoverable}.
  */
 static Exposure fixExposure(int[] small,int w,int h,Develop.Settings s,Faces finder){
  Exposure r=new Exposure();
  double p95=percentile(small,.95),p05=percentile(small,.05);
  if(p95<20){r.unrecoverable="Foto escura demais (quase preta)";return r;}
  if(p05>247){r.unrecoverable="Foto estourada (quase toda branca)";return r;}
  Develop.Source src=new Develop.Source(small.clone(),w,h);double base=s.exposure;
  int[] out=src.render(s);
  float[][] found=null;try{if(finder!=null)found=finder.find(out,w,h);}catch(Exception ignored){}
  int[][] boxes=faceBoxes(found,w,h);
  Light before=Light.of(small,w,h,boxes),now=Light.of(out,w,h,boxes);
  double clipLimit=Math.max(.06,before.clip*1.5+.03),faceClipLimit=Math.max(.12,before.faceClip+.05);
  boolean faces=now.faceMean>=0;
  if(tooBright(now,faces,clipLimit,faceClipLimit)){
   // Menor escurecimento que resolve (busca binária entre 0 e −2 EV).
   double ok=Double.NaN,lo=-2,hi=0;
   Light atLo=render(src,s,base+lo,boxes,w,h);
   if(!tooBright(atLo,faces,clipLimit,faceClipLimit)){ok=lo;for(int i=0;i<6;i++){double mid=(lo+hi)/2;if(tooBright(render(src,s,base+mid,boxes,w,h),faces,clipLimit,faceClipLimit))hi=mid;else{ok=mid;lo=mid;}}}
   else if(atLo.clip<now.clip*.5||(faces&&atLo.faceClip<now.faceClip*.5))ok=lo;
   if(!Double.isNaN(ok)){r.ev=Math.floor(ok*20)/20;r.note=String.format(Locale.ROOT,"Exposição %+.2f EV: %s",r.ev,faces&&now.faceClip>faceClipLimit?"rosto estourado depois do preset":faces&&now.faceMean>222?"rosto claro demais depois do preset":"luz estourada depois do preset");}
  }else if(faces&&now.faceMean<55){
   // Rosto muito escuro: menor clareamento que deixa o rosto visível (~80) sem estourar.
   double target=80,ok=Double.NaN,lo=0,hi=1.2;Light atHi=render(src,s,base+hi,boxes,w,h);
   if(atHi.faceMean>=target&&!tooBright(atHi,true,clipLimit,faceClipLimit)){ok=hi;for(int i=0;i<6;i++){double mid=(lo+hi)/2;Light m=render(src,s,base+mid,boxes,w,h);if(m.faceMean>=target&&!tooBright(m,true,clipLimit,faceClipLimit)){ok=mid;hi=mid;}else lo=mid;}}
   else if(!tooBright(atHi,true,clipLimit,faceClipLimit))ok=hi;
   if(!Double.isNaN(ok)&&ok>0){r.ev=Math.ceil(ok*20)/20;r.note=String.format(Locale.ROOT,"Exposição %+.2f EV: rosto escuro demais",r.ev);}
  }
  s.exposure=Develop.lim(base+r.ev,-5,5);
  return r;
 }
 static boolean tooBright(Light m,boolean faces,double clipLimit,double faceClipLimit){
  return m.clip>clipLimit||(faces&&(m.faceClip>faceClipLimit||m.faceMean>222));
 }
 static Light render(Develop.Source src,Develop.Settings s,double exposure,int[][] boxes,int w,int h){
  double keep=s.exposure;s.exposure=Develop.lim(exposure,-5,5);try{return Light.of(src.render(s),w,h,boxes);}finally{s.exposure=keep;}
 }
 private PhotoCheck(){}
}
