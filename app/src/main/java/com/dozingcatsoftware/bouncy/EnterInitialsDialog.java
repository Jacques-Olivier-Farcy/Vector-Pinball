package com.dozingcatsoftware.bouncy;

import android.app.AlertDialog;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

public class EnterInitialsDialog {

    public interface Callback {
        void onInitialsEntered(String initials);
    }

    public static void show(Context context, long score, Callback callback) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_enter_initials, null);

        TextView scoreDisplay = view.findViewById(R.id.scoreDisplay);
        scoreDisplay.setText(ScoreView.SCORE_FORMAT.format(score));

        EditText initial1 = view.findViewById(R.id.initial1);
        EditText initial2 = view.findViewById(R.id.initial2);
        EditText initial3 = view.findViewById(R.id.initial3);

        // Navigation automatique entre les cases
        initial1.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (s.length() == 1) initial2.requestFocus();
            }
        });

        initial2.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (s.length() == 1) initial3.requestFocus();
                if (s.length() == 0) initial1.requestFocus();
            }
        });

        initial3.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (s.length() == 0) initial2.requestFocus();
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(view)
                .setCancelable(false)
                .setPositiveButton("OK", (d, which) -> {
                    String i1 = initial1.getText().toString();
                    String i2 = initial2.getText().toString();
                    String i3 = initial3.getText().toString();
                    String initials = (i1 + i2 + i3).toUpperCase();
                    callback.onInitialsEntered(initials);
                })
                .create();

        dialog.show();

        // Focus et clavier sur la première case
        initial1.requestFocus();
    }
}
