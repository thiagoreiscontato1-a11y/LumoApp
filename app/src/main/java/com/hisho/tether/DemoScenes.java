package com.hisho.tether;

import android.graphics.*;
import java.io.*;
import java.util.Random;

/** Cenas sintéticas de festa para o modo de demonstração (capturas de tela no emulador), iguais às do iPhone. */
final class DemoScenes {
 static final int[][] PALETTES={
  {0xfffa9e5c,0xff6b2961,0xffffffff},{0xfff2d69e,0xff8c6138,0xffffe699},{0xff3352a0,0xff0d0f29,0xff99d9ff},
  {0xff120f14,0xff08080a,0xff333333},{0xff9ed1b3,0xff2e573d,0xffffffff},{0xfffcbdb3,0xff994766,0xffffd9e6},
  {0xff4d2e7a,0xff1a0d33,0xffff8cf2},{0xfffae08c,0xffd97333,0xffffffff}};

 static int count(){return PALETTES.length;}

 /** Desenha a cena `i` e grava como JPEG. A cena 3 é escura e lisa (vai para Revisão). */
 static void render(int i,File target)throws IOException{
  int w=1800,h=1200;int[] p=PALETTES[i%PALETTES.length];boolean dark=i%PALETTES.length==3;Random rng=new Random(i+7);
  Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
  paint.setShader(new LinearGradient(0,0,0,h,p[0],p[1],Shader.TileMode.CLAMP));c.drawRect(0,0,w,h,paint);paint.setShader(null);
  for(int k=0;k<38;k++){float r=18+rng.nextFloat()*62,x=rng.nextFloat()*w,y=rng.nextFloat()*h*.65f;for(float s=1f;s>0;s-=.2f){paint.setColor((p[2]&0x00ffffff)|0x10000000);c.drawCircle(x,y,r*s,paint);}}
  if(!dark){for(int k=0;k<750;k++){float hue=rng.nextFloat()*360;paint.setColor(Color.HSVToColor(new float[]{hue,.6f,rng.nextBoolean()?1f:.35f}));c.save();c.translate(rng.nextFloat()*w,rng.nextFloat()*h);c.rotate(rng.nextFloat()*180);c.drawRect(-11,-4,11,4,paint);c.restore();}}
  paint.setColor(dark?0xff050505:0xeb0f0d14);
  float[][] people={{-150,.62f},{130,.56f}};
  for(float[] person:people){float cx=w/2f+person[0]+(rng.nextFloat()*120-60),height=h*person[1],head=height*.16f;
   c.drawOval(new RectF(cx-head/2,h-height,cx+head/2,h-height+head*1.15f),paint);
   c.drawRoundRect(new RectF(cx-head*1.05f,h-height+head*1.05f,cx+head*1.05f,h+height),head*.8f,head*.8f,paint);}
  try(OutputStream out=new FileOutputStream(target)){b.compress(Bitmap.CompressFormat.JPEG,92,out);}finally{b.recycle();}
 }

 private DemoScenes(){}
}
