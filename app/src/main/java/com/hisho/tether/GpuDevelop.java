package com.hisho.tether;
import android.opengl.*;import java.nio.*;import java.util.*;import java.util.concurrent.*;

/**
 * Revelação na GPU do celular (OpenGL ES 3.0), com o motor Java ({@link Develop}) como reserva.
 *
 * A CPU continua calculando o que é feito uma vez por foto (campos locais, tabela de tons, LUT de cor); a GPU faz o
 * trabalho por pixel com os shaders do {@link GpuProgram}. Um contexto EGL próprio vive numa thread dedicada
 * ("lumo-gpu"), então funciona no serviço de captura, sem tela.
 *
 * Antes do primeiro uso, um autoteste revela a mesma cena na GPU e no motor Java e compara. Se a GPU divergir, faltar
 * OpenGL ES 3 ou algo falhar, ela é desligada até o app reiniciar e tudo segue na CPU.
 */
final class GpuDevelop{
 static final String TAG="LUMO-GPU";
 /** Fotos menores revelam rápido na CPU; a GPU compensa a partir daqui. */
 static final long MIN_PIXELS=2_000_000;
 static final int STRIP=256;
 /** Ajustes → "Revelar na GPU". */
 static volatile boolean userEnabled=true;
 private static volatile String disabled;
 private static volatile String testResult="";
 private static final Object TEST_LOCK=new Object();
 private static boolean tested;
 private static final ExecutorService GL=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"lumo-gpu");t.setDaemon(true);return t;});
 /** Estado do OpenGL: só é tocado na thread "lumo-gpu". */
 private static State state;

 /** A GPU falhou depois de começar a escrever os pixels: a foto precisa ser decodificada de novo (já na CPU). */
 static final class Dirty extends RuntimeException{Dirty(Throwable cause){super("GPU falhou durante a leitura",cause);}}

 static String status(){return !userEnabled?"CPU (GPU desligada em Ajustes)":disabled!=null?"CPU ("+disabled+")":tested?"GPU":"GPU (autoteste pendente)";}
 static String testResult(){return testResult;}

 /**
  * Revela {@code src} por cima dos próprios pixels na GPU. Devolve false sem tocar nos pixels quando a foto deve ir
  * para a CPU ({@code why} recebe o motivo). Lança {@link Dirty} se falhar depois de começar a escrever.
  */
 static boolean render(Develop.Source src,Develop.Settings settings,StringBuilder why){
  if(!userEnabled){why.append("GPU desligada em Ajustes");return false;}
  if((long)src.w*src.h<MIN_PIXELS){why.append("foto pequena");return false;}
  selfTest();
  if(disabled!=null){why.append(disabled);return false;}
  Develop.Plan plan=new Develop.Plan(src,settings);
  if(plan.identity)return true;
  final GpuProgram.Inputs in;
  try{in=new GpuProgram.Inputs(plan);}catch(RuntimeException e){why.append(e.getMessage());return false;}
  return submit(in,src.px,why);
 }

 private static boolean submit(GpuProgram.Inputs in,int[] px,StringBuilder why){
  final boolean[] wrote={false};
  Future<?> job=GL.submit(()->{if(state==null)state=new State();state.run(in,px,wrote);return null;});
  try{job.get(60,TimeUnit.SECONDS);return true;}
  catch(Exception e){
   Throwable c=e instanceof ExecutionException?e.getCause():e;
   if(c instanceof Unsupported){why.append(c.getMessage());return false;}
   disable("GPU falhou: "+c);why.append(disabled);
   if(e instanceof TimeoutException)job.cancel(true);
   GL.submit(GpuDevelop::release);
   if(wrote[0])throw new Dirty(c);
   return false;
  }
 }

 static void disable(String reason){
  if(disabled==null){disabled=reason;android.util.Log.w(TAG,"desligada: "+reason);CrashReporter.note("gpu · desligada: "+reason);}
 }
 private static void release(){if(state!=null){state.destroy();state=null;}}

 /** Começa o autoteste em segundo plano (início da captura), para a primeira foto não esperar por ele. */
 static void warmUp(){if(userEnabled&&!tested)new Thread(GpuDevelop::selfTest,"lumo-gpu-teste").start();}

 /**
  * Revela a mesma cena (1800×1200, preset completo) na GPU e no motor Java. Aceita o que o teste do desktop aceita
  * (tests/gpu_test.py): 99% dos pixels a até 1 nível, 99,9% a até 2.
  */
 static void selfTest(){
  synchronized(TEST_LOCK){
   if(tested)return;tested=true;
   try{
    int w=1800,h=1200;int[] scene=scene(w,h);Develop.Settings s=Develop.Settings.of(testPreset());
    long t0=System.nanoTime();int[] cpu=new Develop.Source(scene.clone(),w,h).renderInPlace(s);long t1=System.nanoTime();
    Develop.Source g=new Develop.Source(scene.clone(),w,h);StringBuilder why=new StringBuilder();
    GpuProgram.Inputs in=new GpuProgram.Inputs(new Develop.Plan(g,s));
    if(!submit(in,g.px,why)){disable(why.length()>0?why.toString():"autoteste não rodou");testResult="indisponível: "+disabled;android.util.Log.w(TAG,"autoteste "+testResult);return;}
    long t2=System.nanoTime();
    int worst=0;long within1=0,within2=0;
    for(int i=0;i<cpu.length;i++){int a=cpu[i],b=g.px[i],d=0;for(int sh=0;sh<=16;sh+=8)d=Math.max(d,Math.abs(((a>>sh)&255)-((b>>sh)&255)));worst=Math.max(worst,d);if(d<=1)within1++;if(d<=2)within2++;}
    double p1=within1*100./cpu.length,p2=within2*100./cpu.length;
    testResult=String.format(Locale.ROOT,"máx %d · ≤1 %.2f%% · ≤2 %.3f%% · CPU %d ms · GPU %d ms (com compilação)",worst,p1,p2,(t1-t0)/1000000,(t2-t1)/1000000);
    if(p1<99||p2<99.9){disable("autoteste divergiu ("+testResult+")");android.util.Log.e(TAG,"DIVERGE "+testResult);}
    else android.util.Log.i(TAG,"autoteste ok "+testResult);
   }catch(Throwable e){disable("autoteste falhou: "+e);testResult="falhou: "+e;android.util.Log.e(TAG,"autoteste falhou",e);}
  }
 }

 /**
  * Para o CI: autoteste, diagnóstico por etapa (mesmo com a GPU desligada pelo autoteste) e uma revelação de 12 MP
  * na GPU e na CPU, com os tempos no logcat. Termina sempre com "fim do teste da GPU".
  */
 static void benchmark(){
  try{
   selfTest();android.util.Log.i(TAG,"status: "+status()+" · autoteste: "+testResult);
   diagnose();
   if(disabled==null)bench();
  }finally{android.util.Log.i(TAG,"fim do teste da GPU");}
 }

 /** Uma etapa do motor por vez, para saber qual diverge num driver (não liga nem desliga a GPU). */
 static void diagnose(){
  Object[][] cases={
   {"base (exposição)","Exposure2012",.3},
   {"curva de tons","Contrast2012",40,"Blacks2012",-30},
   {"realces e brancos","Highlights2012",-80,"Whites2012",40,"Exposure2012",.5},
   {"sombras (campo local)","Shadows2012",70},
   {"claridade","Clarity2012",50},
   {"textura","Texture",50},
   {"dehaze","Dehaze",40},
   {"cor (LUT)","Vibrance",40,"SaturationAdjustmentBlue",-40,"SplitToningShadowSaturation",30,"SplitToningShadowHue",210},
   {"nitidez","Sharpness",80},
   {"vinheta","PostCropVignetteAmount",-40},
   {"grão","GrainAmount",40},
  };
  int w=1800,h=1200;int[] scene=scene(w,h);
  for(Object[] c:cases){
   try{Map<String,Double> m=new HashMap<>();for(int i=1;i<c.length;i+=2)m.put((String)c[i],((Number)c[i+1]).doubleValue());Develop.Settings s=Develop.Settings.of(m);
    int[] cpu=new Develop.Source(scene.clone(),w,h).renderInPlace(s);Develop.Source g=new Develop.Source(scene.clone(),w,h);
    GpuProgram.Inputs in=new GpuProgram.Inputs(new Develop.Plan(g,s));final int[] px=g.px;
    GL.submit(()->{if(state==null)state=new State();state.run(in,px,new boolean[1]);return null;}).get(120,TimeUnit.SECONDS);
    int worst=0;long d1=0,d2=0;double bias=0;
    for(int i=0;i<cpu.length;i++){int a=cpu[i],b=px[i],d=0;for(int sh=0;sh<=16;sh+=8){int dd=((b>>sh)&255)-((a>>sh)&255);d=Math.max(d,Math.abs(dd));bias+=dd;}worst=Math.max(worst,d);if(d<=1)d1++;if(d<=2)d2++;}
    android.util.Log.i(TAG,String.format(Locale.ROOT,"diag %-22s máx %d · ≤1 %.2f%% · ≤2 %.2f%% · desvio médio %+.3f",c[0],worst,d1*100./cpu.length,d2*100./cpu.length,bias/(3.*cpu.length)));
   }catch(Throwable e){android.util.Log.e(TAG,"diag "+c[0]+" falhou: "+e);}
  }
 }

 static void bench(){
  try{
   // 12 MP: cabe na memória do emulador com as duas versões lado a lado.
   int w=4000,h=3000;int[] scene=scene(w,h);Develop.Settings s=Develop.Settings.of(testPreset());
   for(int round=0;round<2;round++){
    long t0=System.nanoTime();Develop.Source g=new Develop.Source(scene.clone(),w,h);StringBuilder why=new StringBuilder();boolean ok=render(g,s,why);long t1=System.nanoTime();
    int[] cpu=new Develop.Source(scene.clone(),w,h).renderInPlace(s);long t2=System.nanoTime();
    int worst=0;for(int i=0;i<cpu.length;i+=7){int a=cpu[i],b=g.px[i];for(int sh=0;sh<=16;sh+=8)worst=Math.max(worst,Math.abs(((a>>sh)&255)-((b>>sh)&255)));}
    android.util.Log.i(TAG,String.format(Locale.ROOT,"bench 12 MP rodada %d: GPU %s %d ms · CPU %d ms · máx %d",round,ok?"ok":"não ("+why+")",(t1-t0)/1000000,(t2-t1)/1000000,worst));
   }
  }catch(Throwable e){android.util.Log.e(TAG,"bench falhou",e);}
 }

 static Map<String,Double> testPreset(){
  Object[] kv={"IncrementalTemperature",-11,"Exposure2012",.15,"Contrast2012",17,"Highlights2012",-70,"Shadows2012",62,"Whites2012",30,"Blacks2012",-20,"Clarity2012",35,"Texture",10,"Dehaze",8,
   "Vibrance",35,"Saturation",-10,"ParametricShadows",10,"ParametricHighlights",-15,"ToneCurvePV2012.0",0,"ToneCurvePV2012.1",18,"ToneCurvePV2012.2",128,"ToneCurvePV2012.3",132,
   "ToneCurvePV2012.4",255,"ToneCurvePV2012.5",245,"HueAdjustmentOrange",-8,"SaturationAdjustmentBlue",-29,"LuminanceAdjustmentBlue",-20,"SplitToningShadowHue",210,
   "SplitToningShadowSaturation",15,"SplitToningHighlightHue",45,"SplitToningHighlightSaturation",12,"BlueSaturation",25,"RedHue",10,"Sharpness",40,"PostCropVignetteAmount",-21,"GrainAmount",12};
  Map<String,Double> m=new HashMap<>();for(int i=0;i<kv.length;i+=2)m.put((String)kv[i],((Number)kv[i+1]).doubleValue());return m;
 }
 /** Cena fixa: céu em degradê, pele e folhagem em xadrez, rampa de cinzas com linhas, mais ruído determinístico. */
 static int[] scene(int w,int h){
  int[] p=new int[w*h];Random rnd=new Random(7);
  for(int y=0;y<h;y++)for(int x=0;x<w;x++){double u=x/(double)(w-1),v=y/(double)(h-1);int R,G,B;
   if(v<.33){R=(int)(60+90*u);G=(int)(120+80*u);B=(int)(190+50*u);}
   else if(v<.66){boolean skin=((x/6)+(y/6))%2==0;R=skin?(int)(200+40*u):(int)(40+30*u);G=skin?(int)(150+30*u):(int)(110+60*u);B=skin?(int)(120+20*u):(int)(40+20*u);}
   else{int k=(int)(255*u);R=G=B=k;if(x%9==0)R=G=B=255-k;}
   int n=rnd.nextInt(13)-6;p[y*w+x]=0xff000000|clamp(R+n)<<16|clamp(G+n)<<8|clamp(B+n);}
  return p;
 }
 static int clamp(int v){return v<0?0:v>255?255:v;}

 /** Foto, campo ou LUT maior do que a GPU aceita: vai para a CPU sem desligar a GPU. */
 static final class Unsupported extends RuntimeException{Unsupported(String m){super(m);}}

 /** Contexto EGL sem tela, programas compilados e a execução de uma revelação. */
 private static final class State{
  EGLDisplay display;EGLContext context;EGLSurface surface;int maxTexture,max3d,vao,develop,blurH,blurV;
  State(){
   display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);int[] version=new int[2];
   if(display==EGL14.EGL_NO_DISPLAY||!EGL14.eglInitialize(display,version,0,version,1))throw new IllegalStateException("EGL indisponível");
   int[] attrs={EGL14.EGL_RENDERABLE_TYPE,EGLExt.EGL_OPENGL_ES3_BIT_KHR,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE};
   EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];
   if(!EGL14.eglChooseConfig(display,attrs,0,configs,0,1,count,0)||count[0]==0)throw new IllegalStateException("sem OpenGL ES 3");
   context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE},0);
   if(context==null||context==EGL14.EGL_NO_CONTEXT)throw new IllegalStateException("contexto OpenGL ES 3 recusado");
   surface=EGL14.eglCreatePbufferSurface(display,configs[0],new int[]{EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE},0);
   if(!EGL14.eglMakeCurrent(display,surface,surface,context))throw new IllegalStateException("eglMakeCurrent falhou");
   int[] v=new int[1];GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE,v,0);maxTexture=v[0];GLES30.glGetIntegerv(GLES30.GL_MAX_3D_TEXTURE_SIZE,v,0);max3d=v[0];
   develop=program(GpuProgram.DEVELOP);blurH=program(GpuProgram.BLUR_H);blurV=program(GpuProgram.BLUR_V);
   GLES30.glGenVertexArrays(1,v,0);vao=v[0];
   android.util.Log.i(TAG,"OpenGL "+GLES30.glGetString(GLES30.GL_VERSION)+" · "+GLES30.glGetString(GLES30.GL_RENDERER)+" · textura máx "+maxTexture);
  }
  void destroy(){
   try{EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);EGL14.eglDestroySurface(display,surface);EGL14.eglDestroyContext(display,context);}catch(Exception ignored){}
  }

  static int shader(int type,String source){
   int s=GLES30.glCreateShader(type);GLES30.glShaderSource(s,source);GLES30.glCompileShader(s);int[] ok=new int[1];GLES30.glGetShaderiv(s,GLES30.GL_COMPILE_STATUS,ok,0);
   if(ok[0]==0){String log=GLES30.glGetShaderInfoLog(s);GLES30.glDeleteShader(s);throw new IllegalStateException("shader não compilou: "+log);}
   return s;
  }
  static int program(String fragment){
   int vs=shader(GLES30.GL_VERTEX_SHADER,GpuProgram.VERTEX),fs=shader(GLES30.GL_FRAGMENT_SHADER,fragment),p=GLES30.glCreateProgram();
   GLES30.glAttachShader(p,vs);GLES30.glAttachShader(p,fs);GLES30.glLinkProgram(p);GLES30.glDeleteShader(vs);GLES30.glDeleteShader(fs);
   int[] ok=new int[1];GLES30.glGetProgramiv(p,GLES30.GL_LINK_STATUS,ok,0);
   if(ok[0]==0){String log=GLES30.glGetProgramInfoLog(p);GLES30.glDeleteProgram(p);throw new IllegalStateException("programa não ligou: "+log);}
   return p;
  }

  final List<Integer> textures=new ArrayList<>(),framebuffers=new ArrayList<>();

  int texture(int target){int[] t=new int[1];GLES30.glGenTextures(1,t,0);textures.add(t[0]);GLES30.glBindTexture(target,t[0]);
   GLES30.glTexParameteri(target,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_NEAREST);GLES30.glTexParameteri(target,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_NEAREST);
   GLES30.glTexParameteri(target,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);GLES30.glTexParameteri(target,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);
   if(target==GLES30.GL_TEXTURE_3D)GLES30.glTexParameteri(target,GLES30.GL_TEXTURE_WRAP_R,GLES30.GL_CLAMP_TO_EDGE);
   return t[0];}
  int image(int w,int h){int t=texture(GLES30.GL_TEXTURE_2D);GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RGBA8,w,h,0,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,null);return t;}
  int floats(int w,int h,int comps,float[] data){int t=texture(GLES30.GL_TEXTURE_2D);
   GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,comps==2?GLES30.GL_RG32F:GLES30.GL_R32F,w,h,0,comps==2?GLES30.GL_RG:GLES30.GL_RED,GLES30.GL_FLOAT,floatBuffer(data));return t;}
  int framebuffer(int texture){int[] f=new int[1];GLES30.glGenFramebuffers(1,f,0);framebuffers.add(f[0]);GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,f[0]);
   GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,texture,0);
   int status=GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);if(status!=GLES30.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("framebuffer incompleto 0x"+Integer.toHexString(status));
   return f[0];}
  static FloatBuffer floatBuffer(float[] d){FloatBuffer b=ByteBuffer.allocateDirect(d.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();b.put(d).position(0);return b;}
  static void check(String where){int e=GLES30.glGetError();if(e!=GLES30.GL_NO_ERROR)throw new IllegalStateException("erro OpenGL 0x"+Integer.toHexString(e)+" em "+where);}

  static final String[] SAMPLERS={"uSrc","uFTone","uFClar","uFTex","uFHaze","uTable","uLut","uImg","uBlur"};
  /** Uniforms do GpuProgram e cada sampler na sua unidade (dois tipos na mesma unidade invalidam o desenho). */
  void use(int program,GpuProgram.Inputs in,float originY){
   GLES30.glUseProgram(program);
   for(Map.Entry<String,float[]> e:in.uniforms.entrySet()){int loc=GLES30.glGetUniformLocation(program,e.getKey());if(loc<0)continue;float[] v=e.getValue();
    if(e.getKey().equals("uK"))GLES30.glUniform1fv(loc,v.length,v,0);
    else if(v.length==1)GLES30.glUniform1f(loc,v[0]);else if(v.length==2)GLES30.glUniform2f(loc,v[0],v[1]);else if(v.length==3)GLES30.glUniform3f(loc,v[0],v[1],v[2]);else GLES30.glUniform4f(loc,v[0],v[1],v[2],v[3]);}
   for(int i=0;i<SAMPLERS.length;i++){int loc=GLES30.glGetUniformLocation(program,SAMPLERS[i]);if(loc>=0)GLES30.glUniform1i(loc,i);}
   int loc=GLES30.glGetUniformLocation(program,"uOrigin");if(loc>=0)GLES30.glUniform2f(loc,0,originY);
  }
  void bind(int unit,int target,int texture){GLES30.glActiveTexture(GLES30.GL_TEXTURE0+unit);GLES30.glBindTexture(target,texture);}
  void unbindAll(){for(int i=0;i<SAMPLERS.length;i++){bind(i,GLES30.GL_TEXTURE_2D,0);bind(i,GLES30.GL_TEXTURE_3D,0);}}
  void draw(){GLES30.glBindVertexArray(vao);GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);}
  /** Desenha a imagem inteira em faixas (cada uma enviada à parte, longe do limite de tempo do driver). */
  void drawBands(int w,int h){GLES30.glViewport(0,0,w,h);GLES30.glEnable(GLES30.GL_SCISSOR_TEST);
   for(int y0=0;y0<h;y0+=STRIP*4){GLES30.glScissor(0,y0,w,Math.min(STRIP*4,h-y0));draw();GLES30.glFlush();}
   GLES30.glDisable(GLES30.GL_SCISSOR_TEST);}

  void run(GpuProgram.Inputs in,int[] px,boolean[] wrote){
   int w=in.w,h=in.h;
   if(Math.max(w,h)>maxTexture)throw new Unsupported("foto maior que a textura da GPU ("+maxTexture+" px)");
   for(Develop.Field f:new Develop.Field[]{in.tone,in.clarity,in.texture,in.haze})if(f!=null&&Math.max(f.gw,f.gh)>maxTexture)throw new Unsupported("campo local maior que a textura da GPU");
   if(in.lutSize>max3d)throw new Unsupported("LUT maior que a textura 3D da GPU");
   try{
    GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT,1);GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT,1);
    // Foto: o int ARGB em memória little-endian já está na ordem B,G,R,A que os shaders esperam.
    ByteBuffer strip=ByteBuffer.allocateDirect(w*STRIP*4).order(ByteOrder.nativeOrder());IntBuffer ints=strip.asIntBuffer();
    int src=image(w,h);
    for(int y0=0;y0<h;y0+=STRIP){int rows=Math.min(STRIP,h-y0);ints.clear();ints.put(px,y0*w,rows*w);strip.position(0);
     GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D,0,0,y0,w,rows,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,strip);}
    int tone=in.tone==null?0:floats(in.tone.gw,in.tone.gh,2,GpuProgram.fieldData(in.tone));
    int clarity=in.clarity==null?0:floats(in.clarity.gw,in.clarity.gh,2,GpuProgram.fieldData(in.clarity));
    int texture=in.texture==null?0:floats(in.texture.gw,in.texture.gh,1,GpuProgram.fieldData(in.texture));
    int haze=in.haze==null?0:floats(in.haze.gw,in.haze.gh,1,GpuProgram.fieldData(in.haze));
    int table=in.toneTable==null?0:floats(in.toneTable.length,1,1,in.toneTable);
    int lut=0;if(in.lut!=null){lut=texture(GLES30.GL_TEXTURE_3D);int n=in.lutSize;GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D,0,GLES30.GL_RGB32F,n,n,n,0,GLES30.GL_RGB,GLES30.GL_FLOAT,floatBuffer(in.lut));}
    check("envio das texturas");
    int stripTexture=image(w,STRIP),stripFbo=framebuffer(stripTexture);
    int finalProgram,image=0;
    unbindAll();
    if(!in.sharpen)finalProgram=develop;
    else{
     // Nitidez: revela inteira numa textura, desfoca a luminância na horizontal por cima da foto original
     // (que já não é necessária) e faz a vertical + máscara + efeitos em faixas.
     image=image(w,h);framebuffer(image);use(develop,in,0);bindInputs(src,tone,clarity,texture,haze,table,lut);drawBands(w,h);
     unbindAll();framebuffer(src);use(blurH,in,0);bind(7,GLES30.GL_TEXTURE_2D,image);drawBands(w,h);
     unbindAll();finalProgram=blurV;
    }
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,stripFbo);
    for(int y0=0;y0<h;y0+=STRIP){int rows=Math.min(STRIP,h-y0);
     use(finalProgram,in,y0);
     if(in.sharpen){bind(7,GLES30.GL_TEXTURE_2D,image);bind(8,GLES30.GL_TEXTURE_2D,src);}else bindInputs(src,tone,clarity,texture,haze,table,lut);
     GLES30.glViewport(0,0,w,rows);draw();
     strip.position(0);GLES30.glReadPixels(0,0,w,rows,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,strip);
     if(y0==0)check("primeira faixa");
     ints.clear();wrote[0]=true;ints.get(px,y0*w,rows*w);
    }
    check("leitura");
   }finally{
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);unbindAll();
    if(!framebuffers.isEmpty()){int[] f=new int[framebuffers.size()];for(int i=0;i<f.length;i++)f[i]=framebuffers.get(i);GLES30.glDeleteFramebuffers(f.length,f,0);framebuffers.clear();}
    if(!textures.isEmpty()){int[] t=new int[textures.size()];for(int i=0;i<t.length;i++)t[i]=textures.get(i);GLES30.glDeleteTextures(t.length,t,0);textures.clear();}
    GLES30.glFinish();
   }
  }
  void bindInputs(int src,int tone,int clarity,int texture,int haze,int table,int lut){
   bind(GpuProgram.U_SRC,GLES30.GL_TEXTURE_2D,src);bind(GpuProgram.U_TONE,GLES30.GL_TEXTURE_2D,tone);bind(GpuProgram.U_CLAR,GLES30.GL_TEXTURE_2D,clarity);
   bind(GpuProgram.U_TEX,GLES30.GL_TEXTURE_2D,texture);bind(GpuProgram.U_HAZE,GLES30.GL_TEXTURE_2D,haze);bind(GpuProgram.U_TABLE,GLES30.GL_TEXTURE_2D,table);bind(GpuProgram.U_LUT,GLES30.GL_TEXTURE_3D,lut);
  }
 }
 private GpuDevelop(){}
}
