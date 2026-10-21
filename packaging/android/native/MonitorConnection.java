package io.github.abyxs.codingremote;
import android.content.*;
import android.os.*;
import android.app.job.*;

/** Bound monitoring while the process is alive, with OS-scheduled background catch-up. */
final class MonitorConnection {
    private static ServiceConnection connection;
    static synchronized void start(Context c){Context app=c.getApplicationContext();boolean enabled=c.getSharedPreferences("ui",0).getBoolean("notifications",false)||c.getSharedPreferences("ui",0).getBoolean("overlay",false);if(!enabled){stop(app);return;}
        if(connection==null){connection=new ServiceConnection(){
            public void onServiceConnected(ComponentName n,IBinder binder){
                // The service has just built its overlay. Nothing else re-primes it, so an
                // overlay that was switched on before the bind completed - which is every
                // first toggle - waited for a status poll that the foreground gate then
                // refused, and the user saw no pet at all. Ask for the frame now.
                // The service is bound now, so a pet can exist - but only once something
                // actually replaces the front-of-app gate. Calling prime() here forced a
                // draw on every bind, which put the overlay back on screen the moment the
                // user returned to the app.
                if(app.getSharedPreferences("ui",0).getBoolean("overlay",false))StatusOverlay.ensureRunning(app);
            }
            public void onServiceDisconnected(ComponentName n){}};
            if(!app.bindService(new Intent(app,TaskNotificationService.class),connection,Context.BIND_AUTO_CREATE))connection=null;}
        try{JobScheduler jobs=(JobScheduler)app.getSystemService(Context.JOB_SCHEDULER_SERVICE);jobs.schedule(new JobInfo.Builder(91,new ComponentName(app,TaskCheckJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15*60*1000).setPersisted(true).build());}catch(Exception ignored){}
    }
    static synchronized void stop(Context app){if(connection!=null){try{app.unbindService(connection);}catch(Exception ignored){}connection=null;}((JobScheduler)app.getSystemService(Context.JOB_SCHEDULER_SERVICE)).cancel(91);}
}
