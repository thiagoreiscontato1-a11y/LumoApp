package com.hisho.tether;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Editor contextual em tela cheia, baseado em Original → Auto → Preset → Final. */
public class ManualEditorActivity extends Activity{
 int BG,CARD,CARD2,LINE,TEXT,MUTED,BLUE,GREEN,WARN;
 boolean darkMode;
 final Handler handler=new Handler();
 final ExecutorService worker=Executors.newSingleThreadExecutor();
 Jobs jobs;
 ImageView preview;
 TextView title,countLabel,status,controlName,controlValue,baseInfo,scoreLabel;
 SeekBar controlBar;
 HorizontalScrollView parameterScroll;
 LinearLayout parameterStrip,stageStrip;
 final ArrayList<Button> parameterButtons=new ArrayList<>();
 final ArrayList<Button> stageButtons=new ArrayList<>();
 Bitmap originalBitmap,autoBitmap,baseBitmap,previewBitmap;
 String focusId="",stage="final";
 ArrayList<String> targets=new ArrayList<>();
 JSONObject baseValues=new JSONObject(),autoValues=new JSONObject(),presetValues=new JSONObject(),currentValues=new JSONObject();
 boolean settingControls=false,destroyed=false,pasteOnOpen=false;
 volatile boolean previewBusy=false,previewQueued=false;
 int selectedControl=0;
 final String[] keys={"exposure","contrast","highlights","shadows","whites","blacks","temperature","tint","vibrance","saturation"};
 final String[] names={"Exposição","Contraste","Realces","Sombras","Brancos","Pretos","Temperatura","Matiz","Vibração","Saturação"};

