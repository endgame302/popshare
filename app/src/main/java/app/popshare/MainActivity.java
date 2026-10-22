package app.popshare;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/** Settings: Shizuku status, a test button, and which apps get their own share-sheet entry. */
public class MainActivity extends Activity {
    private static final int REQ_SHIZUKU = 42;

    // One background thread for the whole screen instead of a new one per tap.
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private TextView shizukuStatus;
    private Button grantButton;
    private LinearLayout favList;

    private final Shizuku.OnBinderReceivedListener binderReceived = () -> runOnUiThread(this::refreshShizuku);
    private final Shizuku.OnBinderDeadListener binderDead = () -> runOnUiThread(this::refreshShizuku);
    private final Shizuku.OnRequestPermissionResultListener permResult =
            (code, result) -> runOnUiThread(this::refreshShizuku);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        content.setPadding(pad, pad, pad, pad);
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(scroll);

        int text = getColor(R.color.sheet_text);
        int subtle = getColor(R.color.sheet_subtle);

        TextView title = Ui.text(this, getString(R.string.app_name), 28, text, true);
        title.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 8));
        content.addView(title);

        section(getString(R.string.settings_shizuku));
        shizukuStatus = Ui.text(this, "", 15, text, false);
        content.addView(shizukuStatus);
        grantButton = new Button(this);
        grantButton.setText(R.string.grant);
        grantButton.setOnClickListener(v -> {
            try {
                Shizuku.requestPermission(REQ_SHIZUKU);
            } catch (Throwable ignored) {
                refreshShizuku();
            }
        });
        content.addView(grantButton, wrap());

        section(getString(R.string.settings_test));
        Button test = new Button(this);
        test.setText(R.string.test_button);
        test.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_SEND)
                .setClass(this, ShareActivity.class)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, getString(R.string.test_text))));
        content.addView(test, wrap());

        section(getString(R.string.settings_favorites));
        TextView hint = Ui.text(this, getString(R.string.favorites_hint), 13, subtle, false);
        hint.setPadding(0, 0, 0, Ui.dp(this, 8));
        content.addView(hint);
        favList = new LinearLayout(this);
        favList.setOrientation(LinearLayout.VERTICAL);
        content.addView(favList);

        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permResult);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshShizuku();
        loadFavorites();
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived);
        Shizuku.removeBinderDeadListener(binderDead);
        Shizuku.removeRequestPermissionResultListener(permResult);
        worker.shutdown();
        super.onDestroy();
    }

    private void refreshShizuku() {
        Shell.State s = Shell.state();
        boolean installed = isInstalled("moe.shizuku.privileged.api");
        switch (s) {
            case READY:
                shizukuStatus.setText(R.string.shizuku_ok);
                break;
            case NO_PERMISSION:
                shizukuStatus.setText(R.string.shizuku_no_permission);
                break;
            default:
                shizukuStatus.setText(installed ? R.string.shizuku_not_running : R.string.shizuku_not_installed);
        }
        grantButton.setVisibility(s == Shell.State.NO_PERMISSION ? View.VISIBLE : View.GONE);
    }

    private void loadFavorites() {
        worker.execute(() -> {
            List<Targets.Target> all = Targets.all(getApplicationContext());
            Targets.publishShortcuts(getApplicationContext()); // refresh labels/icons after app updates
            runOnUiThread(() -> showFavorites(all));
        });
    }

    private void showFavorites(List<Targets.Target> all) {
        favList.removeAllViews();
        PackageManager pm = getPackageManager();
        int text = getColor(R.color.sheet_text);
        int subtle = getColor(R.color.sheet_subtle);
        for (Targets.Target t : all) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));

            ImageView icon = new ImageView(this);
            icon.setImageDrawable(t.icon(pm));
            int s = Ui.dp(this, 40);
            row.addView(icon, new LinearLayout.LayoutParams(s, s));

            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 8), 0);
            labels.addView(Ui.text(this, t.appLabel, 16, text, false));
            if (!TextUtils.equals(t.label, t.appLabel)) {
                labels.addView(Ui.text(this, t.label, 13, subtle, false));
            }
            row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView star = Ui.text(this, "", 26, 0, false);
            star.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 4), 0);
            paintStar(star, Targets.isFavorite(this, t));
            row.setOnClickListener(v -> {
                boolean on = !Targets.isFavorite(this, t);
                paintStar(star, on);
                worker.execute(
                        () -> Targets.setFavorite(getApplicationContext(), t, on));
            });
            row.addView(star);
            favList.addView(row);
        }
    }

    private void paintStar(TextView star, boolean on) {
        star.setText(on ? "★" : "☆");
        star.setTextColor(on ? getColor(R.color.star_on) : getColor(R.color.sheet_subtle));
        star.setContentDescription(on ? "Starred" : "Not starred");
    }

    private void section(String name) {
        TextView t = Ui.text(this, name, 13, getColor(R.color.icon_bg), true);
        t.setAllCaps(true);
        t.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 8));
        content.addView(t);
    }

    private LinearLayout.LayoutParams wrap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 8);
        return lp;
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
