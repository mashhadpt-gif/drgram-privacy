/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Voice Changer (Fix 72 v3) — professional preset-based voice
 * transformation for outgoing voice messages AND phone/voice-chat calls.
 *
 *   • 16 studio presets (خاموش + 15 voices) built from a clean DSP chain:
 *       WSOLA pitch shift → ring mod → spectral tilt → bandpass → drive → echo
 *   • Fix 72 replaces Fix 40/41's dual-pointer delay-line shifter (which
 *     crossfaded two reads 20 ms apart and produced the buzzy/warbled
 *     "زنبوری" artifact, and whose strong ratios sounded scary instead of
 *     gender-accurate) with a WSOLA-style granular shifter: grains are
 *     waveform-ALIGNED by correlation search before overlap-add, so the
 *     output keeps the natural pitch track of the voice without metallic
 *     sidebands. Moderate ratios (1.16–1.34 / 0.68–0.88) + tilt EQ replace
 *     the old extreme ones — the result is a believable, elegant voice.
 *   • Every preset is pitch + EQ + echo combined, so the chat menu exposes
 *     ALL effects (echo rooms, robot, radio, phone, space…) in one place.
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

        Preset(String name, String desc, float ratio, int echo, float ringHz,
               float bpLow, float bpHigh, float tilt, float drive) {
            this.name = name;
            this.desc = desc;
            this.ratio = ratio;
            this.echo = echo;
            this.ringHz = ringHz;
            this.bpLow = bpLow;
            this.bpHigh = bpHigh;
            this.tilt = tilt;
            this.drive = drive;
        }
    }

    private static final Preset[] PRESETS = {
            new Preset("خاموش", "بدون هیچ تغییری", 1f, ECHO_OFF, 0, 0, 0, 0, 0),
            new Preset("زن طبیعی", "صدای زن، ملایم و باورپذیر", 1.16f, ECHO_OFF, 0, 0, 0, 0.25f, 0),
            new Preset("زن جوان", "زن، روشن و شاداب", 1.26f, ECHO_OFF, 0, 0, 0, 0.35f, 0),
            new Preset("زن پایه", "تبدیل کامل به صدای زن", 1.34f, ECHO_OFF, 0, 0, 0, 0.42f, 0),
            new Preset("مرد طبیعی", "صدای مرد، گرم و آرام", 0.88f, ECHO_OFF, 0, 0, 0, -0.25f, 0),
            new Preset("مرد بم", "مرد، بمِ لطیف", 0.78f, ECHO_OFF, 0, 0, 0, -0.35f, 0),
            new Preset("مرد عمیق", "بمِ عمیق و پرقدرت", 0.68f, ECHO_OFF, 0, 0, 0, -0.42f, 0),
            new Preset("بچه", "صدای کودکانه و شیطنت‌آمیز", 1.48f, ECHO_OFF, 0, 0, 0, 0.30f, 0),
            new Preset("تلفن قدیمی", "تلفن آنالوگ دهه ۷۰", 1f, ECHO_OFF, 0, 450, 2800, 0, 0.35f),
            new Preset("رادیو", "رادیو با اکوی ملایم", 1f, ECHO_STUDIO, 0, 500, 3200, 0, 0.25f),
            new Preset("ربات", "ربات متالیک", 1f, ECHO_OFF, 55, 300, 3400, 0, 0.20f),
            new Preset("فضایی", "موج فضایی عجیب", 1.06f, ECHO_CAVE, 110, 0, 0, 0, 0.15f),
            new Preset("استودیو", "اتاق ضبط حرفه‌ای", 1f, ECHO_STUDIO, 0, 0, 0, 0, 0),
            new Preset("غار", "اکوی غار تاریک", 1f, ECHO_CAVE, 0, 0, 0, 0, 0),
            new Preset("صحنه", "سالن کنسرت", 1f, ECHO_STAGE, 0, 0, 0, 0, 0),
            new Preset("گمنام", "صدای تغییر‌یافته ناشناس", 0.93f, ECHO_STUDIO, 0, 0, 0, 0, 0.15f)
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
     * requested amount, silent while the grain pipeline warms up).
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

    static final class Chain {
        final Preset preset;
        final int sampleRate;

        // WSOLA grain shifter
        final int w;          // grain output length (samples)
        final int h;          // hop = w / 2
        final int tauMax;     // alignment search span
        final int tauStep;
        final float ratio;
        final boolean shift;
        final float[] hann;
        final float[] lastH;  // template = last emitted hop
        final float[] acc;    // 2h overlap-add ring
        final float[] inRing; // input history ring
        final int inCap;
        long inW;             // total input samples written
        long grains;          // grains built so far
        final float[] pend;   // finished output samples FIFO
        int pendHead;
        int pendCount;
        final int pendCap;

        // post FX state
        float ringPhase;
        float lpTilt, lpChest, lp1, lp2, hpPrev, hpOut, lpEcho;
        float d1[], d2[], d3[];
        int p1, p2, p3;
        float aTilt, aChest, aBp1, aBp2, aHp;

        private static float coef(float freqHz, float sr) {
            return (float) (1.0 - Math.exp(-2.0 * Math.PI * freqHz / sr));
        }

        Chain(Preset preset, int sampleRate) {
            this.preset = preset;
            this.sampleRate = sampleRate;
            this.ratio = preset.ratio;
            this.shift = Math.abs(ratio - 1f) > 0.01f;
            int w0 = Math.max(256, Math.round(sampleRate * 0.048f));
            h = Math.max(128, w0 / 2);
            w = h * 2; // keep exact power-of-two-of-hop relation
            tauMax = Math.max(16, Math.round(h * 0.40f));
            tauStep = Math.max(8, tauMax / 6);
            hann = new float[w];
            for (int i = 0; i < w; i++) {
                hann[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (w - 1)));
            }
            lastH = new float[h];
            acc = new float[2 * h];
            inCap = 4 * w + 8192;
            inRing = new float[inCap];
            pendCap = 16 * h; // Fix 73: headroom for large recorder buffers
            pend = new float[pendCap];
            // post FX coefficients
            aTilt = coef(Math.abs(preset.tilt) > 0.01f ? (preset.tilt > 0 ? 1500f : 2400f) : 1500f, sampleRate);
            aChest = coef(280f, sampleRate);
            aBp1 = coef(preset.bpLow > 0 ? preset.bpLow : 20f, sampleRate);
            aBp2 = coef(preset.bpHigh > 0 ? preset.bpHigh : 19000f, sampleRate);
            aHp = coef(preset.bpLow > 0 ? preset.bpLow : 20f, sampleRate);
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

            // 1) pull input shorts
            float[] in = new float[samples];
            for (int i = 0; i < samples; i++) {
                in[i] = buffer.getShort();
            }
            float[] out = new float[samples];

            if (!shift) {
                // no pitch shift: straight copy, FX applied below
                System.arraycopy(in, 0, out, 0, samples);
                applyPostFx(out);
                writeBack(buffer, out, samples);
                return len;
            }

            // 2) push into the input history ring
            for (int i = 0; i < samples; i++) {
                inRing[(int) ((inW + i) % inCap)] = in[i];
            }
            inW += samples;

            // 3) build grains until we have enough finished output
            int filled = 0;
            while (filled < samples) {
                if (pendCount == 0) {
                    if (!tryBuildGrain()) {
                        // pipeline warming up — emit silence for the remainder
                        while (filled < samples) {
                            out[filled++] = 0f;
                        }
                        break;
                    }
                    continue;
                }
                out[filled++] = pendPop();
            }
            applyPostFx(out);
            writeBack(buffer, out, samples);
            return len;
        }

        private boolean tryBuildGrain() {
            long p = grains * (long) h;             // nominal input start of this grain
            long need = p + tauMax + (long) (w * ratio) + h + 8;
            // DrGram Fix 73: the old `p < tauMax` guard blocked grain #0 forever
            // (p = 0 until the first grain builds, so the pipeline emitted pure
            // silence for the WHOLE recording — "records but never plays").
            // The first grain has no template and never looks backwards, so the
            // guard must apply to later grains only. Fix 73 also PRIMES one extra
            // hop of lead before grain #0: the invariant d + pending = C then
            // holds permanently, so the steady state can never starve.
            if (grains == 0) {
                need += h;
            } else if (p < tauMax) {
                return false;                        // not enough history yet (later grains)
            }
            if (inW < need) {
                return false;
            }
            // WSOLA alignment: pick the offset whose next h input samples best
            // continue the last emitted hop (search stride 4 for speed)
            int bestTau = 0;
            if (grains > 0) {
                long bestScore = Long.MAX_VALUE;
                for (int tau = -tauMax; tau <= tauMax; tau += tauStep) {
                    long start = p + tau;
                    if (start < 0 || start + h + 4 >= inW) {
                        continue;
                    }
                    long score = 0;
                    for (int i = 0; i < h; i += 4) {
                        float diff = lastH[i] - inRing[(int) ((start + i) % inCap)];
                        score += (long) (diff * diff * 1024);
                        if (score >= bestScore) {
                            break;
                        }
                    }
                    if (score < bestScore) {
                        bestScore = score;
                        bestTau = tau;
                    }
                }
            }
            long start = p + bestTau;
            int base = (int) (p % acc.length);
            for (int i = 0; i < w; i++) {
                double pos = start + i * (double) ratio;
                int i0 = (int) Math.floor(pos);
                float frac = (float) (pos - i0);
                float a = inRing[(int) ((i0) % inCap)];
                float b = inRing[(int) ((i0 + 1) % inCap)];
                float v = (a * (1f - frac) + b * frac) * hann[i];
                acc[(base + i) % acc.length] += v;
            }
            // the first h cells are now final: push to pend, keep as template, zero them
            for (int i = 0; i < h; i++) {
                float v = acc[(base + i) % acc.length];
                acc[(base + i) % acc.length] = 0f;
                lastH[i] = v;
                pendPush(v);
            }
            grains++;
            return true;
        }

        private void pendPush(float v) {
            if (pendCount >= pendCap) {
                return; // should never happen (consumer drains every call)
            }
            pend[(pendHead + pendCount) % pendCap] = v;
            pendCount++;
        }

        private float pendPop() {
            float v = pend[pendHead];
            pendHead = (pendHead + 1) % pendCap;
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

        /** ring mod → tilt → bandpass → drive → echo, streamed per sample. */
        private void applyPostFx(float[] out) {
            Preset p = preset;
            boolean hasRing = p.ringHz > 0;
            boolean hasTilt = Math.abs(p.tilt) > 0.01f;
            boolean hasBp = p.bpLow > 0 || p.bpHigh > 0 && p.bpHigh < 18000;
            boolean hasDrive = p.drive > 0.01f;
            boolean hasEcho = p.echo != ECHO_OFF;
            if (!hasRing && !hasTilt && !hasBp && !hasDrive && !hasEcho) {
                return;
            }
            float drive = p.drive;
            for (int i = 0; i < out.length; i++) {
                float v = out[i];
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
