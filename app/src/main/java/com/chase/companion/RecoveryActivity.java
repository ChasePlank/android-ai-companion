package com.chase.companion;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.AtomicFile;
import android.view.WindowInsets;
import android.widget.*;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class RecoveryActivity extends Activity {
 private TextView report;
 private String crashText = "";
 private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
 @Override public void onCreate(Bundle state) {
  super.onCreate(state);
  LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(12,25,42));
  root.setPadding(dp(16), dp(32), dp(16), dp(24));
  if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
  root.setOnApplyWindowInsetsListener((view, insets) -> {
   if (Build.VERSION.SDK_INT >= 30) { android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()); view.setPadding(bars.left + dp(16), bars.top + dp(12), bars.right + dp(16), bars.bottom + dp(12)); }
   return insets;
  });
  TextView title = new TextView(this); title.setText("Alongside recovery"); title.setTextColor(Color.WHITE); title.setTextSize(23); root.addView(title);
  TextView help = new TextView(this); help.setTextColor(Color.WHITE); help.setText("Your calibration and conversations have not been cleared. Try the new interface once. If it closes, reopen Alongside here and copy the crash report below. Native crashes may not produce this report."); root.addView(help);
  add(root, "Try new interface", () -> open(AlongsideActivity.class));
  add(root, "Open previous model inspector", () -> open(ModelNodeActivity.class));
  add(root, "Copy crash report", () -> {
   if (crashText.isEmpty()) { Toast.makeText(this, "No captured report yet", Toast.LENGTH_LONG).show(); return; }
   ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
   if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Alongside crash report", crashText));
   Toast.makeText(this, "Crash report copied", Toast.LENGTH_SHORT).show();
  });
  add(root, "Refresh report", this::readReport);
  ScrollView scroll = new ScrollView(this); report = new TextView(this); report.setTextColor(Color.WHITE); report.setTextIsSelectable(true); scroll.addView(report); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
  setContentView(root); root.requestApplyInsets(); readReport();
 }
 private void add(LinearLayout root, String title, Runnable action) { Button button = new Button(this); button.setText(title); button.setAllCaps(false); button.setOnClickListener(v -> action.run()); root.addView(button); }
 private void open(Class<?> activity) {
  try { startActivity(new Intent(this, activity)); }
  catch (RuntimeException error) { crashText = android.util.Log.getStackTraceString(error); report.setText(crashText); }
 }
 private void readReport() {
  AtomicFile file = new AtomicFile(new File(getFilesDir(), "last-startup-crash.txt"));
  if (!file.getBaseFile().exists()) { crashText = ""; report.setText("No captured Java/Kotlin crash yet. Tap Try new interface to reproduce the failure, then reopen Alongside."); return; }
  try (InputStream input = file.openRead()) {
   byte[] bytes = new byte[65536]; int used = 0;
   while (used < bytes.length) { int count = input.read(bytes, used, bytes.length - used); if (count < 0) break; used += count; }
   crashText = new String(bytes, 0, used, StandardCharsets.UTF_8); report.setText(crashText);
  } catch (Exception error) { crashText = ""; report.setText("Could not read crash report: " + error.getMessage()); }
 }
 @Override protected void onResume() { super.onResume(); if (report != null) readReport(); }
}
