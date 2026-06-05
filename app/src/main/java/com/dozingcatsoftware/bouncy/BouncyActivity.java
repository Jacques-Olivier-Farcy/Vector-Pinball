package com.dozingcatsoftware.bouncy;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.badlogic.gdx.physics.box2d.Box2D;
import com.dozingcatsoftware.vectorpinball.model.IStringResolver;
import com.dozingcatsoftware.vectorpinball.util.IOUtils;
import com.dozingcatsoftware.vectorpinball.model.Field;
import com.dozingcatsoftware.vectorpinball.model.FieldDriver;
import com.dozingcatsoftware.vectorpinball.model.GameState;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;

public class BouncyActivity extends Activity {

    static {
        Box2D.init();
    }

    CanvasFieldView canvasFieldView;
    ScoreView scoreView;

    GLFieldView glFieldView;
    GL10Renderer gl10Renderer;
    GL20Renderer gl20Renderer;
    // Semi-arbitrary requirement for Android 6.0 or later to use the OpenGL ES 2.0 renderer.
    // Older devices tend to perform better with 1.0.
    final boolean useOpenGL20 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;

    View buttonPanel;
    Button startGameButton;
    Button resumeGameButton;
    Button endGameButton;
    Button switchTableButton;
    Button aboutButton;
    Button preferencesButton;
    CheckBox unlimitedBallsToggle;
    final static int ACTIVITY_PREFERENCES = 1;

    Handler handler = new Handler(Looper.myLooper());

    IStringResolver stringLookupFn = (key, params) -> {
        int stringId = getResources().getIdentifier(key, "string", getPackageName());
        return getString(stringId, params);
    };
    Field field = new Field(System::currentTimeMillis, stringLookupFn, new VPSoundpool.Player());

    int numberOfLevels;
    int currentLevel = 1;

    // MODIFIÉ : List<Long> → List<HighScoreEntry>
    List<HighScoreEntry> highScores;

    static int MAX_NUM_HIGH_SCORES = 5;
    static String HIGHSCORES_PREFS_KEY = "highScores";
    static String OLD_HIGHSCORE_PREFS_KEY = "highScore";
    static String INITIAL_LEVEL_PREFS_KEY = "initialLevel";

    boolean useZoom = true;
    static final float ZOOM_FACTOR = 1.5f;

    final boolean supportsHapticFeedback = Build.VERSION.SDK_INT >= Build.VERSION_CODES.FROYO;
    boolean useHapticFeedback;

    // Delay after ending a game, before a touch will start a new game.
    static final long END_GAME_DELAY_MS = 1000;
    Long endGameTime = System.currentTimeMillis() - END_GAME_DELAY_MS;

    FieldDriver fieldDriver = new FieldDriver();
    FieldViewManager fieldViewManager = new FieldViewManager();
    OrientationListener orientationListener;

    private static final String TAG = "BouncyActivity";

