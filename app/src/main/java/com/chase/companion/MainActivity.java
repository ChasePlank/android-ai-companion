package com.chase.companion;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.*;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
 private TextToSpeech tts;
 private boolean voiceReady;
 private TextView log;
 private EditText input;
 private static WindowManager wm;
 private static TextView bubble;
 private static WindowManager.LayoutParams position;
 private static final int VOICE = 101;
 @Override public void onCreate(Bundle state) {
  super.onCreate(state);
  LinearLayout root = new LinearLayout(this);
  root.setOrientation(LinearLayout.VERTICAL); root.setPadding(24,48,24,24);
  TextView title = new TextView(this); title.setText("AI Companion — diagnostic build"); title.setTextSize(22); root.addView(title);
  TextView notice = new TextView(this); notice.setText("No AI model connected yet. Replies below are explicit diagnostic echoes. Speech recognition may use your installed provider's cloud service. No always-on microphone."); root.addView(notice);
  button(root, "Enable display over other apps", () -> {
   try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); }
   catch (ActivityNotFoundException e) { toast("Overlay settings unavailable"); }
  });
  button(root, "Show draggable overlay", this::showOverlay);
  button(root, "Hide overlay", MainActivity::hideOverlay);
  button(root, "Tap to speak", this::listen);
  button(root, "Stop spoken reply", () -> { if (tts != null) tts.stop(); });
  input = new EditText(this); input.setHint("Type a diagnostic message"); root.addView(input);
  button(root, "Send diagnostic message", () -> reply(input.getText().toString()));
  log = new TextView(this); log.setTextIsSelectable(true);
  ScrollView scroll = new ScrollView(this); scroll.addView(log); root.addView(scroll, new LinearLayout.LayoutParams(-1,0,1));
  setContentView(root);
  tts = new TextToSpeech(getApplicationContext(), status -> {
   if (status == TextToSpeech.SUCCESS && tts != null) {
    int result = tts.setLanguage(Locale.US);
    voiceReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED;
   }
  });
 }
 private void button(LinearLayout root, String text, Runnable action) { Button b = new Button(this); b.setText(text); b.setOnClickListener(v -> action.run()); root.addView(b); }
 private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
 private void listen() {
  if (tts != null) tts.stop();
  Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
  i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
  i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to your companion");
  try { startActivityForResult(i,VOICE); } catch (ActivityNotFoundException e) { toast("Install or enable a speech recognition provider"); }
 }
 @Override protected void onActivityResult(int request, int result, Intent data) {
  super.onActivityResult(request,result,data);
  if (request == VOICE && result == RESULT_OK && data != null) {
   ArrayList<String> words = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
   if (words != null && !words.isEmpty()) reply(words.get(0));
  }
 }
 private void reply(String text) {
  text = text.trim(); if (text.isEmpty()) return;
  String response = "Diagnostic mode. I received: " + text;
  log.append("You: " + text + "\nCompanion: " + response + "\n\n"); input.setText("");
  if (voiceReady) tts.speak(response,TextToSpeech.QUEUE_FLUSH,null,"diagnostic");
  else toast("Speech output not ready; check installed voice data");
 }
 private void showOverlay() {
  if (!Settings.canDrawOverlays(this)) { toast("Grant display-over-apps permission first"); return; }
  if (bubble != null) return;
  wm = (WindowManager)getApplicationContext().getSystemService(WINDOW_SERVICE);
  TextView view = new TextView(getApplicationContext()); view.setText("AI\nHold to close"); view.setTextSize(16); view.setTextColor(0xffffffff); view.setBackgroundColor(0xdd303f70); view.setGravity(Gravity.CENTER); view.setPadding(20,20,20,20);
  position = new WindowManager.LayoutParams(180,150,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);
  position.gravity = Gravity.TOP | Gravity.LEFT; position.x = 24; position.y = 180;
  view.setOnTouchListener(new android.view.View.OnTouchListener() {
   float sx,sy; int x,y; long started;
   public boolean onTouch(android.view.View v, MotionEvent e) {
    switch(e.getActionMasked()) {
     case MotionEvent.ACTION_DOWN: sx=e.getRawX(); sy=e.getRawY(); x=position.x; y=position.y; started=System.currentTimeMillis(); return true;
     case MotionEvent.ACTION_MOVE: position.x=x+(int)(e.getRawX()-sx); position.y=y+(int)(e.getRawY()-sy); if(bubble!=null) wm.updateViewLayout(bubble,position); return true;
     case MotionEvent.ACTION_UP: if(System.currentTimeMillis()-started>700 && Math.abs(e.getRawX()-sx)<20 && Math.abs(e.getRawY()-sy)<20) hideOverlay(); return true;
     case MotionEvent.ACTION_CANCEL: return true;
    } return false;
   }
  });
  try { wm.addView(view,position); bubble=view; } catch (RuntimeException e) { toast("Overlay failed: " + e.getMessage()); }
 }
 private static void hideOverlay() { if(bubble!=null && wm!=null) { try { wm.removeView(bubble); } catch(IllegalArgumentException ignored) {} bubble=null; } }
 @Override protected void onDestroy() { if(tts!=null) { tts.stop(); tts.shutdown(); } super.onDestroy(); }
}
