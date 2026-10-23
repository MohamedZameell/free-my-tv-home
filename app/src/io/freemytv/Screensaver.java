package io.freemytv;

import android.service.dreams.DreamService;

/**
 * The Free My TV screensaver (enabled in Free My TV mode via `settings put secure screensaver_components`): the
 * weather (WeatherView). The system still decides when it starts and when the TV turns off (e.g. after 10 min idle,
 * then 2 h later), as before: the app doesn't change those timings. Any key exits.
 */
public class Screensaver extends DreamService {
    private WeatherView view;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Watchdog.install(this);
        Ui.init(this);
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        view = new WeatherView(this);
        setContentView(view);
        getWindow().setBackgroundDrawable(null);   // the view paints every pixel
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        view.start();
    }

    @Override public void onDreamingStopped() {
        view.stop();
        super.onDreamingStopped();
    }

    @Override public void onDetachedFromWindow() {
        if (view != null) view.stop();
        super.onDetachedFromWindow();
    }
}
