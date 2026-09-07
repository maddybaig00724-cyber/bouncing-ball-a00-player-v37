package com.bouncingball.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final String PREFS = "bounce_a00";
    private static final String DEFAULT_SERVER = "http://10.0.2.2:3000";

    private GameView game;
    private String playerId;
    private String serverUrl;
    private boolean soundEnabled = true;
    private boolean musicEnabled = true;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        playerId = prefs.getString("playerId", null);
        if (playerId == null) {
            playerId = "P" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            prefs.edit().putString("playerId", playerId).apply();
        }
        serverUrl = prefs.getString("serverUrl", DEFAULT_SERVER);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        game = new GameView(this);
        root.addView(game, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(8, 4, 8, 4);

        Button menu = new Button(this);
        menu.setText("☰ MENU");
        bar.addView(menu, new LinearLayout.LayoutParams(-1, 56));
        root.addView(bar);

        menu.setOnClickListener(v -> showMainMenu());
        setContentView(root);
        game.refreshWallet();
    }

    private void showMainMenu() {
        String[] items = {
                "🔊 Sound Control",
                "🎵 Music Control",
                "🎁 Rewards",
                "🎡 Spin",
                "💰 Wallet / Withdraw",
                "⚙ Backend Settings"
        };

        new AlertDialog.Builder(this)
                .setTitle("BOUNCING BALL A00")
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            soundEnabled = !soundEnabled;
                            toast("Sound: " + (soundEnabled ? "ON" : "OFF"));
                            break;
                        case 1:
                            musicEnabled = !musicEnabled;
                            toast("Music: " + (musicEnabled ? "ON" : "OFF"));
                            break;
                        case 2:
                            showRewards();
                            break;
                        case 3:
                            showSpin();
                            break;
                        case 4:
                            showWallet();
                            break;
                        case 5:
                            showServerSettings();
                            break;
                    }
                }).show();
    }

    private void showRewards() {
        new AlertDialog.Builder(this)
                .setTitle("REWARDS")
                .setMessage(
                        "Score: " + game.serverScore +
                        "\nAvailable score: " + game.availableScore +
                        "\n\nReward conversion is controlled by the server. No guaranteed earnings are promised."
                )
                .setPositiveButton("OK", null)
                .show();
    }

    private void showSpin() {
        new AlertDialog.Builder(this)
                .setTitle("SPIN")
                .setMessage(
                        "Spin rewards are disabled until the server authorizes a spin session. " +
                        "This prevents client-side reward manipulation."
                )
                .setPositiveButton("OK", null)
                .show();
    }

    private void showWallet() {
        new AlertDialog.Builder(this)
                .setTitle("PLAYER WALLET")
                .setMessage(
                        "Player ID: " + playerId +
                        "\nScore: " + game.serverScore +
                        "\nAvailable: " + game.availableScore +
                        "\n\nWithdrawals are currently disabled in this build. A production payout system " +
                        "requires authenticated server-side wallet transactions, eligibility checks, fraud review " +
                        "and compliant payment integration."
                )
                .setPositiveButton("OK", null)
                .show();
    }

    private void showServerSettings() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Backend URL");
        input.setText(serverUrl);

        new AlertDialog.Builder(this)
                .setTitle("BACKEND SETTINGS")
                .setMessage(
                        "Emulator default: http://10.0.2.2:3000\n" +
                        "For a physical phone use your server LAN/HTTPS address."
                )
                .setView(input)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SAVE & SYNC", (d, w) -> {
                    String value = input.getText().toString().trim().replaceAll("/$", "");
                    if (!value.isEmpty()) {
                        serverUrl = value;
                        getSharedPreferences(PREFS, MODE_PRIVATE)
                                .edit()
                                .putString("serverUrl", serverUrl)
                                .apply();
                        game.refreshWallet();
                    }
                }).show();
    }

    private void showGameOver() {
        new AlertDialog.Builder(this)
                .setTitle("GAME OVER")
                .setMessage(
                        "Level reached: " + game.level +
                        "\nServer score: " + game.serverScore +
                        "\n\nReady for another run?"
                )
                .setCancelable(false)
                .setNegativeButton("EXIT", (d, w) -> finish())
                .setPositiveButton("PLAY AGAIN", (d, w) -> game.restartRun())
                .show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private class GameView extends android.view.View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path starPath = new Path();

        private float bx = 80, by = 700;
        private float vx = 0, vy = 0;
        private float cameraX = 0;

        private boolean left;
        private boolean right;
        private boolean jumpPressed;
        private boolean gameOver;

        private int lives = 3;
        private int level = 1;

        private int serverScore = 0;
        private int availableScore = 0;

        // Starting speed remains 7f. Speed rings temporarily boost movement only.
        private static final float BASE_SPEED = 7f;
        private static final float BOOST_SPEED = 12f;
        private float speed = BASE_SPEED;
        private long speedBoostUntil = 0L;

        private final ArrayList<RectF> platforms = new ArrayList<>();
        private final ArrayList<RectF> rings = new ArrayList<>();
        private final ArrayList<Integer> ringTypes = new ArrayList<>();
        private final ArrayList<RectF> gifts = new ArrayList<>();

        private boolean[] ringTaken;
        private boolean[] giftTaken;

        private long lastFrame = System.nanoTime();
        private boolean levelReported = false;

        GameView(Context context) {
            super(context);
            paint.setTypeface(Typeface.create("sans", Typeface.BOLD));
            setFocusable(true);
            buildLevel();
        }

        private void buildLevel() {
            platforms.clear();
            rings.clear();
            ringTypes.clear();
            gifts.clear();

            /*
             * Longer world levels. The camera follows the player, so these
             * objects are real level/world coordinates rather than screen UI.
             */
            platforms.add(new RectF(0, 760, 720, 900));
            platforms.add(new RectF(860, 650, 1130, 680));
            platforms.add(new RectF(1260, 540, 1510, 570));
            platforms.add(new RectF(1650, 650, 1900, 680));
            platforms.add(new RectF(2040, 500, 2320, 530));
            platforms.add(new RectF(2470, 620, 2720, 650));
            platforms.add(new RectF(2870, 450, 3150, 480));
            platforms.add(new RectF(3300, 590, 3650, 620));

            // 0 = speed, 1 = jump, 2 = star.
            addRing(650, 700, 0);
            addRing(1000, 590, 1);
            addRing(1390, 480, 2);
            addRing(1800, 590, 0);
            addRing(2180, 440, 1);
            addRing(2600, 560, 2);
            addRing(3010, 390, 0);
            addRing(3440, 500, 2);

            gifts.add(new RectF(1080, 580, 1120, 620));
            gifts.add(new RectF(2260, 430, 2300, 470));
            gifts.add(new RectF(3110, 380, 3150, 420));

            ringTaken = new boolean[rings.size()];
            giftTaken = new boolean[gifts.size()];

            bx = 80;
            by = 700;
            vx = 0;
            vy = 0;
            cameraX = 0;
            speed = BASE_SPEED;
            speedBoostUntil = 0L;
            doubleJumpAvailable = true;
            levelReported = false;
            gameOver = false;
        }

        private void addRing(float x, float y, int type) {
            rings.add(new RectF(x - 28, y - 28, x + 28, y + 28));
            ringTypes.add(type);
        }

        private boolean doubleJumpAvailable = true;

        @Override
        protected void onDraw(Canvas canvas) {
            tick();

            canvas.drawColor(Color.rgb(7, 17, 31));

            canvas.save();
            canvas.translate(-cameraX, 0);

            drawWorld(canvas);

            canvas.restore();
            drawHud(canvas);
        }

        private void drawWorld(Canvas canvas) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(18, 34, 56));

            for (RectF r : platforms) {
                canvas.drawRect(r, paint);
            }

            // Platform highlights.
            paint.setColor(Color.rgb(34, 62, 88));
            for (RectF r : platforms) {
                canvas.drawRect(r.left, r.top, r.right, r.top + 6, paint);
            }

            for (int i = 0; i < rings.size(); i++) {
                if (!ringTaken[i]) drawRing(canvas, rings.get(i), ringTypes.get(i));
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.MAGENTA);
            for (int i = 0; i < gifts.size(); i++) {
                if (!giftTaken[i]) {
                    RectF g = gifts.get(i);
                    canvas.drawRect(g, paint);
                    paint.setColor(Color.WHITE);
                    paint.setTextSize(13);
                    canvas.drawText("GIFT", g.left - 2, g.top - 7, paint);
                    paint.setColor(Color.MAGENTA);
                }
            }

            // Player.
            paint.setColor(Color.rgb(255, 209, 102));
            canvas.drawCircle(bx, by, 24, paint);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(bx - 8, by - 6, 4, paint);
            canvas.drawCircle(bx + 8, by - 6, 4, paint);

            // Level finish marker.
            float finishX = getLevelFinishX();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(6);
            paint.setColor(Color.WHITE);
            canvas.drawLine(finishX, 250, finishX, 760, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.YELLOW);
            canvas.drawText("FINISH", finishX - 28, 235, paint);
        }

        private void drawRing(Canvas canvas, RectF r, int type) {
            float cx = r.centerX();
            float cy = r.centerY();

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(8);

            if (type == 0) {
                // BLUE SPEED RING
                paint.setColor(Color.CYAN);
                canvas.drawCircle(cx, cy, 25, paint);
                paint.setStrokeWidth(4);
                canvas.drawLine(cx - 15, cy, cx + 12, cy, paint);
                canvas.drawLine(cx + 12, cy, cx + 4, cy - 8, paint);
                canvas.drawLine(cx + 12, cy, cx + 4, cy + 8, paint);
            } else if (type == 1) {
                // GREEN HIGH-JUMP RING
                paint.setColor(Color.GREEN);
                canvas.drawCircle(cx, cy, 25, paint);
                paint.setStrokeWidth(4);
                canvas.drawLine(cx, cy + 12, cx, cy - 12, paint);
                canvas.drawLine(cx, cy - 12, cx - 9, cy - 2, paint);
                canvas.drawLine(cx, cy - 12, cx + 9, cy - 2, paint);
            } else {
                // YELLOW STAR RING
                paint.setColor(Color.YELLOW);
                canvas.drawCircle(cx, cy, 27, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.YELLOW);
                drawStar(canvas, cx, cy, 15, 7);
            }

            paint.setStyle(Paint.Style.FILL);
        }

        private void drawStar(Canvas canvas, float cx, float cy, float outer, float inner) {
            starPath.reset();

            for (int i = 0; i < 10; i++) {
                double angle = -Math.PI / 2 + i * Math.PI / 5;
                float radius = (i % 2 == 0) ? outer : inner;
                float x = cx + (float) Math.cos(angle) * radius;
                float y = cy + (float) Math.sin(angle) * radius;

                if (i == 0) starPath.moveTo(x, y);
                else starPath.lineTo(x, y);
            }

            starPath.close();
            canvas.drawPath(starPath, paint);
        }

        private void drawHud(Canvas canvas) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            paint.setTextSize(25);
            canvas.drawText("⭐ SCORE: " + serverScore, 20, 38, paint);
            canvas.drawText("❤️ LIVES: " + lives, 20, 70, paint);
            canvas.drawText("LEVEL " + level, 20, 102, paint);

            paint.setTextSize(15);
            paint.setColor(Color.LTGRAY);
            canvas.drawText("ID: " + playerId, 20, 128, paint);

            if (speedBoostUntil > System.currentTimeMillis()) {
                paint.setColor(Color.CYAN);
                canvas.drawText("⚡ SPEED BOOST", 20, 151, paint);
            } else {
                paint.setColor(Color.LTGRAY);
                canvas.drawText("SERVER-AUTHORITATIVE SCORE", 20, 151, paint);
            }

            paint.setColor(Color.rgb(26, 48, 74));
            canvas.drawRect(0, getHeight() - 105, 150, getHeight(), paint);
            canvas.drawRect(155, getHeight() - 105, 305, getHeight(), paint);
            canvas.drawRect(getWidth() - 305, getHeight() - 105, getWidth(), getHeight(), paint);

            paint.setColor(Color.WHITE);
            paint.setTextSize(16);
            canvas.drawText("LEFT", 50, getHeight() - 55, paint);
            canvas.drawText("RIGHT", 205, getHeight() - 55, paint);
            canvas.drawText("JUMP", getWidth() - 245, getHeight() - 55, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX();
            float y = event.getY();

            if (gameOver) return true;

            if (event.getAction() == MotionEvent.ACTION_UP ||
                    event.getAction() == MotionEvent.ACTION_CANCEL) {
                left = false;
                right = false;
                return true;
            }

            if ((event.getAction() == MotionEvent.ACTION_DOWN ||
                    event.getAction() == MotionEvent.ACTION_MOVE) &&
                    y > getHeight() - 120) {

                if (x < 155) {
                    left = true;
                    right = false;
                } else if (x < 310) {
                    right = true;
                    left = false;
                } else {
                    jumpPressed = true;
                }
            }

            return true;
        }

        private void tick() {
            if (gameOver) return;

            long now = System.nanoTime();
            float dt = (now - lastFrame) / 1_000_000_000f;
            lastFrame = now;

            if (dt > 0.05f) dt = 0.05f;

            if (speedBoostUntil > System.currentTimeMillis()) {
                speed = BOOST_SPEED;
            } else {
                speed = BASE_SPEED;
            }

            if (left) bx -= speed;
            if (right) bx += speed;

            if (jumpPressed) {
                if (isGrounded()) {
                    vy = -16;
                    doubleJumpAvailable = true;
                } else if (doubleJumpAvailable) {
                    vy = -15;
                    doubleJumpAvailable = false;
                }
                jumpPressed = false;
            }

            float oldY = by;
            vy += 0.8f;
            by += vy;

            for (RectF r : platforms) {
                if (bx + 20 > r.left &&
                        bx - 20 < r.right &&
                        oldY + 24 <= r.top &&
                        by + 24 >= r.top &&
                        vy >= 0) {

                    by = r.top - 24;
                    vy = -11;
                    doubleJumpAvailable = true;
                }
            }

            if (by > getHeight() + 180) {
                lives--;

                if (lives <= 0) {
                    lives = 0;
                    gameOver = true;
                    post(MainActivity.this::showGameOver);
                    invalidate();
                    return;
                }

                bx = Math.max(60, bx - 180);
                by = 650;
                vy = 0;
                doubleJumpAvailable = true;
            }

            float half = Math.max(24, getWidth() / 2f);
            float maxWorldX = getLevelFinishX() + 120;
            bx = Math.max(24, Math.min(maxWorldX, bx));

            checkRings();
            checkGifts();

            float targetCamera = bx - getWidth() * 0.35f;
            cameraX = Math.max(0, Math.min(
                    Math.max(0, getLevelFinishX() - getWidth() + 100),
                    targetCamera
            ));

            if (bx > getLevelFinishX() && !levelReported) {
                levelReported = true;
                sendAction("finish_level");
                level++;
                buildLevel();
            }

            invalidate();
        }

        private float getLevelFinishX() {
            return 3500f;
        }

        private boolean isGrounded() {
            return by >= 736 || Math.abs(vy) < 1.5f;
        }

        private void checkRings() {
            for (int i = 0; i < rings.size(); i++) {
                if (ringTaken[i]) continue;

                RectF r = rings.get(i);
                float dx = bx - r.centerX();
                float dy = by - r.centerY();

                if (dx * dx + dy * dy < 65 * 65) {
                    ringTaken[i] = true;

                    int type = ringTypes.get(i);

                    if (type == 0) {
                        // Speed boost does not reduce the starting speed.
                        speedBoostUntil = System.currentTimeMillis() + 3000L;
                        speed = BOOST_SPEED;
                        sendAction("collect_speed_ring");
                    } else if (type == 1) {
                        vy = -23;
                        doubleJumpAvailable = true;
                        sendAction("collect_jump_ring");
                    } else {
                        sendAction("collect_star_ring");
                    }

                    invalidate();
                }
            }
        }

        private void checkGifts() {
            for (int i = 0; i < gifts.size(); i++) {
                if (giftTaken[i]) continue;

                RectF g = gifts.get(i);

                if (Math.abs(bx - g.centerX()) < 45 &&
                        Math.abs(by - g.centerY()) < 45) {

                    giftTaken[i] = true;
                    sendAction("collect_hidden_gift");
                }
            }
        }

        private void restartRun() {
            lives = 3;
            level = 1;
            buildLevel();
            lastFrame = System.nanoTime();
            invalidate();
        }

        private void sendAction(final String action) {
            final String actionId = UUID.randomUUID().toString();

            new Thread(() -> {
                HttpURLConnection connection = null;

                try {
                    URL url = new URL(serverUrl + "/api/game/action");
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("POST");
                    connection.setConnectTimeout(5000);
                    connection.setReadTimeout(7000);
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json");

                    String body = new JSONObject()
                            .put("playerId", playerId)
                            .put("action", action)
                            .put("actionId", actionId)
                            .toString();

                    try (OutputStream out = connection.getOutputStream()) {
                        out.write(body.getBytes("UTF-8"));
                    }

                    int code = connection.getResponseCode();
                    InputStream stream = code < 400
                            ? connection.getInputStream()
                            : connection.getErrorStream();

                    String response = readAll(stream);

                    if (code >= 400) {
                        throw new IOException("HTTP " + code);
                    }

                    JSONObject json = new JSONObject(response);
                    int score = json.optInt("score", serverScore);
                    int available = json.optInt("availableScore", availableScore);

                    runOnUiThread(() -> {
                        serverScore = score;
                        availableScore = available;
                        invalidate();
                    });

                } catch (Exception e) {
                    runOnUiThread(() ->
                            Toast.makeText(
                                    MainActivity.this,
                                    "Server unavailable",
                                    Toast.LENGTH_SHORT
                            ).show()
                    );
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }).start();
        }

        private void refreshWallet() {
            new Thread(() -> {
                HttpURLConnection connection = null;

                try {
                    String query = URLEncoder.encode(playerId, "UTF-8");
                    URL url = new URL(serverUrl + "/api/wallet?playerId=" + query);

                    connection = (HttpURLConnection) url.openConnection();
                    connection.setConnectTimeout(5000);
                    connection.setReadTimeout(7000);

                    int code = connection.getResponseCode();
                    if (code >= 400) throw new IOException("HTTP " + code);

                    JSONObject json =
                            new JSONObject(readAll(connection.getInputStream()));

                    runOnUiThread(() -> {
                        serverScore = json.optInt("score", 0);
                        availableScore = json.optInt("availableScore", 0);
                        invalidate();
                    });

                } catch (Exception e) {
                    runOnUiThread(() ->
                            Toast.makeText(
                                    MainActivity.this,
                                    "Wallet sync failed",
                                    Toast.LENGTH_SHORT
                            ).show()
                    );
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }).start();
        }

        private String readAll(InputStream input) throws IOException {
            if (input == null) return "{}";

            StringBuilder result = new StringBuilder();

            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(input))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    result.append(line);
                }
            }

            return result.toString();
        }
    }
}

