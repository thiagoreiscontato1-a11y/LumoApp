package com.hisho.tether;
final class ConnectionStatus {
 enum State {IDLE,CONNECTING,READY,BUSY,STOPPED,ERROR}
 volatile State state=State.IDLE;volatile String error="";
 synchronized void connect(){error="";state=State.CONNECTING;}
 synchronized void ready(){if(state==State.CONNECTING||state==State.BUSY||state==State.READY)state=State.READY;}
 synchronized void busy(){if(state==State.READY)state=State.BUSY;}
 synchronized void fail(String message){error=message;state=State.ERROR;}
 synchronized void stop(){if(state!=State.ERROR)state=State.STOPPED;}
 String label(long age){switch(state){case CONNECTING:return "CONECTANDO";case READY:return age>18000?"SEM RESPOSTA":"CONECTADA";case BUSY:return "OCUPADA";case ERROR:return "DESCONECTADA";default:return "SEM CÂMERA";}}
}
