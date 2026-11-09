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

/**
 * Editor contextual em tela cheia, baseado em Original → Auto → Preset → Final, com os mesmos grupos do
 * editor do iPhone (Luz, Cor, Efeitos, Detalhe) e o histograma do Lightroom.
 * Básicos (Luz e o começo de Cor) guardam a diferença sobre automático + preset em {@code manual};
 * os demais guardam o valor final em chaves Camera Raw em {@code extra}, que substituem as do preset.
 */
public class ManualEditorActivity extends Activity{
 int BG,CARD,CARD2,LINE,TEXT,MUTED,BLUE,GREEN,WARN;
 boolean darkMode;
 final Handler handler=new Handler();
 final ExecutorService worker=Executors.newSingleThreadExecutor();
 Jobs jobs;
 ImageView preview;
 HistogramView histogram;
 /** Moldura do modo Corte, por cima da prévia. */
 CropView cropView;
 CurveView curveView;ColorWheelView wheel;
 TextView title,countLabel,status,scoreLabel;
 LinearLayout stageStrip,controlsBox;
 final ArrayList<Button> stageButtons=new ArrayList<>(),groupButtons=new ArrayList<>();
 Bitmap originalBitmap,autoBitmap,baseBitmap,previewBitmap;
 /** Histograma de cada etapa; o da prévia final é refeito a cada quadro. */
 Histogram originalHist,autoHist,baseHist,previewHist;
 /** Máscara de recorte (toque no histograma): cópia de cada bitmap com realces em vermelho e sombras em azul. */
 boolean clipping=false;final HashMap<Bitmap,Bitmap> clipCache=new HashMap<>();
 /** Foto decodificada e ajustes sem o ajuste manual: cada movimento de slider renderiza tudo de novo. */
 Develop.Source editorSource;JSONObject editorSettings;
 /** Foto inteira decodificada (sem corte nem perfil): base do modo Corte e de cada novo corte. */
 int[] fullPixels;int fullW,fullH;
 /** Intensidade do perfil criativo já aplicada em {@link #editorSource} (NaN = sem perfil). */
 double sourceLookAmount=Double.NaN;
 /** Recorte em edição e o recorte com que as etapas foram montadas; proporção travada (0 = livre). */
 Crop crop=Crop.none(),builtCrop=Crop.none();double cropAspect=0;boolean cropDirty=false;
 /** Foto inteira revelada (prévia do modo Corte), girada a cada mudança do endireitar. */
 int[] fullDeveloped;
 int curveChannel=0,gradeRange=1;
 /** Valores do preset em uso (chaves Camera Raw): o que os sliders extras mostram antes de serem alterados. */
 Map<String,Double> presetMap=new HashMap<>();
 String focusId="",stage="final";
 ArrayList<String> targets=new ArrayList<>();
 JSONObject baseValues=new JSONObject(),autoValues=new JSONObject(),presetValues=new JSONObject(),currentValues=new JSONObject();
 /** Ajustes extras alterados no editor, com o valor final (chaves Camera Raw). */
 JSONObject extraValues=new JSONObject();
 boolean settingControls=false,destroyed=false,pasteOnOpen=false;
 volatile boolean previewBusy=false,previewQueued=false;
 int group=0,mixColor=0;
 JSONObject selectedPreset=null; String selectedPresetName=""; boolean presetChanged=false,loading=true,saving=false;
 Button presetButton;
 final String[] keys=Develop.BASIC;
 final String[] names={"Exposição","Contraste","Realces","Sombras","Brancos","Pretos","Temperatura","Matiz","Vibração","Saturação"};
 static final String[] GROUPS={"Luz","Cor","Efeitos","Detalhe","Corte"};
 static final int CROP_GROUP=4;
 static final String[] CURVE_NAMES={"RGB","Vermelho","Verde","Azul"};static final int[] CURVE_COLORS={0xffffffff,0xffff5a5a,0xff4cd964,0xff4c8dff};
 static final String[] GRADE_NAMES={"Sombras","Meios-tons","Realces","Global"};
 static final String[][] GRADE_KEYS={{"SplitToningShadowHue","SplitToningShadowSaturation","ColorGradeShadowLum"},{"ColorGradeMidtoneHue","ColorGradeMidtoneSat","ColorGradeMidtoneLum"},{"SplitToningHighlightHue","SplitToningHighlightSaturation","ColorGradeHighlightLum"},{"ColorGradeGlobalHue","ColorGradeGlobalSat","ColorGradeGlobalLum"}};
 static final String[] ASPECT_NAMES={"Livre","Original","1:1","4:5","3:2","16:9"};
 /** Proporções (lado maior/lado menor), aplicadas na orientação da foto; -1 = a da foto. */
 static final double[] ASPECTS={0,-1,1,5/4.,3/2.,16/9.};
 static final String[] MIX_NAMES={"Vermelho","Laranja","Amarelo","Verde","Turquesa","Azul","Roxo","Magenta"};
 static final float[] MIX_HUES={0,28,52,120,180,220,270,310};

 /** Um slider do editor: básico (delta sobre automático + preset) ou extra (chave Camera Raw com valor final). */
 static final class Control{
  final String key,label,format;final double min,max,step;final boolean basic;
  Control(String key,String label,double min,double max,double step,String format,boolean basic){this.key=key;this.label=label;this.min=min;this.max=max;this.step=step;this.format=format;this.basic=basic;}
  int steps(){return (int)Math.round((max-min)/step);}
  double value(int progress){return min+progress*step;}
  int progress(double v){return Math.max(0,Math.min(steps(),(int)Math.round((v-min)/step)));}
 }
 static final class Row{final Control c;final SeekBar bar;final TextView value;Row(Control c,SeekBar bar,TextView value){this.c=c;this.bar=bar;this.value=value;}}
 final ArrayList<Row> rows=new ArrayList<>();

 static Control basic(int i,String label){boolean exp=i==0;return new Control(Develop.BASIC[i],label,exp?-5:-100,exp?5:100,exp?.01:.1,exp?"%+.2f EV":"%+.1f",true);}
 static Control signed(String key,String label){return new Control(key,label,-100,100,1,"%+.0f",false);}
 static Control unsigned(String key,String label){return new Control(key,label,0,100,1,"%.0f",false);}
 /** Grupos de "Copiar…" para os ajustes extras e as chaves de cada um. */
 static final String[] EXTRA_GROUPS={"Mistura de cores","Calibração","Textura, claridade e desembaçar","Vinheta","Granulação","Nitidez","Curva de tons","Gradação de cores","Intensidade do perfil"};
 /** Chave extra pertence ao grupo {@code g} de {@link #EXTRA_GROUPS}. */
 static boolean inGroup(int g,String key){
  if(g==6)return PhotoEditor.curveKey(key)||key.startsWith("Parametric");
  if(g==7)return key.startsWith("ColorGrade")||key.startsWith("SplitToning");
  if(g==8)return "LookAmount".equals(key);
  return Arrays.asList(extraKeys(g)).contains(key);
 }
 static String[] extraKeys(int g){
  switch(g){
   case 0:{String[] k=new String[24];for(int i=0;i<8;i++){String c=Develop.COLORS[i];k[3*i]="HueAdjustment"+c;k[3*i+1]="SaturationAdjustment"+c;k[3*i+2]="LuminanceAdjustment"+c;}return k;}
   case 1:return new String[]{"RedHue","RedSaturation","GreenHue","GreenSaturation","BlueHue","BlueSaturation"};
   case 2:return new String[]{"Texture","Clarity2012","Dehaze"};
   case 3:return new String[]{"PostCropVignetteAmount","PostCropVignetteMidpoint","PostCropVignetteRoundness","PostCropVignetteFeather","PostCropVignetteHighlightContrast"};
   case 4:return new String[]{"GrainAmount","GrainSize","GrainFrequency"};
   default:return new String[]{"Sharpness","SharpenRadius","SharpenDetail","SharpenEdgeMasking"};
  }
 }

