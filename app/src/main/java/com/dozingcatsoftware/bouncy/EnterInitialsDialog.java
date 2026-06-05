package com.dozingcatsoftware.bouncy;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class EnterInitialsDialog {

    public interface Callback {
        void onInitialsEntered(String initials);
    }

    // L'alphabet disponible : A-Z
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ-".toCharArray();

    // Position courante dans l'alphabet pour chaque case (0=A, 25=Z)
    private static int[] letterIndices = {0, 0, 0};

    // Quelle case est en cours d'édition (0, 1 ou 2)
    private static int activeSlot = 0;

    public static void show(Context context, long score, Callback callback) {
        // Réinitialisation
        letterIndices = new int[]{0, 0, 0};
        activeSlot = 0;

        // ---- Construction de la vue manuellement ----
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);
        root.setBackgroundColor(Color.parseColor("#111111"));

        // Titre
        TextView title = new TextView(context);
        title.setText("NOUVEAU RECORD !");
        title.setTextColor(Color.YELLOW);
        title.setTextSize(20);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        // Score
        TextView scoreDisplay = new TextView(context);
        scoreDisplay.setText(ScoreView.SCORE_FORMAT.format(score));
        scoreDisplay.setTextColor(Color.WHITE);
        scoreDisplay.setTextSize(18);
        scoreDisplay.setTypeface(Typeface.MONOSPACE);
        scoreDisplay.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams scoreParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        scoreParams.topMargin = 8;
        scoreParams.bottomMargin = 24;
        scoreDisplay.setLayoutParams(scoreParams);
        root.addView(scoreDisplay);

        // Instruction
        TextView instruction = new TextView(context);
        instruction.setText("◄ LETTRE ►     ✔ VALIDER");
        instruction.setTextColor(Color.parseColor("#AAAAAA"));
        instruction.setTextSize(13);
        instruction.setTypeface(Typeface.MONOSPACE);
        instruction.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams instrParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        instrParams.bottomMargin = 20;
        instruction.setLayoutParams(instrParams);
        root.addView(instruction);

        // Les 3 cases de lettres
        LinearLayout slotsRow = new LinearLayout(context);
        slotsRow.setOrientation(LinearLayout.HORIZONTAL);
        slotsRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slotsRowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        slotsRowParams.bottomMargin = 28;
        slotsRow.setLayoutParams(slotsRowParams);

        TextView[] slots = new TextView[3];
        for (int i = 0; i < 3; i++) {
            TextView slot = new TextView(context);
            slot.setText("A");
            slot.setTextSize(40);
            slot.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            slot.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams slotParams = new LinearLayout.LayoutParams(80, 100);
            slotParams.setMargins(12, 0, 12, 0);
            slot.setLayoutParams(slotParams);
            slots[i] = slot;
            slotsRow.addView(slot);
        }
        root.addView(slotsRow);

        // Ligne des boutons ◄  ✔  ►
        LinearLayout buttonsRow = new LinearLayout(context);
        buttonsRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonsRow.setGravity(Gravity.CENTER);

        Button btnLeft = new Button(context);
        btnLeft.setText("◄");
        btnLeft.setTextSize(22);
        btnLeft.setTextColor(Color.WHITE);
        btnLeft.setBackgroundColor(Color.parseColor("#333333"));
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnParams.setMargins(8, 0, 8, 0);
        btnLeft.setLayoutParams(btnParams);

        Button btnOk = new Button(context);
        btnOk.setText("✔");
        btnOk.setTextSize(22);
        btnOk.setTextColor(Color.parseColor("#00FF00"));
        btnOk.setBackgroundColor(Color.parseColor("#333333"));
        LinearLayout.LayoutParams btnOkParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnOkParams.setMargins(8, 0, 8, 0);
        btnOk.setLayoutParams(btnOkParams);

        Button btnRight = new Button(context);
        btnRight.setText("►");
        btnRight.setTextSize(22);
        btnRight.setTextColor(Color.WHITE);
        btnRight.setBackgroundColor(Color.parseColor("#333333"));
        LinearLayout.LayoutParams btnRightParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnRightParams.setMargins(8, 0, 8, 0);
        btnRight.setLayoutParams(btnRightParams);

        buttonsRow.addView(btnLeft);
        buttonsRow.addView(btnOk);
        buttonsRow.addView(btnRight);
        root.addView(buttonsRow);

        // ---- Mise à jour de l'affichage ----
        Runnable updateDisplay = new Runnable() {
            @Override public void run() {
                for (int i = 0; i < 3; i++) {
                    slots[i].setText(String.valueOf(ALPHABET[letterIndices[i]]));
                    if (i == activeSlot) {
                        // Case active : vert + souligné
                        slots[i].setTextColor(Color.parseColor("#00FF00"));
                        slots[i].setBackgroundColor(Color.parseColor("#222222"));
                    } else if (i < activeSlot) {
                        // Cases déjà validées : blanc
                        slots[i].setTextColor(Color.WHITE);
                        slots[i].setBackgroundColor(Color.TRANSPARENT);
                    } else {
                        // Cases pas encore atteintes : gris
                        slots[i].setTextColor(Color.parseColor("#555555"));
                        slots[i].setBackgroundColor(Color.TRANSPARENT);
                    }
                }
            }
        };
        updateDisplay.run();

        // ---- Création du dialog ----
        AlertDialog[] dialogHolder = new AlertDialog[1];
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(root)
                .setCancelable(false)
                .create();
        dialogHolder[0] = dialog;

        // ---- Actions des boutons ----

        // ◄ Lettre précédente (Z → A en boucle)
        btnLeft.setOnClickListener(v -> {
            letterIndices[activeSlot] =
                    (letterIndices[activeSlot] - 1 + ALPHABET.length) % ALPHABET.length;
            updateDisplay.run();
        });

        // ► Lettre suivante (Z → A en boucle)
        btnRight.setOnClickListener(v -> {
            letterIndices[activeSlot] =
                    (letterIndices[activeSlot] + 1) % ALPHABET.length;
            updateDisplay.run();
        });

        // ✔ Valider la lettre courante, passer à la suivante ou terminer
        btnOk.setOnClickListener(v -> {
            if (activeSlot < 2) {
                // Passe à la case suivante
                activeSlot++;
                updateDisplay.run();
            } else {
                // Toutes les lettres validées
                String initials = "" +
                        ALPHABET[letterIndices[0]] +
                        ALPHABET[letterIndices[1]] +
                        ALPHABET[letterIndices[2]];
                dialogHolder[0].dismiss();
                callback.onInitialsEntered(initials);
            }
        });

        dialog.show();
    }
}
