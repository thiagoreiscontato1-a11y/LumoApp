package com.hisho.tether;

import android.content.Context;
import android.graphics.*;
import android.view.*;

/**
 * Curva de tons de pontos, como a do Lightroom: toque na linha cria um ponto, arraste para mover, arraste para
 * fora (ou toque duas vezes) para apagar. Os pontos ficam em 0–255 ({x0,y0,x1,y1…}) e a linha usa a mesma spline
 * do motor ({@link Develop#spline}).
 */
final class CurveView extends View{
 interface Listener{void changed(double[] points,boolean done);}
 Listener listener;
 double[] points={0,0,255,255};int color=0xffffffff;Histogram histogram;
 final Paint grid=new Paint(Paint.ANTI_ALIAS_FLAG),line=new Paint(Paint.ANTI_ALIAS_FLAG),dot=new Paint(Paint.ANTI_ALIAS_FLAG),hist=new Paint(Paint.ANTI_ALIAS_FLAG),bg=new Paint();
 final Path path=new Path();
 int dragging=-1;long lastTap;float downX,downY;boolean moved;

 CurveView(Context c){
  super(c);grid.setStyle(Paint.Style.STROKE);line.setStyle(Paint.Style.STROKE);line.setStrokeWidth(dp(2));hist.setColor(0x22ffffff);bg.setColor(0xff07090c);
  setContentDescription("Curva de tons. Toque na linha para criar um ponto e arraste para ajustar");
 }
 float dp(float n){return n*getResources().getDisplayMetrics().density;}
 void set(double[] pts,int color){points=valid(pts)?pts.clone():new double[]{0,0,255,255};this.color=color;invalidate();}
 static boolean valid(double[] p){return p!=null&&p.length>=4&&p.length%2==0;}
 @Override protected void onMeasure(int w,int h){int size=MeasureSpec.getSize(w);setMeasuredDimension(size,size);}

 float pad(){return dp(10);}
 float gx(double x){return pad()+(float)(x/255*(getWidth()-2*pad()));}
 float gy(double y){return getHeight()-pad()-(float)(y/255*(getHeight()-2*pad()));}
 double vx(float x){return Math.max(0,Math.min(255,(x-pad())/(getWidth()-2*pad())*255));}
 double vy(float y){return Math.max(0,Math.min(255,(getHeight()-pad()-y)/(getHeight()-2*pad())*255));}
 double[] normalized(){double[] n=new double[points.length];for(int i=0;i<n.length;i++)n[i]=points[i]/255;return n;}

 @Override protected void onDraw(Canvas c){
  float w=getWidth(),h=getHeight(),p=pad();c.drawRoundRect(0,0,w,h,dp(10),dp(10),bg);
  if(histogram!=null&&histogram.total>0){double[][] lv=histogram.levels();path.reset();path.moveTo(gx(0),gy(0));for(int i=0;i<256;i++)path.lineTo(gx(i),gy(255*Math.max(lv[0][i],Math.max(lv[1][i],lv[2][i]))*.85));path.lineTo(gx(255),gy(0));path.close();c.drawPath(path,hist);}
  grid.setColor(0x26ffffff);grid.setStrokeWidth(dp(1));for(int i=1;i<4;i++){float x=p+(w-2*p)*i/4,y=p+(h-2*p)*i/4;c.drawLine(x,p,x,h-p,grid);c.drawLine(p,y,w-p,y,grid);}
  grid.setColor(0x40ffffff);c.drawLine(gx(0),gy(0),gx(255),gy(255),grid);
  double[] n=normalized();path.reset();for(int i=0;i<=128;i++){double x=i/128.0,y=Develop.spline(n,x);float X=gx(x*255),Y=gy(y*255);if(i==0)path.moveTo(X,Y);else path.lineTo(X,Y);}
  line.setColor(color);c.drawPath(path,line);
  for(int i=0;i<points.length;i+=2){dot.setStyle(Paint.Style.FILL);dot.setColor(i/2==dragging?color:0xff07090c);c.drawCircle(gx(points[i]),gy(points[i+1]),dp(6),dot);dot.setStyle(Paint.Style.STROKE);dot.setStrokeWidth(dp(2));dot.setColor(color);c.drawCircle(gx(points[i]),gy(points[i+1]),dp(6),dot);}
 }

 int nearest(float x,float y){int best=-1;double bd=dp(24);for(int i=0;i<points.length;i+=2){double d=Math.hypot(gx(points[i])-x,gy(points[i+1])-y);if(d<bd){bd=d;best=i/2;}}return best;}
 void remove(int k){if(points.length<=4||k<=0||k>=points.length/2-1)return;double[] n=new double[points.length-2];for(int i=0,j=0;i<points.length;i+=2)if(i/2!=k){n[j++]=points[i];n[j++]=points[i+1];}points=n;}
 int insert(double x,double y){int k=0;while(k<points.length/2&&points[2*k]<x)k++;if(k<points.length/2&&Math.abs(points[2*k]-x)<1)return -1;double[] n=new double[points.length+2];System.arraycopy(points,0,n,0,2*k);n[2*k]=x;n[2*k+1]=y;System.arraycopy(points,2*k,n,2*k+2,points.length-2*k);points=n;return k;}
 void notifyChange(boolean done){invalidate();if(listener!=null)listener.changed(points.clone(),done);}

 @Override public boolean onTouchEvent(MotionEvent e){
  float x=e.getX(),y=e.getY();
  switch(e.getActionMasked()){
   case MotionEvent.ACTION_DOWN:{
    if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);downX=x;downY=y;moved=false;
    int k=nearest(x,y);long now=e.getEventTime();
    if(k>=0&&now-lastTap<320){remove(k);dragging=-1;lastTap=0;notifyChange(true);return true;}lastTap=now;
    if(k<0){double vxv=vx(x),onCurve=255*Develop.spline(normalized(),vxv/255);if(Math.abs(gy(onCurve)-y)>dp(28))return true;k=insert(vxv,onCurve);if(k<0)return true;notifyChange(false);}
    dragging=k;invalidate();return true;}
   case MotionEvent.ACTION_MOVE:{
    if(dragging<0)return true;if(Math.hypot(x-downX,y-downY)>dp(4))moved=true;if(!moved)return true;
    int k=dragging,last=points.length/2-1;
    // Arrastar um ponto do meio para bem longe da área apaga o ponto, como no Lightroom.
    if(k>0&&k<last&&(y<-dp(36)||y>getHeight()+dp(36))){remove(k);dragging=-1;notifyChange(false);return true;}
    double lo=k==0?0:points[2*(k-1)]+1,hi=k==last?255:points[2*(k+1)]-1;points[2*k]=Math.max(lo,Math.min(hi,vx(x)));points[2*k+1]=vy(y);notifyChange(false);return true;}
   case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:
    if(dragging>=0||moved)notifyChange(true);dragging=-1;invalidate();return true;
  }
  return true;
 }
}
