package com.hisho.tether;

import android.content.Context;
import android.graphics.*;
import android.view.*;

/**
 * Moldura do modo Corte por cima da prévia (foto inteira, já girada): arraste os cantos ou as bordas para
 * recortar e o meio para mover. O recorte nunca sai da foto girada ({@link Crop#fits}); com proporção travada,
 * os cantos mantêm a proporção.
 */
final class CropView extends View{
 interface Listener{void changed(Crop crop,boolean done);}
 Listener listener;Crop crop=Crop.none();int imageW=1,imageH=1;double aspect=0;
 final Paint dim=new Paint(),frame=new Paint(Paint.ANTI_ALIAS_FLAG),thirds=new Paint(Paint.ANTI_ALIAS_FLAG),handle=new Paint(Paint.ANTI_ALIAS_FLAG);
 final RectF img=new RectF(),box=new RectF();
 int mode=-1;float lastX,lastY;Crop start;float startX,startY;
 static final int MOVE=0,L=1,T=2,R=4,B=8;

 CropView(Context c){
  super(c);dim.setColor(0xaa000000);frame.setStyle(Paint.Style.STROKE);frame.setColor(0xffffffff);thirds.setStyle(Paint.Style.STROKE);thirds.setColor(0x80ffffff);handle.setColor(0xffffffff);
  setContentDescription("Moldura de recorte. Arraste os cantos para recortar");
 }
 float dp(float n){return n*getResources().getDisplayMetrics().density;}
 void set(Crop c,int w,int h,double aspect){crop=c;imageW=Math.max(1,w);imageH=Math.max(1,h);this.aspect=aspect;invalidate();}
 /** Área da foto na view (FIT_CENTER, como a prévia). */
 void layoutImage(){float vw=getWidth(),vh=getHeight(),s=Math.min(vw/imageW,vh/imageH),w=imageW*s,h=imageH*s;img.set((vw-w)/2,(vh-h)/2,(vw+w)/2,(vh+h)/2);}
 void cropRect(Crop c,RectF out){out.set(img.left+(float)c.left*img.width(),img.top+(float)c.top*img.height(),img.left+(float)c.right*img.width(),img.top+(float)c.bottom*img.height());}

 @Override protected void onDraw(Canvas c){
  layoutImage();cropRect(crop,box);float w=getWidth(),h=getHeight();
  c.drawRect(0,0,w,box.top,dim);c.drawRect(0,box.bottom,w,h,dim);c.drawRect(0,box.top,box.left,box.bottom,dim);c.drawRect(box.right,box.top,w,box.bottom,dim);
  thirds.setStrokeWidth(dp(1));for(int i=1;i<3;i++){float x=box.left+box.width()*i/3,y=box.top+box.height()*i/3;c.drawLine(x,box.top,x,box.bottom,thirds);c.drawLine(box.left,y,box.right,y,thirds);}
  frame.setStrokeWidth(dp(1.5f));c.drawRect(box,frame);
  float k=dp(18),t=dp(4);
  for(int i=0;i<4;i++){float x=(i&1)==0?box.left:box.right,y=(i&2)==0?box.top:box.bottom,sx=(i&1)==0?1:-1,sy=(i&2)==0?1:-1;
   c.drawRect(Math.min(x,x+sx*k),Math.min(y,y+sy*t),Math.max(x,x+sx*k),Math.max(y,y+sy*t),handle);c.drawRect(Math.min(x,x+sx*t),Math.min(y,y+sy*k),Math.max(x,x+sx*t),Math.max(y,y+sy*k),handle);}
 }

 int hit(float x,float y){
  cropRect(crop,box);float r=dp(28);int m=0;
  if(Math.abs(x-box.left)<r)m|=L;else if(Math.abs(x-box.right)<r)m|=R;
  if(Math.abs(y-box.top)<r)m|=T;else if(Math.abs(y-box.bottom)<r)m|=B;
  boolean inX=x>box.left-r&&x<box.right+r,inY=y>box.top-r&&y<box.bottom+r;
  if(m!=0&&inX&&inY){if((m&(L|R))!=0&&!inY)return -1;return m;}
  return box.contains(x,y)?MOVE:-1;
 }
 /** Recorte proposto pelo arraste (dx, dy em fração da foto) a partir de {@code start}. */
 Crop proposal(int m,double dx,double dy){
  double l=start.left,t=start.top,r=start.right,b=start.bottom;
  if(m==MOVE){l+=dx;r+=dx;t+=dy;b+=dy;if(l<0){r-=l;l=0;}if(r>1){l-=r-1;r=1;}if(t<0){b-=t;t=0;}if(b>1){t-=b-1;b=1;}return new Crop(start.angle,l,t,r,b);}
  if((m&L)!=0)l+=dx;if((m&R)!=0)r+=dx;if((m&T)!=0)t+=dy;if((m&B)!=0)b+=dy;
  double min=.05;if(r-l<min){if((m&L)!=0)l=r-min;else r=l+min;}if(b-t<min){if((m&T)!=0)t=b-min;else b=t+min;}
  if(aspect>0){
   // Proporção em pixels: largura·W / (altura·H) = aspect. Borda sozinha ajusta a outra dimensão em torno do centro.
   double want=aspect*imageH/imageW;
   if((m&(L|R))!=0&&(m&(T|B))!=0){double w=r-l,hh=b-t;if(w/hh>want)hh=w/want;else w=hh*want;if((m&L)!=0)l=r-w;else r=l+w;if((m&T)!=0)t=b-hh;else b=t+hh;}
   else if((m&(L|R))!=0){double w=r-l,hh=w/want,cy=(t+b)/2;t=cy-hh/2;b=cy+hh/2;}
   else{double hh=b-t,w=hh*want,cx=(l+r)/2;l=cx-w/2;r=cx+w/2;}
  }
  return new Crop(start.angle,l,t,r,b);
 }
 boolean ok(Crop c){return c.left>=-1e-9&&c.top>=-1e-9&&c.right<=1+1e-9&&c.bottom<=1+1e-9&&Crop.fits(c.left,c.top,c.right,c.bottom,imageW,imageH,c.angle);}

 @Override public boolean onTouchEvent(MotionEvent e){
  float x=e.getX(),y=e.getY();
  switch(e.getActionMasked()){
   case MotionEvent.ACTION_DOWN:mode=hit(x,y);if(mode<0)return true;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);start=crop;startX=x;startY=y;return true;
   case MotionEvent.ACTION_MOVE:{
    if(mode<0)return true;layoutImage();double dx=(x-startX)/img.width(),dy=(y-startY)/img.height();
    // Maior parte do arraste que ainda cabe na foto girada.
    Crop best=null;double lo=0,hi=1;Crop full=proposal(mode,dx,dy);if(ok(full))best=full;else{for(int i=0;i<18;i++){double m=(lo+hi)/2;Crop c=proposal(mode,dx*m,dy*m);if(ok(c)){lo=m;best=c;}else hi=m;}}
    if(best!=null){crop=best;invalidate();if(listener!=null)listener.changed(crop,false);}return true;}
   case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:if(mode>=0&&listener!=null)listener.changed(crop,true);mode=-1;return true;
  }
  return true;
 }
}
