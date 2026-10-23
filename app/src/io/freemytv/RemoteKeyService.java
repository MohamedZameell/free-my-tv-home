package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/** Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. */
public class RemoteKeyService extends AccessibilityService {
    /** TCL's player for HDMI inputs and live TV: it ignores Back, so there Back goes to the home screen. */
    private static final String TV_PLAYER = "com.tcl.tv/com.tcl.player.TVActivity";

    /** The window on screen (package/class), from window changes only: no window content is read. */
    private String foreground = "";
    private boolean backHome;
    private StandbyLight light;

    @Override protected void onServiceConnected() { light = StandbyLight.start(this); }

    @Override public void onDestroy() {
        if (light != null) light.stop(this);
        super.onDestroy();
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) return backToHome(event);
        return RemoteKeys.onKey(this, event);
    }

    /** Consumes the whole press (down and up) when it takes the person home; any other Back goes on untouched. */
    private boolean backToHome(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) backHome = RemoteKeys.supported() && TV_PLAYER.equals(foreground);
            if (backHome && e.getRepeatCount() == 0) {
                AppLog.w("back on the TV player -> home");
                performGlobalAction(GLOBAL_ACTION_HOME);
            }
            return backHome;
        }
        boolean consumed = backHome;
        if (e.getAction() == KeyEvent.ACTION_UP) backHome = false;
        return consumed;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName(), cls = event.getClassName();
        foreground = (pkg == null ? "" : pkg) + "/" + (cls == null ? "" : cls);
    }

    @Override public void onInterrupt() {}
}
