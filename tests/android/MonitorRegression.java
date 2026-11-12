package io.github.abyxs.codingremote.tests;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import java.lang.reflect.Method;

/** Emulator only: exercise notification-enabled startup rather than isolated notification posting. */
public final class MonitorRegression extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            if (context.checkSelfPermission("android.permission.ACCESS_NETWORK_STATE") != PackageManager.PERMISSION_GRANTED)
                throw new AssertionError("Network-constrained jobs require the installed manifest permission");
            context.getSharedPreferences("coding-remote", 0).edit().clear().commit();
            context.getSharedPreferences("ui", 0).edit().putBoolean("notifications", true).putBoolean("overlay", false).commit();
            Activity app = startActivitySync(new Intent().setClassName(context, "io.github.abyxs.codingremote.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Class<?> monitor = Class.forName("io.github.abyxs.codingremote.MonitorConnection");
            Method start = monitor.getDeclaredMethod("start", Context.class); start.setAccessible(true);
            // Same entry point used on launch and resume; no real connection credentials are needed.
            start.invoke(null, context);
            start.invoke(null, context);
            waitForIdleSync();
            JobScheduler scheduler = (JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            JobInfo job = scheduler.getPendingJob(91);
            if (job != null)throw new AssertionError("Event monitor must not schedule polling jobs");
            NotificationManager notifications=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
            SystemClock.sleep(1200);
            for(android.service.notification.StatusBarNotification n:notifications.getActiveNotifications())
                if(n.getId()==90)throw new AssertionError("Persistent monitor notification must be removed");
            context.getSharedPreferences("ui", 0).edit().putBoolean("notifications", false).commit();
            start.invoke(null, context);
            if (scheduler.getPendingJob(91) != null) throw new AssertionError("Disabled monitor still scheduled");
            SystemClock.sleep(500);
            for (android.service.notification.StatusBarNotification n : notifications.getActiveNotifications())
                if (n.getId() == 90) throw new AssertionError("Disabled monitor still has a foreground notification");
            runOnMainSync(() -> app.finish());
            result.putString("stream", "PASS: installed network permission; enabled bound event monitor and repeated resume; no persistent notification or periodic polling job; disable cleanup.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(e));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
