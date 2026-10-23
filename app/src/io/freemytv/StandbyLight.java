package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps TCL's power light on while the TV is off. With the mic on, TCL's "off" is a screenless mode, not standby,
 * so its standby light never comes on: the light stays as the last key blink left it (off). Turning on TCL's
 * "light while on" setting lights it; it goes back to off when the screen comes on.
 */
final class StandbyLight extends BroadcastReceiver {
    private static final String WORKING = "tcl_sys_lights_led_indicator_working";
    /** The power key's own blink ends with the light off about a second after the screen goes: light it after. */
    private static final long AFTER_BLINK_MS = 1500;

    private final Context app;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();

    private StandbyLight(Context app) { this.app = app; }

    static StandbyLight start(Context c) {
        if (!RemoteKeys.supported()) return null;
        StandbyLight light = new StandbyLight(c.getApplicationContext());
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        c.registerReceiver(light, filter);
        return light;
    }

    void stop(Context c) {
        c.unregisterReceiver(this);
        worker.shutdownNow();
    }

    @Override public void onReceive(Context c, Intent intent) {
        boolean off = Intent.ACTION_SCREEN_OFF.equals(intent.getAction());
        worker.schedule(() -> set(off ? 1 : 0), off ? AFTER_BLINK_MS : 0, TimeUnit.MILLISECONDS);
    }

    /** Third-party apps can't write TCL's settings, so this goes through the TV's own ADB, like the mode switch. */
    private void set(int value) {
        try (Adb adb = Adb.connect(app, 10_000, null)) {
            adb.shell("settings put system " + WORKING + " " + value);
        } catch (IOException e) {
            AppLog.w("standby light: " + e.getMessage());
        }
    }
}
