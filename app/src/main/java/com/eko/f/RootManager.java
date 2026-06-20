package com.eko.f;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * RootManager — يدعم نوعين روت:
 *   TYPE_MAGISK : روت عبر Magisk  (su --mount-master أو su)
 *   TYPE_KSU    : روت عبر KernelSU (ksud أو ksu su)
 *
 * طريقة الاستخدام:
 *   RootManager rm = RootManager.getInstance(context);
 *   if (rm.isRooted()) {
 *       RootManager.RootShell shell = rm.openShell();
 *       shell.exec("chmod 777 /dev/bus/usb/001/002");
 *       shell.exec("your_command");
 *       shell.close();
 *   }
 */
public final class RootManager {

    private static final String TAG = "RootManager";

    // ── نوع الروت ──────────────────────────────────────────────────────────────
    public static final int TYPE_NONE   = 0;
    public static final int TYPE_MAGISK = 1;
    public static final int TYPE_KSU    = 2;

    // ── Singleton ──────────────────────────────────────────────────────────────
    private static volatile RootManager sInstance;

    private final Context ctx;
    private int detectedType = -1; // -1 = لم يتم الفحص بعد

    private RootManager(Context context) {
        this.ctx = context.getApplicationContext();
    }

    public static RootManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (RootManager.class) {
                if (sInstance == null) {
                    sInstance = new RootManager(context);
                }
            }
        }
        return sInstance;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // الكشف عن نوع الروت
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * يفحص نوع الروت المتوفر ويُخزّنه في cache.
     * الأولوية: KSU → Magisk → None
     */
    public synchronized int detectRootType() {
        if (detectedType >= 0) return detectedType;

        // 1️⃣ هل العملية نفسها تشتغل بـ UID=0 ؟ (مثلاً لو شُغّل التطبيق من rooted shell)
        if (android.os.Process.myUid() == 0) {
            detectedType = TYPE_MAGISK; // UID=0 → روت حقيقي
            Log.i(TAG, "Root type: UID=0 (system root)");
            return detectedType;
        }

        // 2️⃣ KernelSU: ابحث عن ksud أو /data/adb/ksu/bin/ksuctl
        if (isKsuAvailable()) {
            detectedType = TYPE_KSU;
            Log.i(TAG, "Root type: KernelSU (KSU)");
            return detectedType;
        }

        // 3️⃣ Magisk: ابحث عن su في PATH أو /sbin/su أو /system/bin/su
        if (isMagiskSuAvailable()) {
            detectedType = TYPE_MAGISK;
            Log.i(TAG, "Root type: Magisk (su)");
            return detectedType;
        }

        detectedType = TYPE_NONE;
        Log.w(TAG, "Root type: None");
        return detectedType;
    }

    /** هل الجهاز متروّت بأي طريقة؟ */
    public boolean isRooted() {
        return detectRootType() != TYPE_NONE;
    }

    /** اسم نوع الروت (للعرض في الـ UI) */
    public String getRootTypeName() {
        switch (detectRootType()) {
            case TYPE_KSU:    return "KernelSU";
            case TYPE_MAGISK: return "Magisk";
            default:          return "No Root";
        }
    }

    // ── فحص KSU ───────────────────────────────────────────────────────────────
    private boolean isKsuAvailable() {
        // مسارات KernelSU المعروفة
        String[] ksuPaths = {
            "/data/adb/ksu/bin/ksud",
            "/data/adb/ksu/bin/ksuctl",
            "/system/bin/ksu",
        };
        for (String path : ksuPaths) {
            if (new File(path).canExecute()) {
                Log.d(TAG, "KSU found at: " + path);
                return true;
            }
        }

        // جرّب تشغيل `ksu su -c id` وشوف لو رجع uid=0
        try {
            java.lang.Process p = Runtime.getRuntime().exec(new String[]{"ksu", "su", "-c", "id"});
            String out = readLine(p.getInputStream());
            p.destroy();
            if (out != null && out.contains("uid=0")) return true;
        } catch (Exception ignored) {}

        return false;
    }

    // ── فحص Magisk ────────────────────────────────────────────────────────────
    private boolean isMagiskSuAvailable() {
        // ابحث في PATH
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(":")) {
                if (new File(dir, "su").canExecute()) {
                    Log.d(TAG, "su found in PATH: " + dir);
                    return true;
                }
            }
        }
        // مسارات ثابتة
        String[] suPaths = {
            "/sbin/su", "/system/bin/su", "/system/xbin/su",
            "/su/bin/su", "/magisk/.core/bin/su",
        };
        for (String path : suPaths) {
            if (new File(path).canExecute()) {
                Log.d(TAG, "su found at: " + path);
                return true;
            }
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // تشغيل أوامر بصلاحية روت
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * شغّل أمر واحد بصلاحية روت وانتظر النتيجة.
     * @return exit code (0 = ناجح)
     */
    public int execCommand(String command) {
        try (RootShell shell = openShell()) {
            if (shell == null) return -1;
            return shell.exec(command);
        } catch (Exception e) {
            Log.e(TAG, "execCommand failed: " + command, e);
            return -1;
        }
    }

    /**
     * شغّل أمر وارجع output كـ String.
     * مفيد لـ: chmod، chown، cat /proc/...
     */
    public String execCommandOutput(String command) {
        try (RootShell shell = openShell()) {
            if (shell == null) return null;
            return shell.execWithOutput(command);
        } catch (Exception e) {
            Log.e(TAG, "execCommandOutput failed: " + command, e);
            return null;
        }
    }

    /**
     * افتح shell جديدة — استخدمها في try-with-resources.
     * ترجع null لو مفيش روت.
     */
    public RootShell openShell() {
        int type = detectRootType();
        if (type == TYPE_NONE) {
            Log.w(TAG, "openShell: no root available");
            return null;
        }
        try {
            return new RootShell(type);
        } catch (IOException e) {
            Log.e(TAG, "openShell failed", e);
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RootShell — shell session واحدة
    // ═══════════════════════════════════════════════════════════════════════════

    public static final class RootShell implements AutoCloseable {

        private final java.lang.Process process;
        private final OutputStream stdin;
        private final BufferedReader stdout;
        private final BufferedReader stderr;

        private static final String SENTINEL = "---EKO_DONE---";

        RootShell(int rootType) throws IOException {
            String[] cmd = buildShellCommand(rootType);
            this.process = (java.lang.Process) Runtime.getRuntime().exec(cmd);
            this.stdin   = process.getOutputStream();
            this.stdout  = new BufferedReader(new InputStreamReader(process.getInputStream()));
            this.stderr  = new BufferedReader(new InputStreamReader(process.getErrorStream()));

            // تحقق إن الـ shell شتغلت صح
            send("echo SHELL_OK");
            String line = stdout.readLine();
            if (line == null || !line.contains("SHELL_OK")) {
                close();
                throw new IOException("Root shell did not respond correctly. line=" + line);
            }
        }

        /** بني الأمر المناسب بناءً على نوع الروت */
        private static String[] buildShellCommand(int type) {
            switch (type) {
                case TYPE_KSU:
                    // KSU: ksu su -c sh  أو  ksud su
                    return new String[]{"ksu", "su"};
                case TYPE_MAGISK:
                default:
                    // Magisk: جرّب --mount-master أول (للوصول لـ /dev/bus/usb بدون مشاكل)
                    return new String[]{"su", "--mount-master"};
            }
        }

        /**
         * نفّذ أمر وانتظر انتهاءه.
         * @return exit code الأمر (0 = نجاح)
         */
        public int exec(String command) throws IOException {
            // شيل الأمر وبعدين اطبع exit code متبوعاً بـ SENTINEL
            send(command + "; echo " + SENTINEL + ":$?");

            // اقرأ لحد ما نشوف الـ SENTINEL
            String line;
            int exitCode = 0;
            while ((line = stdout.readLine()) != null) {
                if (line.startsWith(SENTINEL + ":")) {
                    try {
                        exitCode = Integer.parseInt(line.substring(SENTINEL.length() + 1).trim());
                    } catch (NumberFormatException ignored) {}
                    break;
                }
            }
            Log.d("RootShell", "exec [" + command + "] → " + exitCode);
            return exitCode;
        }

        /**
         * نفّذ أمر وارجع أول سطر من الـ output.
         */
        public String execWithOutput(String command) throws IOException {
            send(command + "; echo " + SENTINEL);
            StringBuilder sb = new StringBuilder();
            String line;
            boolean first = true;
            while ((line = stdout.readLine()) != null) {
                if (line.equals(SENTINEL)) break;
                if (!first) sb.append('\n');
                sb.append(line);
                first = false;
            }
            return sb.toString();
        }

        private void send(String cmd) throws IOException {
            stdin.write((cmd + "\n").getBytes("UTF-8"));
            stdin.flush();
        }

        @Override
        public void close() {
            try { stdin.write("exit\n".getBytes()); stdin.flush(); } catch (Exception ignored) {}
            try { process.destroy(); } catch (Exception ignored) {}
        }
    }

    // ── helper ────────────────────────────────────────────────────────────────
    private static String readLine(InputStream is) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            return br.readLine();
        } catch (IOException e) {
            return null;
        }
    }
}