 int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
 void palette(){darkMode=getSharedPreferences("hisho",0).getBoolean("darkMode",false);if(darkMode){BG=0xff0d1015;CARD=0xff151a21;CARD2=0xff1c222b;LINE=0xff2a313b;TEXT=0xfff1f4f8;MUTED=0xff97a2b1;}else{BG=0xfff5f7fa;CARD=0xffffffff;CARD2=0xfff9fbfd;LINE=0xffe3e8ef;TEXT=0xff101722;MUTED=0xff6f7d91;}BLUE=0xff0b7cff;GREEN=0xff20b46a;WARN=0xffff9f43;}
 GradientDrawable shape(int c,int r){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(r));return d;}
 GradientDrawable border(int c,int r){GradientDrawable d=shape(c,r);d.setStroke(dp(1),LINE);return d;}
 TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",0));return t;}
 LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
 LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
 void gap(LinearLayout p,int h){p.addView(new View(this),new LinearLayout.LayoutParams(1,dp(h)));}
 Button compact(String s,View.OnClickListener l){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(12);b.setTextColor(TEXT);b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(dp(8),0,dp(8),0);b.setBackground(border(CARD2,11));b.setOnClickListener(l);return b;}
 void primary(Button b){b.setTextColor(Color.WHITE);b.setBackground(shape(BLUE,11));}

 @Override public void onCreate(Bundle state){
  darkMode=getSharedPreferences("hisho",0).getBoolean("darkMode",false);setTheme(darkMode?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);super.onCreate(state);palette();jobs=new Jobs(this);
  focusId=getIntent().getStringExtra("focusId");pasteOnOpen=getIntent().getBooleanExtra("pasteOnOpen",false);String packed=getIntent().getStringExtra("jobIds");if(packed!=null)for(String id:packed.split(","))if(!id.trim().isEmpty()&&!targets.contains(id.trim()))targets.add(id.trim());if(focusId==null)focusId="";if(!focusId.isEmpty()&&!targets.contains(focusId))targets.add(0,focusId);if(focusId.isEmpty()&&!targets.isEmpty())focusId=targets.get(0);
  getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(CARD);getWindow().getDecorView().setSystemUiVisibility(darkMode?0:(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));

  LinearLayout root=vertical();root.setBackgroundColor(BG);root.setPadding(dp(12),dp(8),dp(12),dp(10));root.setOnApplyWindowInsetsListener((v,in)->{v.setPadding(dp(12)+in.getSystemWindowInsetLeft(),dp(8)+in.getSystemWindowInsetTop(),dp(12)+in.getSystemWindowInsetRight(),dp(10)+in.getSystemWindowInsetBottom());return in.consumeSystemWindowInsets();});setContentView(root);

  LinearLayout top=row();Button back=compact("‹",v->finish());top.addView(back,new LinearLayout.LayoutParams(dp(42),dp(40)));LinearLayout namesBox=vertical();namesBox.setPadding(dp(8),0,0,0);title=text("Editor",15,TEXT,true);namesBox.addView(title);countLabel=text(targets.size()>1?targets.size()+" selecionadas":"1 foto",10,MUTED,false);namesBox.addView(countLabel);top.addView(namesBox,new LinearLayout.LayoutParams(0,-2,1));scoreLabel=text("—/10",12,TEXT,true);scoreLabel.setPadding(dp(9),dp(6),dp(9),dp(6));scoreLabel.setBackground(shape(CARD2,10));top.addView(scoreLabel);root.addView(top);

  gap(root,6);preview=new ImageView(this);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setBackground(shape(0xff07090c,14));LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,0,1);root.addView(preview,pp);
  preview.setOnTouchListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_DOWN){showStage("original");status.setText("Antes · original");return true;}if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL){showStage("final");status.setText("Final · solte para comparar");return true;}return true;});

  stageStrip=row();String[] stages={"Original","Automático","Predefinição","Final"};String[] stageKeys={"original","auto","preset","final"};for(int i=0;i<stages.length;i++){final String sk=stageKeys[i];Button b=compact(stages[i],v->showStage(sk));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(38),1);lp.setMargins(dp(2),dp(6),dp(2),0);stageStrip.addView(b,lp);stageButtons.add(b);}root.addView(stageStrip);
  status=text("Preparando Original → Automático → Predefinição…",10,MUTED,false);status.setGravity(Gravity.CENTER);root.addView(status,new LinearLayout.LayoutParams(-1,dp(25)));

  LinearLayout panel=vertical();panel.setPadding(dp(12),dp(8),dp(12),dp(8));panel.setBackground(border(CARD,14));root.addView(panel);
  LinearLayout h=row();controlName=text("Exposição",14,TEXT,true);h.addView(controlName,new LinearLayout.LayoutParams(0,-2,1));controlValue=text("+0.00 EV",13,BLUE,true);h.addView(controlValue);panel.addView(h);baseInfo=text("Base · carregando…",10,MUTED,false);panel.addView(baseInfo);
  controlBar=new SeekBar(this);controlBar.setProgressTintList(ColorStateList.valueOf(BLUE));controlBar.setThumbTintList(ColorStateList.valueOf(BLUE));controlBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){if(settingControls)return;String key=keys[selectedControl];int min="exposure".equals(key)?-500:-1000;double v=sliderValue(key,p+min);try{currentValues.put(key,v);}catch(Exception ignored){}controlValue.setText(format(key,v));showStage("final");if(user)requestPreview();}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){if(!settingControls)requestPreview();}});panel.addView(controlBar,new LinearLayout.LayoutParams(-1,dp(38)));

  parameterScroll=new HorizontalScrollView(this);parameterScroll.setHorizontalScrollBarEnabled(false);parameterStrip=row();for(int i=0;i<names.length;i++){final int idx=i;Button b=compact(names[i],v->selectControl(idx));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(names[i].length()>9?104:88),dp(36));lp.rightMargin=dp(5);parameterStrip.addView(b,lp);parameterButtons.add(b);}parameterScroll.addView(parameterStrip);panel.addView(parameterScroll,new LinearLayout.LayoutParams(-1,dp(42)));

  gap(root,6);LinearLayout tools=row();Button copy=compact("Copiar…",v->copyAdjustments());tools.addView(copy,new LinearLayout.LayoutParams(0,dp(40),1));Button paste=compact("Colar",v->pasteAdjustments(true));LinearLayout.LayoutParams pl=new LinearLayout.LayoutParams(0,dp(40),1);pl.leftMargin=dp(5);tools.addView(paste,pl);Button reset=compact("Base",v->{setCurrentFromDelta(new JSONObject());requestPreview();});LinearLayout.LayoutParams rl=new LinearLayout.LayoutParams(0,dp(40),1);rl.leftMargin=dp(5);tools.addView(reset,rl);root.addView(tools);
  LinearLayout save=row();Button current=compact("Salvar nesta foto",v->saveTargets(Collections.singletonList(focusId)));primary(current);save.addView(current,new LinearLayout.LayoutParams(0,dp(44),1));Button batch=compact(targets.size()>1?"Aplicar em "+targets.size():"Aplicar em lote",v->{if(targets.size()>1)saveTargets(new ArrayList<>(targets));else toast("Selecione várias fotos na Galeria primeiro.");});if(targets.size()>1)primary(batch);else batch.setEnabled(false);LinearLayout.LayoutParams bl=new LinearLayout.LayoutParams(0,dp(44),1);bl.leftMargin=dp(6);save.addView(batch,bl);root.addView(save);
  selectControl(0);loadFocus();
 }

 void selectControl(int index){selectedControl=Math.max(0,Math.min(keys.length-1,index));String key=keys[selectedControl];settingControls=true;int min="exposure".equals(key)?-500:-1000,max="exposure".equals(key)?500:1000;controlBar.setMax(max-min);double v=val(currentValues,key);int raw=Math.max(min,Math.min(max,sliderRaw(key,v)));controlBar.setProgress(raw-min);controlName.setText(names[selectedControl]);controlValue.setText(format(key,v));baseInfo.setText(baseText(key));for(int i=0;i<parameterButtons.size();i++){Button b=parameterButtons.get(i);b.setTextColor(i==selectedControl?Color.WHITE:TEXT);b.setBackground(i==selectedControl?shape(BLUE,11):border(CARD2,11));}settingControls=false;parameterScroll.post(()->{Button b=parameterButtons.get(selectedControl);parameterScroll.smoothScrollTo(Math.max(0,b.getLeft()-dp(24)),0);});}
 double sliderValue(String key,int raw){return "exposure".equals(key)?raw/100.0:raw/10.0;}
 int sliderRaw(String key,double v){return (int)Math.round(v*("exposure".equals(key)?100:10));}
 String format(String key,double v){if("exposure".equals(key))return String.format(Locale.getDefault(),"%+.2f EV",v);return String.format(Locale.getDefault(),"%+.1f",v);}
 double val(JSONObject o,String key){return o==null?0:o.optDouble(key,0);}
 JSONObject copyJson(JSONObject src){try{return new JSONObject(src==null?"{}":src.toString());}catch(Exception e){return new JSONObject();}}
 JSONObject deltaFromCurrent(){JSONObject d=new JSONObject();for(String key:keys)try{d.put(key,val(currentValues,key)-val(baseValues,key));}catch(Exception ignored){}return d;}
 void setCurrentFromDelta(JSONObject delta){settingControls=true;currentValues=new JSONObject();for(String key:keys){double v=val(baseValues,key)+val(delta,key);double lo="exposure".equals(key)?-5:-100,hi="exposure".equals(key)?5:100;v=Math.max(lo,Math.min(hi,v));try{currentValues.put(key,v);}catch(Exception ignored){}}settingControls=false;selectControl(selectedControl);}
 String baseText(String key){double a=val(autoValues,key),p=val(presetValues,key),sum=val(baseValues,key);return "Base "+format(key,sum)+" · Automático "+format(key,a)+" · Predefinição "+format(key,p);}

 void loadFocus(){if(focusId.isEmpty()){status.setText("Nenhuma foto selecionada.");return;}worker.execute(()->{File original=null,stageOriginal=null,stageAuto=null,stagePreset=null;try{String[] j=jobs.editorJob(focusId);if(j==null)throw new IOException("Foto não encontrada no histórico.");String name=j[1],settingsText=j[2],originalUri=j[3];JSONObject settings=new JSONObject(settingsText);JSONObject existing=settings.optJSONObject("manual");original=new File(getCacheDir(),"editor_src_"+System.nanoTime()+".jpg");stageOriginal=new File(getCacheDir(),"editor_original_"+System.nanoTime()+".jpg");stageAuto=new File(getCacheDir(),"editor_auto_"+System.nanoTime()+".jpg");stagePreset=new File(getCacheDir(),"editor_preset_"+System.nanoTime()+".jpg");copyUri(originalUri,original);
   autoValues=settings.optBoolean("auto",true)?PhotoEditor.scaleControls(PhotoEditor.autoControls(original),settings.optDouble("autoStrength",1.0)):new JSONObject();presetValues=PhotoEditor.presetControls(settings.optJSONObject("preset"));baseValues=PhotoEditor.sumControls(autoValues,presetValues);JSONObject oldDelta=existing==null?new JSONObject():copyJson(existing);
   JSONObject o=new JSONObject();o.put("edge",1200);o.put("auto",false);PhotoEditor.edit(original,stageOriginal,o);
   JSONObject a=new JSONObject();a.put("edge",1200);a.put("auto",settings.optBoolean("auto",true));a.put("autoStrength",settings.optDouble("autoStrength",1.0));PhotoEditor.edit(original,stageAuto,a);
   JSONObject b=new JSONObject(settings.toString());b.remove("manual");b.put("edge",1200);PhotoEditor.edit(original,stagePreset,b);
   Bitmap bo=BitmapFactory.decodeFile(stageOriginal.toString()),ba=BitmapFactory.decodeFile(stageAuto.toString()),bp=BitmapFactory.decodeFile(stagePreset.toString());if(bo==null||ba==null||bp==null)throw new IOException("Não foi possível gerar as prévias.");int score=jobs.quality(focusId)[0];final Bitmap fO=bo,fA=ba,fP=bp;handler.post(()->{if(destroyed){fO.recycle();fA.recycle();fP.recycle();return;}recycleStages();originalBitmap=fO;autoBitmap=fA;baseBitmap=fP;title.setText(name.replaceFirst("(?i)\\.jpg$",""));countLabel.setText(targets.size()>1?targets.size()+" selecionadas":"1 foto");scoreLabel.setText(score>0?score+"/10":"—/10");scoreLabel.setTextColor(score>=8?GREEN:score>=5?WARN:TEXT);setCurrentFromDelta(oldDelta);status.setText("Segure a foto para ver o original");requestPreview();if(pasteOnOpen){pasteOnOpen=false;pasteAdjustments(false);}});
  }catch(Exception e){String msg=e.getMessage();handler.post(()->status.setText("Falha ao preparar editor · "+msg));}finally{if(original!=null)original.delete();if(stageOriginal!=null)stageOriginal.delete();if(stageAuto!=null)stageAuto.delete();if(stagePreset!=null)stagePreset.delete();}});}
 void recycleStages(){if(originalBitmap!=null)originalBitmap.recycle();if(autoBitmap!=null)autoBitmap.recycle();if(baseBitmap!=null)baseBitmap.recycle();if(previewBitmap!=null&&previewBitmap!=baseBitmap)previewBitmap.recycle();originalBitmap=autoBitmap=baseBitmap=previewBitmap=null;}

 void showStage(String which){stage=which;Bitmap b="original".equals(which)?originalBitmap:"auto".equals(which)?autoBitmap:"preset".equals(which)?baseBitmap:(previewBitmap!=null?previewBitmap:baseBitmap);if(b!=null)preview.setImageBitmap(b);for(int i=0;i<stageButtons.size();i++){boolean on=(i==0&&"original".equals(which))||(i==1&&"auto".equals(which))||(i==2&&"preset".equals(which))||(i==3&&"final".equals(which));Button bt=stageButtons.get(i);bt.setTextColor(on?Color.WHITE:TEXT);bt.setBackground(on?shape(BLUE,11):border(CARD2,11));}}
 void requestPreview(){previewQueued=true;if(destroyed||baseBitmap==null||previewBusy)return;startPreviewFrame();}
 void startPreviewFrame(){if(destroyed||baseBitmap==null||previewBusy)return;previewQueued=false;previewBusy=true;JSONObject delta=deltaFromCurrent();worker.execute(()->{Bitmap out=null;try{out=baseBitmap.copy(Bitmap.Config.ARGB_8888,true);if(out==null)throw new IOException("Sem memória para a prévia.");PhotoEditor.manualBitmap(out,delta);final Bitmap ready=out;handler.post(()->{if(destroyed){ready.recycle();return;}if(previewBitmap!=null&&previewBitmap!=baseBitmap&&previewBitmap!=originalBitmap&&previewBitmap!=autoBitmap)previewBitmap.recycle();previewBitmap=ready;if("final".equals(stage))preview.setImageBitmap(ready);previewBusy=false;if(previewQueued)handler.postDelayed(this::startPreviewFrame,16);});}catch(Exception e){if(out!=null)out.recycle();handler.post(()->{previewBusy=false;if(previewQueued)handler.postDelayed(this::startPreviewFrame,16);});}});}

 void copyAdjustments(){boolean[] checked=new boolean[keys.length];Arrays.fill(checked,true);new AlertDialog.Builder(this).setTitle("Copiar quais ajustes?").setMultiChoiceItems(names,checked,(d,which,isChecked)->checked[which]=isChecked).setPositiveButton("Copiar",(d,n)->{JSONObject all=deltaFromCurrent(),clip=new JSONObject();for(int i=0;i<keys.length;i++)if(checked[i])try{clip.put(keys[i],all.optDouble(keys[i],0));}catch(Exception ignored){}getSharedPreferences("hisho",0).edit().putString("manualEditClipboard",clip.toString()).apply();toast("Ajustes selecionados copiados.");}).setNegativeButton("Cancelar",null).show();}
 void pasteAdjustments(boolean toast){String raw=getSharedPreferences("hisho",0).getString("manualEditClipboard","");if(raw.isEmpty()){if(toast)toast("Nenhum refinamento copiado ainda.");return;}try{JSONObject clip=new JSONObject(raw),merged=deltaFromCurrent();Iterator<String> it=clip.keys();while(it.hasNext()){String k=it.next();if(Arrays.asList(keys).contains(k))merged.put(k,clip.optDouble(k,0));}setCurrentFromDelta(merged);showStage("final");requestPreview();if(toast)toast("Ajustes colados sobre a base desta foto.");}catch(Exception e){if(toast)toast("Não foi possível colar os ajustes.");}}

 void saveTargets(List<String> ids){if(ids==null||ids.isEmpty()||ids.get(0).isEmpty())return;final JSONObject chosen=deltaFromCurrent();controlBar.setEnabled(false);status.setText("Aplicando em "+ids.size()+" foto(s)…");worker.execute(()->{int ok=0,review=0,fail=0,sentBefore=0;String lastError="";for(String id:ids){try{SaveResult r=saveOne(id,chosen);ok++;if(r.review)review++;if(r.wasSent)sentBefore++;}catch(Exception e){fail++;lastError=e.getMessage();}}final int fOk=ok,fReview=review,fFail=fail,fSent=sentBefore;final String fErr=lastError;handler.post(()->{controlBar.setEnabled(true);String msg="Concluído · "+fOk+" salva(s)"+(fReview>0?" · "+fReview+" em Revisão":"")+(fFail>0?" · "+fFail+" falha(s)":"");if(fSent>0)msg+=" · "+fSent+" já enviada(s)";status.setText(msg+(fErr.isEmpty()?"":" · "+fErr));toast(msg);});});}
 static final class SaveResult{boolean review,wasSent;SaveResult(boolean r,boolean s){review=r;wasSent=s;}}
 SaveResult saveOne(String id,JSONObject chosen)throws Exception{String[] j=jobs.editorJob(id);if(j==null)throw new IOException("Foto "+id+" não encontrada.");String name=j[1],settingsText=j[2],originalUri=j[3],editedUri=j[4],state=j[5];JSONObject settings=new JSONObject(settingsText);settings.put("manual",copyJson(chosen));File original=new File(getCacheDir(),"manual_original_"+id+".jpg"),output=new File(getCacheDir(),"manual_output_"+id+".jpg");try{copyUri(originalUri,original);PhotoEditor.edit(original,output,settings);boolean curate=settings.optBoolean("curation",true);SmartCurator.Result quality;try{quality=SmartCurator.analyze(output,settings.optInt("curationSensitivity",1),jobs,id);}catch(Exception e){quality=new SmartCurator.Result(true,1,"Falha na curadoria · "+e.getMessage(),"","","");}boolean review=curate&&quality.review;boolean wasSent=jobs.fottoWasSent(id);jobs.updateSettings(id,settings.toString());jobs.fottoDiscardUnsent(id);jobs.setIntelligence(id,quality.pHash,quality.duplicateOf,quality.semantic);jobs.markEdited(id,settings.optString("presetName",""));jobs.setQuality(id,quality.score,quality.reason+(curate?"":" · Curadoria desligada: score informativo"));
   if(review){String old=editedUri;jobs.clearEdited(id);Uri uri=jobs.save(output,"Sob Revisao",name.replaceFirst("(?i)\\.jpg$","_REVISAO.jpg"),id,"edited");jobs.set(id,"review","error",quality.reason);jobs.history(id,System.currentTimeMillis(),"Revisão","Ajuste manual ainda requer revisão · "+quality.reason);if(old!=null&&!old.isEmpty()&&!old.equals(uri.toString()))try{getContentResolver().delete(Uri.parse(old),null,null);}catch(Exception ignored){}return new SaveResult(true,wasSent);}
   if("done".equals(state)&&editedUri!=null&&!editedUri.isEmpty())jobs.overwrite(output,editedUri);else{String old=editedUri;jobs.clearEdited(id);Uri uri=jobs.save(output,"Editadas",name.replaceFirst("(?i)\\.jpg$","_Editada.jpg"),id,"edited");if(old!=null&&!old.isEmpty()&&!old.equals(uri.toString()))try{getContentResolver().delete(Uri.parse(old),null,null);}catch(Exception ignored){}}
   jobs.set(id,"done","error",null);jobs.history(id,System.currentTimeMillis(),"Aprovada","Ajuste manual aprovado para entrega");return new SaveResult(false,wasSent);
  }finally{original.delete();output.delete();}}
 void copyUri(String raw,File out)throws IOException{if(raw==null||raw.isEmpty())throw new IOException("Original não encontrado.");try(InputStream in=getContentResolver().openInputStream(Uri.parse(raw));OutputStream dst=new FileOutputStream(out)){if(in==null)throw new IOException("Original indisponível.");Jobs.copy(in,dst);}}
 void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
 @Override protected void onDestroy(){destroyed=true;previewQueued=false;worker.shutdownNow();recycleStages();jobs.close();super.onDestroy();}
}
