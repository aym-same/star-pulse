package com.smartglasses.starpulse;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * STAR PULSE is intentionally a single custom View so the glass can run it
 * without a layout, image assets, network access, or runtime permissions.
 */
public final class MainActivity extends Activity {
    private GameView gameView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        applyImmersiveFlags();
        gameView = new GameView(this);
        setContentView(gameView);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveFlags();
        }
    }

    private void applyImmersiveFlags() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (gameView != null) {
            gameView.pauseGame();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (gameView != null) {
            gameView.resumeGame();
        }
    }

    @Override
    protected void onDestroy() {
        if (gameView != null) {
            gameView.release();
        }
        super.onDestroy();
    }

    /** Full-screen, portrait game surface. BACK is deliberately left to Activity. */
    private static final class GameView extends View {
        private static final String TAG = "StarPulse";
        private static final float DESIGN_W = 480f;
        private static final float DESIGN_H = 640f;
        private static final long ROUND_LENGTH_MS = 45_000L;
        private static final long TARGET_LENGTH_MS = 1_280L;
        private static final float START_RADIUS = 154f;
        private static final float STAR_RADIUS = 25f;
        private static final int SCREEN_START = 0;
        private static final int SCREEN_PLAYING = 1;
        private static final int SCREEN_RESULT = 2;

        private static final int BG = Color.BLACK;
        private static final int GREEN = Color.rgb(169, 255, 183);
        private static final int BRIGHT = Color.rgb(91, 255, 116);
        private static final int MID = Color.rgb(55, 174, 77);
        private static final int DIM = Color.rgb(38, 92, 52);
        private static final int FAINT = Color.rgb(13, 43, 22);

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private final List<Particle> particles = new ArrayList<Particle>();
        private final Random random = new Random(0x51A7C0DEL);
        private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
        private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        private final Typeface mono = Typeface.create("monospace", Typeface.NORMAL);
        private final ToneBank tones;
        private final Runnable frameLoop = new Runnable() {
            @Override
            public void run() {
                if (screen == SCREEN_PLAYING) {
                    update(SystemClock.uptimeMillis());
                    invalidate();
                    postDelayed(this, 16L);
                } else {
                    invalidate();
                }
            }
        };

        private int screen = SCREEN_START;
        private long roundStartedAt;
        private long targetStartedAt;
        private long feedbackUntil;
        private long pulseUntil;
        private long pausedAt;
        private int score;
        private int combo;
        private int bestCombo;
        private int hits;
        private int misses;
        private String feedback = "";
        private int feedbackColor = GREEN;
        private float lastTouchX;
        private float lastTouchY;

        GameView(Context context) {
            super(context);
            setFocusable(true);
            setFocusableInTouchMode(true);
            tones = new ToneBank();
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            requestFocus();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            requestFocus();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(BG);
            float scale = Math.min(getWidth() / DESIGN_W, getHeight() / DESIGN_H);
            float left = (getWidth() - DESIGN_W * scale) * 0.5f;
            float top = (getHeight() - DESIGN_H * scale) * 0.5f;
            int saved = canvas.save();
            canvas.translate(left, top);
            canvas.scale(scale, scale);
            if (screen == SCREEN_START) {
                drawStart(canvas);
            } else if (screen == SCREEN_PLAYING) {
                drawPlaying(canvas);
            } else {
                drawResult(canvas);
            }
            canvas.restoreToCount(saved);
        }

        private void drawStart(Canvas canvas) {
            drawHeaderMark(canvas, 36f, 58f);
            text(canvas, "STAR PULSE", 240f, 112f, 43f, GREEN, medium, true);
            text(canvas, "瞬間を、タップ。", 240f, 145f, 18f, MID, regular, true);

            drawGlow(canvas, 240f, 269f, 34f, 0.30f);
            drawStar(canvas, 240f, 269f, 29f, GREEN, 0.10f);
            ring(canvas, 240f, 269f, 63f, 1.4f, DIM);
            ring(canvas, 240f, 269f, 81f, 1f, FAINT);

            rect.set(46f, 365f, 434f, 485f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(4, 18, 9));
            canvas.drawRoundRect(rect, 14f, 14f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1f);
            paint.setColor(DIM);
            canvas.drawRoundRect(rect, 14f, 14f, paint);
            paint.setStyle(Paint.Style.FILL);
            text(canvas, "リングが星に重なったらタップ", 240f, 405f, 19f, GREEN, medium, true);
            text(canvas, "45秒のラウンド", 240f, 438f, 16f, MID, regular, true);
            text(canvas, "光が重なったら、タップ", 240f, 464f, 14f, DIM, regular, true);

            float blink = (SystemClock.uptimeMillis() / 500L) % 2L;
            text(canvas, blink == 0f ? "TAP TO START" : "· TAP TO START ·",
                    240f, 548f, 21f, BRIGHT, medium, true);
            text(canvas, "STAR PULSE", 240f, 600f, 11f, DIM, mono, true);
        }

        private void drawPlaying(Canvas canvas) {
            long now = SystemClock.uptimeMillis();
            long elapsed = Math.max(0L, now - roundStartedAt);
            long remaining = Math.max(0L, ROUND_LENGTH_MS - elapsed);
            float seconds = remaining / 1000f;

            drawHeaderMark(canvas, 36f, 58f);
            textLeft(canvas, "SCORE  " + score, 61f, 60f, 17f, GREEN, mono);
            textRight(canvas, String.format(Locale.US, "%02d", (int) Math.ceil(seconds)), 438f, 60f, 24f, GREEN, mono);
            textRight(canvas, "SEC", 438f, 80f, 10f, DIM, mono);
            textLeft(canvas, "COMBO  " + combo, 61f, 82f, 12f, combo > 0 ? BRIGHT : DIM, mono);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1f);
            paint.setColor(FAINT);
            canvas.drawLine(42f, 103f, 438f, 103f, paint);
            paint.setStyle(Paint.Style.FILL);

            final float cx = 240f;
            final float cy = 327f;
            float radius = ringRadius(now);
            drawGlow(canvas, cx, cy, STAR_RADIUS + 8f, 0.36f);
            ring(canvas, cx, cy, 63f, 1f, FAINT);
            ring(canvas, cx, cy, 95f, 1f, FAINT);
            ring(canvas, cx, cy, radius, radius < 58f ? 4f : 2.5f,
                    radius < 44f ? BRIGHT : MID);
            drawStar(canvas, cx, cy, STAR_RADIUS, GREEN, 0.08f);

            updateParticles(canvas);
            if (feedbackUntil > now) {
                float alpha = Math.min(1f, (feedbackUntil - now) / 450f);
                int color = withAlpha(feedbackColor, (int) (alpha * 255f));
                text(canvas, feedback, cx, 442f, 23f, color, medium, true);
            }
            text(canvas, "星とリングが重なる瞬間", cx, 513f, 16f, MID, regular, true);
            text(canvas, "タップで判定", cx, 540f, 12f, DIM, regular, true);

            float progress = Math.min(1f, elapsed / (float) ROUND_LENGTH_MS);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(FAINT);
            canvas.drawRoundRect(new RectF(56f, 585f, 424f, 589f), 2f, 2f, paint);
            paint.setColor(BRIGHT);
            canvas.drawRoundRect(new RectF(56f, 585f, 56f + 368f * progress, 589f), 2f, 2f, paint);
        }

        private void drawResult(Canvas canvas) {
            drawHeaderMark(canvas, 36f, 58f);
            text(canvas, "ROUND COMPLETE", 240f, 119f, 28f, GREEN, medium, true);
            text(canvas, "おつかれさま", 240f, 150f, 17f, MID, regular, true);
            drawGlow(canvas, 240f, 245f, 36f, 0.25f);
            drawStar(canvas, 240f, 245f, 31f, GREEN, 0.10f);

            rect.set(48f, 326f, 432f, 444f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(4, 18, 9));
            canvas.drawRoundRect(rect, 14f, 14f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1f);
            paint.setColor(DIM);
            canvas.drawRoundRect(rect, 14f, 14f, paint);
            paint.setStyle(Paint.Style.FILL);
            text(canvas, "SCORE", 146f, 361f, 13f, DIM, mono, true);
            text(canvas, Integer.toString(score), 146f, 402f, 38f, GREEN, mono, true);
            text(canvas, "BEST COMBO", 334f, 361f, 13f, DIM, mono, true);
            text(canvas, Integer.toString(bestCombo), 334f, 402f, 38f, BRIGHT, mono, true);
            text(canvas, "HIT  " + hits + "    MISS  " + misses, 240f, 429f, 13f, MID, mono, true);

            float blink = (SystemClock.uptimeMillis() / 500L) % 2L;
            text(canvas, blink == 0f ? "TAP TO RESTART" : "· TAP TO RESTART ·",
                    240f, 515f, 20f, BRIGHT, medium, true);
            text(canvas, "もう一度、タップ", 240f, 548f, 12f, DIM, regular, true);
            text(canvas, "STAR PULSE", 240f, 600f, 12f, DIM, mono, true);
        }

        private void update(long now) {
            if (now - roundStartedAt >= ROUND_LENGTH_MS) {
                finishRound();
                return;
            }
            if (now - targetStartedAt >= TARGET_LENGTH_MS) {
                registerMiss(now, true);
            }
            Iterator<Particle> iterator = particles.iterator();
            while (iterator.hasNext()) {
                Particle particle = iterator.next();
                particle.update(0.016f);
                if (particle.life <= 0f) {
                    iterator.remove();
                }
            }
        }

        private float ringRadius(long now) {
            float t = Math.min(1f, Math.max(0f, (now - targetStartedAt) / (float) TARGET_LENGTH_MS));
            // Ease in slightly so the approach to the star is easy to read on the glass.
            float eased = t * t * (3f - 2f * t);
            return START_RADIUS - (START_RADIUS - STAR_RADIUS) * eased;
        }

        private void handlePrimaryAction() {
            long now = SystemClock.uptimeMillis();
            if (screen == SCREEN_START) {
                startRound(now);
            } else if (screen == SCREEN_PLAYING) {
                judgeTap(now);
            } else {
                startRound(now);
            }
        }

        private void startRound(long now) {
            screen = SCREEN_PLAYING;
            roundStartedAt = now;
            targetStartedAt = now;
            feedback = "";
            feedbackUntil = 0L;
            score = 0;
            combo = 0;
            bestCombo = 0;
            hits = 0;
            misses = 0;
            particles.clear();
            tones.play(ToneBank.START);
            Log.d(TAG, "round start durationMs=" + ROUND_LENGTH_MS);
            removeCallbacks(frameLoop);
            post(frameLoop);
            invalidate();
        }

        private void judgeTap(long now) {
            float radius = ringRadius(now);
            float error = Math.abs(radius - STAR_RADIUS);
            // A 45-second casual round: good timing has a generous window.
            if (error <= 15f) {
                registerHit(now, true);
            } else if (error <= 54f) {
                registerHit(now, false);
            } else {
                registerMiss(now, false);
            }
        }

        private void registerHit(long now, boolean perfect) {
            hits++;
            combo++;
            bestCombo = Math.max(bestCombo, combo);
            score += perfect ? 25 + Math.min(30, combo * 2) : 10 + Math.min(20, combo);
            feedback = perfect ? "PERFECT" : "GOOD";
            feedbackColor = perfect ? BRIGHT : GREEN;
            feedbackUntil = now + 560L;
            pulseUntil = now + 240L;
            spawnParticles(perfect ? 22 : 13, perfect);
            float pitch = 1f + Math.min(12, combo) * (perfect ? 0.035f : 0.025f);
            tones.play(perfect ? ToneBank.PERFECT : ToneBank.GOOD, pitch);
            Log.d(TAG, "tap outcome=" + feedback + " radius=" + Math.round(ringRadius(now))
                    + " score=" + score + " combo=" + combo);
            targetStartedAt = now;
        }

        private void registerMiss(long now, boolean automatic) {
            misses++;
            combo = 0;
            feedback = "MISS";
            feedbackColor = MID;
            feedbackUntil = now + (automatic ? 400L : 620L);
            pulseUntil = now + 130L;
            spawnParticles(automatic ? 5 : 8, false);
            tones.play(ToneBank.MISS);
            Log.d(TAG, "tap outcome=MISS automatic=" + automatic + " score=" + score);
            targetStartedAt = now;
        }

        private void finishRound() {
            screen = SCREEN_RESULT;
            removeCallbacks(frameLoop);
            tones.play(ToneBank.FINISH);
            Log.d(TAG, "round finish score=" + score + " hits=" + hits + " misses=" + misses
                    + " bestCombo=" + bestCombo);
            invalidate();
        }

        private void spawnParticles(int count, boolean bright) {
            for (int i = 0; i < count; i++) {
                double angle = random.nextDouble() * Math.PI * 2.0;
                float speed = (bright ? 45f : 30f) + random.nextFloat() * (bright ? 95f : 68f);
                particles.add(new Particle(240f, 327f, (float) Math.cos(angle) * speed,
                        (float) Math.sin(angle) * speed, bright ? BRIGHT : MID,
                        0.42f + random.nextFloat() * 0.34f));
            }
        }

        private void updateParticles(Canvas canvas) {
            for (Particle particle : particles) {
                float lifeFraction = Math.max(0f, particle.life / particle.maxLife);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(withAlpha(particle.color, (int) (lifeFraction * 210f)));
                canvas.drawCircle(particle.x, particle.y, particle.radius * (0.6f + lifeFraction), paint);
            }
            if (pulseUntil > SystemClock.uptimeMillis()) {
                float t = (pulseUntil - SystemClock.uptimeMillis()) / 240f;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2f);
                paint.setColor(withAlpha(BRIGHT, (int) (t * 130f)));
                canvas.drawCircle(240f, 327f, STAR_RADIUS + 8f + (1f - t) * 18f, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }

        private void drawHeaderMark(Canvas canvas, float x, float y) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f);
            paint.setColor(DIM);
            canvas.drawCircle(x, y, 16f, paint);
            paint.setColor(BRIGHT);
            paint.setStrokeWidth(2f);
            canvas.drawLine(x - 7f, y, x + 7f, y, paint);
            canvas.drawLine(x, y - 7f, x, y + 7f, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawGlow(Canvas canvas, float x, float y, float radius, float strength) {
            for (int i = 3; i >= 0; i--) {
                float r = radius * (1f + i * 0.55f);
                int alpha = (int) (strength * 255f / (i + 1));
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(withAlpha(BRIGHT, alpha));
                canvas.drawCircle(x, y, r, paint);
            }
        }

        private void drawStar(Canvas canvas, float x, float y, float radius, int color, float glow) {
            if (glow > 0f) {
                drawGlow(canvas, x, y, radius, glow);
            }
            path.reset();
            for (int i = 0; i < 10; i++) {
                double angle = -Math.PI / 2.0 + i * Math.PI / 5.0;
                float r = (i % 2 == 0) ? radius : radius * 0.43f;
                float px = x + (float) Math.cos(angle) * r;
                float py = y + (float) Math.sin(angle) * r;
                if (i == 0) {
                    path.moveTo(px, py);
                } else {
                    path.lineTo(px, py);
                }
            }
            path.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            canvas.drawPath(path, paint);
            paint.setColor(BG);
            path.reset();
            for (int i = 0; i < 10; i++) {
                double angle = -Math.PI / 2.0 + i * Math.PI / 5.0;
                float r = (i % 2 == 0) ? radius * 0.38f : radius * 0.17f;
                float px = x + (float) Math.cos(angle) * r;
                float py = y + (float) Math.sin(angle) * r;
                if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
            }
            path.close();
            canvas.drawPath(path, paint);
        }

        private void ring(Canvas canvas, float x, float y, float radius, float width, int color) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width);
            paint.setColor(color);
            canvas.drawCircle(x, y, radius, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void text(Canvas canvas, String value, float x, float baseline, float size,
                          int color, Typeface face, boolean centered) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            paint.setTextSize(size);
            paint.setTypeface(face);
            paint.setTextAlign(centered ? Paint.Align.CENTER : Paint.Align.LEFT);
            canvas.drawText(value, x, baseline, paint);
        }

        private void textLeft(Canvas canvas, String value, float x, float baseline, float size,
                              int color, Typeface face) {
            text(canvas, value, x, baseline, size, color, face, false);
        }

        private void textRight(Canvas canvas, String value, float x, float baseline, float size,
                               int color, Typeface face) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            paint.setTextSize(size);
            paint.setTypeface(face);
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(value, x, baseline, paint);
        }

        private static int withAlpha(int color, int alpha) {
            return Color.argb(Math.max(0, Math.min(255, alpha)), Color.red(color),
                    Color.green(color), Color.blue(color));
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                Log.d(TAG, "touch x=" + Math.round(lastTouchX) + " y=" + Math.round(lastTouchY));
                handlePrimaryAction();
            }
            return true;
        }

        @Override
        public boolean onKeyUp(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_SPACE
                    || keyCode == KeyEvent.KEYCODE_BUTTON_A) {
                Log.d(TAG, "key tap keyCode=" + keyCode);
                handlePrimaryAction();
                return true;
            }
            // In particular, leave KEYCODE_BACK to Activity/system navigation.
            return super.onKeyUp(keyCode, event);
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_SPACE
                    || keyCode == KeyEvent.KEYCODE_BUTTON_A) {
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        void pauseGame() {
            pausedAt = SystemClock.uptimeMillis();
            removeCallbacks(frameLoop);
            tones.pause();
        }

        void resumeGame() {
            if (pausedAt == 0L) return;
            long now = SystemClock.uptimeMillis();
            long pausedDuration = Math.max(0L, now - pausedAt);
            if (screen == SCREEN_PLAYING) {
                roundStartedAt += pausedDuration;
                targetStartedAt += pausedDuration;
                removeCallbacks(frameLoop);
                post(frameLoop);
            }
            pausedAt = 0L;
            invalidate();
        }

        void release() {
            removeCallbacks(frameLoop);
            tones.release();
        }

        private static final class Particle {
            float x;
            float y;
            float vx;
            float vy;
            final int color;
            final float maxLife;
            float life;
            final float radius;

            Particle(float x, float y, float vx, float vy, int color, float maxLife) {
                this.x = x;
                this.y = y;
                this.vx = vx;
                this.vy = vy;
                this.color = color;
                this.maxLife = maxLife;
                this.life = maxLife;
                this.radius = 2f + maxLife * 3f;
            }

            void update(float seconds) {
                x += vx * seconds;
                y += vy * seconds;
                vx *= 0.985f;
                vy *= 0.985f;
                vy += 18f * seconds;
                life -= seconds;
            }
        }

        /** Small locally synthesized PCM bank. Nothing is read from storage or network. */
        private static final class ToneBank {
            static final int START = 0;
            static final int GOOD = 1;
            static final int PERFECT = 2;
            static final int MISS = 3;
            static final int FINISH = 4;
            private static final int SAMPLE_RATE = 22_050;
            private final AudioTrack[] tracks = new AudioTrack[5];

            ToneBank() {
                try {
                    tracks[START] = buildTone(220d, 440d, 170, 0.42f, false);
                    tracks[GOOD] = buildTone(520d, 760d, 120, 0.47f, false);
                    tracks[PERFECT] = buildTone(660d, 1_180d, 170, 0.54f, true);
                    tracks[MISS] = buildTone(210d, 85d, 190, 0.38f, false);
                    tracks[FINISH] = buildTone(880d, 440d, 300, 0.48f, true);
                    Log.d(TAG, "synth audio ready sampleRate=" + SAMPLE_RATE);
                } catch (Throwable error) {
                    Log.w(TAG, "audio unavailable; game continues silently", error);
                    release();
                }
            }

            private AudioTrack buildTone(double startHz, double endHz, int durationMs,
                                         float volume, boolean harmonic) {
                int count = Math.max(1, SAMPLE_RATE * durationMs / 1000);
                short[] pcm = new short[count];
                double phase = 0.0;
                for (int i = 0; i < count; i++) {
                    float t = i / (float) count;
                    double hz = startHz + (endHz - startHz) * t;
                    phase += (Math.PI * 2.0 * hz) / SAMPLE_RATE;
                    double sample = Math.sin(phase) * 0.82;
                    if (harmonic) {
                        sample += Math.sin(phase * 2.01) * 0.18;
                    }
                    float attack = Math.min(1f, i / (SAMPLE_RATE * 0.010f));
                    float release = Math.min(1f, (count - i) / (SAMPLE_RATE * 0.035f));
                    pcm[i] = (short) (sample * volume * attack * release * 32767f);
                }
                AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        pcm.length * 2, AudioTrack.MODE_STATIC);
                int written = track.write(pcm, 0, pcm.length);
                if (written != pcm.length) {
                    track.release();
                    throw new IllegalStateException("short PCM write " + written + "/" + pcm.length);
                }
                return track;
            }

            synchronized void play(int which) {
                play(which, 1f);
            }

            synchronized void play(int which, float pitch) {
                if (which < 0 || which >= tracks.length || tracks[which] == null) return;
                try {
                    AudioTrack track = tracks[which];
                    if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) track.stop();
                    track.reloadStaticData();
                    track.setPlaybackHeadPosition(0);
                    float boundedPitch = Math.max(0.75f, Math.min(1.45f, pitch));
                    track.setPlaybackRate((int) (SAMPLE_RATE * boundedPitch));
                    track.play();
                    Log.d(TAG, "sound=" + which + " pitch=" + boundedPitch);
                } catch (Throwable error) {
                    Log.w(TAG, "sound failed " + which, error);
                }
            }

            synchronized void pause() {
                for (AudioTrack track : tracks) {
                    if (track != null) {
                        try {
                            if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) track.pause();
                        } catch (Throwable ignored) {
                            // Audio is a non-critical enhancement for this game.
                        }
                    }
                }
            }

            synchronized void release() {
                for (int i = 0; i < tracks.length; i++) {
                    if (tracks[i] != null) {
                        try {
                            tracks[i].release();
                        } catch (Throwable ignored) {
                            // Already released by a partially failed construction is harmless.
                        }
                        tracks[i] = null;
                    }
                }
            }
        }
    }
}
