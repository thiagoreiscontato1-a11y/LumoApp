package com.hisho.tether;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/**
 * Histograma do editor, no estilo do Lightroom: os três canais somados (onde se sobrepõem fica cinza/branco),
 * triângulos de recorte nos cantos (acesos na cor dos canais recortados) e, ao tocar, a máscara de recorte na foto.
 */
final class HistogramView extends View{
 Histogram data;boolean clipping;
 final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG),line=new Paint(Paint.ANTI_ALIAS_FLAG),bg=new Paint(Paint.ANTI_ALIAS_FLAG),tp=new Paint(Paint.ANTI_ALIAS_FLAG),ring=new Paint(Paint.ANTI_ALIAS_FLAG);
 final Path path=new Path(),tri=new Path();final RectF box=new RectF();
 static final int[] CHANNEL={0xff9a1c1c,0xff1c8a2c,0xff1f4fa8};

 HistogramView(Context c){
  super(c);fill.setStyle(Paint.Style.FILL);fill.setBlendMode(BlendMode.PLUS);
  line.setStyle(Paint.Style.STROKE);bg.setColor(0xff07090c);
  setContentDescription("Histograma. Toque para mostrar o recorte na foto");
 }
 void set(Histogram h){data=h;describe();invalidate();}
 void setClipping(boolean on){clipping=on;describe();invalidate();}
 void describe(){
  String s="Histograma";if(data!=null&&data.total>0)s+=String.format(java.util.Locale.getDefault(),". Realces sem detalhe %.1f%%, sombras sem detalhe %.1f%%",data.highlightFraction()*100,data.shadowFraction()*100);
  setContentDescription(s+(clipping?". Recorte visível na foto, toque para esconder":". Toque para mostrar o recorte na foto"));
 }
 float dp(float n){return n*getResources().getDisplayMetrics().density;}

 @Override protected void onDraw(Canvas c){
  float w=getWidth(),h=getHeight(),r=dp(10);box.set(0,0,w,h);c.drawRoundRect(box,r,r,bg);
  float pad=dp(6),top=dp(14),left=pad,right=w-pad,bottom=h-pad,gw=right-left,gh=bottom-top;
  line.setColor(0x22ffffff);line.setStrokeWidth(dp(1));for(int i=1;i<4;i++){float x=left+gw*i/4;c.drawLine(x,top,x,bottom,line);}
  if(data!=null&&data.total>0){
   double[][] lv=data.levels();
   for(int ch=0;ch<3;ch++){path.reset();path.moveTo(left,bottom);for(int i=0;i<Histogram.BINS;i++)path.lineTo(left+gw*i/(Histogram.BINS-1),(float)(bottom-gh*lv[ch][i]));path.lineTo(right,bottom);path.close();fill.setColor(CHANNEL[ch]);c.drawPath(path,fill);}
  }
  triangle(c,pad,dp(3),true,data==null?0:data.shadowWarning());
  triangle(c,w-pad,dp(3),false,data==null?0:data.highlightWarning());
  if(clipping){line.setColor(0xccffffff);line.setStrokeWidth(dp(1.5f));box.set(dp(.75f),dp(.75f),w-dp(.75f),h-dp(.75f));c.drawRoundRect(box,r,r,line);}
 }
 /** Triângulo de recorte: aceso na cor dos canais recortados, apagado (contorno) sem recorte. */
 void triangle(Canvas c,float x,float y,boolean leftSide,int color){
  float s=dp(9);tri.reset();if(leftSide){tri.moveTo(x,y);tri.lineTo(x+s,y);tri.lineTo(x,y+s);}else{tri.moveTo(x,y);tri.lineTo(x-s,y);tri.lineTo(x,y+s);}tri.close();
  if(color!=0){tp.setStyle(Paint.Style.FILL);tp.setColor(color);}else{tp.setStyle(Paint.Style.STROKE);tp.setStrokeWidth(dp(1));tp.setColor(0x66ffffff);}
  if(clipping&&color!=0){ring.setStyle(Paint.Style.STROKE);ring.setStrokeWidth(dp(2));ring.setColor(0xffffffff);c.drawPath(tri,ring);}
  c.drawPath(tri,tp);
 }
}
