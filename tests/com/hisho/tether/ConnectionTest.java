package com.hisho.tether;
public class ConnectionTest {
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){ConnectionStatus s=new ConnectionStatus();check(s.label(0).equals("SEM CÂMERA"));s.connect();check(s.label(0).equals("CONECTANDO"));s.ready();check(s.label(0).equals("CONECTADA"));check(s.label(19000).equals("SEM RESPOSTA"));s.busy();check(s.label(0).equals("OCUPADA"));s.ready();check(s.label(0).equals("CONECTADA"));s.fail("Cabo removido");s.ready();s.stop();check(s.label(0).equals("DESCONECTADA")&&s.error.equals("Cabo removido"));s.connect();check(s.error.isEmpty());s.stop();s.ready();check(s.label(0).equals("SEM CÂMERA"));System.out.println("PASS: no premature connected state; busy/stale states; detach and failure survive late success and cleanup; reconnect clears old error.");}
}
