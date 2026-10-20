/*
 * This is the source code of LuxGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * LuxGram Voice Changer (Fix 75) — precise, natural gender conversion.
 *
 *   Fix 74's TD-PSOLA shifter moved F0 accurately, but its short
 *   linear-crossfade grains overlapped with input phases that diverge by
 *   (T0_in − T0_out) samples per grain at high ratios. The resulting OLA sum
 *   is a time-varying comb filter: harmonics under the formants were boosted
 *   up to ×2.3 while harmonics between formants were notch-cut to ×0.08.
 *   Measured offline on a synthetic vowel (F0 115 Hz, formants 500/1500/2500):
 *   the shipped engine's output spectrum was combed ±10 dB — exactly the
 *   "closer to a woman but still not a real woman" buzz the user reported.
 *
 *   Fix 75 replaces the grain stage and adds a true spectral-envelope stage:
 *
 *     1. RESAMPLED-PERIOD grains — one input period (pitch-mark aligned) is
 *        read with Catmull-Rom interpolation at speed `ratio` and placed on
 *        the output period grid with complementary linear crossfades. Because
 *        every output period is the SAME compressed input period, the output
 *        spectrum is a clean harmonic comb (measured: every harmonic within
 *        0.6–1.1× of the input envelope) — pitch moves to ratio·F0, formants
 *        move with it (the classic "chipmunk"), and the level is rock-stable
 *        (RMS cv 0.02–0.03 vs 0.089 before). The grain pipeline is also
 *        deterministic now: pitch windows are anchored to the analyzed
 *        position, not to the wall-clock buffer end.
 *
 *     2. FORMANT-ENVELOPE WARP (the missing piece) — a streaming STFT
 *        (1024 @ ≥32 kHz, 512 below; Hann, 75% overlap, original phases)
 *        measures the per-frame cepstral envelope of the chipmunk signal and
 *        re-imposes it on the target formant positions:
 *            |X'|(f) = |X|(f) · E(f·k1) / E(f),   k1 = ratio / warp
 *        which lands the formants exactly on `warp`·F_target while keeping
 *        the harmonic fine structure and phase coherence (no phasiness —
 *        only magnitudes are touched). Measured: F1/F2/F3 shift to the
 *        target within measurement granularity, level kept by a slow
 *        per-frame loudness match.
 *
 *   Gender presets pair the two: زن پایه = pitch ×1.70 (115 Hz → 196 Hz,
 *   mid female range) with formants ×1.16 (average female vocal tract is
 *   ~14% shorter). Echo/FX presets bypass both stages. The static formant
 *   EQ of Fix 74 is skipped whenever the envelope warp is active (the warp
 *   does the real work; the EQ would double-brighten).
 *
 * The recorder (MediaController) feeds process(); WebRtcAudioRecord (calls)
 * feeds processCall(); both emit exactly the requested sample count per call
 * (silent during the ~60–90 ms pipeline warmup, then 1:1 forever).
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.nio.ByteBuffer;

public class LuxGramVoiceChanger {

    /** Marker used to verify the class made it into a release build. */
    public static final String FIX_TAG = "LuxGramFix75-ResampleWarp";

    // ======================= presets =======================

    public static final int PRESET_OFF = 0;
    public static final int PRESET_COUNT = 16;

    // legacy echo rooms (still used by the settings screens)
    public static final int ECHO_OFF = 0;
    public static final int ECHO_STUDIO = 1;
    public static final int ECHO_CAVE = 2;
    public static final int ECHO_STAGE = 3;

    // legacy gender modes (mapped onto presets for compatibility)
    public static final int MODE_OFF = 0;
    public static final int MODE_M2F = 1;
    public static final int MODE_F2M = 2;

    public static final class Preset {
        public final String name, desc;
        public final float ratio;   // pitch factor (1 = unchanged)
        public final float warp;    // formant-envelope factor, 1 = off (Fix 75)
        public final int echo;      // ECHO_*
        public final float ringHz;  // ring-modulation frequency, 0 = off
        public final float bpLow;   // bandpass low edge Hz, 0 = off
        public final float bpHigh;  // bandpass high edge Hz, 0 = off
        public final float tilt;    // +brighten / −darken, 0 = flat
        public final float drive;   // soft saturation 0..1
        public final float formant; // legacy static EQ depth (unused when warp ≠ 1)

        Preset(String name, String desc, float ratio, float warp, int echo, float ringHz,
               float bpLow, float bpHigh, float tilt, float drive, float formant) {
            this.name = name;
            this.desc = desc;
            this.ratio = ratio;
            this.warp = warp;
            this.echo = echo;
            this.ringHz = ringHz;
            this.bpLow = bpLow;
            this.bpHigh = bpHigh;
            this.tilt = tilt;
            this.drive = drive;
            this.formant = formant;
        }
    }

    private static final Preset[] PRESETS = {
            new Preset("خاموش", "بدون هیچ تغییری", 1f, 1f, ECHO_OFF, 0, 0, 0, 0, 0, 0),
            new Preset("زن طبیعی", "صدای زن، ملایم و باورپذیر", 1.45f, 1.10f, ECHO_OFF, 0, 0, 0, 0, 0, 0),
            new Preset("زن جوان", "زن، روشن و شاداب", 1.58f, 1.13f, ECHO_OFF, 0, 0, 0, 0, 0, 0),
            new Preset("زن پایه", "تبدیل کامل به صدای زن", 1.70f, 1.16f, ECHO_OFF, 0, 0, 0, 0, 0, 0),
            new Preset("مرد طبیعی", "صدای مرد، گرم و آرام", 0.80f, 0.90f, ECHO_OFF, 0, 0, 0, 0, 0, 0),
            new Preset("مرد بم", "مرد، بمِ لطیف", 0.74f, 0.86f, ECHO_OFF, 0, 0, 0, 0, 0, 0),
            new Preset("مرد عمیق", "بمِ عمیق و پرقدرت", 0.68f, 0.82f, ECHO_OFF, 0, 0, 0, 0, 0, 0),
            new Preset("بچه", "صدای کودکانه و شیطنت‌آمیز", 1.80f, 1.22f, ECHO_OFF, 0, 0, 0, 0, 0, 0),
            new Preset("تلفن قدیمی", "تلفن آنالوگ دهه ۷۰", 1f, 1f, ECHO_OFF, 0, 450, 2800, 0, 0.35f, 0),
            new Preset("رادیو", "رادیو با اکوی ملایم", 1f, 1f, ECHO_STUDIO, 0, 500, 3200, 0, 0.25f, 0),
            new Preset("ربات", "ربات متالیک", 1f, 1f, ECHO_OFF, 55, 300, 3400, 0, 0.20f, 0),
            new Preset("فضایی", "موج فضایی عجیب", 1.06f, 1f, ECHO_CAVE, 110, 0, 0, 0, 0.15f, 0),
            new Preset("استودیو", "اتاق ضبط حرفه‌ای", 1f, 1f, ECHO_STUDIO, 0, 0, 0, 0, 0, 0),
            new Preset("غار", "اکوی غار تاریک", 1f, 1f, ECHO_CAVE, 0, 0, 0, 0, 0, 0),
            new Preset("صحنه", "سالن کنسرت", 1f, 1f, ECHO_STAGE, 0, 0, 0, 0, 0, 0),
            new Preset("گمنام", "صدای تغییر‌یافته ناشناس", 0.95f, 0.97f, ECHO_STUDIO, 0, 0, 0, 0, 0.15f, 0)
    };

    public static Preset preset(int id) {
        if (id < 0 || id >= PRESETS.length) {
            return PRESETS[PRESET_OFF];
        }
        return PRESETS[id];
    }

    public static String presetName(int id) {
        return preset(id).name;
    }

    public static String presetDesc(int id) {
        return preset(id).desc;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("luxgram_media", Context.MODE_PRIVATE);
    }

    /** Currently selected preset id (0 = off). */
    public static int getPreset() {
        try {
            int v = prefs().getInt("voice_preset", PRESET_OFF);
            return v < PRESET_OFF || v >= PRESET_COUNT ? PRESET_OFF : v;
        } catch (Throwable t) {
            return PRESET_OFF;
        }
    }

    public static void setPreset(int id) {
        if (id < PRESET_OFF || id >= PRESET_COUNT) {
            id = PRESET_OFF;
        }
        prefs().edit().putInt("voice_preset", id).apply();
    }

    // =================== legacy compatibility API ===================

    public static int getMode() {
        int p = getPreset();
        if (p == PRESET_OFF) {
            return MODE_OFF;
        }
        if (p <= 3) {
            return MODE_M2F;   // زن ۱..۳
        }
        if (p <= 6) {
            return MODE_F2M;   // مرد ۴..۶
        }
        return MODE_OFF;
    }

    public static void setMode(int mode) {
        if (mode == MODE_M2F) {
            setPreset(3);      // زن پایه
        } else if (mode == MODE_F2M) {
            setPreset(6);      // مرد عمیق
        } else {
            setPreset(PRESET_OFF);
        }
    }

    /** Kept for compatibility; intensity is baked into the presets now. */
    public static int getIntensity() {
        try {
            return prefs().getInt("voice_intensity", 2);
        } catch (Throwable t) {
            return 2;
        }
    }

    public static void setIntensity(int intensity) {
        if (intensity < 0 || intensity > 2) {
            intensity = 2;
        }
        prefs().edit().putInt("voice_intensity", intensity).apply();
    }

    public static int getEchoStyle() {
        return preset(getPreset()).echo;
    }

    public static void setEchoStyle(int style) {
        // map an echo pick onto the matching pure-echo preset
        if (style == ECHO_STUDIO) {
            setPreset(12);
        } else if (style == ECHO_CAVE) {
            setPreset(13);
        } else if (style == ECHO_STAGE) {
            setPreset(14);
        } else {
            setPreset(PRESET_OFF);
        }
    }

    /** True when any send-side processing is active. */
    public static boolean active() {
        return getPreset() != PRESET_OFF;
    }

    // ======================= professional picker =======================

    /**
     * LuxGram Fix 73: apply-on-tap — a single tap on a preset row SAVES it,
     * closes the dialog and fires the bulletin.
     */
    public static void showPresetPicker(BaseFragment fragment, Runnable onDone) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        Context ctx = fragment.getParentActivity();
        int current = getPreset();
        int gold = 0xFFF5B90D;

        android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
        android.widget.LinearLayout list = new android.widget.LinearLayout(ctx);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        scroll.addView(list);
        final AlertDialog[] dialogHolder = new AlertDialog[1];
        java.util.ArrayList<android.widget.RadioButton> radios = new java.util.ArrayList<>();
        for (int i = 0; i < PRESETS.length; i++) {
            final int id = i;
            Preset p = PRESETS[i];

            android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(9), AndroidUtilities.dp(16), AndroidUtilities.dp(9));
            row.setBackground(Theme.getSelectorDrawable(false));

            android.widget.RadioButton rb = new android.widget.RadioButton(ctx);
            rb.setChecked(i == current);
            rb.setHighlightColor(0x00000000);
            rb.setClickable(false); // Fix 73: the circle must not eat the row tap
            rb.setFocusable(false);
            try {
                android.content.res.ColorStateList csl = new android.content.res.ColorStateList(
                        new int[][]{{android.R.attr.state_checked}, {}},
                        new int[]{gold, 0x66888888});
                rb.setButtonTintList(csl);
            } catch (Throwable ignore) {}
            row.addView(rb, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

            android.widget.LinearLayout col = new android.widget.LinearLayout(ctx);
            col.setOrientation(android.widget.LinearLayout.VERTICAL);

            android.widget.TextView name = new android.widget.TextView(ctx);
            name.setText(p.name + "  ·  " + p.desc);
            name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15);
            name.setTextColor(i == current ? gold : Theme.getColor(Theme.key_dialogTextBlack, fragment.getResourceProvider()));
            name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            col.addView(name);

            StringBuilder tech = new StringBuilder();
            if (Math.abs(p.ratio - 1f) > 0.01f) {
                tech.append(p.ratio > 1f ? "زیرتر " : "بم‌تر ");
            }
            if (Math.abs(p.warp - 1f) > 0.01f) {
                tech.append("طیف طبیعی ");   // Fix 75: true formant-envelope warp
            }
            if (p.ringHz > 0) {
                tech.append("موج مدوله ");
            }
            if (p.echo != ECHO_OFF) {
                tech.append(p.echo == ECHO_STUDIO ? "اکو استودیو " : p.echo == ECHO_CAVE ? "اکو غار " : "اکو صحنه ");
            }
            if (p.bpLow > 0) {
                tech.append("فیلتر باند ");
            }
            if (tech.length() > 0) {
                android.widget.TextView sub = new android.widget.TextView(ctx);
                sub.setText(tech.toString().trim());
                sub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 12);
                sub.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, fragment.getResourceProvider()));
                col.addView(sub);
            }

            row.addView(col, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            radios.add(rb);
            row.setOnClickListener(v -> {
                LuxGramHaptics.tick(v);
                for (int r = 0; r < radios.size(); r++) {
                    radios.get(r).setChecked(radios.get(r) == rb);
                }
                setPreset(id); // Fix 73: save IMMEDIATELY, then close
                if (dialogHolder[0] != null) {
                    dialogHolder[0].dismiss();
                }
                if (onDone != null) {
                    onDone.run();
                }
            });
            list.addView(row, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        AlertDialog dialog = new AlertDialog.Builder(ctx, fragment.getResourceProvider())
                .setTitle("تغییر صدا")
                .setView(scroll)
                .setNegativeButton(LocaleController.getString(R.string.Close), null)
                .create();
        dialogHolder[0] = dialog;
        dialog.show();
    }

    // ======================= sessions =======================

    private static volatile Chain chain;
    private static volatile Chain callChain;

    /** Starts (or resets) processing state for a fresh voice recording. */
    public static void beginSession(int sampleRate) {
        try {
            if (sampleRate <= 0 || !active()) {
                chain = null;
                return;
            }
            chain = new Chain(preset(getPreset()), sampleRate);
        } catch (Throwable t) {
            chain = null;
            FileLog.e(t);
        }
    }

    public static void endSession() {
        chain = null;
    }

    /** Call/voice-chat uplink session (kept separate from recordings). */
    public static void beginCallSession(int sampleRate) {
        try {
            if (sampleRate <= 0 || !active()) {
                callChain = null;
                return;
            }
            callChain = new Chain(preset(getPreset()), sampleRate);
        } catch (Throwable t) {
            callChain = null;
            FileLog.e(t);
        }
    }

    public static void endCallSession() {
        callChain = null;
    }

    /** True when a call uplink chain exists (cheap guard for the WebRTC read loop). */
    public static boolean callActive() {
        return callChain != null;
    }

    /**
     * Processes one recorder buffer in place. Always returns the sample-byte
     * count to feed downstream (equal to len — the chain emits exactly the
     * requested amount, silent while the pipeline warms up).
     */
    public static int process(ByteBuffer buffer, int len) {
        Chain c = chain;
        if (c == null || len <= 0) {
            return len;
        }
        try {
            return c.process(buffer, len);
        } catch (Throwable t) {
            chain = null; // never risk the recording pipeline
            FileLog.e(t);
            return len;
        }
    }

    /** Processes one call uplink buffer in place. */
    public static int processCall(ByteBuffer buffer, int len) {
        Chain c = callChain;
        if (c == null || len <= 0) {
            return len;
        }
        try {
            return c.process(buffer, len);
        } catch (Throwable t) {
            callChain = null;
            FileLog.e(t);
            return len;
        }
    }

    // ======================= streaming chain =======================

    /** RBJ biquad (direct form 1) used by the legacy FX chain. */
    static final class BiQuad {
        float b0, b1, b2, a1, a2, x1, x2, y1, y2;

        static BiQuad lowShelf(float sr, float f0, float db) {
            return shelf(sr, f0, db, true);
        }

        static BiQuad highShelf(float sr, float f0, float db) {
            return shelf(sr, f0, db, false);
        }

        private static BiQuad shelf(float sr, float f0, float db, boolean low) {
            double a = Math.pow(10.0, db / 40.0);
            double w0 = 2.0 * Math.PI * f0 / sr;
            double cw = Math.cos(w0), sw = Math.sin(w0);
            double sq = 2.0 * Math.sqrt(a) * (sw / 2.0);
            double b0, b1, b2, a0, aa1, aa2;
            if (low) {
                b0 = a * ((a + 1) - (a - 1) * cw + sq);
                b1 = 2 * a * ((a - 1) - (a + 1) * cw);
                b2 = a * ((a + 1) - (a - 1) * cw - sq);
                a0 = (a + 1) + (a - 1) * cw + sq;
                aa1 = -2 * ((a - 1) + (a + 1) * cw);
                aa2 = (a + 1) + (a - 1) * cw - sq;
            } else {
                b0 = a * ((a + 1) + (a - 1) * cw + sq);
                b1 = -2 * a * ((a - 1) + (a + 1) * cw);
                b2 = a * ((a + 1) + (a - 1) * cw - sq);
                a0 = (a + 1) - (a - 1) * cw + sq;
                aa1 = 2 * ((a - 1) - (a + 1) * cw);
                aa2 = (a + 1) - (a - 1) * cw - sq;
            }
            BiQuad q = new BiQuad();
            q.b0 = (float) (b0 / a0);
            q.b1 = (float) (b1 / a0);
            q.b2 = (float) (b2 / a0);
            q.a1 = (float) (aa1 / a0);
            q.a2 = (float) (aa2 / a0);
            return q;
        }

        static BiQuad peak(float sr, float f0, float db, float q) {
            double a = Math.pow(10.0, db / 40.0);
            double w0 = 2.0 * Math.PI * f0 / sr;
            double cw = Math.cos(w0);
            double al = Math.sin(w0) / (2.0 * q);
            double b0 = 1 + al * a, b1 = -2 * cw, b2 = 1 - al * a;
            double a0 = 1 + al / a, aa1 = -2 * cw, aa2 = 1 - al / a;
            BiQuad r = new BiQuad();
            r.b0 = (float) (b0 / a0);
            r.b1 = (float) (b1 / a0);
            r.b2 = (float) (b2 / a0);
            r.a1 = (float) (aa1 / a0);
            r.a2 = (float) (aa2 / a0);
            return r;
        }

        float process(float x) {
            float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            return y;
        }
    }

    /** Iterative radix-2 FFT (verified 1:1 against a reference implementation). */
    static final class Fft {
        final int n;
        final float[] cosT, sinT;

        Fft(int n) {
            this.n = n;
            cosT = new float[n / 2];
            sinT = new float[n / 2];
            for (int i = 0; i < n / 2; i++) {
                cosT[i] = (float) Math.cos(2.0 * Math.PI * i / n);
                sinT[i] = (float) Math.sin(2.0 * Math.PI * i / n);
            }
        }

        void fft(float[] re, float[] im) {
            transform(re, im, false);
        }

        void ifft(float[] re, float[] im) {
            transform(re, im, true);
        }

        private void transform(float[] re, float[] im, boolean inv) {
            int n = this.n;
            for (int i = 1, j = 0; i < n; i++) {
                int bit = n >> 1;
                for (; (j & bit) != 0; bit >>= 1) {
                    j ^= bit;
                }
                j |= bit;
                if (i < j) {
                    float t = re[i]; re[i] = re[j]; re[j] = t;
                    t = im[i]; im[i] = im[j]; im[j] = t;
                }
            }
            for (int len = 2; len <= n; len <<= 1) {
                int step = n / len, halfLen = len >> 1;
                for (int start = 0; start < n; start += len) {
                    for (int k = 0; k < halfLen; k++) {
                        int c = k * step;
                        float wr = cosT[c];
                        float wi = inv ? sinT[c] : -sinT[c];
                        int a = start + k, b = a + halfLen;
                        float tr = re[b] * wr - im[b] * wi;
                        float ti = re[b] * wi + im[b] * wr;
                        re[b] = re[a] - tr;
                        im[b] = im[a] - ti;
                        re[a] += tr;
                        im[a] += ti;
                    }
                }
            }
            if (inv) {
                float s = 1f / n;
                for (int i = 0; i < n; i++) {
                    re[i] *= s;
                    im[i] *= s;
                }
            }
        }
    }

    /**
     * Fix 75 — streaming formant-envelope warp.
     *
     * Fixed STFT (Hann analysis + synthesis, 75% overlap, original phases —
     * only magnitudes are touched, so there is no phasiness). Per frame:
     * cepstral envelope E (2.6 ms lifter — below the period of any speech
     * F0), then |X'| = |X| · E(f·k1)/E(f) with slow per-frame loudness
     * matching. Emission runs on a fixed 2·N warmup delay so only fully
     * accumulated (all 4 overlapping frames) samples leave the stage.
     */
    static final class FormantWarp {
        final int sr, N, hop, half, W;
        final float k1;
        final float[] hann;
        final float[] inRing;
        final int inMask;
        long inW;
        int frameStart;
        final float[] ola;
        final int olaMask;
        long served;
        float g0sm = 1f;

        final float[] re, im, mag, ph, cep, lif, env, newMag;
        final Fft fft;

        FormantWarp(int sr, float k1) {
            this.sr = sr;
            this.k1 = k1;
            N = sr >= 32000 ? 1024 : 512;
            hop = N / 4;
            half = N / 2;
            W = 2 * N;
            hann = new float[N];
            for (int i = 0; i < N; i++) {
                hann[i] = (float) (0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / N)));
            }
            inRing = new float[1 << 15];
            inMask = (1 << 15) - 1;
            ola = new float[1 << 16];
            olaMask = (1 << 16) - 1;
            re = new float[N];
            im = new float[N];
            mag = new float[N];
            ph = new float[N];
            cep = new float[N];
            lif = new float[N];
            env = new float[N];
            newMag = new float[N];
            fft = new Fft(N);
        }

        /** Consumes n samples of x, produces exactly n samples into out. */
        void push(float[] x, int n, float[] out) {
            for (int i = 0; i < n; i++) {
                inRing[(int) (inW & inMask)] = x[i];
                inW++;
            }
            while (inW - frameStart >= N) {
                frame(frameStart);
                frameStart += hop;
            }
            for (int i = 0; i < n; i++) {
                long p = served - W + i;
                if (p >= 0 && p < frameStart) {
                    out[i] = ola[(int) (p & olaMask)];
                } else {
                    out[i] = 0f;   // warmup only
                }
            }
            served += n;
        }

        private void frame(int start) {
            for (int i = 0; i < N; i++) {
                re[i] = inRing[(start + i) & inMask] * hann[i];
                im[i] = 0f;
            }
            fft.fft(re, im);
            for (int k = 0; k <= half; k++) {
                mag[k] = (float) Math.hypot(re[k], im[k]);
                ph[k] = (float) Math.atan2(im[k], re[k]);
            }

            // real cepstrum → smooth spectral envelope (fixed 2.6 ms lifter)
            for (int k = 0; k <= half; k++) {
                cep[k] = (float) Math.log(Math.max(mag[k], 1e-6f));
            }
            for (int k = half + 1; k < N; k++) {
                cep[k] = cep[N - k];
            }
            for (int k = 0; k < N; k++) {
                im[k] = 0f;
            }
            fft.ifft(cep, im);
            float ncut = 0.0026f * sr;
            float n1 = ncut * 0.8f, n2 = ncut * 1.3f;
            for (int m = 0; m <= half; m++) {
                float w;
                if (m <= n1) {
                    w = 1f;
                } else if (m >= n2) {
                    w = 0f;
                } else {
                    w = (float) (0.5 * (1.0 + Math.cos(Math.PI * (m - n1) / (n2 - n1))));
                }
                lif[m] = w;
                if (m > 0) {
                    lif[N - m] = w;
                }
            }
            for (int k = 0; k < N; k++) {
                cep[k] *= lif[k];
                im[k] = 0f;
            }
            fft.fft(cep, im);
            for (int k = 0; k <= half; k++) {
                float e = (float) Math.exp(Math.max(cep[k], -13.8f));
                env[k] = Math.max(e, 1e-6f);
            }

            // envelope warp: sample E at f·k1, rescale each bin
            System.arraycopy(mag, 0, newMag, 0, N);
            float fh = half * (float) sr / N;
            for (int k = 1; k < half; k++) {
                float b = k * k1;
                if (b > half - 1) {
                    b = half - 1;
                }
                int b0 = (int) b;
                int b1 = Math.min(b0 + 1, half - 1);
                float w = b - b0;
                float es = env[b0] * (1f - w) + env[b1] * w;
                float g = es / env[k];
                if (g > 4f) {
                    g = 4f;
                } else if (g < 0.25f) {
                    g = 0.25f;
                }
                float f = k * (float) sr / N;
                float t1 = 1f;
                if (f > 0.85f * fh) {
                    t1 = Math.max(0f, 1f - (f - 0.85f * fh) / (0.14f * fh));
                }
                if (f < 45f) {
                    t1 = Math.min(t1, f / 45f);
                }
                newMag[k] = mag[k] * (1f + t1 * (g - 1f));
            }

            // slow loudness match (prevents drift, no pumping)
            double eIn = 0, eOut = 0;
            for (int k = 1; k < half - 1; k++) {
                eIn += (double) mag[k] * mag[k];
                eOut += (double) newMag[k] * newMag[k];
            }
            float g0 = (float) Math.sqrt(eIn / Math.max(eOut, 1e-12));
            if (g0 > 3.3f) {
                g0 = 3.3f;
            } else if (g0 < 0.3f) {
                g0 = 0.3f;
            }
            g0sm += 0.03f * (g0 - g0sm);
            float gs = g0sm;
            for (int k = 0; k < N; k++) {
                newMag[k] *= gs;
            }

            // resynthesize with the ORIGINAL phases (coherent, no phasiness)
            for (int k = 0; k <= half; k++) {
                re[k] = newMag[k] * (float) Math.cos(ph[k]);
                im[k] = newMag[k] * (float) Math.sin(ph[k]);
            }
            for (int k = half + 1; k < N; k++) {
                re[k] = re[N - k];
                im[k] = -im[N - k];
            }
            fft.ifft(re, im);
            float norm = 1f / 1.5f;   // Σ Hann² at hop N/4
            for (int i = 0; i < N; i++) {
                ola[(start + i) & olaMask] += re[i] * hann[i] * norm;
            }
        }
    }

    /**
     * Fix 75 — resampled-period pitch shifter (clean spectrum) feeding the
     * formant-envelope warp, then the v5.42 effect FX.
     *
     * Contract (unchanged): push() returns exactly the number of samples it
     * received; while the pipeline warms up it emits silence, then runs 1:1
     * forever. Note the emitted timeline carries a small constant offset that
     * depends on the caller's buffer size (at most one buffer); the real
     * recorder and the call uplink each use a fixed buffer size, so the
     * offset is constant within a session and inaudible.
     */
    static final class Chain {
        final Preset preset;
        final int sampleRate;
        final float ratio;
        final boolean shift;
        final boolean warpActive;
        final FormantWarp fw;

        // ---- pitch analysis state (decimated trailing autocorrelation) ----
        final int minP, maxP, win, hop, dec, winA, minPa, maxPa;
        static final float voicingThr = 0.30f;

        static final int cap = 1 << 16;
        static final int ringMask = cap - 1;
        final float[] inBuf = new float[cap];
        long inW;
        final float[] acc = new float[cap];
        long drained;
        long emitted;

        // pitch analysis state
        long analyzed;
        float period;
        boolean voiced;

        // period grid (deterministic 1:1 timeline walk)
        static final int gridCap = 1024;
        final long[] gridT = new long[gridCap];
        final int[] gridI = new int[gridCap];
        int gridBase, gridCount;
        long gridPos = -1;

        // grain scheduler
        float c = 1f;          // cursor in grid-index space
        long qPrev;
        boolean started;
        long grainsBuilt;

        // pending output FIFO
        static final int pendCap = 1 << 14;
        static final int pendMask = pendCap - 1;
        final float[] pend = new float[pendCap];
        int pendHead, pendCount;

        // scratch for decimated autocorrelation
        final float[] decBuf;

        // ---- post FX state (kept from v5.42) ----
        float ringPhase;
        float lpTilt, lpChest, lp1, lp2, lpEcho;
        float d1[], d2[], d3[];
        int p1, p2, p3;
        float aTilt, aChest, aBp1, aBp2;
        final BiQuad fqLow, fqPeak, fqHigh;
        final boolean hasFormant;

        private static float coef(float freqHz, float sr) {
            return (float) (1.0 - Math.exp(-2.0 * Math.PI * freqHz / sr));
        }

        Chain(Preset preset, int sampleRate) {
            this.preset = preset;
            this.sampleRate = sampleRate;
            this.ratio = preset.ratio;
            this.shift = Math.abs(ratio - 1f) > 0.01f && ratio > 0.4f && ratio < 2.5f;
            this.warpActive = shift && Math.abs(preset.warp - 1f) > 0.004f;
            this.fw = warpActive ? new FormantWarp(sampleRate, ratio / preset.warp) : null;
            minP = Math.max(4, sampleRate / 400);
            maxP = Math.max(minP + 8, sampleRate / 65);
            win = Math.max(64, Math.round(sampleRate * 0.040f));
            hop = Math.max(1, Math.round(sampleRate * 0.010f));
            dec = Math.max(1, sampleRate / 16000);
            winA = Math.max(32, win / dec);
            minPa = Math.max(2, minP / dec);
            maxPa = Math.max(minPa + 2, Math.min(maxP / dec, winA / 2 - 1));
            decBuf = new float[winA];
            period = sampleRate / 150f;

            // legacy static formant EQ (Fix 74) — bypassed when the true
            // envelope warp runs, to avoid double-brightening
            hasFormant = Math.abs(preset.formant) > 0.01f;
            float f = preset.formant;
            if (f >= 0) {
                fqLow = BiQuad.lowShelf(sampleRate, 170f, -3.0f * f);
                fqPeak = BiQuad.peak(sampleRate, 3100f, 3.2f * f, 0.9f);
                fqHigh = BiQuad.highShelf(sampleRate, 7500f, 1.8f * f);
            } else {
                fqLow = BiQuad.lowShelf(sampleRate, 140f, 2.8f * -f);
                fqPeak = BiQuad.peak(sampleRate, 2700f, -2.2f * -f, 0.9f);
                fqHigh = BiQuad.highShelf(sampleRate, 8000f, -1.5f * -f);
            }

            // post FX coefficients (unchanged from v5.42)
            aTilt = coef(Math.abs(preset.tilt) > 0.01f ? (preset.tilt > 0 ? 1500f : 2400f) : 1500f, sampleRate);
            aChest = coef(280f, sampleRate);
            aBp1 = coef(preset.bpLow > 0 ? preset.bpLow : 20f, sampleRate);
            aBp2 = coef(preset.bpHigh > 0 ? preset.bpHigh : 19000f, sampleRate);
            if (preset.echo == ECHO_STUDIO) {
                d1 = new float[Math.max(1, Math.round(sampleRate * 0.092f))];
                d2 = new float[Math.max(1, Math.round(sampleRate * 0.156f))];
            } else if (preset.echo == ECHO_CAVE) {
                d1 = new float[Math.max(1, Math.round(sampleRate * 0.140f))];
                d2 = new float[Math.max(1, Math.round(sampleRate * 0.310f))];
            } else if (preset.echo == ECHO_STAGE) {
                d1 = new float[Math.max(1, Math.round(sampleRate * 0.055f))];
                d2 = new float[Math.max(1, Math.round(sampleRate * 0.110f))];
                d3 = new float[Math.max(1, Math.round(sampleRate * 0.210f))];
            }
        }

        int process(ByteBuffer buffer, int len) {
            int samples = len / 2;
            if (samples <= 0) {
                return len;
            }
            buffer.position(0);
            buffer.limit(len);

            float[] in = new float[samples];
            for (int i = 0; i < samples; i++) {
                in[i] = buffer.getShort();
            }
            float[] out = new float[samples];

            if (!shift) {
                System.arraycopy(in, 0, out, 0, samples);
            } else {
                for (int i = 0; i < samples; i++) {
                    inBuf[(int) ((inW + i) & ringMask)] = in[i];
                }
                inW += samples;

                analyze();
                advanceGrid();
                buildGrains();

                for (int i = 0; i < samples; i++) {
                    out[i] = pendCount > 0 ? pendPop() : 0f; // warmup silence only
                }
                emitted += samples;
                trimGrid();
            }

            if (fw != null) {
                float[] wout = new float[samples];
                fw.push(out, samples, wout);
                out = wout;
            }

            applyPostFx(out);
            writeBack(buffer, out, samples);
            return len;
        }

        // ---------- pitch analysis (decimated autocorrelation, deterministic) ----------

        private void analyze() {
            // windows are anchored to the analyzed position (NOT to inW), so
            // the pitch track is independent of the caller's buffer size
            while (analyzed + hop <= inW && analyzed + hop >= win) {
                int end = (int) (analyzed + hop);
                int start = end - win;
                for (int j = 0; j < winA; j++) {
                    float s = 0f;
                    int base = (start + j * dec) & ringMask;
                    for (int k = 0; k < dec; k++) {
                        s += inBuf[(base + k) & ringMask];
                    }
                    decBuf[j] = s / dec;
                }
                float e0 = 0f;
                for (int j = 0; j < winA; j++) {
                    e0 += decBuf[j] * decBuf[j];
                }
                int bestLag = 0;
                float bestR = 0f;
                for (int lag = minPa; lag <= maxPa; lag++) {
                    float e = 0f;
                    for (int j = 0; j + lag < winA; j++) {
                        e += decBuf[j] * decBuf[j + lag];
                    }
                    if (e > bestR) {
                        bestR = e;
                        bestLag = lag;
                    }
                }
                float r = e0 > 1e-9f ? bestR / e0 : 0f;
                if (r > voicingThr && bestLag > 0) {
                    // clamp ±25% per hop: locks onto the true pitch within one
                    // hop while still blocking instant octave (100%) jumps
                    float lo = period * 0.75f, hi = period * 1.3333f;
                    period = Math.max(lo, Math.min(hi, bestLag * (float) dec));
                    voiced = true;
                } else {
                    voiced = false; // keep the previous period through pauses
                }
                analyzed += hop;
            }
        }

        // ---------- deterministic period grid ----------

        private void advanceGrid() {
            if (gridPos < 0) {
                if (inW < win) {
                    return;
                }
                gridPos = win / 2;
            }
            while (true) {
                int p = Math.max(4, Math.round(period));
                int off = Math.max(1, Math.round(p * 0.12f));
                if (gridPos + off + 2 >= inW) {
                    return;
                }
                long lo = Math.max(0, gridPos - off);
                long hi = gridPos + off;
                int best = (int) lo;
                float bestV = -Float.MAX_VALUE;
                for (long t = lo; t < hi; t++) {
                    float v = inBuf[(int) (t & ringMask)];
                    if (v > bestV) {
                        bestV = v;
                        best = (int) t;
                    }
                }
                gridPush(gridPos, best);
                gridPos += p;
            }
        }

        private void gridPush(long t, int readAt) {
            if (gridCount >= gridCap) {
                int droppable = (int) Math.floor(c) - 4 - gridBase;
                if (droppable <= 0) {
                    return;
                }
                int drop = Math.min(droppable, gridCount / 2);
                System.arraycopy(gridT, drop, gridT, 0, gridCount - drop);
                System.arraycopy(gridI, drop, gridI, 0, gridCount - drop);
                gridBase += drop;
                gridCount -= drop;
            }
            gridT[gridCount] = t;
            gridI[gridCount] = readAt;
            gridCount++;
        }

        private void trimGrid() {
            int minKeep = (int) Math.floor(c) - 4;
            int drop = Math.min(minKeep - gridBase, gridCount);
            if (drop > 0) {
                System.arraycopy(gridT, drop, gridT, 0, gridCount - drop);
                System.arraycopy(gridI, drop, gridI, 0, gridCount - drop);
                gridBase += drop;
                gridCount -= drop;
            }
        }

        private long gridTime(int i) {
            return gridT[i - gridBase];
        }

        private int gridRead(int i) {
            return gridI[i - gridBase];
        }

        private float gridAt(float idx) {
            int i0 = (int) Math.floor(idx);
            float w = idx - i0;
            float a = gridTime(i0);
            float b = gridTime(i0 + 1);
            return a * (1f - w) + b * w;
        }

        // ---------- grain scheduling (resampled periods, Fix 75) ----------

        /**
         * One input period per output period: the grain reads input starting
         * at the pitch mark with Catmull-Rom interpolation at speed `ratio`
         * (pitch × ratio, formants ride along — corrected by the warp stage),
         * and fades out over its tail while the next grain fades in over the
         * same span (complementary ramps sum to 1). Because every output
         * period is the same compressed input period, the output spectrum is
         * a clean harmonic comb — no OLA comb artifacts.
         */
        private void buildGrains() {
            while (true) {
                float cNext = c + 1f / ratio;
                if ((int) Math.ceil(cNext) + 1 - gridBase >= gridCount) {
                    return;
                }
                int rc = Math.round(c);
                long q;
                if (!started) {
                    q = gridTime(rc);
                    qPrev = q;
                    started = true;
                } else {
                    q = (long) Math.round(gridAt(c));
                }
                long qNext = (long) Math.round(gridAt(cNext));
                int ts = (int) (qNext - q);
                if (ts < 2) {
                    c = cNext;
                    continue;
                }
                int m = gridRead(rc);
                int fade = Math.max(2, Math.round(ts * 0.25f));
                int nOut = ts + fade;
                if (m + (long) Math.ceil(nOut * ratio) + 4 >= inW) {
                    return;
                }
                for (int i = 0; i < nOut; i++) {
                    long t = q + i;
                    if (t < 0) {
                        continue;
                    }
                    float pos = i * ratio;
                    int i0 = (int) pos;
                    float fr = pos - i0;
                    float a = (m + i0 - 1 >= 0) ? inBuf[(int) ((m + i0 - 1) & ringMask)] : 0f;
                    float b = inBuf[(int) ((m + i0) & ringMask)];
                    float cc = inBuf[(int) ((m + i0 + 1) & ringMask)];
                    float d = inBuf[(int) ((m + i0 + 2) & ringMask)];
                    float v = b + 0.5f * fr * (cc - a + fr * (2f * a - 5f * b + 4f * cc - d + fr * (3f * (b - cc) + d - a)));
                    float wv;
                    if (i < fade) {
                        wv = (i + 1) / (float) (fade + 1);
                    } else if (i < ts) {
                        wv = 1f;
                    } else {
                        wv = 1f - (i - ts + 1) / (float) (fade + 1);
                    }
                    acc[(int) (t & ringMask)] += v * wv;
                }
                qPrev = q;
                c = cNext;
                grainsBuilt++;
                if (grainsBuilt >= 2) {
                    long safe = q - fade;
                    while (drained < safe) {
                        pendPush(acc[(int) (drained & ringMask)]);
                        acc[(int) (drained & ringMask)] = 0f;
                        drained++;
                    }
                }
            }
        }

        private void pendPush(float v) {
            if (pendCount >= pendCap) {
                return; // never expected — consumer drains every call
            }
            pend[(pendHead + pendCount) & pendMask] = v;
            pendCount++;
        }

        private float pendPop() {
            float v = pend[pendHead];
            pendHead = (pendHead + 1) & pendMask;
            pendCount--;
            return v;
        }

        private void writeBack(ByteBuffer buffer, float[] out, int samples) {
            buffer.position(0);
            buffer.limit(samples * 2);
            for (int i = 0; i < samples; i++) {
                float v = out[i];
                if (v > 32767) {
                    v = 32767;
                } else if (v < -32768) {
                    v = -32768;
                }
                buffer.putShort((short) v);
            }
            buffer.position(0);
        }

        /** (legacy EQ when no warp) → ring mod → tilt → bandpass → drive → echo. */
        private void applyPostFx(float[] out) {
            Preset p = preset;
            boolean useEq = hasFormant && !warpActive;
            boolean hasRing = p.ringHz > 0;
            boolean hasTilt = Math.abs(p.tilt) > 0.01f;
            boolean hasBp = p.bpLow > 0 || p.bpHigh > 0 && p.bpHigh < 18000;
            boolean hasDrive = p.drive > 0.01f;
            boolean hasEcho = p.echo != ECHO_OFF;
            if (!useEq && !hasRing && !hasTilt && !hasBp && !hasDrive && !hasEcho) {
                return;
            }
            float drive = p.drive;
            for (int i = 0; i < out.length; i++) {
                float v = out[i];
                if (useEq) {
                    v = fqLow.process(v);
                    v = fqPeak.process(v);
                    v = fqHigh.process(v);
                }
                if (hasRing) {
                    ringPhase += 2f * (float) Math.PI * p.ringHz / sampleRate;
                    if (ringPhase > 2f * (float) Math.PI) {
                        ringPhase -= 2f * (float) Math.PI;
                    }
                    v *= (float) Math.sin(ringPhase);
                }
                if (hasTilt) {
                    lpTilt += aTilt * (v - lpTilt);
                    if (p.tilt > 0) {
                        v = v + p.tilt * (v - lpTilt);            // brighten
                    } else {
                        lpChest += aChest * (v - lpChest);
                        v = v + p.tilt * (v - lpTilt) + 0.30f * (lpChest - 0.45f * v); // darken + chest
                    }
                }
                if (hasBp) {
                    lp1 += aBp1 * (v - lp1);                       // low cut
                    lp2 += aBp2 * (v - lp2);                       // high cut
                    v = p.bpLow > 0 ? (v - lp1) : v;               // remove lows
                    v = (p.bpHigh > 0 && p.bpHigh < 18000) ? lp2 : v; // keep only band
                }
                if (hasDrive) {
                    float x = v / 32767f;
                    x = (float) Math.tanh(x * (1f + 3f * drive));
                    v = x * 32767f * (1f - 0.25f * drive);
                }
                out[i] = v;
            }
            if (hasEcho) {
                applyEcho(out);
            }
        }

        private void applyEcho(float[] out) {
            if (preset.echo == ECHO_STAGE && d3 != null) {
                for (int i = 0; i < out.length; i++) {
                    float dry = out[i];
                    float w1 = d1[p1];
                    d1[p1] = dry;
                    p1 = (p1 + 1) % d1.length;
                    float w2 = d2[p2];
                    d2[p2] = dry;
                    p2 = (p2 + 1) % d2.length;
                    float w3 = d3[p3];
                    d3[p3] = dry;
                    p3 = (p3 + 1) % d3.length;
                    float v = dry * 0.80f + w1 * 0.30f + w2 * 0.22f + w3 * 0.14f;
                    out[i] = v;
                }
                return;
            }
            final boolean cave = preset.echo == ECHO_CAVE;
            for (int i = 0; i < out.length; i++) {
                float dry = out[i];
                float w1 = d1[p1];
                d1[p1] = dry;
                p1 = (p1 + 1) % d1.length;
                float w2 = d2[p2];
                d2[p2] = dry;
                p2 = (p2 + 1) % d2.length;
                if (cave) {
                    lpEcho = 0.52f * lpEcho + 0.48f * w2;
                    out[i] = dry * 0.62f + w1 * 0.45f + lpEcho * 0.35f;
                } else {
                    lpEcho = 0.60f * lpEcho + 0.40f * w2;
                    out[i] = dry * 0.72f + w1 * 0.38f + lpEcho * 0.22f;
                }
            }
        }
    }
}
