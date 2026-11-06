package com.hisho.tether;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Modal do Lumo: folha que sobe da base no celular em pé e cartão centralizado no tablet ou deitado.
 * Linhas com ícone, título, subtítulo e indicador; rodapé com ações. Substitui os AlertDialog de lista.
 */
final class LumoSheet {
 static final int PRIMARY=0,SECONDARY=1,DANGER=2,TEXT=3;

 final Activity a;final Dialog dialog;final boolean bottom;
 private final LinearLayout card,body,footer;private final TextView titleView,subtitleView;
 private final ScrollView scroll;private boolean keepOpen;private Runnable dismissed;

 LumoSheet(Activity activity,String title,String subtitle){this(activity,title,subtitle,false);}

 /** {@code floating}: sempre na base e com pouco escurecimento (tour sobre a tela real). */
 LumoSheet(Activity activity,String title,String subtitle,boolean floating){
  a=activity;Configuration cfg=a.getResources().getConfiguration();
  bottom=floating||(cfg.screenWidthDp<600&&cfg.orientation!=Configuration.ORIENTATION_LANDSCAPE);
  dialog=new Dialog(a);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

  FrameLayout root=new FrameLayout(a);int edge=dp(bottom?8:24);root.setPadding(edge,edge,edge,edge);
  root.setOnApplyWindowInsetsListener((v,in)->{v.setPadding(edge+in.getSystemWindowInsetLeft(),edge+in.getSystemWindowInsetTop(),edge+in.getSystemWindowInsetRight(),edge+in.getSystemWindowInsetBottom());return in;});
  card=new LinearLayout(a);card.setOrientation(LinearLayout.VERTICAL);
  GradientDrawable bg=LumoTheme.card(LumoTheme.SURFACE,dp(28),dp(1));card.setBackground(bg);card.setClipToOutline(true);card.setElevation(dp(12));
  card.setPadding(dp(8),dp(bottom?8:18),dp(8),dp(12));
  int maxWidth=dp(floating?640:560);
  root.addView(card,new FrameLayout.LayoutParams(Math.min(maxWidth,a.getResources().getDisplayMetrics().widthPixels-2*edge),-2,Gravity.CENTER_HORIZONTAL|(bottom?Gravity.BOTTOM:Gravity.CENTER)));

  if(bottom){View handle=new View(a);handle.setBackground(LumoTheme.fill(LumoTheme.LINE,dp(2)));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dp(36),dp(4));hp.gravity=Gravity.CENTER_HORIZONTAL;hp.bottomMargin=dp(10);card.addView(handle,hp);}

