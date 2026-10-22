package app.popshare;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Turns an incoming share into an intent for the chosen app, and opens it in a pop-up.
 * See {@link #launch} for the two pop-up routes (direct, and via {@link TrampolineActivity}).
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

    /**
     * Runs on a background thread.
     *
     * Two pop-up routes, tried in order:
     * 1. Direct: the shell starts the target app itself in freeform. The pop-up window then
     *    belongs to the target, so when it moves on (e.g. WhatsApp: contact picker → chat) the
     *    next screen stays in the pop-up. Same command as the manual tests. Used for text and
     *    single-file shares; the file is readable because prepare() already granted it to the
     *    target package directly.
     * 2. Trampoline: needed when the share can't be expressed as `am` arguments (several files,
     *    or anything that isn't text). The window belongs to PopShare's invisible activity, so a
     *    target that later opens a screen in its own task may leave the pop-up.
     */
    static Outcome launch(Context ctx, Intent out, boolean wantPopup) {
        if (wantPopup && Shell.state() == Shell.State.READY) {
            String[] direct = directCommand(out);
            if (direct != null) {
                Shell.Result r = Shell.run(direct);
                if (r.ok()) return Outcome.POPUP;
                Log.w(TAG, "direct pop-up launch failed, trying trampoline: " + r.output);
            }

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

    /** Extras that matter to the receiving app and that `am` can carry as plain strings. */
    private static final Set<String> STRING_EXTRAS = new HashSet<>(Arrays.asList(
            Intent.EXTRA_TEXT, Intent.EXTRA_SUBJECT, Intent.EXTRA_TITLE, Intent.EXTRA_HTML_TEXT));

    /**
     * Builds `am start` arguments that recreate this intent exactly, or returns null if it can't.
     * The arguments go to the process as an argv array (no shell parsing), so text with quotes,
     * spaces or newlines is passed through unchanged.
     */
    @SuppressWarnings("deprecation") // Bundle.get(): we really do want "whatever type it is"
    static String[] directCommand(Intent out) {
        if (out.getComponent() == null) return null;
        if (!Intent.ACTION_SEND.equals(out.getAction())) return null; // a Uri list has no `am` form

        List<String> cmd = new ArrayList<>(Arrays.asList(
                "am", "start", "--user", "current", "--windowingMode", "5",
                "-n", out.getComponent().flattenToShortString(),
                "-a", Intent.ACTION_SEND));
        if (out.getType() != null) {
            cmd.add("-t");
            cmd.add(out.getType());
        }
        Bundle extras = out.getExtras();
        if (extras != null) {
            for (String key : extras.keySet()) {
                Object v = extras.get(key);
                if (v == null) continue;
                if (Intent.EXTRA_STREAM.equals(key) && v instanceof Uri) {
                    cmd.add("--eu");
                    cmd.add(key);
                    cmd.add(v.toString());
                } else if (STRING_EXTRAS.contains(key) && v instanceof CharSequence) {
                    // Rich text (spans) is flattened to plain text here; fine for sharing.
                    cmd.add("--es");
                    cmd.add(key);
                    cmd.add(v.toString());
                } else if (Intent.EXTRA_STREAM.equals(key) || STRING_EXTRAS.contains(key)) {
                    return null; // a content extra in a form `am` can't express: use the trampoline
                }
                // Other extras are the source app's own bookkeeping (referrers, chooser hints);
                // receiving apps ignore them, so they're dropped rather than blocking this route.
            }
        }
        return cmd.toArray(new String[0]);
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
