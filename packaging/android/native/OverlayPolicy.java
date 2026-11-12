package io.github.abyxs.codingremote;

import android.content.SharedPreferences;
import java.util.Calendar;

/** Preference policy, kept independent of window and sprite rendering. */
final class OverlayPolicy {
    final boolean snap,quiet;
    final int width,duration,speed;
    OverlayPolicy(SharedPreferences prefs){
        snap=prefs.getBoolean("overlaySnap",true);
        width=Math.max(160,Math.min(360,prefs.getInt("overlayBubbleWidth",260)));
        duration=Math.max(0,Math.min(60,prefs.getInt("overlayBubbleSeconds",8)));
        speed=Math.max(50,Math.min(150,prefs.getInt("overlayAnimationSpeed",100)));
        int start=prefs.getInt("overlayQuietStart",22),end=prefs.getInt("overlayQuietEnd",8);
        int hour=Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        boolean inWindow=start==end||start<end&&hour>=start&&hour<end||start>end&&(hour>=start||hour<end);
        quiet=prefs.getBoolean("overlayQuietHours",false)&&inWindow;
    }
}

