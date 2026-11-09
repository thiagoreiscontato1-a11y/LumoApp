#!/usr/bin/env python3
"""Orientation.place (EXIF 1–8 aplicado ao copiar os pixels) contra a definição da especificação EXIF."""
import pathlib, subprocess, tempfile
ROOT = pathlib.Path(__file__).resolve().parents[1]
TEST = r'''package com.hisho.tether;
public class OrientationTest{
 public static void main(String[] a){
  int checks=0;
  for(int[] dims:new int[][]{{5,3},{4,4},{1,6},{7,1}})for(int o=1;o<=8;o++){
   int w=dims[0],h=dims[1];int[] out=new int[w*h];
   for(int y=0;y<h;y++){int[] row=new int[w];for(int x=0;x<w;x++)row[x]=y*1000+x;Orientation.place(row,y,w,h,o,out);}
   boolean swap=Orientation.swaps(o);int W=swap?h:w,H=swap?w:h;
   for(int Y=0;Y<H;Y++)for(int X=0;X<W;X++){
    // Pixel de origem de cada pixel exibido, pela tabela de orientações do EXIF.
    int sx,sy;switch(o){case 2:sx=w-1-X;sy=Y;break;case 3:sx=w-1-X;sy=h-1-Y;break;case 4:sx=X;sy=h-1-Y;break;case 5:sx=Y;sy=X;break;case 6:sx=Y;sy=h-1-X;break;case 7:sx=w-1-Y;sy=h-1-X;break;case 8:sx=w-1-Y;sy=X;break;default:sx=X;sy=Y;}
    if(out[Y*W+X]!=sy*1000+sx)throw new AssertionError("orientação "+o+" "+w+"x"+h+" em "+X+","+Y);checks++;
   }
  }
  System.out.println(checks+" verificações de orientação EXIF");
 }
}
'''
with tempfile.TemporaryDirectory() as d:
    p = pathlib.Path(d)
    (p / "OrientationTest.java").write_text(TEST)
    subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-encoding", "UTF-8", "-nowarn", "-d", str(p / "out"),
                    str(ROOT / "app/src/main/java/com/hisho/tether/Orientation.java"), str(p / "OrientationTest.java")], check=True)
    subprocess.run(["java", "-cp", str(p / "out"), "com.hisho.tether.OrientationTest"], check=True)
