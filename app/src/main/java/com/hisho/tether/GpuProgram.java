package com.hisho.tether;
import java.util.*;

/**
 * Revelação na GPU (OpenGL ES 3.0): shaders e parâmetros, sem dependência do Android.
 *
 * Porta para GLSL o trabalho por pixel do {@link Develop.Plan} (balanço de branco, dehaze, tons e ajustes locais,
 * compressão dos realces, curva de tons, cor pela LUT 3D, nitidez, vinheta e grão). O que é calculado uma vez por
 * foto continua na CPU, com o mesmo código: campos locais (grades pequenas), tabela de tons e LUT de cor.
 * O {@link GpuDevelop} só envia texturas e uniforms montados aqui. O mesmo arquivo é usado pelo teste no desktop
 * (tests/gpu_test.py, Mesa llvmpipe), que compara a saída com o motor Java.
 *
 * Texturas RGBA8 guardam os canais na ordem B,G,R,A: é a ordem dos bytes de um int ARGB do Android em memória
 * little-endian, então a foto sobe e desce sem conversão.
 */
final class GpuProgram{
 static final int MAX_RADIUS=8;

 /** Tudo o que a GPU precisa para uma foto. Campos e tabelas ausentes ficam null. */
 static final class Inputs{
  final int w,h;
  final LinkedHashMap<String,float[]> uniforms=new LinkedHashMap<>();
  /** Campos locais: tom (offset+scale), claridade (offset+scale), textura e névoa (só offset). */
  final Develop.Field tone,clarity,texture,haze;
  final float[] toneTable;final float[] lut;final int lutSize;final boolean sharpen;
  Inputs(Develop.Plan p){
   w=p.src.w;h=p.src.h;tone=p.toneF;clarity=p.clarityF;texture=p.textureF;haze=p.hazeF;sharpen=p.sharpen;
   Develop.Lut l=p.lut;if(l==null&&(p.curves!=null||p.color))l=new Develop.Lut(p);
   lut=l==null?null:l.t;lutSize=l==null?0:l.n;
   toneTable=p.tone==null?null:toFloat(p.tone);
   put("uSize",w,h);put("uGain",p.gr,p.gg,p.gb);put("uExpo",p.expo);put("uDehaze",p.dehaze);
   put("uTonal",p.tonal?1:0);put("uLocal",p.local&&p.logY!=null?1:0);put("uSh",p.sh);put("uHl",p.hl);put("uWhites",p.whites);
   put("uClarity",p.clarity);put("uTexture",p.texture);put("uRollPower",p.rollPower);put("uTop",p.top);put("uToneTable",toneTable==null?0:1);
   grid("uGTone",tone);grid("uGClar",clarity);grid("uGTex",texture);grid("uGHaze",haze);
   put("uLutN",lutSize);put("uSharpen",sharpen?1:0);
   Develop.Effects fx=p.fx;
   put("uVig",fx.vignette?1:0);put("uVigAmount",fx.amount);put("uVigInner",fx.inner);put("uVigWidth",fx.width);put("uVigRound",fx.roundness);
   put("uVigPower",fx.power);put("uVigHigh",fx.highlights);put("uVigStyle",fx.style);put("uVigGeo",fx.cx,fx.cy,fx.hw,fx.hh);put("uVigHc",fx.hc);
   put("uGrain",fx.grain?1:0);put("uGrainAmount",fx.grainAmount);put("uGrainCell",fx.cell);put("uGrainRough",fx.rough);
   // Nitidez: o mesmo núcleo gaussiano do Develop.blur, centrado no índice MAX_RADIUS.
   float[] k=new float[2*MAX_RADIUS+1];int r=0;
   if(sharpen){double sigma=Math.max(.5,p.s.sharpenRadius*.8);r=(int)Math.ceil(sigma*3);if(r>MAX_RADIUS)throw new IllegalArgumentException("Raio de nitidez fora do suportado: "+r);
    double[] kk=new double[2*r+1];double sum=0;for(int i=-r;i<=r;i++){kk[i+r]=Math.exp(-i*i/(2*sigma*sigma));sum+=kk[i+r];}for(int i=-r;i<=r;i++)k[i+MAX_RADIUS]=(float)(kk[i+r]/sum);}
   uniforms.put("uK",k);put("uR",r);put("uSharpAmount",p.amount);put("uSharpLimit",p.limit);put("uSharpMask",p.mask);
  }
  void put(String name,double... v){float[] f=new float[v.length];for(int i=0;i<v.length;i++)f[i]=(float)v[i];uniforms.put(name,f);}
  void grid(String name,Develop.Field f){if(f==null)put(name,0,0,0);else put(name,f.gw,f.gh,f.cell);}
 }
 static float[] toFloat(double[] d){float[] f=new float[d.length];for(int i=0;i<d.length;i++)f[i]=(float)d[i];return f;}

