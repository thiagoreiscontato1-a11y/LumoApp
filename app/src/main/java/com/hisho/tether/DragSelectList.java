package com.hisho.tether;
import android.content.Context;import android.view.*;import android.widget.ListView;

/**
 * Lista da Galeria (linhas de miniaturas) com seleção arrastando o dedo, como no Google Fotos e na galeria do iPhone:
 * - no modo de seleção, arrastar de lado sobre as miniaturas marca (ou desmarca) todas entre a primeira e a que está
 *   sob o dedo; arrastar para cima/baixo continua rolando a lista;
 * - segurar uma foto e, sem soltar, arrastar já seleciona o intervalo;
 * - perto da borda de cima ou de baixo a lista rola sozinha enquanto o dedo estiver lá.
 * A lista só calcula índices; quem marca as fotos é o {@link Listener}.
 */
final class DragSelectList extends ListView{
 interface Listener{
  /** Seleção ligada (só então o arraste de lado seleciona). */
  boolean active();
  int columns();
  /** Quantas fotos estão carregadas na lista. */
  int count();
  /** Começou a arrastar a partir da foto {@code anchor}; {@code fromLongPress}: começou segurando a foto. */
  void begin(int anchor,boolean fromLongPress);
  /** Fotos de {@code anchor} até {@code current} (em qualquer ordem). */
  void range(int anchor,int current);
  void end();
 }

 Listener listener;
 private final int slop,edge;
 private float downX,downY,lastX,lastY;
 private boolean decided,dragging;
 private int anchor=-1,current=-1,armed=-1,scrollStep;
 private final Runnable autoScroll=new Runnable(){public void run(){if(!dragging||scrollStep==0)return;scrollListBy(scrollStep);update();postOnAnimation(this);}};

 DragSelectList(Context c){
  super(c);slop=ViewConfiguration.get(c).getScaledTouchSlop();edge=(int)(64*c.getResources().getDisplayMetrics().density);
 }

 boolean isDragging(){return dragging;}

 /**
  * O cabeçalho (filtros e barra de ações) mudou de altura no meio do arraste, por exemplo quando a primeira foto é
  * marcada e a barra aparece: rola a mesma diferença para as miniaturas não saírem de baixo do dedo.
  */
 void headerResized(View header,int delta){if(dragging&&delta!=0&&header.getParent()==this)post(()->scrollListBy(delta));}

 /** Segurou a foto {@code index}: o próximo movimento sem soltar já seleciona a partir dela. */
 void arm(int index){armed=index;}

 /** Índice da foto sob o ponto, ou -1 (cabeçalho, rodapé ou fora das linhas). */
 int indexAt(float x,float y){
  if(listener==null)return -1;
  int pos=pointToPosition((int)x,(int)y);if(pos==INVALID_POSITION)return -1;
  int row=pos-getHeaderViewsCount();if(row<0)return -1;
  View line=getChildAt(pos-getFirstVisiblePosition());if(!(line instanceof ViewGroup))return -1;
  ViewGroup g=(ViewGroup)line;int tiles=g.getChildCount();if(tiles==0)return -1;
  int col=tiles-1;
  for(int i=0;i<tiles-1;i++){View a=g.getChildAt(i),b=g.getChildAt(i+1);if(x<(a.getRight()+b.getLeft())/2f){col=i;break;}}
  int index=row*listener.columns()+col;
  return index<listener.count()?index:-1;
 }

 @Override public boolean onInterceptTouchEvent(MotionEvent e){
  if(listener!=null)switch(e.getActionMasked()){
   case MotionEvent.ACTION_DOWN:downX=e.getX();downY=e.getY();decided=false;dragging=false;break;
   case MotionEvent.ACTION_MOVE:
    if(armed>=0&&!dragging){start(armed,true,e);return true;}
    if(!decided&&listener.active()){
     float dx=Math.abs(e.getX()-downX),dy=Math.abs(e.getY()-downY);
     if(dx>slop||dy>slop){decided=true;if(dx>dy*1.2f){int i=indexAt(downX,downY);if(i>=0){start(i,false,e);return true;}}}
    }
    break;
   case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:armed=-1;break;
   default:break;
  }
  return super.onInterceptTouchEvent(e);
 }

 @Override public boolean onTouchEvent(MotionEvent e){
  if(!dragging){
   if(armed>=0&&e.getActionMasked()==MotionEvent.ACTION_MOVE){start(armed,true,e);return true;}
   if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL)armed=-1;
   return super.onTouchEvent(e);
  }
  int action=e.getActionMasked();
  if(action==MotionEvent.ACTION_MOVE){lastX=e.getX();lastY=e.getY();update();scrollNearEdges();}
  else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)stop();
  return true;
 }

 private void start(int index,boolean fromLongPress,MotionEvent e){
  armed=-1;dragging=true;anchor=index;current=index;lastX=e.getX();lastY=e.getY();
  getParent().requestDisallowInterceptTouchEvent(true);
  listener.begin(index,fromLongPress);listener.range(anchor,current);
 }

 private void update(){
  int i=indexAt(lastX,lastY);
  // Abaixo da última foto carregada: vai até a última.
  if(i<0&&getChildCount()>0&&lastY>getChildAt(getChildCount()-1).getBottom())i=listener.count()-1;
  if(i<0||i==current)return;
  current=i;listener.range(anchor,current);
 }

 private void scrollNearEdges(){
  int step=lastY<edge?-(int)((edge-lastY)/4)-4:lastY>getHeight()-edge?(int)((lastY-(getHeight()-edge))/4)+4:0;
  boolean was=scrollStep!=0;scrollStep=step;
  if(step!=0&&!was)postOnAnimation(autoScroll);
 }

 private void stop(){
  dragging=false;scrollStep=0;removeCallbacks(autoScroll);anchor=-1;current=-1;
  getParent().requestDisallowInterceptTouchEvent(false);
  listener.end();
 }
}
