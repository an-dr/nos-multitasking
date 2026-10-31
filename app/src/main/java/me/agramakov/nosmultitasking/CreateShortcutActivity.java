package me.agramakov.nosmultitasking;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Bundle;

/**
 * Answers the legacy CREATE_SHORTCUT request that apps such as Kea, MacroDroid
 * and some launchers use to list "app shortcuts".
 */
public abstract class CreateShortcutActivity extends Activity {
    protected abstract String action();

    protected abstract String label();

    protected abstract int iconRes();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent target = new Intent(this, ActionActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .putExtra("action", action());
        Intent result = new Intent()
                .putExtra(Intent.EXTRA_SHORTCUT_INTENT, target)
                .putExtra(Intent.EXTRA_SHORTCUT_NAME, label())
                .putExtra(Intent.EXTRA_SHORTCUT_ICON, icon());
        setResult(RESULT_OK, result);
        finish();
    }

    private Bitmap icon() {
        int size = 192;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF000000);
        c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        Drawable d = getDrawable(iconRes());
        int inset = size / 4;
        d.setBounds(inset, inset, size - inset, size - inset);
        d.draw(c);
        return bmp;
    }

    public static class Split extends CreateShortcutActivity {
        protected String action() { return MultitaskService.ACTION_SPLIT; }
        protected String label() { return "Split screen"; }
        protected int iconRes() { return R.drawable.ic_split; }
    }

    public static class Popup extends CreateShortcutActivity {
        protected String action() { return MultitaskService.ACTION_POPUP; }
        protected String label() { return "Pop-up view"; }
        protected int iconRes() { return R.drawable.ic_popup; }
    }
}