 /** Offset e scale intercalados (RG32F); só offset quando o campo não tem scale (R32F). */
 static float[] fieldData(Develop.Field f){
  if(f.scale==null)return f.offset;
  float[] out=new float[f.offset.length*2];for(int i=0;i<f.offset.length;i++){out[2*i]=f.offset[i];out[2*i+1]=f.scale[i];}return out;
 }

 // Unidades de textura
 static final int U_SRC=0,U_TONE=1,U_CLAR=2,U_TEX=3,U_HAZE=4,U_TABLE=5,U_LUT=6,U_IMG=7,U_BLUR=8;

 static final String VERTEX="#version 300 es\n"
  +"void main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.0-1.0,0.0,1.0);}\n";

 static final String COMMON="#version 300 es\n"
  +"precision highp float;precision highp int;precision highp sampler2D;precision highp sampler3D;\n"
  +"uniform vec2 uSize;uniform vec2 uOrigin;\n"
  +"uniform float uVig,uVigAmount,uVigInner,uVigWidth,uVigRound,uVigPower,uVigHigh,uVigStyle,uVigHc;uniform vec4 uVigGeo;\n"
  +"uniform float uGrain,uGrainAmount,uGrainCell,uGrainRough;\n"
  +"out vec4 fragColor;\n"
  +"float luma(vec3 c){return .2126*c.r+.7152*c.g+.0722*c.b;}\n"
  +"float sm(float a,float b,float x){if(x<=a)return 0.0;if(x>=b)return 1.0;float t=(x-a)/(b-a);return t*t*(3.0-2.0*t);}\n"
  +"float exp2c(float x){return exp2(clamp(x,-24.0,8.0));}\n"
  +"float hashi(int x,int y,uint seed){uint h=(uint(x)*0x27d4eb2du)^(uint(y)*0x165667b1u)^seed;h^=h>>15u;h*=0x85ebca6bu;h^=h>>13u;h*=0xc2b2ae35u;h^=h>>16u;return float(h&0xffffffu)/16777215.0*2.0-1.0;}\n"
  +"float noise(float fx,float fy,uint seed){float ix=floor(fx),iy=floor(fy),tx=fx-ix,ty=fy-iy;int x=int(ix),y=int(iy);tx=tx*tx*(3.0-2.0*tx);ty=ty*ty*(3.0-2.0*ty);\n"
  +" float a=hashi(x,y,seed),b=hashi(x+1,y,seed),c=hashi(x,y+1,seed),d=hashi(x+1,y+1,seed);return (a+(b-a)*tx)*(1.0-ty)+(c+(d-c)*tx)*ty;}\n"
  // Develop.Effects.apply: vinheta pós-corte e granulação.
  +"vec3 effects(vec3 c,ivec2 p){\n"
  +" if(uVig>0.5){float x=float(p.x)+0.5-uVigGeo.x,y=float(p.y)+0.5-uVigGeo.y,u=x/uVigGeo.z,v=y/uVigGeo.w;\n"
  +"  if(uVigRound>0.0){float k=uVigRound*uVigRound;u+=(x/uVigHc-u)*k;v+=(y/uVigHc-v)*k;}\n"
  +"  float d=uVigPower==2.0?sqrt(u*u+v*v):pow(pow(abs(u),uVigPower)+pow(abs(v),uVigPower),1.0/uVigPower),m=sm(uVigInner,uVigInner+uVigWidth,d);\n"
  +"  if(m>0.0){if(uVigAmount<0.0){if(uVigStyle==3.0){c*=1.0+uVigAmount*m*0.9;}else{float l=clamp(luma(c),0.0,1.0),f=exp2c(uVigAmount*m*1.5);f=1.0-(1.0-f)*(1.0-uVigHigh*sm(0.5,1.0,l));c*=f;}}\n"
  +"   else{float k=uVigAmount*m*(uVigStyle==3.0?0.9:0.75);c+=(1.0-c)*k;}}}\n"
  +" if(uGrain>0.5){float l=clamp(luma(c),0.0,1.0),fx=float(p.x),fy=float(p.y);\n"
  +"  float nz=noise(fx/uGrainCell,fy/uGrainCell,0x5eedu)*(1.0-0.5*uGrainRough)+noise(fx/(uGrainCell*0.5),fy/(uGrainCell*0.5),0x1cedu)*0.5*uGrainRough;\n"
  +"  c+=vec3(uGrainAmount*nz*(0.35+0.65*4.0*l*(1.0-l)));}\n"
  +" return c;}\n"
  +"vec4 store(vec3 c){c=clamp(c,0.0,1.0);return vec4(c.b,c.g,c.r,1.0);}\n";

