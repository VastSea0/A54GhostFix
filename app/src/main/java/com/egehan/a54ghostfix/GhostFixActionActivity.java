package com.egehan.a54ghostfix;

import android.app.Activity;
import android.os.Bundle;

public class GhostFixActionActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!GhostFixPreferences.sideKeyEnabled(this)) {
            finish();
            return;
        }
        GhostFixer.run(this, (success, message) -> finish());
    }
}
