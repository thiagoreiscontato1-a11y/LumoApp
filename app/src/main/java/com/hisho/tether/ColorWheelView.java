package com.hisho.tether;

import android.content.Context;
import android.graphics.*;
import android.view.*;

/**
 * Roda de cor da Gradação de cores: o ângulo é a matiz (0–360, vermelho à direita, girando no sentido anti-horário)
 * e a distância do centro é a saturação (0–100). Toque duas vezes para zerar a saturação.
 */
final class ColorWheelView extends View{
 interface Listener{void changed(double hue,double sat,boolean done);}
 Listener listener;double hue,sat;
 final Paint disc=new Paint(Paint.ANTI_ALIAS_FLAG),fade=new Paint(Paint.ANTI_ALIAS_FLAG),ring=new Paint(Paint.ANTI_ALIAS_FLAG),knob=new Paint(Paint.ANTI_ALIAS_FLAG);
 long lastTap;int built=-1;

 ColorWheelView(Context c){super(c);ring.setStyle(Paint.Style.STROKE);knob.setStyle(Paint.Style.STROKE);setContentDescription("Roda de cor");}
 float dp(float n){return n*getResources().getDisplayMetrics().density;}
 void set(double hue,double sat){this.hue=hue;this.sat=sat;setContentDescription(String.format(java.util.Locale.getDefault(),"Roda de cor. Matiz %.0f, saturação %.0f",hue,sat));invalidate();}
 @Override protected void onMeasure(int w,int h){int size=MeasureSpec.getSize(w);setMeasuredDimension(size,size);}
 float radius(){return Math.min(getWidth(),getHeight())/2f-dp(10);}

 void shaders(){
  int size=getWidth();if(size==built||size<=0)return;built=size;float cx=size/2f,cy=getHeight()/2f;
  // SweepGradient gira no sentido horário na tela; a matiz cresce no anti-horário.
  int[] colors=new int[13];for(int i=0;i<=12;i++)colors[i]=Color.HSVToColor(new float[]{(360-i*30)%360,.85f,.9f});
  disc.setShader(new SweepGradient(cx,cy,colors,null));
  fade.setShader(new RadialGradient(cx,cy,Math.max(1,radius()),new int[]{0xff6b6b6b,0x006b6b6b},new float[]{0,1},Shader.TileMode.CLAMP));
 }
 @Override protected void onDraw(Canvas c){
  shaders();float cx=getWidth()/2f,cy=getHeight()/2f,r=radius();
  c.drawCircle(cx,cy,r,disc);c.drawCircle(cx,cy,r,fade);ring.setColor(0x55ffffff);ring.setStrokeWidth(dp(1));c.drawCircle(cx,cy,r,ring);c.drawCircle(cx,cy,r/2,ring);
  double a=Math.toRadians(hue);float kx=cx+(float)(Math.cos(a)*r*sat/100),ky=cy-(float)(Math.sin(a)*r*sat/100);
  knob.setColor(0xff000000);knob.setStrokeWidth(dp(4));c.drawCircle(kx,ky,dp(8),knob);knob.setColor(0xffffffff);knob.setStrokeWidth(dp(2));c.drawCircle(kx,ky,dp(8),knob);
 }
 @Override public boolean onTouchEvent(MotionEvent e){
  float cx=getWidth()/2f,cy=getHeight()/2f,r=radius(),dx=e.getX()-cx,dy=cy-e.getY();
  int action=e.getActionMasked();
  if(action==MotionEvent.ACTION_DOWN){if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);long now=e.getEventTime();if(now-lastTap<320){lastTap=0;sat=0;invalidate();if(listener!=null)listener.changed(hue,0,true);return true;}lastTap=now;}
  if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_UP){
   double h=Math.toDegrees(Math.atan2(dy,dx));if(h<0)h+=360;double s=Math.min(100,Math.hypot(dx,dy)/r*100);
   hue=Math.round(h)%360;sat=Math.round(s);invalidate();if(listener!=null)listener.changed(hue,sat,action==MotionEvent.ACTION_UP);return true;
  }
  return true;
 }
}