 /** Passada principal: Develop.Plan.rows. Com nitidez, grava antes dos efeitos (como o pack da CPU). */
 static final String DEVELOP=COMMON
  +"uniform sampler2D uSrc,uFTone,uFClar,uFTex,uFHaze,uTable;uniform sampler3D uLut;\n"
  +"uniform vec3 uGain,uGTone,uGClar,uGTex,uGHaze;\n"
  +"uniform float uExpo,uDehaze,uTonal,uLocal,uSh,uHl,uWhites,uClarity,uTexture,uRollPower,uTop,uToneTable,uLutN,uSharpen;\n"
  +"float decodeS(float v){return v<=0.04045?v/12.92:pow((v+0.055)/1.055,2.4);}\n"
  +"float encodeS(float x){x=clamp(x,0.0,1.0);return x<=0.0031308?x*12.92:1.055*pow(x,1.0/2.4)-0.055;}\n"
  +"float soft(float d,float limit){float q=d/limit;return d/sqrt(1.0+q*q);}\n"
  // Develop.Field: grade suave com interpolação bilinear; com scale é filtro guiado (scale·guia + offset).
  +"float field(sampler2D t,vec3 g,ivec2 p,float guide){int gw=int(g.x),gh=int(g.y),cell=int(g.z);vec2 v;\n"
  +" if(cell==1)v=texelFetch(t,p,0).rg;else{\n"
  +"  float gx=clamp((float(p.x)+0.5)/g.z-0.5,0.0,float(gw-1)),gy=clamp((float(p.y)+0.5)/g.z-0.5,0.0,float(gh-1));int x0=int(gx),y0=int(gy),x1=min(gw-1,x0+1),y1=min(gh-1,y0+1);float fx=gx-float(x0),fy=gy-float(y0);\n"
  +"  v=(texelFetch(t,ivec2(x0,y0),0).rg*(1.0-fy)+texelFetch(t,ivec2(x0,y1),0).rg*fy)*(1.0-fx)+(texelFetch(t,ivec2(x1,y0),0).rg*(1.0-fy)+texelFetch(t,ivec2(x1,y1),0).rg*fy)*fx;}\n"
  +" return v.g*guide+v.r;}\n"
  +"float tableAt(float x){if(!(x>0.0))return texelFetch(uTable,ivec2(0,0),0).r;if(x>=1.0)return texelFetch(uTable,ivec2(1024,0),0).r;float f=x*1024.0;int i=int(f);\n"
  +" float a=texelFetch(uTable,ivec2(i,0),0).r,b=texelFetch(uTable,ivec2(i+1,0),0).r;return a+(b-a)*(f-float(i));}\n"
  +"vec3 fitc(vec3 c,float y){float mx=max(c.r,max(c.g,c.b)),mn=min(c.r,min(c.g,c.b));if(mx<=1.0&&mn>=0.0)return c;if(y>=1.0)return vec3(1.0);if(y<=0.0)return vec3(0.0);\n"
  +" float t=1.0;if(mx>1.0)t=min(t,(1.0-y)/(mx-y));if(mn<0.0)t=min(t,y/(y-mn));return clamp(vec3(y)+(c-vec3(y))*t,0.0,1.0);}\n"
  // Develop.Lut.apply: interpolação tetraédrica. Textura 3D em (b,g,r).
  +"vec3 lutAt(vec3 e){int m=int(uLutN)-1;vec3 f=clamp(e,0.0,1.0)*float(m);ivec3 i=min(ivec3(f),ivec3(m-1));vec3 d=f-vec3(i);\n"
  +" ivec3 base=ivec3(i.b,i.g,i.r),R=ivec3(0,0,1),G=ivec3(0,1,0),B=ivec3(1,0,0),p1,p2;vec4 w;float dr=d.r,dg=d.g,db=d.b;\n"
  +" if(dr>=dg){if(dg>=db){p1=R;p2=R+G;w=vec4(1.0-dr,dr-dg,dg-db,db);}else if(dr>=db){p1=R;p2=R+B;w=vec4(1.0-dr,dr-db,db-dg,dg);}else{p1=B;p2=R+B;w=vec4(1.0-db,db-dr,dr-dg,dg);}}\n"
  +" else{if(db>=dg){p1=B;p2=G+B;w=vec4(1.0-db,db-dg,dg-dr,dr);}else if(db>=dr){p1=G;p2=G+B;w=vec4(1.0-dg,dg-db,db-dr,dr);}else{p1=G;p2=R+G;w=vec4(1.0-dg,dg-dr,dr-db,db);}}\n"
  +" return w.x*texelFetch(uLut,base,0).rgb+w.y*texelFetch(uLut,base+p1,0).rgb+w.z*texelFetch(uLut,base+p2,0).rgb+w.w*texelFetch(uLut,base+R+G+B,0).rgb;}\n"
  +"void main(){ivec2 p=ivec2(gl_FragCoord.xy)+ivec2(uOrigin);vec3 s=texelFetch(uSrc,p,0).bgr;\n"
  +" vec3 lin=vec3(decodeS(s.r),decodeS(s.g),decodeS(s.b));float r=lin.r*uGain.r,g=lin.g*uGain.g,b=lin.b*uGain.b;\n"
  +" if(uDehaze>0.0){float dark=min(min(r,min(g,b)),field(uFHaze,uGHaze,p,0.0)*uExpo),tr=max(0.15,1.0-0.85*uDehaze*min(1.0,dark)),a=1.0-tr;r=(r-a)/tr;g=(g-a)/tr;b=(b-a)/tr;}\n"
  +" else if(uDehaze<0.0){float k=-uDehaze*0.45;r+=(0.85-r)*k;g+=(0.85-g)*k;b+=(0.85-b)*k;}\n"
  +" if(uTonal>0.5){float lum=luma(vec3(r,g,b)),yt=lum,ev=0.0,lp=encodeS(min(1.0,yt));\n"
  +"  if(uLocal>0.5){float ly=log2(max(yt,1e-6)),l0=log2(max(1.0/4096.0,luma(lin)));\n"
  +"   if(uGTone.z>0.0){float base=ly-soft(l0-field(uFTone,uGTone,p,l0),1.0),bl=encodeS(min(1.0,exp2c(base)));\n"
  +"    if(uSh!=0.0)ev+=uSh*(uSh>0.0?1.35:1.0)*(1.0-sm(0.13,0.45,bl))*(0.55+0.45*sm(0.0,0.15,bl));\n"
  +"    if(uHl!=0.0)ev+=uHl*(uHl<0.0?0.75:0.9)*sm(0.4,0.9,bl)*(1.0-sm(0.85,1.0,lp));}\n"
  +"   if(uGClar.z>0.0)ev+=uClarity*2.0*soft(l0-field(uFClar,uGClar,p,l0),1.5)*4.0*lp*(1.0-lp);\n"
  +"   if(uGTex.z>0.0)ev+=uTexture*0.6*soft(l0-field(uFTex,uGTex,p,l0),1.0)*(1.0-sm(0.85,1.0,lp));}\n"
  +"  if(uWhites!=0.0)ev+=uWhites*0.62*sm(0.3,1.0,lp);\n"
  +"  if(ev!=0.0)yt*=exp2c(ev);\n"
  +"  if(uRollPower>1.0&&yt>0.6){float q=(yt-0.6)/(uTop-0.6);yt=q>=1.0?1.0:0.6+0.4*(1.0-pow(1.0-q,uRollPower));}\n"
  +"  if(uToneTable>0.5)yt=decodeS(tableAt(encodeS(min(1.0,yt))));\n"
  +"  if(lum>1e-9){float k=yt/lum;r*=k;g*=k;b*=k;}else{r=yt;g=yt;b=yt;}\n"
  +"  float vmax=max(r,max(g,b));\n"
  +"  if(vmax>0.8){float vs=(vmax-min(r,min(g,b)))/vmax,f=1.0-vs*(1.0-(0.8+0.2*(1.0-exp(-(vmax-0.8)/0.2)))/vmax);r*=f;g*=f;b*=f;}}\n"
  +" vec3 c=fitc(vec3(r,g,b),luma(vec3(r,g,b)));vec3 e=vec3(encodeS(c.r),encodeS(c.g),encodeS(c.b));\n"
  +" if(uLutN>0.0)e=lutAt(e);\n"
  +" fragColor=store(uSharpen>0.5?e:effects(e,p));}\n";

