package com.bouncingball.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
        String[] items = {"🔊 Sound Control", "🎵 Music Control", "🎁 Rewards", "🎡 Spin", "💰 Wallet / Withdraw", "⚙ Backend Settings"};
        new AlertDialog.Builder(this)
                .setTitle("BOUNCING BALL A00")
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0: soundEnabled = !soundEnabled; toast("Sound: " + (soundEnabled ? "ON" : "OFF")); break;
                        case 1: musicEnabled = !musicEnabled; toast("Music: " + (musicEnabled ? "ON" : "OFF")); break;
                        case 2: showRewards(); break;
                        case 3: showSpin(); break;
                        case 4: showWallet(); break;
                        case 5: showServerSettings(); break;
                    }
                }).show();
    }

    private void showRewards() {
        new AlertDialog.Builder(this)
                .setTitle("REWARDS")
                .setMessage("Score: " + game.serverScore + "\nAvailable score: " + game.availableScore + "\n\nReward conversion is controlled by the server. No guaranteed earnings are promised.")
                .setPositiveButton("OK", null).show();
    }

    private void showSpin() {
        new AlertDialog.Builder(this)
                .setTitle("SPIN")
                .setMessage("Spin rewards are disabled until the server authorizes a spin session. This prevents client-side reward manipulation.")
                .setPositiveButton("OK", null).show();
    }

    private void showWallet() {
        new AlertDialog.Builder(this)
                .setTitle("PLAYER WALLET")
                .setMessage("Player ID: " + playerId + "\nScore: " + game.serverScore + "\nAvailable: " + game.availableScore + "\n\nWithdrawals are currently disabled in this build. A production payout system requires authenticated server-side wallet transactions, eligibility checks, fraud review and compliant payment integration.")
                .setPositiveButton("OK", null).show();
    }

    private void showServerSettings() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Backend URL");
        input.setText(serverUrl);
        new AlertDialog.Builder(this)
                .setTitle("BACKEND SETTINGS")
                .setMessage("Emulator default: http://10.0.2.2:3000\nFor a physical phone use your server LAN/HTTPS address.")
                .setView(input)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SAVE & SYNC", (d, w) -> {
                    String value = input.getText().toString().trim().replaceAll("/$", "");
                    if (!value.isEmpty()) {
                        serverUrl = value;
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("serverUrl", serverUrl).apply();
                        game.refreshWallet();
                    }
                }).show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private class GameView extends android.view.View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float bx = 80, by = 700, vx = 0, vy = 0;
        private boolean left, right, jumpPressed;
        private int lives = 3, level = 1;
        private int serverScore = 0, availableScore = 0;
        private float speed = 7f;
        private boolean doubleJumpAvailable = true;
        private final ArrayList<RectF> platforms = new ArrayList<>();
        private final ArrayList<RectF> rings = new ArrayList<>();
        private final ArrayList<RectF> gifts = new ArrayList<>();
        private boolean[] ringTaken, giftTaken;
        private long lastFrame = System.nanoTime();
        private boolean levelReported = false;

        GameView(Context context) {
            super(context);
            paint.setTypeface(Typeface.create("sans", Typeface.BOLD));
            setFocusable(true);
            buildLevel();
        }

        private void buildLevel() {
            platforms.clear(); rings.clear(); gifts.clear();
            platforms.add(new RectF(0, 760, 1080, 900));
            platforms.add(new RectF(180, 600, 420, 630));
            platforms.add(new RectF(520, 480, 760, 510));
            platforms.add(new RectF(820, 360, 1060, 390));
            rings.add(new RectF(300, 545, 350, 595)); // speed booster
            rings.add(new RectF(620, 425, 670, 475)); // high jump
            rings.add(new RectF(870, 305, 920, 355)); // star ring
            gifts.add(new RectF(720, 420, 760, 460)); // hidden gift
            ringTaken = new boolean[rings.size()];
            giftTaken = new boolean[gifts.size()];
            bx = 80; by = 700; vx = 0; vy = 0; speed = 7f;
            doubleJumpAvailable = true; levelReported = false;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            tick();
            canvas.drawColor(Color.rgb(7, 17, 31));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(18, 34, 56));
            for (RectF r : platforms) canvas.drawRect(r, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(9);
            int[] ringColors = {Color.CYAN, Color.GREEN, Color.YELLOW};
            for (int i = 0; i < rings.size(); i++) {
                if (!ringTaken[i]) {
                    paint.setColor(ringColors[i]);
                    canvas.drawCircle(rings.get(i).centerX(), rings.get(i).centerY(), 24, paint);
                }
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.MAGENTA);
            for (int i = 0; i < gifts.size(); i++) if (!giftTaken[i]) canvas.drawRect(gifts.get(i), paint);

            paint.setColor(Color.rgb(255, 209, 102));
            canvas.drawCircle(bx, by, 24, paint);
            paint.setColor(Color.WHITE);
            paint.setTextSize(25);
            canvas.drawText("⭐ SCORE: " + serverScore, 20, 38, paint);
            canvas.drawText("❤️ LIVES: " + lives, 20, 70, paint);
            canvas.drawText("LEVEL " + level, 20, 102, paint);
            paint.setTextSize(15);
            paint.setColor(Color.LTGRAY);
            canvas.drawText("ID: " + playerId, 20, 128, paint);
            canvas.drawText("SERVER-AUTHORITATIVE SCORE", 20, 151, paint);

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
            float x = event.getX(), y = event.getY();
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                left = right = false;
                return true;
            }
            if ((event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) && y > getHeight() - 120) {
                if (x < 155) { left = true; right = false; }
                else if (x < 310) { right = true; left = false; }
                else { jumpPressed = true; }
            }
            return true;
        }

        private void tick() {
            float dt = (System.nanoTime() - lastFrame) / 1_000_000_000f;
            lastFrame = System.nanoTime();
            if (dt > 0.05f) dt = 0.05f;
            if (left) bx -= speed;
            if (right) bx += speed;

            if (jumpPressed) {
                if (isGrounded()) { vy = -16; doubleJumpAvailable = true; }
                else if (doubleJumpAvailable) { vy = -15; doubleJumpAvailable = false; }
                jumpPressed = false;
            }

            float oldY = by;
            vy += 0.8f;
            by += vy;
            for (RectF r : platforms) {
                if (bx + 20 > r.left && bx - 20 < r.right && oldY + 24 <= r.top && by + 24 >= r.top && vy >= 0) {
                    by = r.top - 24;
                    vy = -11;
                    doubleJumpAvailable = true;
                }
            }

            if (by > getHeight() + 120) {
                lives--;
                bx = 80; by = 700; vy = 0; doubleJumpAvailable = true;
                if (lives <= 0) { lives = 3; level = 1; buildLevel(); }
            }
            bx = Math.max(24, Math.min(Math.max(24, getWidth() - 24), bx));
            checkRings(); checkGifts();
            if (bx > getWidth() - 60 && !levelReported) {
                levelReported = true;
                sendAction("finish_level");
                level++;
                buildLevel();
            }
            invalidate();
        }

        private boolean isGrounded() { return by >= 736 || Math.abs(vy) < 1.5f; }

        private void checkRings() {
            for (int i = 0; i < rings.size(); i++) if (!ringTaken[i]) {
                RectF r = rings.get(i);
                float dx = bx - r.centerX(), dy = by - r.centerY();
                if (dx * dx + dy * dy < 55 * 55) {
                    ringTaken[i] = true;
                    if (i == 0) speed = 10f;
                    else if (i == 1) vy = -21;
                    sendAction(i == 2 ? "collect_star_ring" : "ring_combo");
                }
            }
        }

        private void checkGifts() {
            for (int i = 0; i < gifts.size(); i++) if (!giftTaken[i]) {
                RectF g = gifts.get(i);
                if (Math.abs(bx - g.centerX()) < 45 && Math.abs(by - g.centerY()) < 45) {
                    giftTaken[i] = true;
                    sendAction("collect_hidden_gift");
                }
            }
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
                    InputStream stream = code < 400 ? connection.getInputStream() : connection.getErrorStream();
                    String response = readAll(stream);
                    if (code >= 400) throw new IOException("HTTP " + code);
                    JSONObject json = new JSONObject(response);
                    int score = json.optInt("score", serverScore);
                    int available = json.optInt("availableScore", availableScore);
                    runOnUiThread(() -> { serverScore = score; availableScore = available; invalidate(); });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Server unavailable", Toast.LENGTH_SHORT).show());
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
                    JSONObject json = new JSONObject(readAll(connection.getInputStream()));
                    runOnUiThread(() -> {
                        serverScore = json.optInt("score", 0);
                        availableScore = json.optInt("availableScore", 0);
                        invalidate();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Wallet sync failed", Toast.LENGTH_SHORT).show());
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }).start();
        }

        private String readAll(InputStream input) throws IOException {
            if (input == null) return "{}";
            StringBuilder result = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
                String line;
                while ((line = reader.readLine()) != null) result.append(line);
            }
            return result.toString();
        }
    }
}
