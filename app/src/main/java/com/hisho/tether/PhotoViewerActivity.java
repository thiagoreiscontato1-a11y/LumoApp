package com.hisho.tether;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Foto em tela cheia: deslizar entre fotos, pinça/duplo toque para zoom, favoritar, editar, aprovar, enviar, compartilhar e excluir. */
public class PhotoViewerActivity extends Activity {
 static final String EXTRA_IDS="ids",EXTRA_START="start";

 private final ArrayList<String> ids=new ArrayList<>();
 private int index;
 private Jobs database;
 private ZoomImageView image;
 private LinearLayout top,bottom,actions;
 private TextView counter,favorite,stage,reason,score;
 private String[] row;
 private boolean chrome=true,destroyed=false,changed=false;
 private final Handler handler=new Handler(Looper.getMainLooper());
 private final ExecutorService loader=Executors.newSingleThreadExecutor();
 private long generation=0;
 private Bitmap shown;

 int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}

 @Override public void onCreate(Bundle saved){
  setTheme(android.R.style.Theme_Material_NoActionBar);
  super.onCreate(saved);
  database=new Jobs(this);
  String[] list=getIntent().getStringArrayExtra(EXTRA_IDS);
  if(list!=null)ids.addAll(Arrays.asList(list));
  index=Math.max(0,ids.indexOf(saved!=null?saved.getString("current",""):getIntent().getStringExtra(EXTRA_START)));
  if(ids.isEmpty()){finish();return;}
  getWindow().setStatusBarColor(Color.BLACK);getWindow().setNavigationBarColor(Color.BLACK);

  FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);setContentView(root);
  image=new ZoomImageView(this);root.addView(image,new FrameLayout.LayoutParams(-1,-1));
  image.listener=new ZoomImageView.Listener(){
   public void onSwipe(int direction){move(direction);}
   public void onTap(){toggleChrome();}
  };

  top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(dp(14),dp(10),dp(14),dp(18));
  top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0xcc000000,0x00000000}));
  top.addView(circle("✕",v->finish()),new LinearLayout.LayoutParams(dp(42),dp(42)));
  counter=label("",14,Color.WHITE,true);counter.setGravity(Gravity.CENTER);top.addView(counter,new LinearLayout.LayoutParams(0,-2,1));
  favorite=circle("☆",v->toggleFavorite());top.addView(favorite,new LinearLayout.LayoutParams(dp(42),dp(42)));
  TextView info=circle("i",v->showInfo());LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(42),dp(42));ip.leftMargin=dp(8);top.addView(info,ip);
  root.addView(top,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));

  bottom=new LinearLayout(this);bottom.setOrientation(LinearLayout.VERTICAL);bottom.setPadding(dp(16),dp(26),dp(16),dp(16));
  bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,new int[]{0xe6000000,0x00000000}));
  LinearLayout meta=new LinearLayout(this);meta.setGravity(Gravity.BOTTOM);
  LinearLayout texts=new LinearLayout(this);texts.setOrientation(LinearLayout.VERTICAL);
  stage=label("",14,Color.WHITE,true);stage.setPadding(dp(10),dp(5),dp(10),dp(5));texts.addView(stage,new LinearLayout.LayoutParams(-2,-2));
  reason=label("",12,LumoTheme.SECONDARY,false);reason.setMaxLines(2);reason.setPadding(0,dp(5),0,0);texts.addView(reason);
  meta.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
  score=label("",22,Color.WHITE,true);score.setGravity(Gravity.CENTER);meta.addView(score,new LinearLayout.LayoutParams(dp(58),dp(58)));
  bottom.addView(meta);
  actions=new LinearLayout(this);LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(58));ap.topMargin=dp(12);bottom.addView(actions,ap);
  root.addView(bottom,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));

  root.setOnApplyWindowInsetsListener((v,in)->{top.setPadding(dp(14),in.getSystemWindowInsetTop()+dp(8),dp(14),dp(18));bottom.setPadding(dp(16),dp(26),dp(16),in.getSystemWindowInsetBottom()+dp(14));return in;});
  show(0);
 }

 TextView label(String value,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));return t;}

 TextView circle(String value,View.OnClickListener l){TextView t=label(value,17,Color.WHITE,true);t.setGravity(Gravity.CENTER);t.setBackground(LumoTheme.fill(0x73000000,dp(21)));t.setOnClickListener(l);return t;}

 Button action(String title,int color,View.OnClickListener l){
  Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextSize(12);b.setTextColor(color);b.setTypeface(Typeface.create("sans-serif-medium",0));
  b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33ffffff),LumoTheme.fill(0x1affffff,dp(14)),null));
  b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(dp(4),0,dp(4),0);b.setOnClickListener(l);return b;
 }

 void addAction(Button b){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);if(actions.getChildCount()>0)lp.leftMargin=dp(6);actions.addView(b,lp);}

 String current(){return ids.get(index);}

 /** Mostra a foto atual; `slide` anima a troca (−1/+1). */
 void show(int slide){
  row=database.viewerRow(current());
  if(row==null){ids.remove(index);if(ids.isEmpty()){finish();return;}index=Math.min(index,ids.size()-1);row=database.viewerRow(current());if(row==null){finish();return;}}
  counter.setText((index+1)+" de "+ids.size());
  boolean fav=database.isFavorite(row[0]);favorite.setText(fav?"★":"☆");favorite.setTextColor(fav?LumoTheme.WARN:Color.WHITE);
  String st=row[3];int sc=0;try{sc=Integer.parseInt(row[4]);}catch(Exception ignored){}
  boolean review="review".equals(st),done="done".equals(st);
  stage.setText(review?"⚠ Revisão":done?"✓ Aprovada":"● "+("error".equals(st)?"Falha":"Editando"));
  stage.setTextColor(review?LumoTheme.WARN:done?LumoTheme.OK:LumoTheme.CYAN);stage.setBackground(LumoTheme.fill(0x8c000000,dp(14)));
  reason.setText(row[5].replace("Sob revisão: ",""));
  score.setText(sc>0?sc+"\n/10":"—");score.setTextSize(sc>0?16:20);
  score.setBackground(LumoTheme.card(0x8c000000,dp(14),dp(1.5f)));score.setVisibility(sc>0?View.VISIBLE:View.INVISIBLE);
  actions.removeAllViews();
  addAction(action("Editar",Color.WHITE,v->edit()));
  if(review)addAction(action("Aprovar",LumoTheme.OK,v->approve()));
  else if(done)addAction(action("Enviar",LumoTheme.CYAN,v->send()));
  addAction(action("Compartilhar",Color.WHITE,v->share()));
  addAction(action("Excluir",LumoTheme.DANGER,v->confirmDelete()));
  load(row[2],slide);
 }

 void load(String uri,int slide){
  final long gen=++generation;
  if(slide!=0){image.animate().translationX(-slide*image.getWidth()*0.35f).alpha(0f).setDuration(120).start();}
  loader.execute(()->{
   Bitmap b=decode(uri,2400);
   handler.post(()->{
    if(destroyed||gen!=generation){if(b!=null)b.recycle();return;}
    image.setImageBitmap(b);
    if(shown!=null&&shown!=b)shown.recycle();
    shown=b;
    image.setTranslationX(slide*image.getWidth()*0.35f);image.setAlpha(slide==0?1f:0f);
    image.animate().translationX(0).alpha(1f).setDuration(170).start();
   });
  });
 }

 Bitmap decode(String value,int edge){
  if(value==null||value.isEmpty())return null;
  try{Uri uri=Uri.parse(value);BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;
   try(InputStream in=getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,o);}
   if(o.outWidth<=0)return null;o.inJustDecodeBounds=false;o.inSampleSize=1;
   while(Math.max(o.outWidth,o.outHeight)/(o.inSampleSize*2)>=edge)o.inSampleSize*=2;
   try(InputStream in=getContentResolver().openInputStream(uri)){return BitmapFactory.decodeStream(in,null,o);}
  }catch(Exception e){return null;}
 }

 void move(int direction){
  int next=index+direction;
  if(next<0||next>=ids.size()){image.animate().translationX(-direction*dp(24)).setDuration(90).withEndAction(()->image.animate().translationX(0).setDuration(120).start()).start();return;}
  index=next;image.reset();show(direction);
 }

 void toggleChrome(){chrome=!chrome;float a=chrome?1f:0f;top.animate().alpha(a).setDuration(180).start();bottom.animate().alpha(a).setDuration(180).start();top.setClickable(chrome);bottom.setClickable(chrome);setChildrenEnabled(top,chrome);setChildrenEnabled(bottom,chrome);}

 void setChildrenEnabled(ViewGroup g,boolean on){for(int i=0;i<g.getChildCount();i++){View v=g.getChildAt(i);v.setEnabled(on);if(v instanceof ViewGroup)setChildrenEnabled((ViewGroup)v,on);}}

 void toggleFavorite(){boolean on=database.toggleFavorite(current());changed=true;favorite.setText(on?"★":"☆");favorite.setTextColor(on?LumoTheme.WARN:Color.WHITE);try{((Vibrator)getSystemService(VIBRATOR_SERVICE)).vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));}catch(Exception ignored){}}

 void edit(){Intent e=new Intent(this,ManualEditorActivity.class).putExtra("focusId",current()).putExtra("jobIds",current()).putExtra("pasteOnOpen",false);changed=true;startActivity(e);}

 void approve(){
  if(row==null)return;final String id=row[0],name=row[1],uri=row[2];toast("Aprovando…");
  loader.execute(()->{try{PhotoActions.approve(this,database,id,name,uri);handler.post(()->{changed=true;toast("Foto aprovada e liberada para entrega.");show(0);});}catch(Exception e){handler.post(()->toast("Não foi possível aprovar: "+e.getMessage()));}});
 }

 void send(){String blocker=PhotoActions.fottoBlocker(this);if(!blocker.isEmpty()){toast(blocker);return;}FottoSync.sendSelected(this,Collections.singletonList(current()));toast("Foto adicionada à entrega direta.");}

 void share(){if(row==null||row[2]==null)return;try{startActivity(PhotoActions.share(Uri.parse(row[2])));}catch(Exception e){toast("Não há aplicativo para compartilhar.");}}

 void confirmDelete(){
  String blocker=PhotoActions.deletionBlocker(this);if(!blocker.isEmpty()){toast(blocker);return;}
  new AlertDialog.Builder(this).setTitle("Excluir esta foto?").setMessage("Apaga o original e a cópia editada salvas pelo Lumo. Não apaga a foto do cartão nem a já enviada ao Fotto.")
   .setNegativeButton("Cancelar",null).setPositiveButton("Excluir",(d,n)->{final String id=current();loader.execute(()->{try{database.deletePhoto(id);handler.post(()->{changed=true;ids.remove(id);if(ids.isEmpty()){finish();return;}index=Math.min(index,ids.size()-1);show(0);});}catch(Exception e){handler.post(()->toast("Falha ao excluir: "+e.getMessage()));}});}).show();
 }

 void showInfo(){
  if(row==null)return;StringBuilder s=new StringBuilder();s.append(row[1]).append("\n\n");String r=row[5];if(!r.isEmpty())s.append(r).append("\n\n");
  String semantic=database.semanticNote(row[0]);if(!semantic.isEmpty())s.append("Curadoria inteligente: ").append(semantic).append("\n\n");
  java.text.SimpleDateFormat tf=new java.text.SimpleDateFormat("HH:mm:ss",Locale.ROOT);
  for(String[] h:database.historyRows(row[0])){long ts=0;try{ts=Long.parseLong(h[0]);}catch(Exception ignored){}s.append(tf.format(new Date(ts))).append(" · ").append(h[1]).append(h[2].isEmpty()?"":" · "+h[2]).append('\n');}
  new AlertDialog.Builder(this).setTitle("Informações").setMessage(s.toString().trim()).setPositiveButton("Fechar",null).show();
 }

 void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}

 @Override protected void onResume(){super.onResume();if(row!=null){String[] fresh=database.viewerRow(current());if(fresh==null||!Arrays.equals(fresh,row))show(0);}}
 @Override public void finish(){if(changed)setResult(RESULT_OK);super.finish();}
 @Override protected void onSaveInstanceState(Bundle state){if(!ids.isEmpty())state.putString("current",current());super.onSaveInstanceState(state);}
 @Override protected void onDestroy(){destroyed=true;loader.shutdownNow();if(shown!=null)shown.recycle();super.onDestroy();}
}
