package com.hisho.tether;
import java.io.*;import java.util.*;

final class SmartCurator{
 static final class Result{
  final boolean review;final int score;final String reason,semantic,pHash,duplicateOf;
  Result(boolean review,int score,String reason,String semantic,String pHash,String duplicateOf){this.review=review;this.score=Math.max(1,Math.min(10,score));this.reason=reason;this.semantic=semantic;this.pHash=pHash;this.duplicateOf=duplicateOf;}
 }
 static Result analyze(File file,int sensitivity,Jobs jobs,String id)throws Exception{
  Curator.Result tech=Curator.analyze(file,sensitivity);
  SemanticCurator.Result sem=SemanticCurator.analyze(file,sensitivity);
  String ph=PhotoHash.perceptualHash(file),dup=jobs==null?"":jobs.visualDuplicate(id,ph,file.length());
  int score=tech.score-sem.penalty-(dup.isEmpty()?0:3);score=Math.max(1,Math.min(10,score));
  ArrayList<String> parts=new ArrayList<>();if(tech.review)parts.add(tech.reason);if(!sem.note.isEmpty())parts.add(sem.note);if(!dup.isEmpty())parts.add("Possível duplicata de outra foto");
  boolean review=tech.review||sem.review||(!dup.isEmpty()&&sensitivity>0);
  String reason=parts.isEmpty()?("Aprovada · nota "+score+"/10"):join(parts);
  return new Result(review,score,reason,sem.note,ph,dup);
 }
 static String join(List<String> p){StringBuilder s=new StringBuilder();for(String x:p){if(s.length()>0)s.append(" · ");s.append(x);}return s.toString();}
 private SmartCurator(){}
}
