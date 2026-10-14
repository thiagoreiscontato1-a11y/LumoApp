package com.hisho.tether;

import android.graphics.drawable.GradientDrawable;

/** Paleta única do Lumo (mesma do iPhone): sempre escura, azul/ciano do logo como destaque. */
final class LumoTheme {
 static final int BG=0xff07090c;
 static final int SURFACE=0xff12161c;
 static final int SURFACE_STRONG=0xff1b2028;
 static final int LINE=0xff222831;
 static final int TEXT=0xffffffff;
 static final int SECONDARY=0x9effffff;
 static final int TERTIARY=0x61ffffff;
 static final int BLUE=0xff0b7cff;
 static final int CYAN=0xff22dff3;
 static final int INDIGO=0xff2446d8;
 static final int OK=0xff33db85;
 static final int WARN=0xffffba38;
 static final int DANGER=0xffff545c;

 static GradientDrawable gradient(float radius){
  GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.BL_TR,new int[]{BLUE,CYAN});d.setCornerRadius(radius);return d;
 }

 static GradientDrawable fill(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}

 static GradientDrawable card(int color,float radius,float stroke){GradientDrawable d=fill(color,radius);d.setStroke(Math.max(1,Math.round(stroke)),LINE);return d;}

 private LumoTheme(){}
}
