package app.popshare;

import android.content.pm.PackageManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/** Runs shell commands with ADB-level rights through Shizuku. */
final class Shell {
    private static final String TAG = "PopShare/Shell";

    private Shell() {}

    enum State { NOT_RUNNING, NO_PERMISSION, READY }

    static State state() {
        try {
            if (!Shizuku.pingBinder()) return State.NOT_RUNNING;
            if (Shizuku.isPreV11()) return State.NOT_RUNNING;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                    ? State.READY : State.NO_PERMISSION;
        } catch (Throwable t) {
            return State.NOT_RUNNING;
        }
    }

    static final class Result {
        final int exitCode;
        final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        /** `am start` reports most failures on stdout with exit code 0, so check the text too. */
        boolean ok() {
            return exitCode == 0 && !output.contains("Error") && !output.contains("Exception");
        }
    }

    /**
     * Shizuku 13 keeps newProcess() private in favour of bound "user services", but it is still
     * present and is the lightest way to run a single short command. Called by reflection.
     */
    static Result run(String... cmd) {
        try {
            Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            Process p = (Process) m.invoke(null, cmd, null, null);
            // Drain stderr on its own thread so a chatty stderr can't block stdout (or vice versa).
            final String[] err = {""};
            Thread errReader = new Thread(() -> {
                try {
                    err[0] = read(p.getErrorStream());
                } catch (Exception ignored) {
                }
            }, "shell-stderr");
            errReader.start();

            // Shizuku's remote process doesn't support Process.waitFor(timeout): it throws
            // "process hasn't exited" instead of waiting. So use the plain blocking waitFor()
            // and enforce the timeout with a watchdog that destroys a hung process.
            final boolean[] timedOut = {false};
            Thread watchdog = new Thread(() -> {
                try {
                    Thread.sleep(10_000);
                    timedOut[0] = true;
                    p.destroy();
                } catch (InterruptedException ignored) {
                }
            }, "shell-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();

            String out = read(p.getInputStream());
            int code = p.waitFor();
            watchdog.interrupt();
            errReader.join(1000);
            if (timedOut[0]) return new Result(-1, out + err[0] + "\n(timed out)");
            return new Result(code, out + err[0]);
        } catch (Throwable t) {
            Log.w(TAG, "shell command failed", t);
            return new Result(-1, String.valueOf(t));
        }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }
}
