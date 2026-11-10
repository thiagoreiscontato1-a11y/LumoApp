package com.hisho.tether;

/**
 * Histograma RGB da foto revelada, como o do Lightroom: um canal por cor, com aviso de recorte nos
 * dois extremos (triângulos) e a máscara de recorte (realces estourados em vermelho, sombras sem
 * detalhe em azul). Não depende do Android; ios/LumoCore/Sources/LumoCore/Histogram.swift é a mesma conta.
 */
final class Histogram{
 static final int BINS=256;
 /** Pixel recortado: canal em 254–255 (realces) ou 0–1 (sombras), com a folga da compressão JPEG. */
 static final int HIGH=254,LOW=1;
 /** Amostras contadas no máximo; a foto do editor (~1200 px) é lida inteira quase sempre. */
 static final int MAX_SAMPLES=1<<20;
 static final int RED=0xffff4040,GREEN=0xff40e060,BLUE=0xff4c8dff,WHITE=0xffffffff;
 static final int CLIP_HIGH=0xffff2a2a,CLIP_LOW=0xff2a6bff;

 final int[][] channels=new int[3][BINS];
 /** Pixels contados; recortados por canal (R, G, B) e nos três canais ao mesmo tempo. */
 int total,highAll,lowAll;final int[] high=new int[3],low=new int[3];

 static Histogram of(int[] argb){
  Histogram h=new Histogram();if(argb==null||argb.length==0)return h;
  int step=Math.max(1,(argb.length+MAX_SAMPLES-1)/MAX_SAMPLES);int[] r=h.channels[0],g=h.channels[1],b=h.channels[2];
  for(int i=0;i<argb.length;i+=step){int v=argb[i],R=(v>>16)&255,G=(v>>8)&255,B=v&255;r[R]++;g[G]++;b[B]++;h.total++;
   int hi=0,lo=0;if(R>=HIGH){h.high[0]++;hi++;}if(G>=HIGH){h.high[1]++;hi++;}if(B>=HIGH){h.high[2]++;hi++;}
   if(R<=LOW){h.low[0]++;lo++;}if(G<=LOW){h.low[1]++;lo++;}if(B<=LOW){h.low[2]++;lo++;}
   if(hi==3)h.highAll++;if(lo==3)h.lowAll++;}
  return h;
 }

 /**
  * Altura de cada barra (0–1) dos três canais, suavizada em 3 barras. A referência é o maior pico fora dos
  * extremos: um mar de pixels pretos ou estourados não achata o resto, e as pontas só encostam no topo.
  */
 double[][] levels(){
  double[][] out=new double[3][BINS];double ref=0;
  for(int c=0;c<3;c++){int[] n=channels[c];for(int i=0;i<BINS;i++){double v=(n[Math.max(0,i-1)]+2.0*n[i]+n[Math.min(BINS-1,i+1)])/4;out[c][i]=v;if(i>1&&i<BINS-2)ref=Math.max(ref,v);}}
  if(ref<=0)for(double[] o:out)for(double v:o)ref=Math.max(ref,v);
  if(ref>0)for(double[] o:out)for(int i=0;i<BINS;i++)o[i]=Math.min(1,o[i]/ref);
  return out;
 }

 /** Cor do triângulo de realces: branco se os três canais estouram juntos, a cor dos canais recortados, ou 0 (sem recorte). */
 int highlightWarning(){return warning(highAll,high);}
 /** Cor do triângulo de sombras, com a mesma regra. */
 int shadowWarning(){return warning(lowAll,low);}
 static int warning(int all,int[] channel){
  if(all>0)return WHITE;boolean r=channel[0]>0,g=channel[1]>0,b=channel[2]>0;if(!r&&!g&&!b)return 0;
  return 0xff000000|(r?0xff:0x40)<<16|(g?0xff:0x40)<<8|(b?0xff:0x40);
 }
 /** Fração (0–1) da foto sem detalhe nos realces (três canais estourados) e nas sombras. */
 double highlightFraction(){return total==0?0:highAll/(double)total;}
 double shadowFraction(){return total==0?0:lowAll/(double)total;}

 /** Cópia dos pixels com a máscara de recorte: realces sem detalhe em vermelho, sombras sem detalhe em azul. */
 static int[] clipping(int[] argb){
  int[] out=argb.clone();
  for(int i=0;i<out.length;i++){int v=out[i],R=(v>>16)&255,G=(v>>8)&255,B=v&255;
   if(R>=HIGH&&G>=HIGH&&B>=HIGH)out[i]=CLIP_HIGH;else if(R<=LOW&&G<=LOW&&B<=LOW)out[i]=CLIP_LOW;}
  return out;
 }
}