 /** Nitidez, 1ª passada: desfoque horizontal da luminância (16 bits em dois canais RGBA8). */
 static final String BLUR_H=COMMON
  +"uniform sampler2D uImg;uniform float uK["+(2*MAX_RADIUS+1)+"];uniform float uR;\n"
  +"void main(){ivec2 p=ivec2(gl_FragCoord.xy);int r=int(uR),w=int(uSize.x);float a=0.0;\n"
  +" for(int i=-"+MAX_RADIUS+";i<="+MAX_RADIUS+";i++){if(i<-r||i>r)continue;a+=uK[i+"+MAX_RADIUS+"]*luma(texelFetch(uImg,ivec2(clamp(p.x+i,0,w-1),p.y),0).bgr);}\n"
  +" float v=floor(clamp(a,0.0,1.0)*65535.0+0.5),hi=floor(v/256.0);fragColor=vec4(hi/255.0,(v-hi*256.0)/255.0,0.0,1.0);}\n";

 /** Nitidez, 2ª passada: desfoque vertical, máscara de nitidez (Plan.sharpenRows) e efeitos. */
 static final String BLUR_V=COMMON
  +"uniform sampler2D uImg,uBlur;uniform float uK["+(2*MAX_RADIUS+1)+"];uniform float uR,uSharpAmount,uSharpLimit,uSharpMask;\n"
  +"float unpack(vec4 t){return (floor(t.r*255.0+0.5)*256.0+floor(t.g*255.0+0.5))/65535.0;}\n"
  +"void main(){ivec2 p=ivec2(gl_FragCoord.xy)+ivec2(uOrigin);int r=int(uR),h=int(uSize.y);float bl=0.0;\n"
  +" for(int j=-"+MAX_RADIUS+";j<="+MAX_RADIUS+";j++){if(j<-r||j>r)continue;bl+=uK[j+"+MAX_RADIUS+"]*unpack(texelFetch(uBlur,ivec2(p.x,clamp(p.y+j,0,h-1)),0));}\n"
  +" vec3 v=texelFetch(uImg,p,0).bgr;float d=luma(v)-bl,weight=uSharpMask>0.0?sm(uSharpMask*0.5,uSharpMask*1.5,abs(d)):1.0,add=uSharpAmount*clamp(d,-uSharpLimit,uSharpLimit)*weight;\n"
  +" fragColor=store(effects(v+vec3(add),p));}\n";

 private GpuProgram(){}
}
