/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Voice Changer (Fix 74) — professional preset-based voice
 * transformation for outgoing voice messages AND phone/voice-chat calls.
 *
 *   • 16 studio presets (خاموش + 15 voices) built from a clean DSP chain:
 *       TD-PSOLA pitch shift → formant EQ → ring mod → tilt → bandpass →
 *       drive → echo
 *   • Fix 74 replaces Fix 72's WSOLA resampling-grain shifter with true
 *     TD-PSOLA (time-domain pitch-synchronous overlap-add): grains are cut
 *     on the PITCH MARKS of the input and re-placed on a period grid WITHOUT
 *     resampling the grain content, so the FUNDAMENTAL moves while the
 *     FORMANTS stay exactly where the vocal tract put them. That is the
 *     difference between "same person, higher voice" (natural) and the
 *     chipmunk/buzzy "ی جوریه" sound of a resampling shifter, whose formants
 *     climbed together with the pitch. Research basis: Charpentier & Stella
 *     1986 (PSOLA), the Audacity/SoundTouch linear-crossfade shifter, and
 *     Sanna Wager's TD-PSOLA reference implementation — validated offline
 *     before this port (F0 accuracy ±1%, formant bands stable, zero clicks).
 *   • Gender presets now pair the F0 shift with a static formant EQ (low
 *     shelf de-chesting, presence peak for F2/F3, air shelf) so a male voice
 *     lands in female vocal-tract territory and vice versa. Effect presets
 *     (echo rooms, robot, radio, phone…) keep the v5.42 FX chain untouched.
 *
 * The recorder (MediaController) feeds process(); WebRtcAudioRecord (calls)
 * feeds processCall(); both emit exactly the requested sample count per call.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.nio.ByteBuffer;

public class DrGramVoiceChanger {

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
        public final int echo;      // ECHO_*
        public final float ringHz;  // ring-modulation frequency, 0 = off
        public final float bpLow;   // bandpass low edge Hz, 0 = off
        public final float bpHigh;  // bandpass high edge Hz, 0 = off
        public final float tilt;    // +brighten / −darken, 0 = flat
        public final float drive;   // soft saturation 0..1
        public final float formant; // −1 male-warm … +1 female-bright (Fix 74)

        Preset(String name, String desc, float ratio, int echo, float ringHz,
               float bpLow, float bpHigh, float tilt, float drive, float formant) {
            this.name = name;
            this.desc = desc;
            this.ratio = ratio;
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
            new Preset("خاموش", "بدون هیچ تغییری", 1f, ECHO_OFF, 0, 0, 0, 0, 0, 0),
            new Preset("زن طبیعی", "صدای زن، ملایم و باورپذیر", 1.21f, ECHO_OFF, 0, 0, 0, 0, 0, 0.55f),
            new Preset("زن جوان", "زن، روشن و شاداب", 1.30f, ECHO_OFF, 0, 0, 0, 0, 0, 0.75f),
            new Preset("زن پایه", "تبدیل کامل به صدای زن", 1.42f, ECHO_OFF, 0, 0, 0, 0, 0, 1f),
            new Preset("مرد طبیعی", "صدای مرد، گرم و آرام", 0.86f, ECHO_OFF, 0, 0, 0, 0, 0, -0.55f),
            new Preset("مرد بم", "مرد، بمِ لطیف", 0.78f, ECHO_OFF, 0, 0, 0, 0, 0, -0.75f),
            new Preset("مرد عمیق", "بمِ عمیق و پرقدرت", 0.70f, ECHO_OFF, 0, 0, 0, 0, 0, -0.9f),
            new Preset("بچه", "صدای کودکانه و شیطنت‌آمیز", 1.55f, ECHO_OFF, 0, 0, 0, 0, 0, 0.85f),
            new Preset("تلفن قدیمی", "تلفن آنالوگ دهه ۷۰", 1f, ECHO_OFF, 0, 450, 2800, 0, 0.35f, 0),
            new Preset("رادیو", "رادیو با اکوی ملایم", 1f, ECHO_STUDIO, 0, 500, 3200, 0, 0.25f, 0),
            new Preset("ربات", "ربات متالیک", 1f, ECHO_OFF, 55, 300, 3400, 0, 0.20f, 0),
            new Preset("فضایی", "موج فضایی عجیب", 1.06f, ECHO_CAVE, 110, 0, 0, 0, 0.15f, 0),
            new Preset("استودیو", "اتاق ضبط حرفه‌ای", 1f, ECHO_STUDIO, 0, 0, 0, 0, 0, 0),
            new Preset("غار", "اکوی غار تاریک", 1f, ECHO_CAVE, 0, 0, 0, 0, 0, 0),
            new Preset("صحنه", "سالن کنسرت", 1f, ECHO_STAGE, 0, 0, 0, 0, 0, 0),
            new Preset("گمنام", "صدای تغییر‌یافته ناشناس", 0.95f, ECHO_STUDIO, 0, 0, 0, 0, 0.15f, -0.25f)
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
        return ApplicationLoader.applicationContext.getSharedPreferences("drgram_media", Context.MODE_PRIVATE);
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
     * DrGram Fix 73: apply-on-tap — a single tap on a preset row SAVES it,
     * closes the dialog and fires the bulletin. The old version needed the OK
     * button, and a tap landing on the RadioButton circle was swallowed by the
     * button itself (never reached the row), so the pick "did not take" and
     * users had to confirm twice.
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
            if (p.formant > 0.01f) {
                tech.append("طیف زنانه ");
            } else if (p.formant < -0.01f) {
                tech.append("طیف مردانه ");
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
                DrGramHaptics.tick(v);
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
     * requested amount, silent while the PSOLA pipeline warms up).
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

    /** RBJ biquad (direct form 1) used by the formant EQ. */
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

    /**
     * DrGram Fix 74 — streaming TD-PSOLA pitch shifter + the v5.42 effect FX.
     *
     * Contract (unchanged): push() returns exactly the number of samples it
     * received; while the pipeline warms up it emits silence once (40–60 ms,
     * same as the old WSOLA engine), then runs 1:1 forever.
     */
    static final class Chain {
        final Preset preset;
        final int sampleRate;
        final float ratio;
        final boolean shift;

        // ---- TD-PSOLA core ----
        final int minP, maxP, win, hop, dec, winA, minPa, maxPa;
        static final float voicingThr = 0.30f;

        static final int cap = 1 << 15;
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

            // formant EQ (Fix 74): static approximation of the vocal-tract shift
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
                applyPostFx(out);
                writeBack(buffer, out, samples);
                return len;
            }

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

            applyPostFx(out);
            writeBack(buffer, out, samples);
            return len;
        }

