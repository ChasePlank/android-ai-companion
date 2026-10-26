package com.chase.companion;

import android.app.Activity;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.widget.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class LocalChatActivity extends Activity {
 private static final String ENDPOINT = "http://127.0.0.1:12434/v1/chat/completions";
 private static final String MODEL = "qwen2.5-0.5b-instruct-q4_k_m";
 private final ExecutorService worker = Executors.newSingleThreadExecutor();
 private final ArrayList<JSONObject> history = new ArrayList<>();
 private volatile HttpURLConnection connection;
 private volatile int generation;
 private boolean busy, ready, closed;
 private TextToSpeech tts;
 private EditText input;
 private TextView status, transcript;
 private Button send;
 private ScrollView scroll;
 @Override public void onCreate(Bundle saved) {
  super.onCreate(saved);
  LinearLayout root = new LinearLayout(this); root.setOrientation(1); root.setPadding(24,56,24,24);
  TextView title = new TextView(this); title.setText("Local Qwen Companion"); title.setTextSize(22); root.addView(title);
  status = new TextView(this); status.setText("Server: 127.0.0.1:12434 — not tested yet. Keep your local server running."); root.addView(status);
  add(root,"Overlay / diagnostic controls", () -> startActivity(new Intent(this,MainActivity.class)));
  scroll = new ScrollView(this); transcript = new TextView(this); transcript.setTextIsSelectable(true); scroll.addView(transcript); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
  input = new EditText(this); input.setHint("Message your local Qwen model"); input.setMaxLines(4); root.addView(input);
  send = add(root,"Send to Qwen", () -> submit(input.getText().toString()));
  add(root,"Speak a message", this::listen);
  add(root,"Stop request / speech", this::cancel);
  add(root,"Clear conversation", () -> { cancel(); history.clear(); transcript.setText(""); status.setText("Conversation cleared"); });
  setContentView(root);
  tts = new TextToSpeech(getApplicationContext(), result -> {
   if(result == TextToSpeech.SUCCESS && tts != null) { int language=tts.setLanguage(Locale.US); ready=language!=TextToSpeech.LANG_MISSING_DATA && language!=TextToSpeech.LANG_NOT_SUPPORTED; }
  });
 }
 private Button add(LinearLayout root,String text,Runnable action) { Button b=new Button(this); b.setText(text); b.setOnClickListener(v -> action.run()); root.addView(b); return b; }
 private JSONObject message(String role,String text) throws JSONException { return new JSONObject().put("role",role).put("content",text); }
 private void append(String text) { transcript.append(text+"\n\n"); scroll.post(() -> scroll.fullScroll(android.view.View.FOCUS_DOWN)); }
 private void submit(String text) {
  text=text.trim(); if(text.isEmpty() || busy) return;
  final String prompt=text; final int ticket=++generation;
  final JSONArray messages=new JSONArray();
  try {
   messages.put(message("system","You are a helpful personal companion. Be concise, honest and friendly. You cannot see the screen or control this phone. Do not claim to have performed actions."));
   int budget=prompt.length(); ArrayList<JSONObject> recent=new ArrayList<>();
   for(int i=history.size()-2;i>=0;i-=2) {
    int size=history.get(i).optString("content").length()+history.get(i+1).optString("content").length();
    if(budget+size>4000) break; recent.add(0,history.get(i+1)); recent.add(0,history.get(i)); budget+=size;
   }
   for(JSONObject item:recent) messages.put(item); messages.put(message("user",prompt));
  } catch(JSONException e) { status.setText("Could not prepare request: "+e.getMessage()); return; }
  busy=true; send.setEnabled(false); input.setText(""); if(tts!=null) tts.stop();
  append("You: "+prompt); status.setText("Waiting for local Qwen…");
  worker.execute(() -> {
   HttpURLConnection current=null;
   try {
    if(ticket!=generation) return;
    current=(HttpURLConnection)new URL(ENDPOINT).openConnection(); connection=current;
    if(ticket!=generation) return;
    current.setConnectTimeout(10000); current.setReadTimeout(180000); current.setRequestMethod("POST"); current.setDoOutput(true); current.setInstanceFollowRedirects(false);
    current.setRequestProperty("Content-Type","application/json"); current.setRequestProperty("Authorization","Bearer anything");
    JSONObject body=new JSONObject().put("model",MODEL).put("messages",messages).put("stream",false).put("max_tokens",256).put("temperature",0.7);
    try(OutputStream output=current.getOutputStream()) { output.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
    int code=current.getResponseCode(); InputStream source=code>=200 && code<300 ? current.getInputStream() : current.getErrorStream();
    StringBuilder raw=new StringBuilder();
    if(source!=null) try(BufferedReader reader=new BufferedReader(new InputStreamReader(source,StandardCharsets.UTF_8))) { char[] buffer=new char[2048]; int count; while((count=reader.read(buffer))!=-1) { raw.append(buffer,0,count); if(raw.length()>262144) throw new IOException("Response too large"); } }
    if(code<200 || code>=300) throw new IOException("HTTP "+code+": "+raw.substring(0,Math.min(raw.length(),1200)));
    JSONObject result=new JSONObject(raw.toString());
    String answer=result.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim();
    if(answer.isEmpty()) throw new IOException("Server returned an empty reply");
    runOnUiThread(() -> {
     if(closed || ticket!=generation) return;
     busy=false; send.setEnabled(true);
     try { history.add(message("user",prompt)); history.add(message("assistant",answer)); while(history.size()>12) { history.remove(0); history.remove(0); } } catch(JSONException ignored) {}
     append("Qwen: "+answer); status.setText("Local reply received"); if(ready) tts.speak(answer,TextToSpeech.QUEUE_FLUSH,null,"qwen");
    });
   } catch(Exception error) {
    final String details=error.getClass().getSimpleName()+": "+error.getMessage();
    runOnUiThread(() -> { if(closed || ticket!=generation) return; busy=false; send.setEnabled(true); status.setText("Local request failed"); append("Error: "+details+"\nNo cloud fallback was used. Check that your server and model are running."); });
   } finally { if(current!=null) current.disconnect(); if(connection==current) connection=null; }
  });
 }
 private void cancel() {
  generation++; busy=false; if(send!=null) send.setEnabled(true); if(tts!=null) tts.stop();
  final HttpURLConnection active=connection;
  if(active!=null) new Thread(active::disconnect,"cancel-local-request").start();
  if(status!=null) status.setText("Stopped locally; server may finish an in-flight generation");
 }
 private void listen() {
  if(busy) { status.setText("Stop the current request before speaking"); return; }
  if(tts!=null) tts.stop();
  Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH); intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
  try { startActivityForResult(intent,201); } catch(ActivityNotFoundException e) { status.setText("No speech recognition provider available"); }
 }
 @Override protected void onActivityResult(int request,int result,Intent data) { super.onActivityResult(request,result,data); if(request==201 && result==RESULT_OK && data!=null) { ArrayList<String> words=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS); if(words!=null && !words.isEmpty()) submit(words.get(0)); } }
 @Override protected void onDestroy() { closed=true; cancel(); worker.shutdownNow(); if(tts!=null) tts.shutdown(); super.onDestroy(); }
}
