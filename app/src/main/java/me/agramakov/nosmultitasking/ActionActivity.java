package me.agramakov.nosmultitasking;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/** Invisible activity behind launcher shortcuts: forwards the action and finishes at once. */
public class ActionActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String action = getIntent().getStringExtra("action");
        if (!MultitaskService.run(action == null ? MultitaskService.ACTION_SPLIT : action)) {
            Toast.makeText(this, "Enable the accessibility service first", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        finish();
    }
}
