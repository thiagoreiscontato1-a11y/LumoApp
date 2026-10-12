package com.hisho.tether;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import android.widget.*;
public class DemoActivationActivity extends Activity {
 public void onCreate(Bundle state){super.onCreate(state);if(!DemoLicense.isDemo(this)||DemoLicense.allowed(this)){open();return;}
  boolean expired=DemoLicense.prefs(this).getLong("start",0)>0;
  LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setGravity(Gravity.CENTER_VERTICAL);int pad=(int)(24*getResources().getDisplayMetrics().density);box.setPadding(pad,pad,pad,pad);box.setBackgroundColor(0xfff5f7fa);
  TextView title=new TextView(this);title.setText(expired?"Seu teste terminou":"LUMO Demo");title.setTextSize(28);title.setTextColor(0xff101722);box.addView(title);
  TextView note=new TextView(this);note.setText(expired?"Os 15 dias de avaliação terminaram. Suas fotos não foram apagadas. Entre em contato com quem forneceu o Lumo para continuar.":"Digite o código recebido para liberar todas as funções por 15 dias neste aparelho. A contagem começa na ativação e continua mesmo com o app fechado.");note.setTextSize(16);note.setTextColor(0xff364152);note.setPadding(0,pad,0,pad);box.addView(note);
  if(!expired){EditText input=new EditText(this);input.setSingleLine();input.setHint("Código de demonstração");input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);box.addView(input);Button activate=new Button(this);activate.setText("Ativar 15 dias");box.addView(activate);activate.setOnClickListener(v->{if(DemoLicense.activate(this,input.getText().toString()))open();else input.setError("Código inválido. Confira o código recebido.");});}
  Button exit=new Button(this);exit.setText("Fechar");exit.setOnClickListener(v->finishAndRemoveTask());box.addView(exit);setContentView(box);
 }
 void open(){startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));finish();}
}
