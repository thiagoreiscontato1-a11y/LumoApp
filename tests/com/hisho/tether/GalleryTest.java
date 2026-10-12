package com.hisho.tether;
import java.util.*;
public class GalleryTest {
 static GallerySelection.Photo p(String id){return new GallerySelection.Photo(id,id+".jpg");}
 static void check(boolean value){if(!value)throw new AssertionError();}
 public static void main(String[] args){GallerySelection g=new GallerySelection();g.replace(Arrays.asList(p("1"),p("2")));check(g.selected.equals("2")&&g.follow);g.select("1");g.replace(Arrays.asList(p("1"),p("2"),p("3")));check(g.selected.equals("1")&&!g.follow);g.move(-1);check(g.selected.equals("1"));g.move(1);check(g.selected.equals("2"));g.followLatest();check(g.selected.equals("3")&&g.follow);g.replace(Arrays.asList(p("2"),p("3"),p("4")));check(g.selected.equals("4"));g.select("2");g.replace(Arrays.asList(p("3"),p("4")));check(g.selected.equals("2")&&!g.follow&&g.index()==-1);g.followLatest();check(g.selected.equals("4"));g.select("missing");check(g.selected.equals("4"));GallerySelection empty=new GallerySelection();empty.replace(Collections.emptyList());empty.move(1);check(empty.index()==-1);System.out.println("PASS: live following, fixed review on arrivals, previous/next bounds, evicted selection and empty gallery.");}
}
