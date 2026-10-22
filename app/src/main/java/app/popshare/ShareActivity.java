package app.popshare;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.IntentCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Receives shares. Either straight from a per-app direct-share entry (opens that app at once),
 * or from the "Pop-up share" entry (shows a small picker first).
 */
public class ShareActivity extends Activity {
    private static final int COLUMNS = 4;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Intent incoming;
    private LinearLayout sheet;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        incoming = getIntent();
        String action = incoming.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            finish();
            return;
        }

        buildSheet();

        // Direct-share entry: the system tells us which shortcut was tapped.
        ComponentName direct = Targets.fromShortcutId(incoming.getStringExtra(Intent.EXTRA_SHORTCUT_ID));
        if (direct != null) {
            Targets.Target t = Targets.find(this, incoming, direct);
            if (t != null) {
                open(t, true);
                return;
            }
            // That app can't take this kind of content (e.g. a PDF to a text-only app): let the user pick.
        }
        showPicker();
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    // ---- Layout ----

    private void buildSheet() {
        FrameLayout root = new FrameLayout(this);
        root.setOnClickListener(v -> {
            if (!busy) finish();
        });

        sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClickable(true); // taps inside don't dismiss
        int pad = Ui.dp(this, 20);
        sheet.setPadding(pad, Ui.dp(this, 10), pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getColor(R.color.sheet_bg));
        float r = Ui.dp(this, 28);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(bg);

        // Edge-to-edge: keep content above the navigation bar.
        sheet.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(pad, Ui.dp(this, 10), pad, pad + bars.bottom);
            return insets;
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        int maxWidth = Ui.dp(this, 560); // tablets / foldables: don't stretch across the screen
        if (getResources().getDisplayMetrics().widthPixels > maxWidth) {
            lp.width = maxWidth;
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        }
        root.addView(sheet, lp);
        setContentView(root);
    }

    private void showPicker() {
        sheet.removeAllViews();
        int text = getColor(R.color.sheet_text);
        int subtle = getColor(R.color.sheet_subtle);

        View handle = new View(this);
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(subtle);
        hb.setCornerRadius(Ui.dp(this, 2));
        handle.setBackground(hb);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = Ui.dp(this, 14);
        sheet.addView(handle, hlp);

        sheet.addView(Ui.text(this, getString(R.string.sheet_title), 20, text, true));
        String preview = preview();
        if (preview != null) {
            TextView pv = Ui.text(this, preview, 13, subtle, false);
            pv.setMaxLines(2);
            pv.setEllipsize(TextUtils.TruncateAt.END);
            pv.setPadding(0, Ui.dp(this, 4), 0, 0);
            sheet.addView(pv);
        }

        List<Targets.Target> targets = Targets.forShare(this, incoming);
        if (targets.isEmpty()) {
            TextView none = Ui.text(this, getString(R.string.no_targets), 15, text, false);
            none.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 24));
            sheet.addView(none);
            return;
        }

        GridView grid = new GridView(this);
        grid.setNumColumns(COLUMNS);
        grid.setVerticalSpacing(Ui.dp(this, 8));
        grid.setSelector(android.R.color.transparent);
        grid.setAdapter(new TargetAdapter(targets));
        grid.setOnItemClickListener((p, v, pos, id) -> open(targets.get(pos), true));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            open(targets.get(pos), false);
            return true;
        });
        int rows = (targets.size() + COLUMNS - 1) / COLUMNS;
        int rowH = Ui.dp(this, 112); // 52dp icon + two lines of label + padding + spacing
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.min(rows * rowH, maxH));
        glp.topMargin = Ui.dp(this, 16);
        sheet.addView(grid, glp);

        TextView hint = Ui.text(this, getString(R.string.sheet_hint), 12, subtle, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Ui.dp(this, 12), 0, 0);
        sheet.addView(hint);
    }

    private void showProgress() {
        sheet.removeAllViews();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 20));
        ProgressBar pb = new ProgressBar(this);
        row.addView(pb, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));
        TextView t = Ui.text(this, getString(R.string.preparing), 15, getColor(R.color.sheet_text), false);
        t.setPadding(Ui.dp(this, 16), 0, 0, 0);
        row.addView(t);
        sheet.addView(row);
    }

    private String preview() {
        CharSequence txt = incoming.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (Intent.ACTION_SEND_MULTIPLE.equals(incoming.getAction())) {
            ArrayList<?> many = IntentCompat.getParcelableArrayListExtra(
                    incoming, Intent.EXTRA_STREAM, android.net.Uri.class);
            if (many != null && !many.isEmpty()) return many.size() + " items";
        }
        if (!TextUtils.isEmpty(txt)) return txt.toString().replace('\n', ' ');
        if (Launcher.hasStreams(incoming)) return incoming.getType() != null ? "1 item · " + incoming.getType() : "1 item";
        return null;
    }

    // ---- Launch ----

    private void open(Targets.Target t, boolean popup) {
        if (busy) return;
        busy = true;
        if (Launcher.hasStreams(incoming)) showProgress();
        else sheet.setVisibility(View.INVISIBLE);
        Targets.recordUse(this, t.component);

        worker.execute(() -> {
            Intent out = Launcher.prepare(getApplicationContext(), incoming, t.component);
            Launcher.Outcome o = Launcher.launch(this, out, popup);
            runOnUiThread(() -> {
                if (o == Launcher.Outcome.FULLSCREEN_FALLBACK) {
                    Toast.makeText(this, R.string.fallback_toast, Toast.LENGTH_SHORT).show();
                } else if (o == Launcher.Outcome.FAILED) {
                    Toast.makeText(this, getString(R.string.launch_failed, t.label), Toast.LENGTH_SHORT).show();
                }
                finish();
            });
        });
    }

    private final class TargetAdapter extends BaseAdapter {
        private final List<Targets.Target> items;
        private final PackageManager pm = getPackageManager();

        TargetAdapter(List<Targets.Target> items) {
            this.items = items;
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            LinearLayout cell = (LinearLayout) convert;
            if (cell == null) {
                cell = new LinearLayout(ShareActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(0, Ui.dp(ShareActivity.this, 6), 0, Ui.dp(ShareActivity.this, 6));
                ImageView icon = new ImageView(ShareActivity.this);
                int s = Ui.dp(ShareActivity.this, 52);
                cell.addView(icon, new LinearLayout.LayoutParams(s, s));
                TextView label = Ui.text(ShareActivity.this, "", 12, getColor(R.color.sheet_text), false);
                label.setGravity(Gravity.CENTER);
                label.setMaxLines(2);
                label.setEllipsize(TextUtils.TruncateAt.END);
                label.setPadding(0, Ui.dp(ShareActivity.this, 6), 0, 0);
                cell.addView(label);
            }
            Targets.Target t = items.get(i);
            ((ImageView) cell.getChildAt(0)).setImageDrawable(t.icon(pm));
            ((TextView) cell.getChildAt(1)).setText(t.label);
            return cell;
        }
    }
}
