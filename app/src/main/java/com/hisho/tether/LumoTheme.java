package com.hisho.tether;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

/** Paleta única do Lumo (mesma do iPhone): sempre escura, azul/ciano do logo como destaque. */
final class LumoTheme {
 static final int BG=0xff07090c;
 static final int SURFACE=0xff12161c;
 static final int SURFACE_STRONG=0xff1b2028;
 static final int LINE=0xff222831;
 static final int TEXT=0xffffffff;
 static final int SECONDARY=0x9effffff;
 /** Rótulos pequenos: 55% de branco mantém 4,5:1 ou mais sobre o fundo e as superfícies. */
 static final int TERTIARY=0x8cffffff;
 static final int BLUE=0xff0b7cff;
 /** Azul dos botões sólidos com texto branco (5,2:1); o BLUE do logo fica para destaques. */
 static final int BLUE_SOLID=0xff0a66e6;
 static final int CYAN=0xff22dff3;
 static final int INDIGO=0xff2446d8;
 static final int OK=0xff33db85;
 static final int WARN=0xffffba38;
 static final int DANGER=0xffff545c;
 /** Texto sobre o degradê azul→ciano: escuro, porque branco não passa de 2,5:1 no ciano. */
 static final int ON_ACCENT=0xff07090c;

 static GradientDrawable gradient(float radius){
  GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.BL_TR,new int[]{BLUE,CYAN});d.setCornerRadius(radius);return d;
 }

 static GradientDrawable fill(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}

 static GradientDrawable card(int color,float radius,float stroke){GradientDrawable d=fill(color,radius);d.setStroke(Math.max(1,Math.round(stroke)),LINE);return d;}

 /** Ícone vetorial de res/drawable (ic_<nome>), já tingido. O build não gera R, então o recurso é buscado pelo nome. */
 static Drawable icon(Context c,String name,int color,int size){
  int id=c.getResources().getIdentifier("ic_"+name,"drawable",c.getPackageName());if(id==0)return null;
  Drawable d=c.getDrawable(id);if(d==null)return null;d=d.mutate();d.setTint(color);d.setBounds(0,0,size,size);return d;
 }

 static GradientDrawable dot(int color,int size){GradientDrawable d=new GradientDrawable();d.setShape(GradientDrawable.OVAL);d.setColor(color);d.setSize(size,size);d.setBounds(0,0,size,size);return d;}

 private LumoTheme(){}
}
