package io.github.luyzfernando08.sleeptimer;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Puts the TV to sleep at once, with no screen of its own: the cleanyourtv launcher's Control Center opens it for
 * its Power tile. Ends a running timer too, as "Turn off now" does.
 */
public class SleepNowActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!Timer.sleepNow(this)) {
            Toast.makeText(this, R.string.no_service, Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
