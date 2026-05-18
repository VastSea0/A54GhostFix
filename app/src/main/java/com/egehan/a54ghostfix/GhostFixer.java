package com.egehan.a54ghostfix;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

final class GhostFixer {
    interface Callback {
        void onResult(boolean success, String message);
    }

    static final int REQUEST_CODE_SHIZUKU = 5401;

    private static final String TAG = "A54GhostFix";
    private static final String RESET_COMMAND =
            "service call SemInputDeviceManagerService 30 i32 1 i32 1 s16 module_off_master; " +
            "sleep 2; " +
            "service call SemInputDeviceManagerService 30 i32 1 i32 1 s16 module_on_master";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private GhostFixer() {
    }

    static boolean isShizukuReady() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean hasPermission() {
        if (!isShizukuReady()) {
            return false;
        }
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void requestPermission() {
        if (!isShizukuReady()) {
            return;
        }
        Shizuku.requestPermission(REQUEST_CODE_SHIZUKU);
    }

    static void run(Context context, Callback callback) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            if (!isShizukuReady()) {
                post(callback, false, appContext.getString(R.string.result_shizuku_missing));
                toast(appContext, appContext.getString(R.string.toast_shizuku_missing));
                return;
            }
            if (!hasPermission()) {
                post(callback, false, appContext.getString(R.string.result_permission_needed));
                toast(appContext, appContext.getString(R.string.toast_permission_needed));
                return;
            }

            Process process = null;
            try {
                process = newShizukuShellProcess(RESET_COMMAND);
                int exitCode = process.waitFor();
                String output = readAll(process.getInputStream()).trim();
                String error = readAll(process.getErrorStream()).trim();
                Log.i(TAG, "exit=" + exitCode + " output=" + output + " error=" + error);

                boolean success = exitCode == 0 && output.contains("004b004f");
                if (success) {
                    post(callback, true, appContext.getString(R.string.result_success));
                    toast(appContext, appContext.getString(R.string.toast_fix_sent));
                } else {
                    String message = error.isEmpty()
                            ? appContext.getString(R.string.result_unexpected_shell, output)
                            : error;
                    post(callback, false, message);
                    toast(appContext, appContext.getString(R.string.toast_fix_failed));
                }
            } catch (Throwable throwable) {
                Log.e(TAG, "Ghost fix failed", throwable);
                post(callback, false, throwable.getMessage() == null ? throwable.toString() : throwable.getMessage());
                toast(appContext, appContext.getString(R.string.toast_fix_failed));
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }
        });
    }

    private static Process newShizukuShellProcess(String command) throws Exception {
        Method newProcess = Shizuku.class.getDeclaredMethod(
                "newProcess",
                String[].class,
                String[].class,
                String.class
        );
        newProcess.setAccessible(true);
        return (Process) newProcess.invoke(
                null,
                new Object[]{new String[]{"sh", "-c", command}, null, null}
        );
    }

    private static void post(Callback callback, boolean success, String message) {
        if (callback == null) {
            return;
        }
        MAIN.post(() -> callback.onResult(success, message));
    }

    private static void toast(Context context, String message) {
        MAIN.post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }

    private static String readAll(InputStream stream) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }
}
