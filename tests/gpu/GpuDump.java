package com.hisho.tether;
import java.io.*;import java.nio.*;import java.nio.file.*;import java.util.*;

/** Gera os dados de um caso para tests/gpu_test.py: foto, parâmetros do GpuProgram e a saída do motor Java. */
public class GpuDump{
 static Map<String,Double> preset(Object... kv){Map<String,Double> m=new HashMap<>();for(int i=0;i<kv.length;i+=2)m.put((String)kv[i],((Number)kv[i+1]).doubleValue());return m;}
 static final Map<String,Map<String,Double>> CASES=new LinkedHashMap<>();
 static{
  Object[] full={"IncrementalTemperature",-11,"Exposure2012",.15,"Contrast2012",17,"Highlights2012",-70,"Shadows2012",62,"Whites2012",30,"Blacks2012",-20,"Clarity2012",35,"Texture",10,"Dehaze",8,"Vibrance",35,"Saturation",-10,
  "ParametricShadows",10,"ParametricHighlights",-15,"ToneCurvePV2012.0",0,"ToneCurvePV2012.1",18,"ToneCurvePV2012.2",128,"ToneCurvePV2012.3",132,"ToneCurvePV2012.4",255,"ToneCurvePV2012.5",245,
  "HueAdjustmentOrange",-8,"SaturationAdjustmentBlue",-29,"LuminanceAdjustmentBlue",-20,"SaturationAdjustmentGreen",-30,"HueAdjustmentGreen",25,
  "SplitToningShadowHue",210,"SplitToningShadowSaturation",15,"SplitToningHighlightHue",45,"SplitToningHighlightSaturation",12,"ColorGradeBlending",60,
  "BlueSaturation",25,"RedHue",10,"ShadowTint",-5,"Sharpness",40,"PostCropVignetteAmount",-21,"GrainAmount",12};
  CASES.put("completo",preset(full));
  CASES.put("tipico",preset("Exposure2012",.3,"Contrast2012",10,"Highlights2012",-40,"Shadows2012",30,"Vibrance",15,"Sharpness",40,"Clarity2012",10,"PostCropVignetteAmount",-15));
  CASES.put("sem-nitidez",preset("Exposure2012",.6,"Whites2012",-30,"Highlights2012",-80,"Texture",40,"Dehaze",-30,"Saturation",20,"PostCropVignetteAmount",25,"PostCropVignetteStyle",3,"GrainAmount",30,"GrainFrequency",80,"GrainSize",60));
  CASES.put("nitidez-forte",preset("Sharpness",150,"SharpenRadius",3,"SharpenDetail",80,"SharpenEdgeMasking",60,"Dehaze",40,"Clarity2012",-40,"PostCropVignetteAmount",-60,"PostCropVignetteRoundness",60,"PostCropVignetteHighlightContrast",50,"GrainAmount",20));
  CASES.put("pb",preset("ConvertToGrayscale",1,"GrayMixerBlue",-60,"GrayMixerOrange",30,"Contrast2012",40,"Blacks2012",30,"PostCropVignetteRoundness",-70,"PostCropVignetteAmount",-30));
  CASES.put("so-tom",preset("Exposure2012",-.5,"Contrast2012",-30));
 }
 public static void main(String[] a)throws Exception{
  Path out=Paths.get(a[0]);int w=Integer.parseInt(a[1]),h=Integer.parseInt(a[2]);Files.createDirectories(out);
  int[] base=DevelopTest.scene(w,h);Random rnd=new Random(7);
  for(int i=0;i<base.length;i++){int v=base[i],n=rnd.nextInt(13)-6;base[i]=0xff000000|Math.max(0,Math.min(255,((v>>16)&255)+n))<<16|Math.max(0,Math.min(255,((v>>8)&255)+n))<<8|Math.max(0,Math.min(255,(v&255)+n));}
  ints(out.resolve("src.bin"),base);
  write(out.resolve("develop.glsl"),GpuProgram.DEVELOP);write(out.resolve("blur_h.glsl"),GpuProgram.BLUR_H);write(out.resolve("blur_v.glsl"),GpuProgram.BLUR_V);write(out.resolve("vertex.glsl"),GpuProgram.VERTEX);
  StringBuilder index=new StringBuilder("{");
  for(Map.Entry<String,Map<String,Double>> c:CASES.entrySet()){
   Develop.Settings s=Develop.Settings.of(c.getValue());Path d=out.resolve(c.getKey());Files.createDirectories(d);
   Develop.Source src=new Develop.Source(base.clone(),w,h);Develop.Plan plan=new Develop.Plan(src,s);GpuProgram.Inputs in=new GpuProgram.Inputs(plan);
   StringBuilder j=new StringBuilder("{\"w\":"+w+",\"h\":"+h+",\"sharpen\":"+in.sharpen+",\"lutSize\":"+in.lutSize+",\"uniforms\":{");
   boolean first=true;for(Map.Entry<String,float[]> u:in.uniforms.entrySet()){if(!first)j.append(',');first=false;j.append('"').append(u.getKey()).append("\":[");for(int i=0;i<u.getValue().length;i++){if(i>0)j.append(',');j.append(u.getValue()[i]);}j.append(']');}
   j.append("},\"fields\":{");first=true;
   String[] names={"tone","clarity","texture","haze"};Develop.Field[] fs={in.tone,in.clarity,in.texture,in.haze};
   for(int k=0;k<4;k++){if(fs[k]==null)continue;if(!first)j.append(',');first=false;j.append('"').append(names[k]).append("\":{\"gw\":").append(fs[k].gw).append(",\"gh\":").append(fs[k].gh).append(",\"comps\":").append(fs[k].scale==null?1:2).append('}');floats(d.resolve(names[k]+".bin"),GpuProgram.fieldData(fs[k]));}
   j.append("}}");write(d.resolve("meta.json"),j.toString());
   if(in.toneTable!=null)floats(d.resolve("table.bin"),in.toneTable);if(in.lut!=null)floats(d.resolve("lut.bin"),in.lut);
   ints(d.resolve("cpu.bin"),new Develop.Source(base.clone(),w,h).renderInPlace(s));
   index.append(index.length()>1?",":"").append('"').append(c.getKey()).append('"').append(":1");
  }
  write(out.resolve("cases.json"),index.append('}').toString());
 }
 static void write(Path p,String s)throws IOException{Files.write(p,s.getBytes("UTF-8"));}
 static void ints(Path p,int[] v)throws IOException{ByteBuffer b=ByteBuffer.allocate(v.length*4).order(ByteOrder.LITTLE_ENDIAN);b.asIntBuffer().put(v);Files.write(p,b.array());}
 static void floats(Path p,float[] v)throws IOException{ByteBuffer b=ByteBuffer.allocate(v.length*4).order(ByteOrder.LITTLE_ENDIAN);b.asFloatBuffer().put(v);Files.write(p,b.array());}
}
