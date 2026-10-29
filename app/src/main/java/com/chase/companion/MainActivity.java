package com.chase.companion;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.widget.*;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
 private TextToSpeech tts;
 private boolean voiceReady;
 private TextView log;
 private EditText input;
 private static final int VOICE = 101;
 @Override public void onCreate(Bundle state) {
  super.onCreate(state);
  LinearLayout root = new LinearLayout(this);
  root.setOrientation(LinearLayout.VERTICAL); root.setPadding(24,48,24,24);
  TextView title = new TextView(this); title.setText("AI Companion — overlay controls"); title.setTextSize(22); root.addView(title);
  TextView notice = new TextView(this); notice.setText("Floating Roxanne is avatar-only: drag anywhere in her window, or hold still to remove. Framing and adjustments stay here in the app. Diagnostic messages below are echoes, not Qwen replies. Use Qwen chat for the local model. Speech recognition may use your provider's cloud service. No always-on microphone."); root.addView(notice);
  button(root, "Enable display over other apps", () -> {
   try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); }
   catch (ActivityNotFoundException e) { toast("Overlay settings unavailable"); }
  });
  button(root, "Show floating Roxanne", this::showOverlay);
  button(root, "Hide overlay", FloatingAvatarOverlay::hide);
  button(root, "Overlay settings: framing, zoom, idle and diagnostics", () -> OverlaySettings.show(this));
  button(root, "Tap to speak", this::listen);
  button(root, "Stop spoken reply", () -> { if (tts != null) tts.stop(); });
  input = new EditText(this); input.setHint("Type a diagnostic message"); root.addView(input);
  button(root, "Send diagnostic message", () -> reply(input.getText().toString()));
  log = new TextView(this); log.setTextIsSelectable(true); root.addView(log);
  ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(root);
  setContentView(scroll);
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
  FloatingAvatarOverlay.show(getApplicationContext());
 }
 @Override protected void onDestroy() { if(tts!=null) { tts.stop(); tts.shutdown(); } super.onDestroy(); }
}
