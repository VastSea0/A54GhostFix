package com.egehan.a54ghostfix;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private TextView statusText;
    private MaterialButton permissionButton;
    private MaterialButton fixButton;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::refresh;
    private final Shizuku.OnBinderDeadListener binderDeadListener = this::refresh;
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
            (requestCode, grantResult) -> refresh();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);
        permissionButton = findViewById(R.id.permission_button);
        fixButton = findViewById(R.id.fix_button);

        permissionButton.setOnClickListener(view -> GhostFixer.requestPermission());
        fixButton.setOnClickListener(view -> runFix());
    }

    @Override
    protected void onResume() {
        super.onResume();
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);
        refresh();
    }

    @Override
    protected void onPause() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        super.onPause();
    }

    private void refresh() {
        boolean ready = GhostFixer.isShizukuReady();
        boolean permitted = GhostFixer.hasPermission();

        if (!ready) {
            statusText.setText(R.string.status_shizuku_missing);
        } else if (!permitted) {
            statusText.setText(R.string.status_permission_needed);
        } else {
            statusText.setText(R.string.status_ready);
        }

        permissionButton.setVisibility(ready && !permitted ? View.VISIBLE : View.GONE);
        fixButton.setEnabled(ready && permitted);
    }

    private void runFix() {
        fixButton.setEnabled(false);
        statusText.setText(R.string.fixing);
        GhostFixer.run(this, (success, message) -> {
            statusText.setText(message);
            fixButton.setEnabled(GhostFixer.isShizukuReady() && GhostFixer.hasPermission());
        });
    }
}