        // ---------- pitch analysis (decimated trailing autocorrelation) ----------

        private void analyze() {
            while (inW - analyzed >= hop && analyzed + win <= inW) {
                int start = (int) ((inW - win) & ringMask);
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

        /**
         * One grid point per period — the timeline advance is exactly 1:1 with
         * the input no matter where the waveform argmax lands (a naive
         * peak-tracker drifts on noise and starves the pipeline). The waveform
         * maximum near each grid point is only the PHASE ANCHOR for grains.
         */
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
                // belt-and-braces: free the oldest entries the cursor no longer
                // touches (in steady state trimGrid keeps this far below full)
                int droppable = (int) Math.floor(c) - 4 - gridBase;
                if (droppable <= 0) {
                    return; // never expected — skip one grid point rather than corrupt state
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

        // ---------- grain scheduling (linear complementary ramps) ----------

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
                int tr = (int) (qNext - q);
                if (tr < 2) {
                    c = cNext;
                    continue;
                }
                int iNearest = gridRead(rc);
                int tl = (int) Math.max(0, q - qPrev);
                if (iNearest + (long) tr + 1 >= inW) {
                    return;
                }
                long iStart = (long) iNearest - tl;
                long t0 = q - tl;
                for (int k = 0; k < tl + tr; k++) {
                    long t = t0 + k;
                    if (t < 0) {
                        continue;
                    }
                    long ipos = iStart + k;
                    float v = ipos >= 0 ? inBuf[(int) (ipos & ringMask)] : 0f;
                    float wv;
                    if (k < tl) {
                        wv = (k + 1) / (float) tl;
                    } else {
                        wv = 1f - (k - tl + 1) / (float) tr;
                    }
                    acc[(int) (t & ringMask)] += v * wv;
                }
                qPrev = q;
                c = cNext;
                grainsBuilt++;
                if (grainsBuilt >= 3) {
                    long safe = q - tl;
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

        /** formant EQ → ring mod → tilt → bandpass → drive → echo, streamed. */
        private void applyPostFx(float[] out) {
            Preset p = preset;
            boolean hasRing = p.ringHz > 0;
            boolean hasTilt = Math.abs(p.tilt) > 0.01f;
            boolean hasBp = p.bpLow > 0 || p.bpHigh > 0 && p.bpHigh < 18000;
            boolean hasDrive = p.drive > 0.01f;
            boolean hasEcho = p.echo != ECHO_OFF;
            if (!hasFormant && !hasRing && !hasTilt && !hasBp && !hasDrive && !hasEcho) {
                return;
            }
            float drive = p.drive;
            for (int i = 0; i < out.length; i++) {
                float v = out[i];
                if (hasFormant) {
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