 void showEditorTour(int step){
  String[] titles={"Comparar etapas","Ajustar a foto","Histograma","Trocar preset","Antes e depois","Salvar e aplicar"};
  String[] notes={"Os botões Original, Automático, Predefinição e Final mostram como a imagem evolui. Toque em cada etapa para comparar.",
   "Os ajustes ficam em quatro grupos, como no Lightroom: Luz (exposição, contraste, realces, sombras, brancos, pretos), Cor (balanço de branco, vibração, saturação, mistura de cores e calibração), Efeitos (textura, claridade, desembaçar, vinheta e granulação) e Detalhe (nitidez). Cada slider mostra o valor final; o número fica azul quando foi alterado. Toque duas vezes no número para voltar ao valor do preset.",
   "O histograma mostra os canais vermelho, verde e azul da etapa na tela. Os triângulos acendem quando há recorte: o da esquerda nas sombras e o da direita nos realces (branco quando os três canais recortam juntos). Toque no histograma para pintar na foto os realces sem detalhe em vermelho e as sombras sem detalhe em azul; toque de novo para esconder.",
   "Toque em Trocar preset para escolher outro visual. A prévia é refeita a partir do original preservado. Ajuste os controles depois para refinar o resultado.",
   "Mantenha o dedo sobre a prévia para ver o original e solte para voltar ao resultado final.",
   "Toque em Exportar novamente para salvar a foto atual. Se abriu várias fotos selecionadas na Galeria, Aplicar em lote permite reprocessar o grupo. Aguarde a conclusão antes de voltar à Galeria."};
  int last=titles.length-1;
  AlertDialog.Builder b=new AlertDialog.Builder(this).setTitle((step+1)+"/"+titles.length+" · "+titles[step]).setMessage(notes[step]).setPositiveButton(step==last?"Concluir":"Próximo",(d,n)->{if(step<last)showEditorTour(step+1);}).setNegativeButton("Sair",null);if(step>0)b.setNeutralButton("Voltar",(d,n)->showEditorTour(step-1));b.show();
 }
 int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
 void palette(){darkMode=true;BG=LumoTheme.BG;CARD=LumoTheme.SURFACE;CARD2=LumoTheme.SURFACE_STRONG;LINE=LumoTheme.LINE;TEXT=LumoTheme.TEXT;MUTED=LumoTheme.SECONDARY;BLUE=LumoTheme.BLUE;GREEN=LumoTheme.OK;WARN=LumoTheme.WARN;}
 GradientDrawable shape(int c,int r){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(r));return d;}
 GradientDrawable border(int c,int r){GradientDrawable d=shape(c,r);d.setStroke(dp(1),LINE);return d;}
 TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",0));return t;}
 LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
 LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
 void gap(LinearLayout p,int h){p.addView(new View(this),new LinearLayout.LayoutParams(1,dp(h)));}
 Button compact(String s,View.OnClickListener l){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(12);b.setTextColor(TEXT);b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(dp(8),0,dp(8),0);b.setBackground(border(CARD2,11));b.setOnClickListener(l);return b;}
 void primary(Button b){b.setTextColor(Color.WHITE);b.setBackground(shape(BLUE,11));}

 @Override public void onCreate(Bundle state){
  darkMode=true;setTheme(LumoTheme.appTheme(this));super.onCreate(state);palette();jobs=new Jobs(this);
  focusId=getIntent().getStringExtra("focusId");pasteOnOpen=getIntent().getBooleanExtra("pasteOnOpen",false);String packed=getIntent().getStringExtra("jobIds");if(packed!=null)for(String id:packed.split(","))if(!id.trim().isEmpty()&&!targets.contains(id.trim()))targets.add(id.trim());if(focusId==null)focusId="";if(!focusId.isEmpty()&&!targets.contains(focusId))targets.add(0,focusId);if(focusId.isEmpty()&&!targets.isEmpty())focusId=targets.get(0);
  getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(CARD);getWindow().getDecorView().setSystemUiVisibility(darkMode?0:(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));

  LinearLayout root=vertical();root.setBackgroundColor(BG);root.setPadding(dp(12),dp(8),dp(12),dp(10));root.setOnApplyWindowInsetsListener((v,in)->{v.setPadding(dp(12)+in.getSystemWindowInsetLeft(),dp(8)+in.getSystemWindowInsetTop(),dp(12)+in.getSystemWindowInsetRight(),dp(10)+in.getSystemWindowInsetBottom());return in.consumeSystemWindowInsets();});setContentView(root);

  LinearLayout top=row();Button back=compact("‹",v->finish());top.addView(back,new LinearLayout.LayoutParams(dp(42),dp(40)));LinearLayout namesBox=vertical();namesBox.setPadding(dp(8),0,0,0);title=text("Editor",15,TEXT,true);namesBox.addView(title);countLabel=text(targets.size()>1?targets.size()+" selecionadas":"1 foto",10,MUTED,false);namesBox.addView(countLabel);top.addView(namesBox,new LinearLayout.LayoutParams(0,-2,1));scoreLabel=text("—/10",12,TEXT,true);scoreLabel.setPadding(dp(9),dp(6),dp(9),dp(6));scoreLabel.setBackground(shape(CARD2,10));top.addView(scoreLabel);root.addView(top);

  gap(root,6);LinearLayout workspace=row();root.addView(workspace,new LinearLayout.LayoutParams(-1,0,1));LinearLayout photoArea=vertical();workspace.addView(photoArea,new LinearLayout.LayoutParams(0,-1,1));FrameLayout previewFrame=new FrameLayout(this);preview=new ImageView(this);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setBackground(shape(0xff07090c,14));previewFrame.addView(preview,new FrameLayout.LayoutParams(-1,-1));
  cropView=new CropView(this);cropView.setVisibility(View.GONE);cropView.listener=(c,done)->{crop=c;cropDirty=true;};previewFrame.addView(cropView,new FrameLayout.LayoutParams(-1,-1));
  LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,0,1);photoArea.addView(previewFrame,pp);
  preview.setOnTouchListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_DOWN){showStage("original");status.setText("Antes · original");return true;}if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL){showStage("final");status.setText("Final · solte para comparar");return true;}return true;});

  stageStrip=row();String[] stages={"Original","Automático","Predefinição","Final"};String[] stageKeys={"original","auto","preset","final"};for(int i=0;i<stages.length;i++){final String sk=stageKeys[i];Button b=compact(stages[i],v->showStage(sk));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(38),1);lp.setMargins(dp(2),dp(6),dp(2),0);stageStrip.addView(b,lp);stageButtons.add(b);}photoArea.addView(stageStrip);
  status=text("Preparando Original → Automático → Predefinição…",10,MUTED,false);status.setGravity(Gravity.CENTER);photoArea.addView(status,new LinearLayout.LayoutParams(-1,dp(38)));

  // Coluna de ajustes: histograma e grupos fixos no topo, sliders do grupo rolando embaixo.
  int width=getResources().getConfiguration().screenWidthDp;LinearLayout side=vertical();side.setPadding(dp(8),dp(8),dp(8),dp(8));side.setBackground(border(CARD,14));LinearLayout.LayoutParams sideLp=new LinearLayout.LayoutParams(dp(width>=600?280:Math.max(156,width*.43f)),-1);sideLp.leftMargin=dp(8);workspace.addView(side,sideLp);
  histogram=new HistogramView(this);histogram.setOnClickListener(v->setClipping(!clipping));side.addView(histogram,new LinearLayout.LayoutParams(-1,dp(width>=600?96:72)));
  gap(side,6);LinearLayout tabs=null;
  for(int i=0;i<GROUPS.length;i++){if(i%3==0){tabs=row();side.addView(tabs);}final int g=i;Button b=compact(GROUPS[i],v->selectGroup(g));b.setTextSize(11);b.setPadding(dp(2),0,dp(2),0);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(40),1);lp.setMargins(i%3==0?0:dp(3),dp(3),0,0);tabs.addView(b,lp);groupButtons.add(b);}
  gap(side,6);ScrollView adjustmentScroll=new ScrollView(this);side.addView(adjustmentScroll,new LinearLayout.LayoutParams(-1,0,1));
  LinearLayout panel=vertical();adjustmentScroll.addView(panel);
  presetButton=compact("Trocar preset",v->choosePreset());panel.addView(presetButton,new LinearLayout.LayoutParams(-1,dp(44)));
  controlsBox=vertical();panel.addView(controlsBox);
  gap(panel,8);panel.addView(compact("Ajuda · tour do editor",v->showEditorTour(0)),new LinearLayout.LayoutParams(-1,dp(44)));

  gap(root,6);LinearLayout tools=row();Button copy=compact("Copiar…",v->copyAdjustments());tools.addView(copy,new LinearLayout.LayoutParams(0,dp(40),1));Button paste=compact("Colar",v->pasteAdjustments(true));LinearLayout.LayoutParams pl=new LinearLayout.LayoutParams(0,dp(40),1);pl.leftMargin=dp(5);tools.addView(paste,pl);Button reset=compact("Base",v->{if(loading||saving)return;extraValues=new JSONObject();setCurrentFromDelta(new JSONObject());requestPreview();});LinearLayout.LayoutParams rl=new LinearLayout.LayoutParams(0,dp(40),1);rl.leftMargin=dp(5);tools.addView(reset,rl);root.addView(tools);
  LinearLayout save=row();Button current=compact("Exportar novamente",v->saveTargets(Collections.singletonList(focusId)));primary(current);save.addView(current,new LinearLayout.LayoutParams(0,dp(44),1));Button batch=compact(targets.size()>1?"Aplicar em "+targets.size():"Aplicar em lote",v->{if(targets.size()>1)saveTargets(new ArrayList<>(targets));else toast("Selecione várias fotos na Galeria primeiro.");});if(targets.size()>1)primary(batch);else batch.setEnabled(false);LinearLayout.LayoutParams bl=new LinearLayout.LayoutParams(0,dp(44),1);bl.leftMargin=dp(6);save.addView(batch,bl);root.addView(save);
  selectGroup(0);loadFocus();
 }

 // ---------------------------------------------------------------- Grupos e sliders

 void selectGroup(int g){
  int old=group;group=g;for(int i=0;i<groupButtons.size();i++){Button b=groupButtons.get(i);if(i==g)primary(b);else{b.setTextColor(TEXT);b.setBackground(border(CARD2,11));}}
  if(old==CROP_GROUP&&g!=CROP_GROUP)leaveCropMode();
  controlsBox.removeAllViews();rows.clear();curveView=null;wheel=null;
  switch(g){
   case 0:
    for(int i=0;i<6;i++)addRow(basic(i,names[i]));
    curvePanel();
    break;
   case 1:
    if(hasLook()){section("Perfil · "+lookName());addRow(new Control("LookAmount","Intensidade",0,200,1,"%.0f",false));}
    else if(!loading){section("Perfil");TextView none=text("O preset não traz perfil criativo.",11,MUTED,false);controlsBox.addView(none);}
    for(int i=6;i<10;i++)addRow(basic(i,names[i]));
    section("Mistura de cores · "+MIX_NAMES[mixColor]);mixChips();String c=Develop.COLORS[mixColor];
    addRow(signed("HueAdjustment"+c,"Matiz"));addRow(signed("SaturationAdjustment"+c,"Saturação"));addRow(signed("LuminanceAdjustment"+c,"Luminância"));
    gradingPanel();
    section("Calibração");
    addRow(signed("RedHue","Vermelho · matiz"));addRow(signed("RedSaturation","Vermelho · saturação"));addRow(signed("GreenHue","Verde · matiz"));addRow(signed("GreenSaturation","Verde · saturação"));addRow(signed("BlueHue","Azul · matiz"));addRow(signed("BlueSaturation","Azul · saturação"));
    break;
   case 2:
    addRow(signed("Texture","Textura"));addRow(signed("Clarity2012","Claridade"));addRow(signed("Dehaze","Desembaçar"));
    section("Vinheta");
    addRow(signed("PostCropVignetteAmount","Quantidade"));addRow(unsigned("PostCropVignetteMidpoint","Ponto médio"));addRow(signed("PostCropVignetteRoundness","Arredondamento"));addRow(unsigned("PostCropVignetteFeather","Difusão"));addRow(unsigned("PostCropVignetteHighlightContrast","Realces"));
    section("Granulação");
    addRow(unsigned("GrainAmount","Quantidade"));addRow(unsigned("GrainSize","Tamanho"));addRow(unsigned("GrainFrequency","Aspereza"));
    break;
   case 3:
    addRow(new Control("Sharpness","Nitidez",0,150,1,"%.0f",false));addRow(new Control("SharpenRadius","Raio",.5,3,.1,"%.1f",false));addRow(unsigned("SharpenDetail","Detalhe"));addRow(unsigned("SharpenEdgeMasking","Máscara"));
    break;
   default:
    cropPanel();if(old!=CROP_GROUP)enterCropMode();
  }
  refreshControls();
 }
 /** Linha de botões de escolha (canal da curva, faixa da gradação, proporção do corte), com o atual destacado. */
 void choices(String[] labels,int selected,int perRow,java.util.function.IntConsumer pick){
  LinearLayout line=null;for(int i=0;i<labels.length;i++){if(i%perRow==0){line=row();controlsBox.addView(line);}final int k=i;Button b=compact(labels[i],v->pick.accept(k));b.setTextSize(11);b.setPadding(dp(2),0,dp(2),0);if(i==selected)primary(b);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(40),1);lp.setMargins(i%perRow==0?0:dp(3),dp(3),0,0);line.addView(b,lp);}
 }

 // ---------------------------------------------------------------- Curva de tons

 /** Curva de tons como no Lightroom: pontos por canal (RGB, vermelho, verde, azul) e a paramétrica. */
 void curvePanel(){
  section("Curva de tons");
  choices(new String[]{"RGB","R","G","B"},curveChannel,4,k->{curveChannel=k;selectGroup(group);});
  gap(controlsBox,6);curveView=new CurveView(this);curveView.histogram=previewHist!=null?previewHist:baseHist;curveView.listener=(pts,done)->{setCurve(curveChannel,pts);showStage("final");requestPreview();};
  controlsBox.addView(curveView,new LinearLayout.LayoutParams(-1,-2));
  TextView hint=text("Toque na linha para criar um ponto; arraste para fora ou toque duas vezes para apagar.",10,MUTED,false);hint.setPadding(0,dp(4),0,0);controlsBox.addView(hint);
  controlsBox.addView(compact("Restaurar curva "+CURVE_NAMES[curveChannel],v->{if(loading||saving)return;clearCurve(curveChannel);refreshControls();showStage("final");requestPreview();}),new LinearLayout.LayoutParams(-1,dp(44)));
  section("Curva paramétrica");
  addRow(signed("ParametricHighlights","Realces"));addRow(signed("ParametricLights","Claros"));addRow(signed("ParametricDarks","Escuros"));addRow(signed("ParametricShadows","Sombras"));
  addRow(unsigned("ParametricShadowSplit","Divisão · sombras"));addRow(unsigned("ParametricMidtoneSplit","Divisão · meios-tons"));addRow(unsigned("ParametricHighlightSplit","Divisão · realces"));
 }
 /** Pontos da curva do canal: a do editor, a do preset ou a reta. */
 double[] curvePoints(int ch){
  String name=Develop.CURVES[ch];ArrayList<Double> v=new ArrayList<>();
  if(extraValues.has(name+".0")){for(int i=0;extraValues.has(name+"."+i)&&i<128;i++)v.add(extraValues.optDouble(name+"."+i,0));}
  else for(int i=0;presetMap.containsKey(name+"."+i)&&i<128;i++)v.add(presetMap.get(name+"."+i));
  if(v.size()<4||v.size()%2!=0)return new double[]{0,0,255,255};double[] out=new double[v.size()];for(int i=0;i<out.length;i++)out[i]=v.get(i);return out;
 }
 void clearCurve(int ch){String name=Develop.CURVES[ch];Iterator<String> it=extraValues.keys();ArrayList<String> drop=new ArrayList<>();while(it.hasNext()){String k=it.next();if(k.startsWith(name+"."))drop.add(k);}for(String k:drop)extraValues.remove(k);}
 void setCurve(int ch,double[] pts){clearCurve(ch);String name=Develop.CURVES[ch];try{for(int i=0;i<pts.length;i++)extraValues.put(name+"."+i,Math.round(pts[i]*100)/100.0);}catch(JSONException ignored){}}

 // ---------------------------------------------------------------- Gradação de cores

 /** Rodas de Sombras, Meios-tons, Realces e Global, com luminância, mistura e equilíbrio. */
 void gradingPanel(){
  section("Gradação de cores · "+GRADE_NAMES[gradeRange]);
  choices(GRADE_NAMES,gradeRange,2,k->{gradeRange=k;selectGroup(group);});
  gap(controlsBox,6);wheel=new ColorWheelView(this);final String[] gk=GRADE_KEYS[gradeRange];
  wheel.listener=(h,sat,done)->{try{extraValues.put(gk[0],h);extraValues.put(gk[1],sat);}catch(JSONException ignored){}touchedGrading();refreshControls();showStage("final");requestPreview();};
  controlsBox.addView(wheel,new LinearLayout.LayoutParams(-1,-2));
  addRow(new Control(gk[0],"Matiz",0,360,1,"%.0f°",false));addRow(unsigned(gk[1],"Saturação"));addRow(signed(gk[2],"Luminância"));
  addRow(unsigned("ColorGradeBlending","Mistura"));addRow(signed("SplitToningBalance","Equilíbrio"));
 }
 static boolean gradingKey(String k){return k.startsWith("ColorGrade")||k.startsWith("SplitToning");}
 /**
  * Mexer na gradação grava a Mistura que está na tela: sem isso, a primeira chave ColorGrade trocaria o padrão do
  * motor (100 dos presets antigos de Split Toning → 50 do Lightroom 10+) e a foto mudaria sozinha.
  */
 void touchedGrading(){if(!extraValues.has("ColorGradeBlending"))try{extraValues.put("ColorGradeBlending",Develop.value("ColorGradeBlending",presetMap));}catch(JSONException ignored){}}

 // ---------------------------------------------------------------- Perfil criativo

 JSONObject look(){JSONObject preset=editorSettings==null?null:editorSettings.optJSONObject("preset");return preset==null?null:preset.optJSONObject("Look");}
 boolean hasLook(){return look()!=null;}
 String lookName(){JSONObject l=look();return l==null?"":l.optString("name","Perfil criativo");}

 // ---------------------------------------------------------------- Corte

 void cropPanel(){
  section("Endireitar");
  addRow(new Control("crop.angle","Ângulo",-45,45,.1,"%+.1f°",false));
  section("Proporção");
  int sel=0;for(int i=1;i<ASPECTS.length;i++){double a=aspectValue(ASPECTS[i]);if(cropAspect>0&&(Math.abs(a-cropAspect)<1e-6||Math.abs(1/a-cropAspect)<1e-6))sel=i;}
  choices(ASPECT_NAMES,sel,3,k->setAspect(aspectValue(ASPECTS[k])));
  controlsBox.addView(compact("Girar proporção (retrato ↔ paisagem)",v->{if(cropAspect>0)setAspect(1/cropAspect);}),new LinearLayout.LayoutParams(-1,dp(44)));
  gap(controlsBox,8);
  controlsBox.addView(compact("Restaurar corte",v->{crop=Crop.none();cropAspect=0;cropDirty=true;showCropPreview();selectGroup(group);}),new LinearLayout.LayoutParams(-1,dp(44)));
  TextView hint=text("Arraste os cantos ou as bordas da moldura; arraste o meio para mover. O corte é aplicado ao sair desta aba.",10,MUTED,false);hint.setPadding(0,dp(6),0,0);controlsBox.addView(hint);
 }
 /** Proporção em pixels (largura/altura) na orientação da foto: "Original" é a da foto inteira. */
 double aspectValue(double a){if(a<0)return fullH>0?fullW/(double)fullH:0;if(a==0||fullW<=0)return a;return fullH>fullW?1/a:a;}
 void setAspect(double a){if(loading||saving)return;cropAspect=a;crop=Crop.largest(fullW,fullH,crop.angle,a);cropDirty=true;showCropPreview();selectGroup(group);}
 /** Ângulo do endireitar: o recorte encolhe para continuar dentro da foto girada. */
 void setAngle(double angle){
  Crop c=new Crop(angle,crop.left,crop.top,crop.right,crop.bottom);
  // Recorte inteiro (sem corte manual) segue o maior que cabe, como no Lightroom.
  boolean full=crop.isIdentity()||Math.abs(crop.right-crop.left-(Crop.largest(fullW,fullH,crop.angle,cropAspect).right-Crop.largest(fullW,fullH,crop.angle,cropAspect).left))<1e-3;
  crop=full?Crop.largest(fullW,fullH,angle,cropAspect>0?cropAspect:0):c.constrained(fullW,fullH);cropDirty=true;showCropPreview();
 }
 void enterCropMode(){
  if(loading||fullPixels==null)return;cropView.setVisibility(View.VISIBLE);status.setText("Corte: arraste a moldura");
  final JSONObject options=currentOptions();
  worker.execute(()->{try{options.remove("crop");int[] size={fullW,fullH};int[] prepped=PhotoEditor.prepare(fullPixels.clone(),size,options);int[] dev=PhotoEditor.render(new Develop.Source(prepped,size[0],size[1]),options);handler.post(()->{if(destroyed)return;fullDeveloped=dev;if(group==CROP_GROUP)showCropPreview();});}catch(Throwable e){handler.post(()->status.setText("Falha ao preparar o corte · "+e.getMessage()));}});
 }
 /** Prévia do Corte: foto inteira girada pelo ângulo e a moldura por cima. */
 void showCropPreview(){
  if(group!=CROP_GROUP)return;int[] base=fullDeveloped!=null?fullDeveloped:fullPixels;if(base==null)return;
  Bitmap b=Bitmap.createBitmap(Crop.rotated(base,fullW,fullH,crop.angle,0xff07090c),fullW,fullH,Bitmap.Config.ARGB_8888);Bitmap old=cropPreview;cropPreview=b;preview.setImageBitmap(b);if(old!=null)old.recycle();
  cropView.set(crop,fullW,fullH,cropAspect);
 }
 Bitmap cropPreview;
 void leaveCropMode(){
  cropView.setVisibility(View.GONE);fullDeveloped=null;
  if(cropDirty&&!crop.equalsCrop(builtCrop)){rebuild("Aplicando o corte…");}else{showStage(stage);status.setText("Segure a foto para ver o original");}
  cropDirty=false;if(cropPreview!=null){Bitmap old=cropPreview;handler.post(old::recycle);cropPreview=null;}
 }
 /** Ajustes atuais (preset, manual, extra e corte) como opções do PhotoEditor. */
 JSONObject currentOptions(){JSONObject o=copyJson(editorSettings);try{o.put("manual",deltaFromCurrent());if(extraValues.length()>0)o.put("extra",copyJson(extraValues));JSONObject c=PhotoEditor.cropJson(crop);if(c!=null)o.put("crop",c);else o.remove("crop");}catch(JSONException ignored){}return o;}

 void section(String s){gap(controlsBox,10);TextView t=text(s.toUpperCase(Locale.getDefault()),10,LumoTheme.TERTIARY,true);t.setLetterSpacing(.08f);controlsBox.addView(t);gap(controlsBox,2);}
 /** Oito cores da mistura (como os círculos do Lightroom), em duas linhas para caber na coluna do celular. */
 void mixChips(){
  LinearLayout line=null;
  for(int i=0;i<8;i++){if(i%4==0){line=row();controlsBox.addView(line,new LinearLayout.LayoutParams(-1,dp(40)));}final int k=i;
   View dot=new View(this);GradientDrawable d=new GradientDrawable();d.setShape(GradientDrawable.OVAL);d.setColor(Color.HSVToColor(new float[]{MIX_HUES[i],.8f,.95f}));if(i==mixColor)d.setStroke(dp(3),Color.WHITE);dot.setBackground(d);
   FrameLayout cell=new FrameLayout(this);cell.setContentDescription("Mistura de cores: "+MIX_NAMES[i]+(i==mixColor?", selecionada":""));cell.setOnClickListener(v->{mixColor=k;selectGroup(group);});cell.addView(dot,new FrameLayout.LayoutParams(dp(26),dp(26),Gravity.CENTER));line.addView(cell,new LinearLayout.LayoutParams(0,-1,1));}
 }
 void addRow(Control c){
  LinearLayout h=row();h.addView(text(c.label,12,TEXT,true),new LinearLayout.LayoutParams(0,-2,1));TextView value=text("0",11,BLUE,true);value.setPadding(dp(8),dp(4),0,dp(4));h.addView(value);controlsBox.addView(h);
  final long[] lastTap={0};value.setOnClickListener(v->{long now=SystemClock.uptimeMillis();if(now-lastTap[0]<350){lastTap[0]=0;resetControl(c);}else lastTap[0]=now;});
  SeekBar bar=new SeekBar(this);bar.setMax(c.steps());bar.setProgressTintList(ColorStateList.valueOf(BLUE));bar.setThumbTintList(ColorStateList.valueOf(BLUE));bar.setContentDescription(c.label);
  bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
   public void onProgressChanged(SeekBar b,int progress,boolean user){if(settingControls||!user)return;double n=c.value(progress);
    if("crop.angle".equals(c.key)){setAngle(n);showValue(c,value,n);return;}
    try{(c.basic?currentValues:extraValues).put(c.key,n);}catch(Exception ignored){}if(gradingKey(c.key)){touchedGrading();if(wheel!=null)refreshWheel();}showValue(c,value,n);showStage("final");requestPreview();}
   public void onStartTrackingTouch(SeekBar b){}
   public void onStopTrackingTouch(SeekBar b){if(!settingControls)requestPreview();}});
  controlsBox.addView(bar,new LinearLayout.LayoutParams(-1,dp(40)));rows.add(new Row(c,bar,value));
 }
 /** Valor que o slider mostra: final (automático + preset + ajuste) nos básicos; o do editor ou o do preset nos extras. */
 double shown(Control c){if("crop.angle".equals(c.key))return crop.angle;return c.basic?val(currentValues,c.key):extraValues.has(c.key)?extraValues.optDouble(c.key,0):Develop.value(c.key,presetMap);}
 boolean changed(Control c){if("crop.angle".equals(c.key))return Math.abs(crop.angle)>1e-9;return c.basic?Math.abs(val(currentValues,c.key)-val(baseValues,c.key))>1e-9:extraValues.has(c.key);}
 void showValue(Control c,TextView label,double v){label.setText(String.format(Locale.getDefault(),c.format,v));label.setTextColor(changed(c)?BLUE:MUTED);label.setContentDescription(c.label+" "+label.getText()+(changed(c)?". Toque duas vezes para voltar ao preset":""));}
 /** Toque duplo no número: volta ao valor do preset (básico) ou tira a chave do editor (extra). */
 void resetControl(Control c){if(loading||saving)return;if("crop.angle".equals(c.key)){setAngle(0);refreshControls();return;}try{if(c.basic)currentValues.put(c.key,val(baseValues,c.key));else extraValues.remove(c.key);}catch(Exception ignored){}refreshControls();showStage("final");requestPreview();}
 void refreshControls(){
  settingControls=true;for(Row r:rows){double v=shown(r.c);r.bar.setProgress(r.c.progress(v));showValue(r.c,r.value,v);r.bar.setEnabled(!loading&&!saving);}settingControls=false;
  if(curveView!=null)curveView.set(curvePoints(curveChannel),CURVE_COLORS[curveChannel]);
  if(wheel!=null)refreshWheel();
 }
 void refreshWheel(){String[] gk=GRADE_KEYS[gradeRange];wheel.set(extraValues.has(gk[0])?extraValues.optDouble(gk[0],0):Develop.value(gk[0],presetMap),extraValues.has(gk[1])?extraValues.optDouble(gk[1],0):Develop.value(gk[1],presetMap));}

 void choosePreset(){if(loading||saving||previewBusy)return;ArrayList<String> list=PresetLibrary.names(this);list.add(0,"Sem preset");new AlertDialog.Builder(this).setTitle("Preset para reexportação").setItems(list.toArray(new String[0]),(d,n)->{selectedPreset=n==0?new JSONObject():PresetLibrary.get(this,list.get(n));selectedPresetName=list.get(n);presetChanged=true;presetButton.setText(selectedPresetName);loadFocus();}).setNegativeButton("Cancelar",null).show();}
 double val(JSONObject o,String key){return o==null?0:o.optDouble(key,0);}
 JSONObject copyJson(JSONObject src){try{return new JSONObject(src==null?"{}":src.toString());}catch(Exception e){return new JSONObject();}}
 JSONObject deltaFromCurrent(){JSONObject d=new JSONObject();for(String key:keys)try{d.put(key,val(currentValues,key)-val(baseValues,key));}catch(Exception ignored){}return d;}
 void setCurrentFromDelta(JSONObject delta){settingControls=true;currentValues=new JSONObject();for(String key:keys){double v=val(baseValues,key)+val(delta,key);double lo="exposure".equals(key)?-5:-100,hi="exposure".equals(key)?5:100;v=Math.max(lo,Math.min(hi,v));try{currentValues.put(key,v);}catch(Exception ignored){}}settingControls=false;refreshControls();}
 /** Só chaves Camera Raw conhecidas, dentro da faixa do Lightroom. */
 static JSONObject cleanExtra(JSONObject src){JSONObject out=new JSONObject();if(src==null)return out;Iterator<String> it=src.keys();while(it.hasNext()){String k=it.next();double[] r=Develop.RANGES.get(k);if(r==null&&PhotoEditor.curveKey(k))r=new double[]{0,255};double v=src.optDouble(k,Double.NaN);if(r==null||Double.isNaN(v))continue;try{out.put(k,Math.max(r[0],Math.min(r[1],v)));}catch(Exception ignored){}}return out;}

 // ---------------------------------------------------------------- Foto e prévia

 void loadFocus(){loading=true;refreshControls();if(focusId.isEmpty()){status.setText("Nenhuma foto selecionada.");return;}worker.execute(()->{File original=null;Stages st=null;try{String[] j=jobs.editorJob(focusId);if(j==null)throw new IOException("Foto não encontrada no histórico.");String name=j[1],settingsText=j[2],originalUri=j[3];JSONObject settings=new JSONObject(settingsText);if(presetChanged){settings.put("preset",copyJson(selectedPreset));settings.put("presetName",selectedPresetName);settings.remove("manual");settings.remove("extra");}JSONObject existing=settings.optJSONObject("manual"),existingExtra=cleanExtra(settings.optJSONObject("extra"));Crop savedCrop=PhotoEditor.crop(settings);if(savedCrop==null)savedCrop=Crop.none();original=new File(getCacheDir(),"editor_src_"+System.nanoTime()+".jpg");copyUri(originalUri,original);
   // A foto é decodificada uma vez (inteira, para o modo Corte); Original, Auto, Preset e a prévia final saem do mesmo motor.
   Bitmap full=PhotoEditor.decode(original,1200);final int fw=full.getWidth(),fh=full.getHeight();final int[] px=new int[fw*fh];full.getPixels(px,0,fw,0,0,fw,fh);full.recycle();
   JSONObject base=new JSONObject(settings.toString());base.remove("manual");base.remove("extra");base.remove("crop");JSONObject oldDelta=existing==null?new JSONObject():copyJson(existing);
   st=buildStages(px,fw,fh,base,savedCrop,existingExtra);
   int score=jobs.quality(focusId)[0];final Stages fs=st;st=null;final Crop fc=savedCrop;handler.post(()->{if(destroyed){fs.recycle();return;}fullPixels=px;fullW=fw;fullH=fh;crop=fc;builtCrop=fc;editorSettings=base;extraValues=existingExtra;install(fs);title.setText(name.replaceFirst("(?i)\\.jpg$",""));countLabel.setText(targets.size()>1?targets.size()+" selecionadas":"1 foto");scoreLabel.setText(score>0?score+"/10":"—/10");scoreLabel.setTextColor(score>=8?GREEN:score>=5?WARN:TEXT);setCurrentFromDelta(oldDelta);selectGroup(group==CROP_GROUP?0:group);status.setText("Segure a foto para ver o original");showStage(stage);requestPreview();if(pasteOnOpen){pasteOnOpen=false;pasteAdjustments(false);}});
  }catch(Throwable e){if(st!=null)st.recycle();String msg=e instanceof OutOfMemoryError?"memória insuficiente":e.getMessage();handler.post(()->{loading=false;refreshControls();status.setText("Falha ao preparar editor · "+msg);});}finally{if(original!=null)original.delete();}});}

 /** Etapas do editor montadas no worker: foto recortada, fonte com o perfil criativo e as três prévias fixas. */
 static final class Stages{
  Bitmap original,auto,base;Histogram hO,hA,hP;Develop.Source source;int[] plain;int w,h;double lookAmount=Double.NaN;JSONObject autoValues,presetValues,baseValues;Map<String,Double> presetMap;
  void recycle(){if(original!=null)original.recycle();if(auto!=null)auto.recycle();if(base!=null)base.recycle();}
 }
 /** Recorta a foto inteira, aplica o perfil e revela Automático e Predefinição (roda no worker). */
 static Stages buildStages(int[] full,int w,int h,JSONObject base,Crop c,JSONObject extra)throws Exception{
  Stages st=new Stages();int[] px=full;int[] size={w,h};
  if(c!=null&&!c.isIdentity()){int[] o=new int[2];px=c.constrained(w,h).apply(full,w,h,o);size=o;}
  st.plain=px;st.w=size[0];st.h=size[1];
  try{
   st.original=Bitmap.createBitmap(px,size[0],size[1],Bitmap.Config.ARGB_8888);st.hO=Histogram.of(px);
   Develop.Source plain=new Develop.Source(px,size[0],size[1]),src=plain;
   JSONObject withExtra=new JSONObject(base.toString());if(extra!=null&&extra.length()>0)withExtra.put("extra",extra);
   CreativeProfile.Profile prof=PhotoEditor.profile(withExtra);
   if(prof!=null){int[] q=px.clone();CreativeProfile.apply(q,size[0],size[1],prof);src=new Develop.Source(q,size[0],size[1]);st.lookAmount=Develop.value("LookAmount",PhotoEditor.merged(base.optJSONObject("preset"),extra));}
   st.source=src;st.presetMap=PhotoEditor.values(base.optJSONObject("preset"));
   st.autoValues=PhotoEditor.autoValues(src,base);st.presetValues=PhotoEditor.presetControls(base.optJSONObject("preset"));st.baseValues=PhotoEditor.sumControls(st.autoValues,st.presetValues);
   JSONObject a=new JSONObject();a.put("auto",base.optBoolean("auto",true));a.put("autoStrength",base.optDouble("autoStrength",1.0));
   int[] pa=PhotoEditor.render(plain,a),pb=PhotoEditor.render(src,base);
   st.auto=Bitmap.createBitmap(pa,size[0],size[1],Bitmap.Config.ARGB_8888);st.base=Bitmap.createBitmap(pb,size[0],size[1],Bitmap.Config.ARGB_8888);st.hA=Histogram.of(pa);st.hP=Histogram.of(pb);
   return st;
  }catch(Throwable e){st.recycle();throw e;}
 }
 /** Troca as etapas na tela pelas novas (thread principal). */
 void install(Stages st){
  loading=false;recycleStages();originalBitmap=st.original;autoBitmap=st.auto;baseBitmap=st.base;originalHist=st.hO;autoHist=st.hA;baseHist=st.hP;
  editorSource=st.source;plainPixels=st.plain;plainW=st.w;plainH=st.h;sourceLookAmount=st.lookAmount;presetMap=st.presetMap;autoValues=st.autoValues;presetValues=st.presetValues;baseValues=st.baseValues;
 }
 /** Foto recortada sem perfil (para refazer a fonte quando a intensidade do perfil muda). */
 int[] plainPixels;int plainW,plainH;
 /** Remonta as etapas com o corte atual, mantendo os ajustes. */
 void rebuild(String message){
  if(fullPixels==null)return;loading=true;refreshControls();status.setText(message);final JSONObject delta=deltaFromCurrent(),extra=copyJson(extraValues),base=copyJson(editorSettings);final Crop c=crop;final int[] px=fullPixels;final int w=fullW,h=fullH;
  worker.execute(()->{try{Stages st=buildStages(px,w,h,base,c,extra);handler.post(()->{if(destroyed){st.recycle();return;}builtCrop=c;install(st);previewBitmap=null;previewHist=null;setCurrentFromDelta(delta);status.setText("Segure a foto para ver o original");showStage("final");requestPreview();});}
   catch(Throwable e){String msg=e instanceof OutOfMemoryError?"memória insuficiente":e.getMessage();handler.post(()->{loading=false;refreshControls();status.setText("Falha ao aplicar · "+msg);});}});
 }
 void recycleStages(){clearClips();if(originalBitmap!=null)originalBitmap.recycle();if(autoBitmap!=null)autoBitmap.recycle();if(baseBitmap!=null)baseBitmap.recycle();if(previewBitmap!=null&&previewBitmap!=baseBitmap)previewBitmap.recycle();originalBitmap=autoBitmap=baseBitmap=previewBitmap=null;originalHist=autoHist=baseHist=previewHist=null;editorSource=null;}

 void showStage(String which){
  if(group==CROP_GROUP&&cropView!=null&&cropView.getVisibility()==View.VISIBLE)return;
  stage=which;boolean fin=!"original".equals(which)&&!"auto".equals(which)&&!"preset".equals(which);
  Bitmap b="original".equals(which)?originalBitmap:"auto".equals(which)?autoBitmap:"preset".equals(which)?baseBitmap:(previewBitmap!=null?previewBitmap:baseBitmap);
  Histogram h="original".equals(which)?originalHist:"auto".equals(which)?autoHist:"preset".equals(which)?baseHist:(previewHist!=null&&previewBitmap!=null?previewHist:baseHist);
  if(b!=null)preview.setImageBitmap(display(b));histogram.set(h);
  for(int i=0;i<stageButtons.size();i++){boolean on=(i==0&&"original".equals(which))||(i==1&&"auto".equals(which))||(i==2&&"preset".equals(which))||(i==3&&fin);Button bt=stageButtons.get(i);bt.setTextColor(on?Color.WHITE:TEXT);bt.setBackground(on?shape(BLUE,11):border(CARD2,11));}
 }
 /** Bitmap na tela: com a máscara de recorte quando ligada (cópia guardada até o bitmap sair de cena). */
 Bitmap display(Bitmap b){
  if(!clipping||b==null)return b;Bitmap c=clipCache.get(b);if(c!=null)return c;
  try{int w=b.getWidth(),h=b.getHeight();int[] px=new int[w*h];b.getPixels(px,0,w,0,0,w,h);c=Bitmap.createBitmap(Histogram.clipping(px),w,h,Bitmap.Config.ARGB_8888);clipCache.put(b,c);return c;}catch(OutOfMemoryError e){return b;}
 }
 void setClipping(boolean on){clipping=on;histogram.setClipping(on);if(!on)clearClips();showStage(stage);status.setText(on?"Recorte: realces sem detalhe em vermelho, sombras em azul":"Segure a foto para ver o original");}
 void clearClips(){for(Bitmap c:clipCache.values())if(c!=null)c.recycle();clipCache.clear();}
 void dropClip(Bitmap b){Bitmap c=clipCache.remove(b);if(c!=null)c.recycle();}
 void requestPreview(){previewQueued=true;if(destroyed||loading||baseBitmap==null||previewBusy)return;startPreviewFrame();}
 void startPreviewFrame(){if(destroyed||loading||baseBitmap==null||previewBusy)return;previewQueued=false;previewBusy=true;JSONObject delta=deltaFromCurrent();JSONObject extra=copyJson(extraValues);final boolean clip=clipping;final Develop.Source current=editorSource;final JSONObject options=copyJson(editorSettings);
  // Intensidade do perfil criativo mudou: a fonte (foto recortada com o perfil) é refeita antes de revelar.
  final double wantLook=hasLook()?Develop.value("LookAmount",PhotoEditor.merged(options.optJSONObject("preset"),extra)):Double.NaN;final boolean relook=!Double.isNaN(wantLook)&&wantLook!=sourceLookAmount&&plainPixels!=null;final int[] plain=plainPixels;final int pw=plainW,ph=plainH;
  worker.execute(()->{Bitmap out=null,outClip=null;try{if(current==null)throw new IOException("Editor sem foto.");options.put("manual",delta);if(extra.length()>0)options.put("extra",extra);
   Develop.Source source=current;if(relook){CreativeProfile.Profile prof=PhotoEditor.profile(options);int[] q=plain.clone();if(prof!=null)CreativeProfile.apply(q,pw,ph,prof);source=new Develop.Source(q,pw,ph);final Develop.Source fresh=source;handler.post(()->{if(editorSource==current){editorSource=fresh;sourceLookAmount=wantLook;}});}
   int[] px=PhotoEditor.render(source,options);out=Bitmap.createBitmap(px,source.w,source.h,Bitmap.Config.ARGB_8888);final Histogram hist=Histogram.of(px);if(clip)outClip=Bitmap.createBitmap(Histogram.clipping(px),source.w,source.h,Bitmap.Config.ARGB_8888);final Bitmap ready=out,readyClip=outClip;handler.post(()->{if(destroyed){ready.recycle();if(readyClip!=null)readyClip.recycle();return;}if(previewBitmap!=null&&previewBitmap!=baseBitmap&&previewBitmap!=originalBitmap&&previewBitmap!=autoBitmap){dropClip(previewBitmap);previewBitmap.recycle();}previewBitmap=ready;previewHist=hist;if(readyClip!=null){if(clipping)clipCache.put(ready,readyClip);else readyClip.recycle();}if("final".equals(stage)&&group!=CROP_GROUP){preview.setImageBitmap(display(ready));histogram.set(hist);}if(curveView!=null){curveView.histogram=hist;curveView.invalidate();}previewBusy=false;if(previewQueued)handler.postDelayed(this::startPreviewFrame,16);});}catch(Throwable e){if(out!=null)out.recycle();if(outClip!=null)outClip.recycle();handler.post(()->{previewBusy=false;if(previewQueued)handler.postDelayed(this::startPreviewFrame,16);});}});}

 // ---------------------------------------------------------------- Copiar e colar

 /** Área de transferência: deltas dos básicos nas chaves do editor e, em "extra", os demais ajustes com valor final. */
 void copyAdjustments(){
  String[] items=new String[keys.length+EXTRA_GROUPS.length];System.arraycopy(names,0,items,0,keys.length);System.arraycopy(EXTRA_GROUPS,0,items,keys.length,EXTRA_GROUPS.length);
  boolean[] checked=new boolean[items.length];Arrays.fill(checked,true);
  new AlertDialog.Builder(this).setTitle("Copiar quais ajustes?").setMultiChoiceItems(items,checked,(d,which,isChecked)->checked[which]=isChecked).setPositiveButton("Copiar",(d,n)->{
   JSONObject all=deltaFromCurrent(),clip=new JSONObject(),extra=new JSONObject();
   for(int i=0;i<keys.length;i++)if(checked[i])try{clip.put(keys[i],all.optDouble(keys[i],0));}catch(Exception ignored){}
   for(int g=0;g<EXTRA_GROUPS.length;g++)if(checked[keys.length+g]){Iterator<String> ek=extraValues.keys();while(ek.hasNext()){String k=ek.next();if(inGroup(g,k))try{extra.put(k,extraValues.optDouble(k,0));}catch(Exception ignored){}}}
   try{if(extra.length()>0)clip.put("extra",extra);}catch(Exception ignored){}
   getSharedPreferences("hisho",0).edit().putString("manualEditClipboard",clip.toString()).apply();toast("Ajustes selecionados copiados.");}).setNegativeButton("Cancelar",null).show();
 }
 void pasteAdjustments(boolean toast){if(loading||saving)return;String raw=getSharedPreferences("hisho",0).getString("manualEditClipboard","");if(raw.isEmpty()){if(toast)toast("Nenhum refinamento copiado ainda.");return;}try{JSONObject clip=new JSONObject(raw),merged=deltaFromCurrent();Iterator<String> it=clip.keys();while(it.hasNext()){String k=it.next();if(Arrays.asList(keys).contains(k))merged.put(k,clip.optDouble(k,0));}JSONObject extra=cleanExtra(clip.optJSONObject("extra"));for(int ch=0;ch<4;ch++)if(extra.has(Develop.CURVES[ch]+".0"))clearCurve(ch);Iterator<String> ek=extra.keys();while(ek.hasNext()){String k=ek.next();extraValues.put(k,extra.optDouble(k,0));}setCurrentFromDelta(merged);showStage("final");requestPreview();if(toast)toast("Ajustes colados sobre a base desta foto.");}catch(Exception e){if(toast)toast("Não foi possível colar os ajustes.");}}

 // ---------------------------------------------------------------- Salvar

 void saveTargets(List<String> ids){if(loading||saving){toast("Aguarde a operação atual.");return;}if(ids==null||ids.isEmpty()||ids.get(0).isEmpty())return;final JSONObject chosen=deltaFromCurrent(),chosenExtra=copyJson(extraValues);saving=true;refreshControls();presetButton.setEnabled(false);status.setText("Aplicando em "+ids.size()+" foto(s)…");worker.execute(()->{int ok=0,review=0,fail=0,sentBefore=0;String lastError="";for(String id:ids){if(!DemoLicense.allowed(this))break;try{SaveResult r=saveOne(id,chosen,chosenExtra);ok++;if(r.review)review++;if(r.wasSent)sentBefore++;}catch(Exception e){fail++;lastError=e.getMessage();}}final int fOk=ok,fReview=review,fFail=fail,fSent=sentBefore;final String fErr=lastError;handler.post(()->{saving=false;refreshControls();presetButton.setEnabled(true);String msg="Concluído · "+fOk+" salva(s)"+(fReview>0?" · "+fReview+" em Revisão":"")+(fFail>0?" · "+fFail+" falha(s)":"");if(fSent>0)msg+=" · "+fSent+" já enviada(s)";status.setText(msg+(fErr.isEmpty()?"":" · "+fErr));toast(msg);});});}
 static final class SaveResult{boolean review,wasSent;SaveResult(boolean r,boolean s){review=r;wasSent=s;}}
 SaveResult saveOne(String id,JSONObject chosen,JSONObject chosenExtra)throws Exception{String[] j=jobs.editorJob(id);if(j==null)throw new IOException("Foto "+id+" não encontrada.");String name=j[1],settingsText=j[2],originalUri=j[3],editedUri=j[4],state=j[5];JSONObject settings=new JSONObject(settingsText);if(presetChanged){settings.put("preset",copyJson(selectedPreset));settings.put("presetName",selectedPresetName);}settings.put("manual",copyJson(chosen));if(chosenExtra!=null&&chosenExtra.length()>0)settings.put("extra",copyJson(chosenExtra));else settings.remove("extra");
  // O corte vale só para a foto que estava na tela; as demais do lote mantêm o próprio.
  if(id.equals(focusId)){JSONObject c=PhotoEditor.cropJson(crop);if(c!=null)settings.put("crop",c);else settings.remove("crop");}settings.put("edge",PhotoEditor.exportEdge(this));File original=new File(getCacheDir(),"manual_original_"+id+".jpg"),output=new File(getCacheDir(),"manual_output_"+id+".jpg");try{copyUri(originalUri,original);PhotoEditor.Export export=PhotoEditor.edit(original,output,settings);boolean curate=settings.optBoolean("curation",true);SmartCurator.Result quality;try{if(export.curation==null)throw export.curationError!=null?export.curationError:new IOException("sem medições");quality=SmartCurator.finish(export.curation,export.sharpness,export.exposure,settings.optInt("curationSensitivity",1),jobs,id,output.length());}catch(Exception e){quality=new SmartCurator.Result(true,1,"Falha na curadoria · "+e.getMessage(),"","","");}boolean review=curate&&quality.review;boolean wasSent=jobs.fottoWasSent(id);jobs.updateSettings(id,settings.toString());jobs.fottoDiscardUnsent(id);jobs.setIntelligence(id,quality.pHash,quality.duplicateOf,quality.semantic);jobs.markEdited(id,settings.optString("presetName",""));jobs.setQuality(id,quality.score,quality.reason+(curate?"":" · Curadoria desligada: score informativo"));
   if(review){String old=editedUri;jobs.clearEdited(id);Uri uri=jobs.save(output,"Sob Revisao",name.replaceFirst("(?i)\\.jpg$","_REVISAO.jpg"),id,"edited");jobs.set(id,"review","error",quality.reason);jobs.history(id,System.currentTimeMillis(),"Revisão","Ajuste manual ainda requer revisão · "+quality.reason);if(old!=null&&!old.isEmpty()&&!old.equals(uri.toString()))try{getContentResolver().delete(Uri.parse(old),null,null);}catch(Exception ignored){}return new SaveResult(true,wasSent);}
   if("done".equals(state)&&editedUri!=null&&!editedUri.isEmpty())jobs.overwrite(output,editedUri);else{String old=editedUri;jobs.clearEdited(id);Uri uri=jobs.save(output,"Editadas",name.replaceFirst("(?i)\\.jpg$","_Editada.jpg"),id,"edited");if(old!=null&&!old.isEmpty()&&!old.equals(uri.toString()))try{getContentResolver().delete(Uri.parse(old),null,null);}catch(Exception ignored){}}
   jobs.set(id,"done","error",null);jobs.history(id,System.currentTimeMillis(),"Aprovada","Ajuste manual aprovado para entrega");if(!wasSent)FottoSync.kick(ManualEditorActivity.this);return new SaveResult(false,wasSent);
  }finally{original.delete();output.delete();}}
 void copyUri(String raw,File out)throws IOException{if(raw==null||raw.isEmpty())throw new IOException("Original não encontrado.");try(InputStream in=getContentResolver().openInputStream(Uri.parse(raw));OutputStream dst=new FileOutputStream(out)){if(in==null)throw new IOException("Original indisponível.");Jobs.copy(in,dst);}}
 void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
 @Override protected void onDestroy(){destroyed=true;previewQueued=false;worker.shutdownNow();recycleStages();jobs.close();super.onDestroy();}
}
