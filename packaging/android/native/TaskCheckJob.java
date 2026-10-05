package io.github.abyxs.codingremote;
import android.app.job.*;
public final class TaskCheckJob extends JobService {
    private volatile boolean stopped;
    @Override public boolean onStartJob(JobParameters params){stopped=false;new Thread(()->{try{TaskNotificationService.checkOnce(this,null);}finally{if(!stopped)jobFinished(params,false);}},"task-background-check").start();return true;}
    @Override public boolean onStopJob(JobParameters params){stopped=true;return true;}
}
