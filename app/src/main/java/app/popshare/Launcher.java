package app.popshare;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.core.content.FileProvider;
import androidx.core.content.IntentCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Turns an incoming share into an intent for the chosen app, and opens it in a pop-up.
 *
 * How the pop-up works: Shizuku runs `am start --windowingMode 5` on our own
 * {@link TrampolineActivity}, which creates a new task in freeform (Samsung pop-up view).
 * The trampoline then starts the chosen app *without* FLAG_ACTIVITY_NEW_TASK, so it joins
 * that same floating task. Because the trampoline belongs to this app, all extras, ClipData
 * and file grants pass through untouched — the shell never handles the shared content.
 */
final class Launcher {
    private static final String TAG = "PopShare/Launcher";
    static final String EXTRA_TOKEN = "app.popshare.TOKEN";
    private static final long CACHE_MAX_AGE_MS = 24L * 60 * 60 * 1000;

    private Launcher() {}

    // ---- Hand-off between ShareActivity and TrampolineActivity (same process, a few ms apart) ----

    private static final class Pending {
        final Intent intent;
        final long createdAt = SystemClock.elapsedRealtime();

        Pending(Intent intent) {
            this.intent = intent;
        }
    }

    private static final Map<String, Pending> PENDING = new HashMap<>();

    private static synchronized String park(Intent intent) {
        // Drop anything stale (e.g. a launch that failed half-way).
        long now = SystemClock.elapsedRealtime();
        for (Iterator<Pending> it = PENDING.values().iterator(); it.hasNext(); ) {
            if (now - it.next().createdAt > 60_000) it.remove();
        }
        String token = UUID.randomUUID().toString();
        PENDING.put(token, new Pending(intent));
        return token;
    }

    static synchronized Intent take(String token) {
        if (token == null) return null;
        Pending p = PENDING.remove(token);
        return p == null ? null : p.intent;
    }

    // ---- Building the outgoing intent ----

    /** True if the share carries files that will need copying (so the UI can show progress). */
    static boolean hasStreams(Intent incoming) {
        return !streams(incoming).isEmpty();
    }

    /** Runs on a background thread: may copy shared files. */
    static Intent prepare(Context ctx, Intent incoming, ComponentName target) {
        Intent out = new Intent(incoming.getAction());
        out.setComponent(target);
        out.setType(incoming.getType());
        if (incoming.getExtras() != null) out.putExtras(incoming.getExtras());
        out.removeExtra(Intent.EXTRA_SHORTCUT_ID);
        out.removeExtra(EXTRA_TOKEN);

        ArrayList<Uri> in = streams(incoming);
        if (!in.isEmpty()) {
            cleanCache(ctx);
            ArrayList<Uri> mine = new ArrayList<>();
            for (Uri u : in) mine.add(copyToOwnProvider(ctx, u));

            if (Intent.ACTION_SEND_MULTIPLE.equals(incoming.getAction())) {
                out.putParcelableArrayListExtra(Intent.EXTRA_STREAM, mine);
            } else {
                out.putExtra(Intent.EXTRA_STREAM, mine.get(0));
            }
            ContentResolver cr = ctx.getContentResolver();
            ClipData clip = null;
            for (Uri u : mine) {
                if (clip == null) clip = ClipData.newUri(cr, "shared", u);
                else clip.addItem(new ClipData.Item(u));
                // Belt and braces: an explicit grant survives even if the target re-launches itself.
                ctx.grantUriPermission(target.getPackageName(), u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            out.setClipData(clip);
            out.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        return out;
    }

    private static ArrayList<Uri> streams(Intent incoming) {
        ArrayList<Uri> list = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(incoming.getAction())) {
            ArrayList<Uri> many = IntentCompat.getParcelableArrayListExtra(incoming, Intent.EXTRA_STREAM, Uri.class);
            if (many != null) for (Uri u : many) if (u != null) list.add(u);
        } else {
            Uri one = IntentCompat.getParcelableExtra(incoming, Intent.EXTRA_STREAM, Uri.class);
            if (one != null) list.add(one);
        }
        return list;
    }

    /**
     * The read grant the source app gave us belongs to our share activity and ends when it
     * closes, so a pass-through URI could stop working inside the target. A copy under our own
     * FileProvider can be granted to the target for as long as it needs.
     */
    private static Uri copyToOwnProvider(Context ctx, Uri src) {
        if (!ContentResolver.SCHEME_CONTENT.equals(src.getScheme())) return src;
        ContentResolver cr = ctx.getContentResolver();
        String name = displayName(cr, src);
        String mime = cr.getType(src);
        if (mime != null && name.indexOf('.') < 0) {
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (ext != null) name = name + "." + ext;
        }
        File dir = new File(new File(ctx.getCacheDir(), "shared"), UUID.randomUUID().toString());
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File dst = new File(dir, name);
        try (InputStream is = cr.openInputStream(src); OutputStream os = new FileOutputStream(dst)) {
            if (is == null) return src;
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        } catch (Exception e) {
            Log.w(TAG, "copy failed, passing original uri", e);
            return src;
        }
        return FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".files", dst);
    }

    private static String displayName(ContentResolver cr, Uri uri) {
        String name = null;
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {
        }
        if (TextUtils.isEmpty(name)) name = uri.getLastPathSegment();
        if (TextUtils.isEmpty(name)) name = "shared";
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        return name.length() > 120 ? name.substring(name.length() - 120) : name;
    }

    private static void cleanCache(Context ctx) {
        File root = new File(ctx.getCacheDir(), "shared");
        File[] dirs = root.listFiles();
        if (dirs == null) return;
        long cutoff = System.currentTimeMillis() - CACHE_MAX_AGE_MS;
        for (File d : dirs) {
            if (d.lastModified() < cutoff) deleteRecursive(d);
        }
    }

    private static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ---- Launching ----

    /** Outcome reported back to the UI. */
    enum Outcome { POPUP, FULLSCREEN_BY_CHOICE, FULLSCREEN_FALLBACK, FAILED }

    /** Runs on a background thread. */
    static Outcome launch(Context ctx, Intent out, boolean wantPopup) {
        if (wantPopup && Shell.state() == Shell.State.READY) {
            String token = park(out);
            String trampoline = ctx.getPackageName() + "/" + TrampolineActivity.class.getName();
            Shell.Result r = Shell.run("am", "start",
                    "--user", "current",
                    "--windowingMode", "5",          // WINDOWING_MODE_FREEFORM = Samsung pop-up view
                    "--activity-multiple-task",      // a fresh pop-up every time, never reuse an old one
                    "-n", trampoline,
                    "--es", EXTRA_TOKEN, token);
            if (r.ok()) return Outcome.POPUP;
            take(token);
            Log.w(TAG, "pop-up launch failed: " + r.output);
            return startFullscreen(ctx, out) ? Outcome.FULLSCREEN_FALLBACK : Outcome.FAILED;
        }
        boolean ok = startFullscreen(ctx, out);
        if (!ok) return Outcome.FAILED;
        return wantPopup ? Outcome.FULLSCREEN_FALLBACK : Outcome.FULLSCREEN_BY_CHOICE;
    }

    private static boolean startFullscreen(Context ctx, Intent out) {
        try {
            ctx.startActivity(new Intent(out).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "full-screen launch failed", e);
            return false;
        }
    }
}
