package io.github.abyxs.codingremote;

import android.app.*;
import android.os.*;
import java.util.*;

/** Visibility includes the scanner and dialogs, and survives activity hand-offs. */
public final class AppVisibility extends Application implements Application.ActivityLifecycleCallbacks {
    private static volatile boolean visible;
    private static final Set<Runnable> listeners=new HashSet<>();
    private final Set<Activity> started=Collections.newSetFromMap(new IdentityHashMap<Activity,Boolean>());
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable hidden=()->{if(started.isEmpty())changed(false);};
    static boolean visible(){return visible;}
    static void add(Runnable listener){listeners.add(listener);}
    static void remove(Runnable listener){listeners.remove(listener);}
    private void changed(boolean value){if(visible==value)return;visible=value;if(value){NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);for(android.service.notification.StatusBarNotification item:manager.getActiveNotifications())if("completion_v2".equals(item.getNotification().getChannelId()))manager.cancel(item.getTag(),item.getId());}for(Runnable listener:new ArrayList<>(listeners))listener.run();}
    @Override public void onCreate(){super.onCreate();registerActivityLifecycleCallbacks(this);}
    public void onActivityStarted(Activity activity){main.removeCallbacks(hidden);started.add(activity);changed(true);}
    public void onActivityStopped(Activity activity){started.remove(activity);if(started.isEmpty())main.postDelayed(hidden,200);}
    public void onActivityCreated(Activity a,Bundle b){} public void onActivityResumed(Activity a){} public void onActivityPaused(Activity a){} public void onActivitySaveInstanceState(Activity a,Bundle b){} public void onActivityDestroyed(Activity a){}
}
