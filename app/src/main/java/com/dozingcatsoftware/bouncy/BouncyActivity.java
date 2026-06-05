package com.dozingcatsoftware.bouncy;

import static com.dozingcatsoftware.bouncy.ScoreView.TOUCH_TO_START_MESSAGE;

import java.io.IOException;
import java.io.InputStream;
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

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

public class BouncyActivity extends Activity {

    static {
        Box2D.init();
    }

    CanvasFieldView canvasFieldView;
    ScoreView scoreView;
    View pauseButton;
    GLFieldView glFieldView;
    GL10Renderer gl10Renderer;
    GL20Renderer gl20Renderer;

    // Semi-arbitrary requirement for Android 6.0 or later to use the OpenGL ES 2.0 renderer.
    // Older devices tend to perform better with 1.0.
    final boolean useOpenGL20 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;

    View buttonPanel;
    View highScorePanel;
    View selectTableRow;
    ImageButton nextTableButton;
    ImageButton previousTableButton;
    Button startGameButton;
    Button resumeGameButton;
    Button endGameButton;
    Button aboutButton;
    Button preferencesButton;
    Button quitButton;
    Button showHighScoreButton;
    Button hideHighScoreButton;
    CheckBox unlimitedBallsToggle;
    ViewGroup highScoreListLayout;
    View noHighScoresTextView;
    View topSpacerView;
    View bottomSpacerView;

    final static int ACTIVITY_PREFERENCES = 1;

    Handler handler = new Handler(Looper.myLooper());

    IStringResolver stringLookupFn = (key, params) -> {
        int stringId = getResources().getIdentifier(key, "string", getPackageName());
        return getString(stringId, params);
    };

    final Field field = new Field(System::currentTimeMillis, stringLookupFn, new VPSoundpool.Player());

    int numberOfLevels;
    int currentLevel = 1;

    // MODIFIÉ : List<Long> → List<HighScoreEntry>
    List<HighScoreEntry> highScores;
    Long lastScore = 0L;
    boolean showingHighScores = false;

    static int MAX_NUM_HIGH_SCORES = 5;
    static String HIGHSCORES_PREFS_KEY = "highScores";
    static String LAST_SCORE_PREFS_KEY = "lastScore";
    static String OLD_HIGHSCORE_PREFS_KEY = "highScore";
    static String INITIAL_LEVEL_PREFS_KEY = "initialLevel";

    boolean useZoom = true;
    static final float ZOOM_FACTOR = 1.5f;

    final boolean supportsHapticFeedback = Build.VERSION.SDK_INT >= Build.VERSION_CODES.FROYO;
    boolean useHapticFeedback;

    // Delay after ending a game, before a touch will start a new game.
    static final long END_GAME_DELAY_MS = 1000;
    Long endGameTime = System.currentTimeMillis() - END_GAME_DELAY_MS;

    final FieldViewManager fieldViewManager = new FieldViewManager(field, () -> doStartGame(null));
    final FieldDriver fieldDriver = new FieldDriver(field, fieldViewManager::draw);

    OrientationListener orientationListener;
    BroadcastReceiver powerSaveModeReceiver;
    OnBackInvokedCallback backInvokedCallback;

    private static final String TAG = "BouncyActivity";

    /** Called when the activity is first created. */
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String arch = System.getProperty("os.arch");
        Log.i(TAG, "os.arch: " + arch);
        Log.i(TAG, "API level: " + Build.VERSION.SDK_INT);
        Log.i(TAG, "Target frame rate: " + getMaxFrameRateForDisplay());

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

        scoreView = findViewById(R.id.scoreView);
        scoreView.setField(field);

        highScores = this.highScoresFromPreferencesForCurrentLevel();
        lastScore = this.lastScoreFromPreferencesForCurrentLevel();
        scoreView.setHighScores(highScores);

