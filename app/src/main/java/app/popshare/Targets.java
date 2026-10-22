package app.popshare;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Which apps can receive a share, the user's favourites, and the direct-share shortcuts. */
final class Targets {
    static final String SHORTCUT_CATEGORY = "app.popshare.category.POPUP_TARGET";
    static final String SHORTCUT_PREFIX = "fav:";
    private static final String PREFS = "targets";
    private static final String KEY_FAVS = "favorites";

    private Targets() {}

    static final class Target {
        final ComponentName component;
        final CharSequence label;
        final CharSequence appLabel;
        final ResolveInfo info;

        Target(PackageManager pm, ResolveInfo ri) {
            ActivityInfo ai = ri.activityInfo;
            component = new ComponentName(ai.packageName, ai.name);
            label = ri.loadLabel(pm);
            appLabel = ai.applicationInfo.loadLabel(pm);
            info = ri;
        }

        Drawable icon(PackageManager pm) {
            return info.loadIcon(pm);
        }

        String key() {
            return component.flattenToShortString();
        }
    }

    /** Apps that can receive this exact share, favourites first, then most used, then A–Z. */
    static List<Target> forShare(Context ctx, Intent incoming) {
        String type = incoming.getType() != null ? incoming.getType() : "*/*";
        List<Target> list = query(ctx, incoming.getAction(), type);
        sort(ctx, list);
        return list;
    }

    /** Everything that accepts some kind of share, for the favourites screen. */
    static List<Target> all(Context ctx) {
        Map<String, Target> byKey = new LinkedHashMap<>();
        for (String type : new String[]{"*/*", "text/plain", "image/*", "video/*", "application/pdf"}) {
            for (Target t : query(ctx, Intent.ACTION_SEND, type)) byKey.putIfAbsent(t.key(), t);
        }
        List<Target> list = new ArrayList<>(byKey.values());
        sort(ctx, list);
        return list;
    }

    @SuppressWarnings("deprecation") // int-flags overload is fine down to API 30
    private static List<Target> query(Context ctx, String action, String type) {
        PackageManager pm = ctx.getPackageManager();
        Intent q = new Intent(action == null ? Intent.ACTION_SEND : action).setType(type);
        Map<String, Target> byKey = new LinkedHashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            if (ri.activityInfo == null || ctx.getPackageName().equals(ri.activityInfo.packageName)) continue;
            Target t = new Target(pm, ri);
            byKey.putIfAbsent(t.key(), t); // some apps list the same activity twice
        }
        return new ArrayList<>(byKey.values());
    }

    static Target find(Context ctx, Intent incoming, ComponentName c) {
        for (Target t : forShare(ctx, incoming)) if (t.component.equals(c)) return t;
        return null;
    }

    private static void sort(Context ctx, List<Target> list) {
        SharedPreferences p = prefs(ctx);
        List<String> favs = new ArrayList<>(favorites(ctx));
        Collections.sort(list, (a, b) -> {
            int fa = favs.indexOf(a.key()), fb = favs.indexOf(b.key());
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            int ua = p.getInt("use:" + a.key(), 0), ub = p.getInt("use:" + b.key(), 0);
            if (ua != ub) return Integer.compare(ub, ua);
            return a.label.toString().compareToIgnoreCase(b.label.toString());
        });
    }

    static void recordUse(Context ctx, ComponentName c) {
        SharedPreferences p = prefs(ctx);
        String k = "use:" + c.flattenToShortString();
        p.edit().putInt(k, p.getInt(k, 0) + 1).apply();
    }

    // ---- Favourites ----

    static Set<String> favorites(Context ctx) {
        // Stored as one newline-separated string so the order the user starred them is kept.
        String raw = prefs(ctx).getString(KEY_FAVS, "");
        Set<String> out = new LinkedHashSet<>();
        for (String s : raw.split("\n")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    static boolean isFavorite(Context ctx, Target t) {
        return favorites(ctx).contains(t.key());
    }

    static void setFavorite(Context ctx, Target t, boolean on) {
        Set<String> favs = favorites(ctx);
        if (on) favs.add(t.key());
        else favs.remove(t.key());
        prefs(ctx).edit().putString(KEY_FAVS, String.join("\n", favs)).apply();
        publishShortcuts(ctx);
    }

    // ---- Direct-share shortcuts (one per favourite) ----

    static void publishShortcuts(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, Target> installed = new LinkedHashMap<>();
        for (Target t : all(ctx)) installed.put(t.key(), t);

        int max = Math.max(1, ShortcutManagerCompat.getMaxShortcutCountPerActivity(ctx));
        List<ShortcutInfoCompat> shortcuts = new ArrayList<>();
        int rank = 0;
        for (String key : favorites(ctx)) {
            Target t = installed.get(key);
            if (t == null) continue; // uninstalled since it was starred
            if (shortcuts.size() >= max) break;
            Intent open = new Intent(Intent.ACTION_VIEW).setClass(ctx, MainActivity.class);
            shortcuts.add(new ShortcutInfoCompat.Builder(ctx, SHORTCUT_PREFIX + key)
                    .setShortLabel(t.appLabel)
                    .setLongLabel(t.appLabel + " (pop-up)")
                    .setIcon(IconCompat.createWithBitmap(toBitmap(t.icon(pm))))
                    .setIntent(open)
                    .setCategories(Collections.singleton(SHORTCUT_CATEGORY))
                    .setLongLived(true)
                    .setRank(rank++)
                    .build());
        }
        try {
            ShortcutManagerCompat.setDynamicShortcuts(ctx, shortcuts);
        } catch (Exception ignored) {
            // Rate-limited or rejected by the launcher; the picker still works.
        }
    }

    static ComponentName fromShortcutId(String id) {
        if (id == null || !id.startsWith(SHORTCUT_PREFIX)) return null;
        return ComponentName.unflattenFromString(id.substring(SHORTCUT_PREFIX.length()));
    }

    private static Bitmap toBitmap(Drawable d) {
        int size = 192;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return b;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
