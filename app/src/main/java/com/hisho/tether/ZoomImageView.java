package com.hisho.tether;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/** Imagem com pinça, duplo toque e arraste; sem zoom, o deslizar lateral troca de foto. */
final class ZoomImageView extends ImageView {
 interface Listener { void onSwipe(int direction); void onTap(); }

 private final Matrix base=new Matrix(),zoom=new Matrix(),draw=new Matrix();
 private final ScaleGestureDetector scaler;
 private final GestureDetector gestures;
 private float scale=1f;
 Listener listener;

 ZoomImageView(Context c){
  super(c);
  setScaleType(ScaleType.MATRIX);
  scaler=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
   @Override public boolean onScale(ScaleGestureDetector d){zoomBy(d.getScaleFactor(),d.getFocusX(),d.getFocusY());return true;}
  });
  gestures=new GestureDetector(c,new GestureDetector.SimpleOnGestureListener(){
   @Override public boolean onDown(MotionEvent e){return true;}
   @Override public boolean onSingleTapConfirmed(MotionEvent e){if(listener!=null)listener.onTap();return true;}
   @Override public boolean onDoubleTap(MotionEvent e){if(scale>1.05f)reset();else zoomBy(2.8f,e.getX(),e.getY());return true;}
   @Override public boolean onScroll(MotionEvent a,MotionEvent b,float dx,float dy){if(scale>1.01f){zoom.postTranslate(-dx,-dy);clamp();apply();}return true;}
   @Override public boolean onFling(MotionEvent a,MotionEvent b,float vx,float vy){
    if(scale<=1.01f&&a!=null&&listener!=null&&Math.abs(vx)>Math.abs(vy)*1.3f&&Math.abs(b.getX()-a.getX())>dp(40)){listener.onSwipe(vx<0?1:-1);return true;}
    return false;
   }
  });
 }

 int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}

 @Override public void setImageDrawable(Drawable d){super.setImageDrawable(d);fit();}
 @Override protected void onSizeChanged(int w,int h,int ow,int oh){super.onSizeChanged(w,h,ow,oh);fit();}

 /** Volta ao enquadramento inteiro. */
 void reset(){zoom.reset();scale=1f;apply();}

 private void fit(){
  Drawable d=getDrawable();
  base.reset();zoom.reset();scale=1f;
  if(d!=null&&getWidth()>0&&getHeight()>0&&d.getIntrinsicWidth()>0){
   RectF src=new RectF(0,0,d.getIntrinsicWidth(),d.getIntrinsicHeight()),dst=new RectF(0,0,getWidth(),getHeight());
   base.setRectToRect(src,dst,Matrix.ScaleToFit.CENTER);
  }
  apply();
 }

 private void zoomBy(float factor,float fx,float fy){
  float target=Math.max(1f,Math.min(6f,scale*factor));factor=target/scale;scale=target;
  zoom.postScale(factor,factor,fx,fy);
  if(scale<=1.001f)zoom.reset();
  clamp();apply();
 }

 /** Impede que a foto ampliada saia da tela. */
 private void clamp(){
  Drawable d=getDrawable();if(d==null)return;
  Matrix m=new Matrix(base);m.postConcat(zoom);
  RectF r=new RectF(0,0,d.getIntrinsicWidth(),d.getIntrinsicHeight());m.mapRect(r);
  float dx=0,dy=0,w=getWidth(),h=getHeight();
  if(r.width()<=w)dx=(w-r.width())/2-r.left;else if(r.left>0)dx=-r.left;else if(r.right<w)dx=w-r.right;
  if(r.height()<=h)dy=(h-r.height())/2-r.top;else if(r.top>0)dy=-r.top;else if(r.bottom<h)dy=h-r.bottom;
  zoom.postTranslate(dx,dy);
 }

 private void apply(){draw.set(base);draw.postConcat(zoom);setImageMatrix(draw);}

 @Override public boolean onTouchEvent(MotionEvent e){
  scaler.onTouchEvent(e);
  if(!scaler.isInProgress())gestures.onTouchEvent(e);
  return true;
 }
}
