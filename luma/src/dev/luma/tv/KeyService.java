package dev.luma.tv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/** The TCL remote's app buttons. The TV's policy handed them to Google TV's launcher, which is switched off, so
 *  they did nothing; this service catches them anywhere and opens the app. Matched by scan code (getevent on the
 *  household's remote), since TCL's key codes (4020, 4062…) are vendor-specific. TCL Channel is left to the TV,
 *  which opens it itself. Enabled by luma.sh keys. */
public final class KeyService extends AccessibilityService {
    // ponytail: this household's remote; add a scan code here for another button.
    static final int[] SCANS={245,752,242,640,652};
    static final String[] APPS={"org.smarttube.stable","com.amazon.amazonvideo.livingroom","com.netflix.ninja",
        "com.univision.prendetv","com.spotify.tv.android"}; // YouTube button opens SmartTube, Apple TV button opens Spotify (household choices)

    static String app(int scanCode){for(int i=0;i<SCANS.length;i++)if(SCANS[i]==scanCode)return APPS[i];return null;}

    @Override protected boolean onKeyEvent(KeyEvent event){
        String pkg=app(event.getScanCode());if(pkg==null)return false;
        Intent launch=getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if(launch==null)launch=getPackageManager().getLaunchIntentForPackage(pkg);
        if(launch==null)return false; // app not installed: the TV keeps the button
        if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)
            try{startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}catch(RuntimeException ignored){}
        return true; // the button is ours, down and up
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event){}
    @Override public void onInterrupt(){}
}
