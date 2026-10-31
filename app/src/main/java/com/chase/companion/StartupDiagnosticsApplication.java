package com.chase.companion;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.AtomicFile;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class StartupDiagnosticsApplication extends Application {
 @Override public void onCreate() {
  super.onCreate();
  registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
   @Override public void onActivityCreated(Activity activity, Bundle state) {
    // Activity dispatches this during super.onCreate(), before the subclass's UI setup.
    if (activity.getClass().getName().equals("com.chase.companion.AlongsideActivity")) {
     activity.getWindow().getDecorView();
    }
   }
   @Override public void onActivityStarted(Activity activity) {}
   @Override public void onActivityResumed(Activity activity) {}
   @Override public void onActivityPaused(Activity activity) {}
   @Override public void onActivityStopped(Activity activity) {}
   @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
   @Override public void onActivityDestroyed(Activity activity) {}
  });
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
