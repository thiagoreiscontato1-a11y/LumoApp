package com.hisho.tether;
import java.util.*;

/** Histograma e máscara de recorte do editor, e os padrões do Lightroom mostrados nos sliders. Rodado por tests/histogram_test.py. */
public class HistogramTest{
 static int checks=0;
 static void check(boolean b,String what){checks++;if(!b)throw new AssertionError(what);}
 static int rgb(int r,int g,int b){return 0xff000000|r<<16|g<<8|b;}
 static int[] fill(int n,int c){int[] p=new int[n];Arrays.fill(p,c);return p;}

 public static void main(String[] args){
  Histogram empty=Histogram.of(new int[0]);
  check(empty.total==0&&empty.highlightWarning()==0&&empty.shadowWarning()==0,"vazio sem aviso");
  for(double[] l:empty.levels())for(double v:l)check(v==0,"vazio zerado");

  // Cinza médio: um pico em 128 nos três canais, sem recorte.
  Histogram gray=Histogram.of(fill(1000,rgb(128,128,128)));
  check(gray.total==1000,"conta todos");
  for(int c=0;c<3;c++){check(gray.channels[c][128]==1000,"bin 128 canal "+c);check(Math.abs(gray.levels()[c][128]-1)<1e-9,"pico no topo");}
  check(gray.highlightWarning()==0&&gray.shadowWarning()==0,"cinza sem recorte");
  check(gray.highlightFraction()==0&&gray.shadowFraction()==0,"cinza sem fração");

  // Preto e branco puros: os dois triângulos acesos em branco, fração certa.
  int[] bw=new int[400];for(int i=0;i<bw.length;i++)bw[i]=i<300?rgb(255,255,255):i<350?rgb(0,0,0):rgb(90,140,200);
  Histogram h=Histogram.of(bw);
  check(h.highlightWarning()==Histogram.WHITE,"realces estourados: branco");
  check(h.shadowWarning()==Histogram.WHITE,"sombras sem detalhe: branco");
  check(Math.abs(h.highlightFraction()-.75)<1e-9,"75% estourado");
  check(Math.abs(h.shadowFraction()-.125)<1e-9,"12,5% preto");
  // A referência ignora os extremos: o mar de branco não achata o resto, e as pontas param no topo.
  double[][] lv=h.levels();check(lv[0][255]==1&&lv[0][0]==1,"pontas limitadas a 1");check(lv[0][90]==1,"pico do meio não achatado "+lv[0][90]);
  for(double[] l:lv)for(double v:l)check(v>=0&&v<=1,"alturas 0–1");

  // Vermelho saturado: só o canal R estoura e G/B ficam no zero; nada sem detalhe nos três.
  Histogram red=Histogram.of(fill(50,rgb(255,0,0)));
  check(red.highAll==0&&red.lowAll==0,"vermelho não recorta os três canais");
  check(red.highlightWarning()==0xffff4040,"triângulo de realces vermelho "+Integer.toHexString(red.highlightWarning()));
  check(red.shadowWarning()==0xff40ffff,"triângulo de sombras ciano (G e B) "+Integer.toHexString(red.shadowWarning()));

  // Máscara: branco vira vermelho, preto vira azul, o resto fica igual; o original não muda.
  int[] src={rgb(255,255,255),rgb(254,255,254),rgb(0,0,0),rgb(1,0,1),rgb(255,0,0),rgb(30,60,90)};int[] copy=src.clone();
  int[] m=Histogram.clipping(src);
  check(Arrays.equals(src,copy),"clipping não altera a entrada");
  check(m[0]==Histogram.CLIP_HIGH&&m[1]==Histogram.CLIP_HIGH,"realce sem detalhe em vermelho");
  check(m[2]==Histogram.CLIP_LOW&&m[3]==Histogram.CLIP_LOW,"sombra sem detalhe em azul");
  check(m[4]==src[4]&&m[5]==src[5],"cores normais intactas");

  // Foto grande: amostragem limitada, proporções mantidas.
  int[] big=new int[3_000_000];for(int i=0;i<big.length;i++)big[i]=i%2==0?rgb(255,255,255):rgb(100,100,100);
  Histogram hb=Histogram.of(big);check(hb.total<=Histogram.MAX_SAMPLES&&hb.total>0,"amostragem limitada "+hb.total);

  // Sliders extras: valor sem preset = padrão do Lightroom, igual ao que o motor usa.
  Map<String,Double> none=new HashMap<>();Develop.Settings d=Develop.Settings.of(none);
  check(Develop.value("SharpenRadius",none)==d.sharpenRadius,"raio padrão");check(Develop.value("SharpenDetail",none)==d.sharpenDetail,"detalhe padrão");
  check(Develop.value("PostCropVignetteMidpoint",none)==d.vignetteMidpoint,"ponto médio padrão");check(Develop.value("PostCropVignetteFeather",none)==d.vignetteFeather,"difusão padrão");
  check(Develop.value("GrainSize",none)==d.grainSize&&Develop.value("GrainFrequency",none)==d.grainRoughness,"granulação padrão");
  check(Develop.value("PostCropVignetteAmount",none)==0&&Develop.value("Texture",none)==0,"demais em zero");
  Map<String,Double> p=new HashMap<>();p.put("PostCropVignetteAmount",-30.0);check(Develop.value("PostCropVignetteAmount",p)==-30,"valor do preset");
  for(String k:Develop.DEFAULTS.keySet())check(Develop.RANGES.containsKey(k),"padrão de chave conhecida "+k);

  System.out.println("PASS: "+checks+" verificações do histograma, recorte e padrões dos sliders");
 }
}
