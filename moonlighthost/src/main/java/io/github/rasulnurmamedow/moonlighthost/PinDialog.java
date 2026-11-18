package io.github.rasulnurmamedow.moonlighthost;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;

/** Default {@link PinProvider}: a simple dialog on the given activity. */
public final class PinDialog implements PinProvider {
    private final Activity activity;

    public PinDialog(Activity activity) {
        this.activity = activity;
    }

    @Override
    public void requestPin(String deviceName, PinCallback callback) {
        activity.runOnUiThread(() -> {
            if (activity.isFinishing()) { callback.onPin(null); return; }
            EditText input = new EditText(activity);
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
            input.setGravity(Gravity.CENTER);
            input.setTextSize(28);
            input.setHint("0000");
            final boolean[] answered = {false};
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.moonlighthost_pin_title, deviceName))
                    .setMessage(R.string.moonlighthost_pin_message)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        answered[0] = true;
                        callback.onPin(input.getText().toString().trim());
                    })
                    .setNegativeButton(android.R.string.cancel, (d, w) -> {
                        answered[0] = true;
                        callback.onPin(null);
                    })
                    .setOnDismissListener(d -> { if (!answered[0]) callback.onPin(null); })
                    .create();
            dialog.show();
            input.requestFocus();
        });
    }
}
