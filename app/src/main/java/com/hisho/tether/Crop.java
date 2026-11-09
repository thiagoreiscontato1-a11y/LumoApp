package com.hisho.tether;

/**
 * Recorte e endireitar, aplicados nos pixels antes da revelação (a vinheta do Lightroom é "pós-corte").
 * A foto gira {@code angle} graus em torno do centro (positivo = sentido horário) numa tela do mesmo tamanho e o
 * retângulo {@code left, top, right, bottom} (0–1 dessa tela) é recortado, sempre dentro da foto girada.
 * Não depende do Android; ios/LumoCore/Sources/LumoCore/Crop.swift é a mesma conta.
 */
final class Crop{
 static final double MAX_ANGLE=45;
 final double angle,left,top,right,bottom;
 Crop(double angle,double left,double top,double right,double bottom){
  this.angle=Math.max(-MAX_ANGLE,Math.min(MAX_ANGLE,angle));
  double l=clamp01(left),t=clamp01(top),r=clamp01(right),b=clamp01(bottom);
  if(r-l<.02){double c=(l+r)/2;l=Math.max(0,c-.01);r=Math.min(1,l+.02);}if(b-t<.02){double c=(t+b)/2;t=Math.max(0,c-.01);b=Math.min(1,t+.02);}
  this.left=l;this.top=t;this.right=r;this.bottom=b;
 }
 static Crop none(){return new Crop(0,0,0,1,1);}
 boolean equalsCrop(Crop o){return o!=null&&Math.abs(angle-o.angle)<1e-9&&Math.abs(left-o.left)<1e-9&&Math.abs(top-o.top)<1e-9&&Math.abs(right-o.right)<1e-9&&Math.abs(bottom-o.bottom)<1e-9;}
 boolean isIdentity(){return Math.abs(angle)<1e-6&&left<=1e-6&&top<=1e-6&&right>=1-1e-6&&bottom>=1-1e-6;}
 static double clamp01(double v){return Double.isNaN(v)?0:Math.max(0,Math.min(1,v));}

 /** Ponto da tela (pixels) → ponto da foto original (pixels). */
 static double[] toSource(double px,double py,int w,int h,double angle){
  double a=Math.toRadians(angle),c=Math.cos(a),s=Math.sin(a),cx=w/2.0,cy=h/2.0,dx=px-cx,dy=py-cy;
  return new double[]{cx+c*dx+s*dy,cy-s*dx+c*dy};
 }
 static boolean inside(double nx,double ny,int w,int h,double angle){double[] q=toSource(nx*w,ny*h,w,h,angle);return q[0]>=-1e-6&&q[0]<=w+1e-6&&q[1]>=-1e-6&&q[1]<=h+1e-6;}
 static boolean fits(double l,double t,double r,double b,int w,int h,double angle){return inside(l,t,w,h,angle)&&inside(r,t,w,h,angle)&&inside(l,b,w,h,angle)&&inside(r,b,w,h,angle);}

 /** O mesmo recorte encolhido em torno do centro (e, se preciso, levado ao centro) até caber na foto girada. */
 Crop constrained(int w,int h){
  if(fits(left,top,right,bottom,w,h,angle))return this;
  double cx=(left+right)/2,cy=(top+bottom)/2,hw=(right-left)/2,hh=(bottom-top)/2;
  for(int step=0;step<=20;step++){
   double k=step/20.0,x=cx+(.5-cx)*k,y=cy+(.5-cy)*k;
   if(!inside(x,y,w,h,angle))continue;
   double lo=0,hi=1;for(int i=0;i<40;i++){double m=(lo+hi)/2;if(fits(x-hw*m,y-hh*m,x+hw*m,y+hh*m,w,h,angle))lo=m;else hi=m;}
   if(lo>0)return new Crop(angle,x-hw*lo,y-hh*lo,x+hw*lo,y+hh*lo);
  }
  return new Crop(angle,.49,.49,.51,.51);
 }
 /**
  * Maior recorte centrado com a proporção pedida (largura/altura em pixels; 0 = livre, usa a da foto)
  * que cabe na foto girada: o que o Lightroom faz ao endireitar.
  */
 static Crop largest(int w,int h,double angle,double aspect){
  double ratio=aspect>0?aspect:w/(double)h;double nw=1,nh=(w/ratio)/h;if(nh>1){nh=1;nw=(h*ratio)/w;}
  double lo=0,hi=1;for(int i=0;i<40;i++){double m=(lo+hi)/2;if(fits(.5-nw*m/2,.5-nh*m/2,.5+nw*m/2,.5+nh*m/2,w,h,angle))lo=m;else hi=m;}
  return new Crop(angle,.5-nw*lo/2,.5-nh*lo/2,.5+nw*lo/2,.5+nh*lo/2);
 }
 /** Tamanho do resultado em pixels para uma foto w×h. */
 int[] size(int w,int h){return new int[]{Math.max(1,(int)Math.round((right-left)*w)),Math.max(1,(int)Math.round((bottom-top)*h))};}

