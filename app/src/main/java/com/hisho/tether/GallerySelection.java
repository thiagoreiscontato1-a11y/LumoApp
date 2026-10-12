package com.hisho.tether;
import java.util.*;
/** Selection is independent of incoming edits so review never jumps to a new photo. */
final class GallerySelection {
 static final class Photo {final String uri,name;Photo(String uri,String name){this.uri=uri;this.name=name;}}
 final ArrayList<Photo> photos=new ArrayList<>();String selected="";boolean follow=true;
 void replace(List<Photo> incoming){photos.clear();photos.addAll(incoming);if(follow||selected.isEmpty())followLatest();}
 int index(){for(int i=0;i<photos.size();i++)if(photos.get(i).uri.equals(selected))return i;return -1;}
 void select(String uri){for(Photo p:photos)if(p.uri.equals(uri)){selected=uri;follow=false;return;}}
 void followLatest(){follow=true;if(!photos.isEmpty())selected=photos.get(photos.size()-1).uri;}
 void move(int delta){int i=index();if(i<0||i+delta<0||i+delta>=photos.size())return;select(photos.get(i+delta).uri);}
 String name(){int i=index();return i<0?"FOTO EM REVISÃO":photos.get(i).name;}
}
