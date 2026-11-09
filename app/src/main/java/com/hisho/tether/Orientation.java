package com.hisho.tether;

/**
 * Orientação EXIF (1–8) aplicada ao copiar os pixels, sem criar um segundo bitmap girado.
 * Mesmo resultado das matrizes que o PhotoEditor.decode usa (5 = transposta, 6 = 90° horário,
 * 7 = transversa, 8 = 90° anti-horário). Sem dependência do Android: testado no desktop.
 */
final class Orientation{
 /** Orientações 5–8 trocam largura e altura. */
 static boolean swaps(int orientation){return orientation>=5&&orientation<=8;}

 /** Copia a linha {@code y} (largura w, foto w×h no arquivo) para a posição já orientada em {@code out}. */
 static void place(int[] row,int y,int w,int h,int orientation,int[] out){
  switch(orientation){
   case 2:{int base=y*w+w-1;for(int x=0;x<w;x++)out[base-x]=row[x];break;}
   case 3:{int base=(h-1-y)*w+w-1;for(int x=0;x<w;x++)out[base-x]=row[x];break;}
   case 4:System.arraycopy(row,0,out,(h-1-y)*w,w);break;
   case 5:for(int x=0;x<w;x++)out[x*h+y]=row[x];break;
   case 6:for(int x=0;x<w;x++)out[x*h+h-1-y]=row[x];break;
   case 7:for(int x=0;x<w;x++)out[(w-1-x)*h+h-1-y]=row[x];break;
   case 8:for(int x=0;x<w;x++)out[(w-1-x)*h+y]=row[x];break;
   default:System.arraycopy(row,0,out,y*w,w);
  }
 }
 private Orientation(){}
}
