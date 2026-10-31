package com.chase.companion;

import android.app.Application;
import android.util.AtomicFile;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class StartupDiagnosticsApplication extends Application {
 @Override public void onCreate() {
  super.onCreate();
  final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
  Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
   try {
    String report = "Thread: " + thread.getName() + "\n" + Log.getStackTraceString(error);
    if (report.length() > 65536) report = report.substring(0, 65536);
    AtomicFile file = new AtomicFile(new File(getFilesDir(), "last-startup-crash.txt"));
    FileOutputStream out = file.startWrite();
    try { out.write(report.getBytes(StandardCharsets.UTF_8)); file.finishWrite(out); }
    catch (Throwable failure) { file.failWrite(out); }
   } catch (Throwable ignored) {}
   if (previous != null) previous.uncaughtException(thread, error);
   else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10); }
  });
 }
}
