package io.github.abyxs.codingremote;
import android.content.*;
import android.os.*;
import android.app.job.*;

/** Bound monitoring while the process is alive, with OS-scheduled background catch-up. */
final class MonitorConnection {
    private static ServiceConnection connection;
    static synchronized void start(Context c){Context app=c.getApplicationContext();boolean enabled=c.getSharedPreferences("ui",0).getBoolean("notifications",false)||c.getSharedPreferences("ui",0).getBoolean("overlay",false);if(!enabled){stop(app);return;}
        if(connection==null){connection=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder binder){}public void onServiceDisconnected(ComponentName n){}};if(!app.bindService(new Intent(app,TaskNotificationService.class),connection,Context.BIND_AUTO_CREATE))connection=null;}
        JobScheduler jobs=(JobScheduler)app.getSystemService(Context.JOB_SCHEDULER_SERVICE);jobs.schedule(new JobInfo.Builder(91,new ComponentName(app,TaskCheckJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15*60*1000).setPersisted(true).build());
    }
    static synchronized void stop(Context app){if(connection!=null){try{app.unbindService(connection);}catch(Exception ignored){}connection=null;}((JobScheduler)app.getSystemService(Context.JOB_SCHEDULER_SERVICE)).cancel(91);}
}