    /** Called when the activity is first created. */
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String arch = System.getProperty("os.arch");
        Log.i(TAG, "App started, os.arch=" + arch);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.main);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            this.getWindow().setNavigationBarColor(Color.BLACK);
        }

        this.numberOfLevels = FieldLayoutReader.getNumberOfLevels(this);
        this.currentLevel = getInitialLevel();
        resetFieldForCurrentLevel();

        canvasFieldView = findViewById(R.id.canvasFieldView);
        canvasFieldView.setManager(fieldViewManager);

        glFieldView = findViewById(R.id.glFieldView);
        if (useOpenGL20) {
            gl20Renderer = new GL20Renderer(glFieldView, (shaderPath) -> {
                try {
                    InputStream input = getAssets().open(shaderPath);
                    return IOUtils.utf8FromStream(input);
                }
                catch(IOException ex) {
                    throw new RuntimeException(ex);
                }
            });
            gl20Renderer.setManager(fieldViewManager);
        }
        else {
            gl10Renderer = new GL10Renderer(glFieldView);
            gl10Renderer.setManager(fieldViewManager);
        }

        fieldViewManager.setField(field);
        fieldViewManager.setStartGameAction(() -> doStartGame(null));

        scoreView = findViewById(R.id.scoreView);
        scoreView.setField(field);

        fieldDriver.setField(field);
        fieldDriver.setDrawFunction(fieldViewManager::draw);

        highScores = this.highScoresFromPreferencesForCurrentLevel();
        scoreView.setHighScores(highScores);

        buttonPanel = findViewById(R.id.buttonPanel);
        startGameButton = findViewById(R.id.startGameButton);
        resumeGameButton = findViewById(R.id.resumeGameButton);
        endGameButton = findViewById(R.id.endGameButton);
        switchTableButton = findViewById(R.id.switchTableButton);
        aboutButton = findViewById(R.id.aboutButton);
        preferencesButton = findViewById(R.id.preferencesButton);
        unlimitedBallsToggle = findViewById(R.id.unlimitedBallsToggle);

        List<View> allButtons = Arrays.asList(
                startGameButton, resumeGameButton, endGameButton, switchTableButton,
                aboutButton, preferencesButton, unlimitedBallsToggle);
        for (View button : allButtons) {
            button.setOnTouchListener((view, motionEvent) -> {
                if (motionEvent.getAction() == MotionEvent.ACTION_UP) {
                    Rect r = new Rect();
                    view.getLocalVisibleRect(r);
                    if (r.contains((int)motionEvent.getX(), (int)motionEvent.getY())) {
                        view.requestFocus();
                        view.performClick();
                        return true;
                    }
                }
                return false;
            });
        }

        updateFromPreferences();

        // Initialize audio, loading resources in a separate thread.
        VPSoundpool.initSounds(this);
        (new Thread(VPSoundpool::loadSounds)).start();
        VPSoundpool.hapticFn = () -> {
            if (supportsHapticFeedback && useHapticFeedback) {
                scoreView.performHapticFeedback(
                        HapticFeedbackConstants.KEYBOARD_TAP,
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            }
        };
        this.setVolumeControlStream(AudioManager.STREAM_MUSIC);
    }

    @Override public void onResume() {
        super.onResume();
        try {
            Method setUiMethod = View.class.getMethod("setSystemUiVisibility", int.class);
            setUiMethod.invoke(scoreView, 1);
        }
        catch (Exception ignored) {
        }
        fieldDriver.resetFrameRate();
        updateButtons();
    }

    @Override public void onPause() {
        pauseGame();
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) {
            pauseGame();
        }
        else {
            if (field.getGameState().isGameInProgress()) {
                if (glFieldView != null) {
                    glFieldView.onResume();
                }
                fieldViewManager.draw();
                updateButtons();
            }
            else {
                unpauseGame();
            }
        }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (field.getGameState().isGameInProgress() && !field.getGameState().isPaused()) {
                pauseGame();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    public void pauseGame() {
        VPSoundpool.pauseMusic();
        GameState state = field.getGameState();
        if (!state.isGameInProgress()) return;
        if (state.isPaused()) return;
        state.setPaused(true);

        if (orientationListener != null) orientationListener.stop();
        fieldDriver.stop();
        if (glFieldView != null) glFieldView.onPause();

        updateButtons();
    }

    public void unpauseGame() {
        if (!field.getGameState().isPaused()) return;
        field.getGameState().setPaused(false);

        handler.postDelayed(this::tick, 75);
        if (orientationListener != null) orientationListener.start();

        fieldDriver.start();
        if (glFieldView != null) glFieldView.onResume();

        updateButtons();
    }

    void updateButtons() {
        GameState state = field.getGameState();
        if (state.isPaused()) {
            buttonPanel.setVisibility(View.VISIBLE);
            startGameButton.setVisibility(View.GONE);
            resumeGameButton.setVisibility(View.VISIBLE);
            endGameButton.setVisibility(View.VISIBLE);
            switchTableButton.setVisibility(View.GONE);
            unlimitedBallsToggle.setVisibility(View.GONE);
            resumeGameButton.requestFocus();
        }
        else {
            if (state.isGameInProgress()) {
                buttonPanel.setVisibility(View.GONE);
            }
            else {
                buttonPanel.setVisibility(View.VISIBLE);
                startGameButton.setVisibility(View.VISIBLE);
                resumeGameButton.setVisibility(View.GONE);
                endGameButton.setVisibility(View.GONE);
                switchTableButton.setVisibility(View.VISIBLE);
                unlimitedBallsToggle.setVisibility(View.VISIBLE);
                startGameButton.requestFocus();
            }
        }
    }

    @Override public void onDestroy() {
        VPSoundpool.cleanup();
        super.onDestroy();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);

        switch (requestCode) {
            case ACTIVITY_PREFERENCES:
                updateFromPreferences();
                break;
        }
    }

    void gotoPreferences() {
        Intent settingsActivity = new Intent(getBaseContext(), BouncyPreferences.class);
        startActivityForResult(settingsActivity, ACTIVITY_PREFERENCES);
    }

    void gotoAbout() {
        AboutActivity.startForLevel(this, this.currentLevel);
    }

    void updateFromPreferences() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        fieldViewManager.setIndependentFlippers(prefs.getBoolean("independentFlippers", true));
        scoreView.setShowFPS(prefs.getBoolean("showFPS", false));

        int lineWidth = 0;
        try {
            lineWidth = Integer.parseInt(prefs.getString("lineWidth", "0"));
        }
        catch (NumberFormatException ignored) {}
        if (lineWidth != fieldViewManager.getCustomLineWidth()) {
            fieldViewManager.setCustomLineWidth(lineWidth);
        }

        boolean useOpenGL = prefs.getBoolean("useOpenGL", false);
        if (useOpenGL) {
            if (glFieldView.getVisibility() != View.VISIBLE) {
                canvasFieldView.setVisibility(View.GONE);
                glFieldView.setVisibility(View.VISIBLE);
                fieldViewManager.setFieldRenderer(useOpenGL20 ? gl20Renderer : gl10Renderer);
            }
        }
        else {
            if (canvasFieldView.getVisibility() != View.VISIBLE) {
                glFieldView.setVisibility(View.GONE);
                canvasFieldView.setVisibility(View.VISIBLE);
                fieldViewManager.setFieldRenderer(canvasFieldView);
            }
        }

        useZoom = prefs.getBoolean("zoom", true);
        fieldViewManager.setZoom(useZoom ? ZOOM_FACTOR : 1.0f);

        VPSoundpool.setSoundEnabled(prefs.getBoolean("sound", true));
        VPSoundpool.setMusicEnabled(prefs.getBoolean("music", true));
        useHapticFeedback = prefs.getBoolean("haptic", false);
    }

    void tick() {
        scoreView.invalidate();
        scoreView.setFPS(fieldDriver.getAverageFPS());
        scoreView.setDebugMessage(field.getDebugMessage());
        updateHighScoreAndButtonPanel();
        handler.postDelayed(this::tick, 100);
    }

    /**
     * Si la partie est terminée, vérifie si le score est un highscore.
     * Si oui, affiche le dialog de saisie des initiales.
     */
    void updateHighScoreAndButtonPanel() {
        if (buttonPanel.getVisibility() == View.VISIBLE) return;
        synchronized (field) {
            GameState state = field.getGameState();
            if (!field.getGameState().isGameInProgress()) {
                this.endGameTime = System.currentTimeMillis();
                updateButtons();

                // MODIFIÉ : on demande les initiales si le score est un highscore
                if (!state.hasUnlimitedBalls()) {
                    long score = field.getGameState().getScore();
                    long lowestHighScore = highScores.get(this.highScores.size() - 1).score;
                    if (score > lowestHighScore || highScores.size() < MAX_NUM_HIGH_SCORES) {
                        EnterInitialsDialog.show(this, score, (initials) -> {
                            this.updateHighScoreForCurrentLevel(score, initials);
                        });
                    }
                }
            }
        }
    }

    String highScorePrefsKeyForLevel(int theLevel) {
        return HIGHSCORES_PREFS_KEY + "." + theLevel;
    }

    /**
     * MODIFIÉ : retourne une List<HighScoreEntry> rétrocompatible avec l'ancien format.
     */
    List<HighScoreEntry> highScoresFromPreferences(int theLevel) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        String scoresAsString = prefs.getString(highScorePrefsKeyForLevel(theLevel), "");
        if (scoresAsString.length() > 0) {
            try {
                String[] fields = scoresAsString.split(",");
                List<HighScoreEntry> scores = new ArrayList<>();
                for (String f : fields) {
                    scores.add(HighScoreEntry.deserialize(f));
                }
                return scores;
            }
            catch (Exception ex) {
                return Collections.singletonList(new HighScoreEntry("AAA", 0L));
            }
        }
        else {
            // Rétrocompatibilité avec l'ancien format pré-1.5
            long oldPrefsScore = prefs.getLong(OLD_HIGHSCORE_PREFS_KEY + "." + currentLevel, 0);
            return Collections.singletonList(new HighScoreEntry("AAA", oldPrefsScore));
        }
    }

    // MODIFIÉ : sérialise des HighScoreEntry
    void writeHighScoresToPreferences(int level, List<HighScoreEntry> scores) {
        StringBuilder scoresAsString = new StringBuilder();
        scoresAsString.append(scores.get(0).serialize());
        for (int i = 1; i < scores.size(); i++) {
            scoresAsString.append(",").append(scores.get(i).serialize());
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(highScorePrefsKeyForLevel(level), scoresAsString.toString());
        editor.commit();
    }

    List<HighScoreEntry> highScoresFromPreferencesForCurrentLevel() {
        return highScoresFromPreferences(currentLevel);
    }

    // MODIFIÉ : prend les initiales en paramètre
    void updateHighScore(int theLevel, long score, String initials) {
        List<HighScoreEntry> newHighScores = new ArrayList<>(this.highScores);
        newHighScores.add(new HighScoreEntry(initials, score));
        Collections.sort(newHighScores, (a, b) -> Long.compare(b.score, a.score));
        if (newHighScores.size() > MAX_NUM_HIGH_SCORES) {
            newHighScores = newHighScores.subList(0, MAX_NUM_HIGH_SCORES);
        }
        this.highScores = newHighScores;
        writeHighScoresToPreferences(theLevel, this.highScores);
        scoreView.setHighScores(this.highScores);
    }

    // MODIFIÉ : prend les initiales en paramètre
    void updateHighScoreForCurrentLevel(long score, String initials) {
        updateHighScore(currentLevel, score, initials);
    }

    int getInitialLevel() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        int startLevel = prefs.getInt(INITIAL_LEVEL_PREFS_KEY, 1);
        if (startLevel < 1 || startLevel > numberOfLevels) startLevel = 1;
        return startLevel;
    }

    void setInitialLevel(int level) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt(INITIAL_LEVEL_PREFS_KEY, level);
        editor.commit();
    }

    public void doStartGame(View view) {
        if (field.getGameState().isPaused()) {
            unpauseGame();
            return;
        }
        if (endGameTime == null || (System.currentTimeMillis() < endGameTime + END_GAME_DELAY_MS)) {
            return;
        }
        if (!field.getGameState().isGameInProgress()) {
            synchronized (field) {
                buttonPanel.setVisibility(View.GONE);
                resetFieldForCurrentLevel();

                if (unlimitedBallsToggle.isChecked()) {
                    field.startGameWithUnlimitedBalls();
                }
                else {
                    field.startGame();
                }
            }
            VPSoundpool.playStart();
            endGameTime = null;
        }
    }

    public void doEndGame(View view) {
        unpauseGame();
        synchronized (field) {
            field.endGame();
        }
    }

    public void doPreferences(View view) {
        gotoPreferences();
    }

    public void doAbout(View view) {
        gotoAbout();
    }

    public void scoreViewClicked(View view) {
        if (field.getGameState().isGameInProgress()) {
            if (field.getGameState().isPaused()) {
                unpauseGame();
            }
            else {
                pauseGame();
            }
        }
        else {
            doStartGame(null);
        }
    }

    public void doSwitchTable(View view) {
        currentLevel = (currentLevel == numberOfLevels) ? 1 : currentLevel + 1;
        synchronized (field) {
            resetFieldForCurrentLevel();
        }
        this.setInitialLevel(currentLevel);
        this.highScores = this.highScoresFromPreferencesForCurrentLevel();
        scoreView.setHighScores(highScores);
        fieldDriver.resetFrameRate();
    }

    void resetFieldForCurrentLevel() {
        field.resetForLayoutMap(FieldLayoutReader.layoutMapForLevel(this, currentLevel));
    }
}
