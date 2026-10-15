package org.xmlpull.v1;

/** Subconjunto da API XmlPullParser do Android usado por Develop.parse (testes no desktop). */
public interface XmlPullParser {
 int START_DOCUMENT=0,END_DOCUMENT=1,START_TAG=2,END_TAG=3,TEXT=4;
 int getEventType()throws Exception;
 int next()throws Exception;
 int getDepth();
 String getNamespace();
 String getName();
 int getAttributeCount();
 String getAttributeNamespace(int index);
 String getAttributeName(int index);
 String getAttributeValue(int index);
 String nextText()throws Exception;
}
