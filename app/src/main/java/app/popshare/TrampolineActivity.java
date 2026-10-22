package app.popshare;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

/**
 * Started by the shell in pop-up (freeform) mode. Starts the real target into this same task
 * and disappears, so the target is what ends up inside the pop-up window.
 */
public class TrampolineActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent target = Launcher.take(getIntent().getStringExtra(Launcher.EXTRA_TOKEN));
        if (target != null) {
            try {
                // No FLAG_ACTIVITY_NEW_TASK: the target must join this floating task.
                startActivity(target);
            } catch (Exception e) {
                Log.w("PopShare/Trampoline", "target launch failed", e);
                CharSequence name = target.getComponent() != null
                        ? target.getComponent().getPackageName() : "app";
                Toast.makeText(this, getString(R.string.launch_failed, name), Toast.LENGTH_SHORT).show();
            }
        }
        finish();
    }
}