 /** Pixels recortados (e girados) com interpolação bilinear; {@code out} recebe largura e altura. */
 int[] apply(int[] px,int w,int h,int[] out){
  int[] sz=size(w,h);final int ow=sz[0],oh=sz[1];out[0]=ow;out[1]=oh;
  if(isIdentity()&&ow==w&&oh==h)return px;
  final int[] dst=new int[ow*oh];final double a=Math.toRadians(angle),c=Math.cos(a),s=Math.sin(a),cx=w/2.0,cy=h/2.0,x0=left*w,y0=top*h;
  final boolean rotate=Math.abs(angle)>1e-9;
  Develop.parallel(oh,(ya,yb)->{for(int y=ya;y<yb;y++){double py=y0+y+.5-cy;for(int x=0;x<ow;x++){double pxx=x0+x+.5-cx;
   double sx,sy;if(rotate){sx=cx+c*pxx+s*py-.5;sy=cy-s*pxx+c*py-.5;}else{sx=cx+pxx-.5;sy=cy+py-.5;}
   dst[y*ow+x]=sample(px,w,h,sx,sy);}}});
  return dst;
 }
 /** Foto inteira girada na tela do mesmo tamanho, com {@code fill} fora dela (prévia do modo Corte). */
 static int[] rotated(int[] px,int w,int h,double angle,int fill){
  if(Math.abs(angle)<1e-9)return px;final int[] dst=new int[w*h];final double a=Math.toRadians(angle),c=Math.cos(a),s=Math.sin(a),cx=w/2.0,cy=h/2.0;
  Develop.parallel(h,(ya,yb)->{for(int y=ya;y<yb;y++){double py=y+.5-cy;for(int x=0;x<w;x++){double pxx=x+.5-cx,sx=cx+c*pxx+s*py,sy=cy-s*pxx+c*py;
   dst[y*w+x]=sx<0||sy<0||sx>w||sy>h?fill:sample(px,w,h,sx-.5,sy-.5);}}});
  return dst;
 }
 static int sample(int[] px,int w,int h,double x,double y){
  if(x<0)x=0;if(y<0)y=0;if(x>w-1)x=w-1;if(y>h-1)y=h-1;int x0=(int)x,y0=(int)y,x1=Math.min(w-1,x0+1),y1=Math.min(h-1,y0+1);double fx=x-x0,fy=y-y0;
  int a=px[y0*w+x0],b=px[y0*w+x1],c=px[y1*w+x0],d=px[y1*w+x1];
  if(fx==0&&fy==0)return a;
  double w00=(1-fx)*(1-fy),w10=fx*(1-fy),w01=(1-fx)*fy,w11=fx*fy;
  int r=(int)Math.round(((a>>16)&255)*w00+((b>>16)&255)*w10+((c>>16)&255)*w01+((d>>16)&255)*w11);
  int g=(int)Math.round(((a>>8)&255)*w00+((b>>8)&255)*w10+((c>>8)&255)*w01+((d>>8)&255)*w11);
  int bl=(int)Math.round((a&255)*w00+(b&255)*w10+(c&255)*w01+(d&255)*w11);
  return 0xff000000|r<<16|g<<8|bl;
 }
}
