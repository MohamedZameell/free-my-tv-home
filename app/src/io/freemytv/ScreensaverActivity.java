package io.freemytv;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Back on the home screen starts the screensaver at once, like tvOS. The system's own "start dreaming" needs root on
 * this TCL (and its Daydream shortcut does nothing), so this shows the same screensaver (the weather) as a page of the
 * home screen. Any key goes back home. It lets the TV sleep at its usual time: it keeps the screen on while it shows,
 * so it closes after the TV's sleep timer and the system then turns the TV off as it would have.
 */
public class ScreensaverActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WeatherView view;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Watchdog.install(this);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        getWindow().setBackgroundDrawable(null);   // the view paints every pixel
        view = new WeatherView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        view.start();
        long sleep = Settings.Secure.getLong(getContentResolver(), "sleep_timeout", -1);
        if (sleep > 0) handler.postDelayed(this::finish, sleep);
    }

    @Override protected void onPause() {
        handler.removeCallbacksAndMessages(null);
        view.stop();
        super.onPause();
        finish();   // never left behind the home screen or an app
    }

    /** Any key but the volume (the first press only wakes, as on tvOS: it doesn't also act on the home screen). */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