  LinearLayout head=new LinearLayout(a);head.setGravity(Gravity.CENTER_VERTICAL);head.setPadding(dp(12),0,0,0);
  LinearLayout texts=new LinearLayout(a);texts.setOrientation(LinearLayout.VERTICAL);
  titleView=label(title,21,LumoTheme.TEXT,true);texts.addView(titleView);
  subtitleView=label(subtitle==null?"":subtitle,14,LumoTheme.SECONDARY,false);subtitleView.setPadding(0,dp(3),0,0);subtitleView.setLineSpacing(dp(2),1);subtitleView.setVisibility(subtitle==null||subtitle.isEmpty()?View.GONE:View.VISIBLE);texts.addView(subtitleView);
  head.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
  ImageButton close=new ImageButton(a);close.setImageDrawable(LumoTheme.icon(a,"close",LumoTheme.SECONDARY,dp(22)));close.setScaleType(ImageView.ScaleType.CENTER);close.setContentDescription("Fechar");close.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff),LumoTheme.fill(LumoTheme.SURFACE_STRONG,dp(24)),null));close.setOnClickListener(v->dialog.cancel());
  head.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48)));
  card.addView(head);

  final int maxBody=(int)(a.getResources().getDisplayMetrics().heightPixels*(floating?.42f:.62f));
  scroll=new ScrollView(a){@Override protected void onMeasure(int w,int h){super.onMeasure(w,View.MeasureSpec.makeMeasureSpec(maxBody,View.MeasureSpec.AT_MOST));}};
  scroll.setVerticalScrollBarEnabled(false);scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
  body=new LinearLayout(a);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(0,dp(10),0,dp(4));scroll.addView(body);
  card.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
  footer=new LinearLayout(a);footer.setGravity(Gravity.CENTER_VERTICAL|Gravity.END);footer.setPadding(dp(8),dp(10),dp(8),dp(2));footer.setVisibility(View.GONE);
  card.addView(footer,new LinearLayout.LayoutParams(-1,-2));

  dialog.setContentView(root);
  root.setOnClickListener(v->{if(dialogCancelable&&outside)dialog.cancel();});card.setClickable(true);
  Window w=dialog.getWindow();
  if(w!=null){w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setDimAmount(floating?.18f:.62f);w.setWindowAnimations(bottom?android.R.style.Animation_InputMethod:android.R.style.Animation_Dialog);}
  dialog.setOnDismissListener(d->{if(dismissed!=null)dismissed.run();});
 }
 private boolean dialogCancelable=true,outside=true;

 int dp(float n){return Math.round(n*a.getResources().getDisplayMetrics().density);}

 TextView label(String value,float size,int color,boolean bold){TextView t=new TextView(a);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));return t;}

 LumoSheet subtitle(String s){subtitleView.setText(s);subtitleView.setVisibility(s==null||s.isEmpty()?View.GONE:View.VISIBLE);return this;}

 /** Título de grupo em versalete pequeno. */
 LumoSheet section(String name){TextView t=label(name.toUpperCase(java.util.Locale.ROOT),12,LumoTheme.TERTIARY,true);t.setLetterSpacing(.1f);t.setPadding(dp(12),dp(14),dp(12),dp(6));body.addView(t);return this;}

 /** Linha de ação: ícone num círculo tingido, título, subtítulo opcional e seta. Fecha o modal e executa. */
 LumoSheet item(String icon,int tint,String title,String subtitle,Runnable action){return row(icon,tint,title,subtitle,null,false,action);}

 /** Linha de escolha: marca a opção atual com um check. */
 LumoSheet choice(String icon,int tint,String title,String subtitle,boolean selected,Runnable action){return row(icon,tint,title,subtitle,null,selected,action);}

 /** Linha de leitura (sem toque): ícone, rótulo e valor à direita colorido pelo estado. */
 LumoSheet status(String icon,int tint,String title,String value){return row(icon,tint,title,null,value,false,null);}

 private LumoSheet row(String icon,int tint,String title,String subtitle,String value,boolean selected,Runnable action){
  LinearLayout r=new LinearLayout(a);r.setGravity(Gravity.CENTER_VERTICAL);r.setMinimumHeight(dp(action==null?52:60));r.setPadding(dp(12),dp(6),dp(12),dp(6));
  if(action!=null){r.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ffffff),selected?LumoTheme.fill(0x1422dff3,dp(18)):null,LumoTheme.fill(Color.WHITE,dp(18))));r.setOnClickListener(v->{v.performHapticFeedback(Build.VERSION.SDK_INT>=30?HapticFeedbackConstants.CONFIRM:HapticFeedbackConstants.VIRTUAL_KEY);if(!keepOpen)dialog.dismiss();action.run();});}
  ImageView art=new ImageView(a);art.setScaleType(ImageView.ScaleType.CENTER);art.setImageDrawable(LumoTheme.icon(a,icon,tint,dp(22)));art.setBackground(LumoTheme.fill((tint&0x00ffffff)|0x24000000,dp(20)));art.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
  r.addView(art,new LinearLayout.LayoutParams(dp(40),dp(40)));
  LinearLayout texts=new LinearLayout(a);texts.setOrientation(LinearLayout.VERTICAL);texts.setPadding(dp(14),0,dp(8),0);
  TextView t=label(title,16,LumoTheme.TEXT,true);texts.addView(t);
  if(subtitle!=null&&!subtitle.isEmpty()){TextView s=label(subtitle,13,LumoTheme.SECONDARY,false);s.setPadding(0,dp(2),0,0);s.setMaxLines(3);texts.addView(s);}
  r.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
  if(value!=null){TextView v=label(value,14,tint,true);v.setGravity(Gravity.END);v.setMaxWidth(dp(260));r.addView(v);}
  else if(selected){ImageView check=new ImageView(a);check.setImageDrawable(LumoTheme.icon(a,"check",LumoTheme.CYAN,dp(22)));r.addView(check,new LinearLayout.LayoutParams(dp(24),dp(24)));r.setContentDescription(title+(subtitle==null?"":", "+subtitle)+", selecionado");}
  else if(action!=null){ImageView more=new ImageView(a);more.setImageDrawable(LumoTheme.icon(a,"chevron_right",LumoTheme.TERTIARY,dp(22)));more.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);r.addView(more,new LinearLayout.LayoutParams(dp(24),dp(24)));}
  body.addView(r,new LinearLayout.LayoutParams(-1,-2));return this;
 }

 /** Parágrafo de texto (tour, explicações). */
 LumoSheet text(String value){TextView t=label(value,16,LumoTheme.TEXT,false);t.setLineSpacing(dp(4),1);t.setPadding(dp(12),dp(4),dp(12),dp(8));body.addView(t);return this;}

 /** Aviso destacado (ex.: sessão do Fotto expirada). */
 LumoSheet banner(String icon,int color,String value){LinearLayout r=new LinearLayout(a);r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(dp(14),dp(12),dp(14),dp(12));r.setBackground(LumoTheme.fill((color&0x00ffffff)|0x1f000000,dp(16)));
  ImageView art=new ImageView(a);art.setImageDrawable(LumoTheme.icon(a,icon,color,dp(22)));r.addView(art,new LinearLayout.LayoutParams(dp(24),dp(24)));
  TextView t=label(value,14,LumoTheme.TEXT,false);t.setPadding(dp(12),0,0,0);t.setLineSpacing(dp(2),1);r.addView(t,new LinearLayout.LayoutParams(0,-2,1));
  LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(dp(4),dp(4),dp(4),dp(8));body.addView(r,lp);return this;}

 LumoSheet view(View v){body.addView(v);return this;}

 /** Botão do rodapé. PRIMARY é o azul sólido; TEXT é um link discreto à esquerda. */
 LumoSheet action(String title,int style,Runnable r){
  footer.setVisibility(View.VISIBLE);Button b=new Button(a);b.setText(title);b.setAllCaps(false);b.setTextSize(15);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setMinWidth(dp(64));b.setMinimumWidth(dp(64));b.setPadding(dp(18),0,dp(18),0);b.setStateListAnimator(null);
  int fg=style==PRIMARY?Color.WHITE:style==DANGER?LumoTheme.DANGER:style==TEXT?LumoTheme.SECONDARY:LumoTheme.TEXT;b.setTextColor(fg);
  GradientDrawable shape=style==PRIMARY?LumoTheme.fill(LumoTheme.BLUE_SOLID,dp(24)):style==TEXT?LumoTheme.fill(Color.TRANSPARENT,dp(24)):LumoTheme.fill(LumoTheme.SURFACE_STRONG,dp(24));
  b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff),shape,LumoTheme.fill(Color.WHITE,dp(24))));
  b.setOnClickListener(v->{v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);if(!keepOpen)dialog.dismiss();if(r!=null)r.run();});
  if(style==TEXT){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);footer.addView(b,0,lp);View spacer=new View(a);footer.addView(spacer,1,new LinearLayout.LayoutParams(0,1,1));}
  else{LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.leftMargin=dp(8);footer.addView(b,lp);}
  return this;
 }

 /** Mantém aberto ao tocar numa linha/ação (o chamador decide quando fechar). */
 LumoSheet keepOpen(){keepOpen=true;return this;}
 LumoSheet cancelable(boolean c){dialogCancelable=c;dialog.setCancelable(c);dialog.setCanceledOnTouchOutside(c);return this;}
 /** Toque fora do cartão fecha (padrão) ou não (tour). */
 LumoSheet outsideCancels(boolean c){outside=c;dialog.setCanceledOnTouchOutside(c);return this;}
 LumoSheet onDismiss(Runnable r){dismissed=r;return this;}
 LumoSheet onCancel(Runnable r){dialog.setOnCancelListener(d->r.run());return this;}

 Dialog show(){if(!a.isFinishing()&&!a.isDestroyed())dialog.show();return dialog;}
 void dismiss(){if(dialog.isShowing())dialog.dismiss();}
}