        buttonPanel = findViewById(R.id.buttonPanel);
        selectTableRow = findViewById(R.id.selectTableRow);
        highScorePanel = findViewById(R.id.highScorePanel);
        nextTableButton = findViewById(R.id.nextTableButton);
        previousTableButton = findViewById(R.id.previousTableButton);
        startGameButton = findViewById(R.id.startGameButton);
        resumeGameButton = findViewById(R.id.resumeGameButton);
        endGameButton = findViewById(R.id.endGameButton);
        aboutButton = findViewById(R.id.aboutButton);
        preferencesButton = findViewById(R.id.preferencesButton);
        quitButton = findViewById(R.id.quitButton);
        unlimitedBallsToggle = findViewById(R.id.unlimitedBallsToggle);
        showHighScoreButton = findViewById(R.id.highScoreButton);
        hideHighScoreButton = findViewById(R.id.hideHighScoreButton);
        highScoreListLayout = findViewById(R.id.highScoreListLayout);
        noHighScoresTextView = findViewById(R.id.noHighScoresTextView);
        pauseButton = findViewById(R.id.pauseIcon);
        topSpacerView = findViewById(R.id.topSpacerView);
        bottomSpacerView = findViewById(R.id.bottomSpacerView);

        List<View> allButtons = Arrays.asList(
                nextTableButton, previousTableButton, startGameButton, resumeGameButton, endGameButton,
                aboutButton, preferencesButton, quitButton, unlimitedBallsToggle, showHighScoreButton, hideHighScoreButton);

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            scoreView.setOnApplyWindowInsetsListener(this::applyWindowInsets);
        }

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            powerSaveModeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    handler.postDelayed(BouncyActivity.this::resetGameFrameRate, 100);
                }
            };
            IntentFilter filter = new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            registerReceiver(powerSaveModeReceiver, filter);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backInvokedCallback = this::doCustomBackAction;
        }

        updateFromPreferences();
    }

    @TargetApi(Build.VERSION_CODES.R)
    WindowInsets applyWindowInsets(View v, WindowInsets windowInsets) {
        Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        ViewGroup.LayoutParams topLayoutParams = topSpacerView.getLayoutParams();
        topLayoutParams.height = insets.top;
        topSpacerView.setLayoutParams(topLayoutParams);
        topSpacerView.setVisibility(View.VISIBLE);

        ViewGroup.LayoutParams bottomLayoutParams = bottomSpacerView.getLayoutParams();
        bottomLayoutParams.height = insets.bottom;
        bottomSpacerView.setLayoutParams(bottomLayoutParams);
        bottomSpacerView.setVisibility(View.VISIBLE);

        return WindowInsets.CONSUMED;
    }

    void enterFullscreenMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // Do nothing
        }
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController insetsController = getWindow().getInsetsController();
            if (insetsController != null) {
                insetsController.hide(WindowInsets.Type.statusBars());
                insetsController.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            final View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(flags);
            decorView.setOnSystemUiVisibilityChangeListener((visibility) -> {
                if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    decorView.setSystemUiVisibility(flags);
                }
            });
        }
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            final View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE);
        }
    }

    private float getMaxFrameRateForDisplay() {
        Display display = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay();
        return Math.max(60, display.getRefreshRate() * 1.01f);
    }

    private boolean isPowerSaveModeEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm.isPowerSaveMode();
        }
        return false;
    }

    private void resetGameFrameRate() {
        float maxGameFrameRate = getMaxFrameRateForDisplay();
        if (isPowerSaveModeEnabled()) {
            maxGameFrameRate = Math.min(60f, maxGameFrameRate);
        }
        fieldDriver.setMaxTargetFrameRate(maxGameFrameRate);
        fieldDriver.resetFrameRate();
    }

    @Override public void onResume() {
        super.onResume();
        enterFullscreenMode();
        resetGameFrameRate();
        updateUiControls();
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
                updateUiControls();
            }
            else {
                unpauseGame();
            }
        }
    }

    private boolean hasCustomBackAction() {
        if (showingHighScores) {
            return true;
        }
        if (field.getGameState().isGameRunning()) {
            return true;
        }
        return false;
    }

    private void doCustomBackAction() {
        if (showingHighScores) {
            hideHighScore(null);
        }
        else if (field.getGameState().isGameRunning()) {
            pauseGame();
        }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && hasCustomBackAction()) {
            doCustomBackAction();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_P) {
            if (field.getGameState().isGameRunning()) {
                pauseGame();
                return true;
            }
        }
        if (!field.getGameState().isGameInProgress() && buttonPanel.getVisibility() == View.VISIBLE) {
            if (FieldViewManager.LEFT_FLIPPER_KEYS.contains(keyCode)) {
                doPreviousTable(null);
                startGameButton.requestFocus();
                return true;
            }
            if (FieldViewManager.RIGHT_FLIPPER_KEYS.contains(keyCode)) {
                doNextTable(null);
                startGameButton.requestFocus();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    public void pauseGame() {
        VPSoundpool.pauseMusic();
        GameState state = field.getGameState();
        if (state.isPaused()) return;
        state.setPaused(true);
        if (orientationListener != null) orientationListener.stop();
        fieldDriver.stop();
        if (glFieldView != null) glFieldView.onPause();
        showingHighScores = false;
        updateUiControls();
    }

    public void unpauseGame() {
        if (!field.getGameState().isPaused()) return;
        field.getGameState().setPaused(false);
        handler.postDelayed(this::tick, 75);
        if (orientationListener != null) orientationListener.start();
        fieldDriver.start();
        if (glFieldView != null) glFieldView.onResume();
        showingHighScores = false;
        updateUiControls();
    }

    private void updateBackCallbackEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (hasCustomBackAction()) {
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_DEFAULT, backInvokedCallback);
            }
            else {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backInvokedCallback);
            }
        }
    }

    void updateUiControls() {
        GameState state = field.getGameState();
        boolean paused = state.isPaused();
        boolean gameInProgress = state.isGameInProgress();
        updateBackCallbackEnabled();

        if (gameInProgress && !paused) {
            buttonPanel.setVisibility(View.GONE);
            highScorePanel.setVisibility(View.GONE);
            pauseButton.setVisibility(View.VISIBLE);
        }
        else if (showingHighScores) {
            buttonPanel.setVisibility(View.GONE);
            highScorePanel.setVisibility(View.VISIBLE);
            hideHighScoreButton.requestFocus();
            pauseButton.setVisibility(View.GONE);
        }
        else if (gameInProgress) {
            buttonPanel.setVisibility(View.VISIBLE);
            highScorePanel.setVisibility(View.GONE);
            selectTableRow.setVisibility(View.GONE);
            unlimitedBallsToggle.setVisibility(View.GONE);
            startGameButton.setVisibility(View.GONE);
            resumeGameButton.setVisibility(View.VISIBLE);
            endGameButton.setVisibility(View.VISIBLE);
            resumeGameButton.requestFocus();
            pauseButton.setVisibility(View.GONE);
        } else {
            buttonPanel.setVisibility(View.VISIBLE);
            highScorePanel.setVisibility(View.GONE);
            selectTableRow.setVisibility(View.VISIBLE);
            unlimitedBallsToggle.setVisibility(View.VISIBLE);
            startGameButton.setVisibility(View.VISIBLE);
            resumeGameButton.setVisibility(View.GONE);
            endGameButton.setVisibility(View.GONE);
            startGameButton.requestFocus();
            pauseButton.setVisibility(View.GONE);
        }
    }

    @Override public void onDestroy() {
        VPSoundpool.cleanup();
        if (powerSaveModeReceiver != null) {
            unregisterReceiver(powerSaveModeReceiver);
        }
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

    private float speedMultiplierForPreferenceValue(String val) {
        if ("fast".equals(val)) return 1.2f;
        if ("slow".equals(val)) return 0.85f;
        return 1.0f;
    }

    void updateFromPreferences() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        fieldViewManager.setIndependentFlippers(prefs.getBoolean("independentFlippers", true));
        scoreView.setShowFps(prefs.getBoolean("showFPS", false));

        int lineWidth = 0;
        try {
            lineWidth = Integer.parseInt(prefs.getString("lineWidth", "0"));
        }
        catch (NumberFormatException ignored) {}
        if (lineWidth != fieldViewManager.getCustomLineWidth()) {
            fieldViewManager.setCustomLineWidth(lineWidth);
        }

        boolean showBallTrails = prefs.getBoolean("showBallTrails", true);
        field.setBallTrailsEnabled(showBallTrails);

        boolean showScoreAnimations = prefs.getBoolean("showScoreAnimations", true);
        field.setScoreAnimationsEnabled(showScoreAnimations);

        boolean useOpenGL = prefs.getBoolean("useOpenGL", true);
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

        String gameSpeed = prefs.getString("gameSpeed", "");
        field.setGameSpeedMultiplier(speedMultiplierForPreferenceValue(gameSpeed));

        VPSoundpool.setSoundEnabled(prefs.getBoolean("sound", true));
        VPSoundpool.setMusicEnabled(prefs.getBoolean("music", true));
        useHapticFeedback = prefs.getBoolean("haptic", false);
    }

    void tick() {
        scoreView.invalidate();
        scoreView.setCurrentFps(fieldDriver.getAverageFps());
        scoreView.setTargetFps(fieldDriver.getTargetFps());
        scoreView.setDebugMessage(field.getDebugMessage());
        updateHighScoreAndButtonPanel();
        handler.postDelayed(this::tick, 100);
    }

    /**
     * If the score of the current or previous game is greater than the previous high score,
     * update high score in preferences and ScoreView. Also show button panel if game has ended.
     */
    void updateHighScoreAndButtonPanel() {
        if (buttonPanel.getVisibility() == View.VISIBLE ||
                highScorePanel.getVisibility() == View.VISIBLE) return;

        synchronized (field) {
            GameState state = field.getGameState();
            if (!field.getGameState().isGameInProgress()) {
                this.endGameTime = System.currentTimeMillis();
                scoreView.gameOverMessageIndex = TOUCH_TO_START_MESSAGE;
                updateUiControls();

                // MODIFIÉ : on demande les initiales si le score est un highscore
                if (!state.hasUnlimitedBalls()) {
                    long score = field.getGameState().getScore();
                    long lowestHighScore = highScores.get(this.highScores.size() - 1).score;
                    if (score > lowestHighScore || highScores.size() < MAX_NUM_HIGH_SCORES) {
                        this.updateLastScoreForCurrentLevel(score);
                        EnterInitialsDialog.show(this, score, (initials) -> {
                            this.updateHighScoreForCurrentLevel(score, initials);
                        });
                    } else {
                        this.updateLastScoreForCurrentLevel(score);
                    }
                }
            }
        }
    }

    String highScorePrefsKeyForLevel(int theLevel) {
        return HIGHSCORES_PREFS_KEY + "." + theLevel;
    }

    String lastScorePrefsKeyForLevel(int theLevel) {
        return LAST_SCORE_PREFS_KEY + "." + theLevel;
    }

    /**
     * MODIFIÉ : retourne une liste de HighScoreEntry au lieu de Long.
     * Rétrocompatible avec l'ancien format (score seul sans initiales).
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

    Long lastScoreFromPreferences(int theLevel) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        return prefs.getLong(lastScorePrefsKeyForLevel(theLevel), 0L);
    }

    // MODIFIÉ : prend une liste de HighScoreEntry
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

    void writeLastScoreToPreferences(int level, long lastScore) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        SharedPreferences.Editor editor = prefs.edit();
        editor.putLong(lastScorePrefsKeyForLevel(level), lastScore);
        editor.commit();
    }

    List<HighScoreEntry> highScoresFromPreferencesForCurrentLevel() {
        return highScoresFromPreferences(currentLevel);
    }

    Long lastScoreFromPreferencesForCurrentLevel() {
        return lastScoreFromPreferences(currentLevel);
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

    private void updateLastScoreForCurrentLevel(long score) {
        this.lastScore = score;
        writeLastScoreToPreferences(currentLevel, score);
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
            if (hasWindowFocus() && buttonPanel.getVisibility() == View.VISIBLE) {
                unpauseGame();
            }
            return;
        }
        if (endGameTime == null || (System.currentTimeMillis() < endGameTime + END_GAME_DELAY_MS)) {
            return;
        }
        if (!field.getGameState().isGameInProgress()) {
            synchronized (field) {
                buttonPanel.setVisibility(View.GONE);
                highScorePanel.setVisibility(View.GONE);
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
            updateUiControls();
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

    public void doQuit(View view) {
        this.finish();
    }

    public void doAbout(View view) {
        gotoAbout();
    }

    public void scoreViewClicked(View view) {
        GameState state = field.getGameState();
        if (state.isGameInProgress() && !state.isPaused()) {
            pauseGame();
        }
        else if (!state.isGameInProgress()) {
            doStartGame(view);
        }
    }

    void switchToTable(int tableNum) {
        this.currentLevel = tableNum;
        synchronized (field) {
            resetFieldForCurrentLevel();
        }
        this.setInitialLevel(currentLevel);
        this.highScores = this.highScoresFromPreferencesForCurrentLevel();
        this.lastScore = this.lastScoreFromPreferencesForCurrentLevel();
        scoreView.setHighScores(highScores);
        fieldDriver.resetFrameRate();
    }

    public void doSwitchTable(View view) {
        doNextTable(view);
    }

    public void doNextTable(View view) {
        int nextTableNum = (currentLevel == numberOfLevels) ? 1 : currentLevel + 1;
        switchToTable(nextTableNum);
    }

    public void doPreviousTable(View view) {
        int prevTableNum = (currentLevel == 1) ? numberOfLevels : currentLevel - 1;
        switchToTable(prevTableNum);
    }

    void resetFieldForCurrentLevel() {
        field.resetForLayoutMap(FieldLayoutReader.layoutMapForLevel(this, currentLevel));
    }

    public void showHighScore(View view) {
        this.fillHighScoreAdapter();
        showingHighScores = true;
        updateUiControls();
    }

    public void hideHighScore(View view) {
        showingHighScores = false;
        updateUiControls();
    }

    // MODIFIÉ : affiche "AAA   123 456" au lieu du score seul
    private void fillHighScoreAdapter() {
        LinearLayout.LayoutParams params = null;
        this.highScoreListLayout.removeAllViews();
        for (int index = 0; index < this.highScores.size(); index++) {
            HighScoreEntry entry = this.highScores.get(index);
            if (entry.isValid()) {
                if (params == null) {
                    params = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
                    params.bottomMargin = (int)(16 * getResources().getDisplayMetrics().scaledDensity);
                }
                String scoreText = entry.initials + "   " + ScoreView.SCORE_FORMAT.format(entry.score);
                TextView scoreItem = this.createHighScoreTextView(scoreText, params);
                this.highScoreListLayout.addView(scoreItem);
            }
        }
        if (this.lastScore > 0 && params != null) {
            String lastScoreText = this.getString(R.string.last_score_message, ScoreView.SCORE_FORMAT.format(this.lastScore));
            TextView lastScoreItem = this.createHighScoreTextView(lastScoreText, params);
            this.highScoreListLayout.addView(lastScoreItem);
        }
        this.noHighScoresTextView.setVisibility(
                this.highScoreListLayout.getChildCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private TextView createHighScoreTextView(String scoreText, LinearLayout.LayoutParams params) {
        TextView scoreItem = new TextView(this);
        scoreItem.setLayoutParams(params);
        scoreItem.setText(scoreText);
        scoreItem.setTextSize(22);
        scoreItem.setTextColor(Color.argb(255, 240, 240, 240));
        scoreItem.setGravity(Gravity.END);
        return scoreItem;
    }
}
