package com.hisho.tether;
import android.app.*;import android.content.*;import android.content.pm.ServiceInfo;import android.hardware.usb.*;import android.os.*;import android.net.Uri;import org.json.*;import java.io.*;import java.security.*;import java.util.*;import java.util.concurrent.*;
public class CaptureService extends Service{
 public static volatile CaptureService active;public volatile String status="Iniciando",detail="",lastImage="",cameraName="Canon";public volatile int received=0,edited=0,recovered=0,reviewed=0;public volatile boolean capturing=false;
 public final ConnectionStatus link=new ConnectionStatus();private volatile UsbDevice attached;private android.net.Network wifiNetwork;private android.net.ConnectivityManager.NetworkCallback wifiCallback;private final java.util.concurrent.atomic.AtomicBoolean alertArmed=new java.util.concurrent.atomic.AtomicBoolean(false);private volatile Ptp transport;private boolean detachRegistered;
 private final BroadcastReceiver detach=new BroadcastReceiver(){public void onReceive(Context c,Intent intent){UsbDevice d=intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);if(d!=null&&attached!=null&&d.getDeviceId()==attached.getDeviceId()){link.fail("Câmera desconectada. Conecte o cabo e tente novamente.");status=link.error;capturing=false;finish=true;lostAlert(status);log(status);}}};
 void lostAlert(String reason){if(alertArmed.compareAndSet(true,false))DisconnectAlert.show(this,reason);}
 public String connectionLabel(){Ptp p=transport;return link.label(p==null?0:SystemClock.elapsedRealtime()-p.lastActivity);}
 private volatile boolean abort=false,finish=false,usbFinished=true;private Thread usbThread,editThread;private Jobs jobs;private PowerManager.WakeLock wake;
 private final Object originalLock=new Object();private final StringBuilder logs=new StringBuilder();
 public IBinder onBind(Intent i){return null;}
 public void onCreate(){super.onCreate();active=this;IntentFilter filter=new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);if(Build.VERSION.SDK_INT>=33)registerReceiver(detach,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(detach,filter);detachRegistered=true;jobs=new Jobs(this);NotificationManager n=getSystemService(NotificationManager.class);n.createNotificationChannel(new NotificationChannel("capture","Captura e edição",NotificationManager.IMPORTANCE_LOW));wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"LUMO:Capture");}
 public int onStartCommand(Intent intent,int flags,int startId){
  if(intent==null){stopSelf();return START_NOT_STICKY;}
  if("stop".equals(intent.getAction())){alertArmed.set(false);capturing=false;finish=true;link.stop();status="Finalizando foto e fila…";return START_NOT_STICKY;}
  if(editThread!=null)return START_NOT_STICKY;
  UsbDevice device=intent.getParcelableExtra("device");attached=device;String wifiHost=intent.getStringExtra("wifiHost");wifiNetwork=intent.getParcelableExtra("wifiNetwork");boolean hasCamera=device!=null||wifiHost!=null;if(device!=null){String n=device.getProductName();cameraName=n==null||n.trim().isEmpty()?"Canon USB":n.trim();}else if(wifiHost!=null)cameraName="Canon Wi-Fi";if(hasCamera)link.connect();
  int type=hasCamera?ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE:0;
  if(Build.VERSION.SDK_INT>=35)type|=ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING;else type|=ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
  try{if(Build.VERSION.SDK_INT>=29)startForeground(1,notification(),type);else startForeground(1,notification());}catch(Exception e){status="Não foi possível iniciar serviço: "+e.getMessage();link.fail(status);getSharedPreferences("hisho",0).edit().putString("lastStatus",status).apply();stopSelf();return START_NOT_STICKY;}
  wake.acquire(6*60*60*1000L);jobs.retry();
  String settings=intent.getStringExtra("settings");if(settings==null)settings="{}";final String config=settings;
  capturing=hasCamera;usbFinished=!hasCamera;finish=!hasCamera;
  if(wifiNetwork!=null){wifiCallback=new android.net.ConnectivityManager.NetworkCallback(){public void onLost(android.net.Network n){if(n.equals(wifiNetwork)&&capturing){link.fail("Rede Wi-Fi da câmera desconectada.");status=link.error;capturing=false;finish=true;lostAlert(status);log(status);}}};getSystemService(android.net.ConnectivityManager.class).registerNetworkCallback(new android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build(),wifiCallback);}
  if(hasCamera){usbThread=new Thread(()->capture(device,config,wifiHost),"LUMO-USB");usbThread.start();}
  editThread=new Thread(this::editLoop,"LUMO-Editor");editThread.setPriority(Thread.MIN_PRIORITY);editThread.start();return START_NOT_STICKY;
 }
 Notification notification(){Intent stop=new Intent(this,CaptureService.class).setAction("stop");PendingIntent action=PendingIntent.getService(this,3,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);PendingIntent open=PendingIntent.getActivity(this,2,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);return new Notification.Builder(this,"capture").setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("LUMO · "+received+" originais / "+edited+" editadas").setContentText(status).setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Parar captura",action).build()).build();}
 void log(String message){synchronized(logs){logs.append(new java.text.SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(new Date())).append(" ").append(message).append('\n');if(logs.length()>15000)logs.delete(0,logs.length()-15000);}try{getSystemService(NotificationManager.class).notify(1,notification());}catch(Exception ignored){}}
 public String report(){synchronized(logs){return logs.toString();}}
 public int pending(){return jobs.pending();}
 static String hash(String value)throws Exception{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));StringBuilder b=new StringBuilder();for(byte v:bytes)b.append(String.format("%02x",v));return b.toString();}
 void originals()throws Exception{synchronized(originalLock){String[] item;while((item=jobs.originalPending())!=null){try{Uri uri=jobs.save(new File(item[1]),"Originais",item[2],item[0],"original");jobs.set(item[0],"ready","original",uri.toString());}catch(Exception e){jobs.set(item[0],"originalError","error",e.toString());throw e;}}}}
 void capture(UsbDevice device,String settings,String wifiHost){
  Ptp ptp=null;try{
   JSONObject session=new JSONObject(settings);boolean backfill=session.optBoolean("backfill",true);
   Set<String> seen=new HashSet<>();ArrayDeque<int[]> backlog=new ArrayDeque<>();UsbManager manager=(UsbManager)getSystemService(USB_SERVICE);
   String serial;try{serial=device==null?"wifi:"+wifiHost:device.getSerialNumber();}catch(Exception e){serial=null;}if(serial==null)serial=device==null?"wifi:"+wifiHost:String.valueOf(device.getProductId());
   File spool=new File(getFilesDir(),"queue");spool.mkdirs();
   for(int attempt=1;attempt<=3;attempt++){
    if(!capturing)return;
    if(wifiHost==null&&(!manager.hasPermission(device)||!manager.getDeviceList().containsKey(device.getDeviceName())))throw new IOException("Câmera indisponível. Confira o cabo e a autorização USB.");
    try{
     status="Confirmando conexão · tentativa "+attempt+" de 3…";log(status);if(wifiHost==null)ptp=new Ptp(manager,device);else{
      if(wifiNetwork==null)throw new IOException("Conecte o celular ao Wi-Fi da câmera.");
      String identity=getSharedPreferences("hisho",0).getString("wifiGuid",null);if(identity==null){identity=UUID.randomUUID().toString();getSharedPreferences("hisho",0).edit().putString("wifiGuid",identity).apply();}
      ptp=new WifiPtp(wifiNetwork,wifiHost,identity);status="Wi-Fi: autorize LUMO na câmera, se solicitado.";log(status);
     }
     transport=ptp;
     status="Abrindo sessão PTP…";log(status);ptp.open();if(!capturing)return;
     // Confirma a camada USB/PTP imediatamente. Antes, a UI permanecia em
     // CONECTANDO durante toda a listagem do cartão, especialmente perceptível na R8.
     link.ready();alertArmed.set(true);
     int vid=device==null?0:device.getVendorId(),pid=device==null?0:device.getProductId();
     boolean r8=device!=null&&vid==0x04A9&&(pid==0x330C||pid==0x3113);
     status=(r8?"Canon R8 detectada · ":"")+"PTP conectado · ativando captura Canon…";log(status+" USB "+String.format(Locale.ROOT,"%04X:%04X",vid,pid));
     ptp.captureMode();if(!capturing)return;
     try{ptp.drain();}catch(Ptp.Failure e){if(e.code!=0x2019)throw e;}
     status=(r8?"R8 conectada · ":"Conectada · ")+"lendo índice do cartão…";log(status);
     List<int[]> initial=ptp.objects();seen.clear();backlog.clear();
     for(int[] o:initial)seen.add(o[0]+":"+o[1]);
     if(backfill)for(int i=initial.size()-1;i>=0;i--)backlog.addLast(initial.get(i));
     if(!capturing)return;
     status=backlog.isEmpty()?"Conexão confirmada. Fotografe na câmera.":"Conexão confirmada · retroativo em segundo plano ("+backlog.size()+")";log(status);break;
    }catch(IOException e){log("Abertura falhou: "+e.getMessage());if(ptp!=null){try{ptp.close();}catch(Exception ignored){}ptp=null;transport=null;}backlog.clear();if(!capturing)return;if(attempt==3)throw e;link.connect();status="Câmera ainda não respondeu. Tentando novamente…";log(status);Thread.sleep(900L*attempt);}
   }
   if(ptp==null||!capturing)return;
   int transientErrors=0;
   while(capturing){long start=SystemClock.elapsedRealtime();
    try{
     ptp.drain();List<int[]> objects=ptp.objects();boolean liveReceived=false;
     for(int[] o:objects){if(!capturing)break;String key=o[0]+":"+o[1];if(seen.contains(key))continue;if(downloadObject(ptp,o,serial,settings,spool,seen,false))liveReceived=true;}
     if(capturing&&!liveReceived&&!backlog.isEmpty()){
      int[] old=backlog.peekFirst();status="Conectada · recuperando retroativo ("+backlog.size()+" restante(s))";
      try{downloadObject(ptp,old,serial,settings,spool,seen,true);backlog.removeFirst();}
      catch(Ptp.Failure e){if(e.code==0x2009){backlog.removeFirst();log("Retroativo ignorado · objeto já não existe no cartão.");}else throw e;}
     }
     transientErrors=0;if(capturing){link.ready();status=backlog.isEmpty()?"Conexão confirmada · aguardando fotos":"Conexão confirmada · retroativo em segundo plano ("+backlog.size()+")";}
    }catch(Ptp.Failure e){if((e.code==0x2019||e.code==0x2009)&&++transientErrors<=8){link.busy();status="Câmera ocupada · aguardando resposta";log(status);}else throw e;}
    long wait=Math.max(1,800-(SystemClock.elapsedRealtime()-start));Thread.sleep(wait);
   }
  }catch(Exception e){if(link.error.isEmpty()&&capturing)link.fail("Captura pausada: "+e.getMessage());status=link.error.isEmpty()?"Captura encerrada.":link.error;if(!link.error.isEmpty())lostAlert(status);log(status);}finally{capturing=false;link.stop();try{if(ptp!=null)ptp.close();}catch(Exception e){log("Falha ao liberar USB: "+e.getMessage());}finally{transport=null;usbFinished=true;finish=true;}log("Conexão encerrada; concluindo edições pendentes.");}
 }
 String settingsForNext(String fallback){
  String live=getSharedPreferences("hisho",0).getString("liveSettings","");
  return live==null||live.trim().isEmpty()?fallback:live;
 }
 boolean downloadObject(Ptp ptp,int[] o,String serial,String settings,File spool,Set<String> seen,boolean retro)throws Exception{
  String key=o[0]+":"+o[1];Ptp.Info info=ptp.info(o[1]);String id=hash(serial+":"+o[0]+":"+info.name+":"+info.date+":"+info.size);
  if(jobs.exists(id)){seen.add(key);return false;}
  if(getFilesDir().getUsableSpace()<info.size*3L+64*1024*1024)throw new IOException("Pouco espaço livre. Libere armazenamento e reconecte.");
  File temp=new File(spool,id+".part"),saved=new File(spool,id+".jpg");long t=SystemClock.elapsedRealtime();
  log((retro?"Recuperando ":"Baixando ")+info.name+" · "+info.size+" bytes · "+(o[0])+" · "+(ptp instanceof WifiPtp?"Wi-Fi":"USB"));
  try{ptp.download(o[1],temp,info.size);if(!temp.renameTo(saved))throw new IOException("Falha ao guardar original.");}finally{temp.delete();}
  String name=info.name.replaceAll("[^a-zA-Z0-9_.-]","_").replaceFirst("\\.[^.]+$","")+"_"+id.substring(0,12)+".jpg";
  String contentHash=PhotoHash.sha256(saved),pHash="";try{pHash=PhotoHash.perceptualHash(saved);}catch(Exception ignored){}
  String duplicate=jobs.duplicateByContent(contentHash);if(!duplicate.isEmpty()){saved.delete();seen.add(key);log("Duplicata exata ignorada · "+info.name);return false;}
  long capturedAt=PhotoTime.parsePtp(info.date);String current=settingsForNext(settings);
  jobs.add(id,saved,name,current,capturedAt,contentHash,pHash,info.size);originals();seen.add(key);received++;if(retro)recovered++;
  getSharedPreferences("hisho",0).edit().putLong("lastPhotoAt",System.currentTimeMillis()).putInt("lastReceived",received).apply();
  detail=(retro?"Retroativo":"Último recebimento")+" + gravação: "+String.format(Locale.ROOT,"%.2f",(SystemClock.elapsedRealtime()-t)/1000.)+" s";
  status=retro?"Recuperando fotos anteriores · edição em fila":"Recebendo · edição em fila independente";log("Original salvo: "+name+" · "+detail);return true;
 }
 void editLoop(){
  try{
   originals();
   while(!abort){String[] item=jobs.next();if(item==null){if(finish&&usbFinished)break;Thread.sleep(300);continue;}
    File output=new File(getCacheDir(),item[0]+".jpg");
    try{
     jobs.set(item[0],"editing",null,null);long t=SystemClock.elapsedRealtime();JSONObject cfg=new JSONObject(item[3]);
     PhotoEditor.edit(new File(item[1]),output,cfg);
     boolean curate=cfg.optBoolean("curation",true);SmartCurator.Result quality;
     try{quality=SmartCurator.analyze(output,cfg.optInt("curationSensitivity",1),jobs,item[0]);}catch(Exception e){quality=new SmartCurator.Result(true,1,"Falha na curadoria · "+e.getMessage(),"","","");}
     jobs.setIntelligence(item[0],quality.pHash,quality.duplicateOf,quality.semantic);
     jobs.markEdited(item[0],cfg.optString("presetName",""));
     // O score é calculado sempre; a opção Curadoria controla apenas o bloqueio para Revisão.
     if(curate&&quality.review){
      jobs.setQuality(item[0],quality.score,quality.reason);Uri uri=jobs.save(output,"Sob Revisao",item[2].replace(".jpg","_REVISAO.jpg"),item[0],"edited");jobs.set(item[0],"review","error",quality.reason);jobs.history(item[0],System.currentTimeMillis(),"Revisão","Bloqueada para envio · "+quality.reason);new File(item[1]).delete();reviewed++;lastImage=uri.toString();getSharedPreferences("hisho",0).edit().putString("lastImage",lastImage).putLong("lastCurationAt",System.currentTimeMillis()).apply();
      log("CURADORIA · SOB REVISÃO · "+item[2]+" · "+quality.reason+" · envio bloqueado");continue;
     }
     String qualityNote=quality.reason+(curate?"":" · Curadoria desligada: nota informativa");jobs.setQuality(item[0],quality.score,qualityNote);Uri uri=jobs.save(output,"Editadas",item[2].replace(".jpg","_Editada.jpg"),item[0],"edited");jobs.set(item[0],"done","error",null);jobs.history(item[0],System.currentTimeMillis(),"Aprovada","Liberada para entrega");new File(item[1]).delete();edited++;lastImage=uri.toString();getSharedPreferences("hisho",0).edit().putString("lastImage",lastImage).putLong("lastEditAt",System.currentTimeMillis()).putLong("lastCurationAt",System.currentTimeMillis()).apply();FottoSync.kick(this);log("Editada salva · "+String.format(Locale.ROOT,"%.2f",(SystemClock.elapsedRealtime()-t)/1000.)+" s · nota "+quality.score+"/10 · "+jobs.pending()+" pendentes");
    }catch(Exception|OutOfMemoryError e){jobs.set(item[0],"error","error",e.toString());log("Falha na edição; original preservado: "+e.getMessage());}
    finally{output.delete();}
   }
  }catch(Exception e){log("Fila interrompida: "+e.getMessage());capturing=false;}
  finally{if(capturing){capturing=false;}while(!usbFinished){try{Thread.sleep(100);}catch(InterruptedException e){break;}}status=!link.error.isEmpty()?link.error:jobs.pending()>0?"Pausado. Há fotos pendentes; toque em Retomar fila.":reviewed>0?"Sessão encerrada · "+reviewed+" foto(s) em Sob Revisão.":"Sessão encerrada. Fotos salvas nas pastas.";log(status);getSharedPreferences("hisho",0).edit().putString("lastReport",report()).putString("lastStatus",status).putInt("lastReceived",received).putInt("lastEdited",edited).putInt("lastRecovered",recovered).putInt("lastReviewed",reviewed).apply();stopForeground(STOP_FOREGROUND_REMOVE);if(active==this)active=null;stopSelf();}
 }
 @Override public void onTimeout(int startId,int fgsType){abort=true;capturing=false;finish=true;log("Limite de segundo plano do Android atingido; fila preservada.");stopSelf();}
 public void onDestroy(){if(wifiCallback!=null){try{getSystemService(android.net.ConnectivityManager.class).unregisterNetworkCallback(wifiCallback);}catch(Exception ignored){}wifiCallback=null;}if(detachRegistered){unregisterReceiver(detach);detachRegistered=false;}link.stop();abort=true;capturing=false;finish=true;if(wake!=null&&wake.isHeld())wake.release();if(editThread==null||!editThread.isAlive())if(active==this)active=null;super.onDestroy();}
}
