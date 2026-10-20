/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger.camera;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.WindowManager;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.List;

public class CameraSession {

    public CameraInfo cameraInfo;
    private String currentFlashMode;
    private OrientationEventListener orientationEventListener;
    private int lastOrientation = -1;
    private int lastDisplayOrientation = -1;
    private boolean isVideo;
    private final Size pictureSize;
    private final Size previewSize;
    private final int pictureFormat;
    private boolean initied;
    private int maxZoom;
    private boolean meteringAreaSupported;
    private int currentOrientation;
    private int diffOrientation;
    private int jpegOrientation;
    private boolean sameTakePictureOrientation;
    private boolean flipFront = true;
    private float currentZoom;
    private boolean optimizeForBarcode;
    private boolean useTorch;
    private boolean isRound;
    private boolean destroyed;

    public ArrayList<String> availableFlashModes = new ArrayList<>();

    // LuxGram Fix 41: latest Camera1 face-detection result, consumed by the
    // CameraView shader (skin smoothing / nose slim / teeth whiten).
    public volatile android.hardware.Camera.Face[] luxgramFaces;
    public volatile boolean luxgramHwFacesStarted; // Fix 46: hardware detection actually started
    public volatile long luxgramHwFaceSeenAt;      // Fix 46: last time hardware reported >=1 face
    public final android.hardware.Camera.FaceDetectionListener luxgramFaceListener = (faces, cam) -> {
        luxgramFaces = faces;
        if (faces != null && faces.length > 0) {
            luxgramHwFaceSeenAt = android.os.SystemClock.elapsedRealtime();
        }
    };

    // ================= LuxGram Fix 46: software face detection =================
    // Most phone front cameras expose no hardware face detection
    // (getMaxNumDetectedFaces() == 0), which left the live beauty warps dead
    // (only global skin smoothing worked). The bundled ML Kit detector now
    // covers that gap: one-shot NV21 preview frames are decoded on a tiny
    // executor and the result feeds the exact same uniform arrays the
    // hardware path produces. Zero extra cost while hardware detection works.
    public volatile int luxgramSoftCount;
    public volatile float[] luxgramSoftFaceData = new float[8];
    public volatile float[] luxgramSoftNoseData = new float[4];
    public volatile float[] luxgramSoftMouthData = new float[4];
    public volatile float[] luxgramSoftEyeLData = new float[4];
    public volatile float[] luxgramSoftEyeRData = new float[4];
    // LuxGram Fix 51: real organ rects from the mesh (vec4 per face: cx, cy,
    // halfW, halfH — frame-normalized). Zero halfW = not available.
    public volatile float[] luxgramSoftNoseRect = new float[8];
    public volatile float[] luxgramSoftMouthRect = new float[8];
    public volatile float[] luxgramSoftEyeLRect = new float[8];
    public volatile float[] luxgramSoftEyeRRect = new float[8];
    // LuxGram Fix 52: exact sampled contours from MediaPipe, normalized to source frame.
    public volatile float[] luxgramSoftFaceOval = new float[64]; // 16 points x 2 faces
    public volatile float[] luxgramSoftNosePoly = new float[48]; // 12 points x 2 faces
    public volatile float[] luxgramSoftLipOuter = new float[48]; // 12 points x 2 faces
    public volatile float[] luxgramSoftEyeLPoly = new float[32]; // 8 points x 2 faces
    public volatile float[] luxgramSoftEyeRPoly = new float[32];
    public volatile long luxgramSoftAt;
    private volatile long luxgramSoftLastArmAt;
    private volatile boolean luxgramSoftBusy;
    // LuxGram Fix 50: true when the last software result came from the MediaPipe
    // mesh tracker (iris-grade anchors) rather than the ML Kit box detector.
    private volatile boolean luxgramSoftMeshPrecise;
    // LuxGram Fix 48: tracking state — ROI-guided re-detection, reacquire
    // pacing and an outlier gate against wild landmark jumps.
    private final float[] luxgramSoftRoi = new float[4];   // cx, cy, halfW, halfH (normalized)
    private volatile long luxgramSoftRoiAt;
    private volatile boolean luxgramSoftHadFaces;
    private final float[] luxgramSoftLastCenter = new float[2];
    private volatile boolean luxgramSoftHasLastCenter;
    private volatile int luxgramSoftSkipStreak;

    private static final java.util.concurrent.Executor luxgramSoftExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "LuxGramSoftFace");
                t.setPriority(Thread.MIN_PRIORITY);
                t.setDaemon(true);
                return t;
            });

    private static final long LUXGRAM_SOFT_MIN_INTERVAL_MS = 150;   // while tracking (Fix 50: mesh tracker is fast)
    private static final long LUXGRAM_SOFT_REACQUIRE_MS = 200;      // Fix 50: fast reacquire
    private static final long LUXGRAM_SOFT_RESULT_TTL_MS = 2600;
    private static final long LUXGRAM_HW_STALE_MS = 1200;
    private static final long LUXGRAM_SOFT_ROI_TTL_MS = 1600;       // Fix 48: ROI freshness
    private static final long LUXGRAM_SOFT_OUTLIER_MS = 1200;       // Fix 48: gate window

    /**
     * Called once per rendered frame from the GL thread. Arms a one-shot
     * preview callback when the live beauty needs face anchors, then re-arms
     * itself until faces come back or the user turns the face effects off.
     *
     * LuxGram Fix 50: the MediaPipe mesh tracker runs REGARDLESS of hardware
     * detection — its 478-point temporal result is strictly better than the
     * coarse hardware boxes (which were the source of the jumping face
     * centre). Hardware landmarks remain the fallback when the tracker is
     * unavailable.
     */
    public void luxgramSoftwareDetectTick(android.hardware.Camera.Face[] hwFaces) {
        try {
            if (destroyed || !org.telegram.messenger.LuxGramMedia.cameraBeautyNeedsFaces()) {
                return;
            }
            android.hardware.Camera camera = cameraInfo == null ? null : cameraInfo.camera;
            if (camera == null) {
                return;
            }
            long now = android.os.SystemClock.elapsedRealtime();
            if (luxgramSoftBusy
                    || now - luxgramSoftLastArmAt < (luxgramSoftHadFaces
                            ? LUXGRAM_SOFT_MIN_INTERVAL_MS : LUXGRAM_SOFT_REACQUIRE_MS)) {
                return;
            }
            luxgramSoftLastArmAt = now;
            try {
                camera.setOneShotPreviewCallback(this::luxgramOnSoftFrame);
            } catch (Throwable t) {
                FileLog.e(t);
            }
        } catch (Throwable ignore) {
            // camera preview resilience first
        }
    }

    private long sessionStartedAt = -1;

    private long sessionStartedAt() {
        if (sessionStartedAt < 0) {
            sessionStartedAt = android.os.SystemClock.elapsedRealtime();
        }
        return sessionStartedAt;
    }

    private void luxgramOnSoftFrame(byte[] data, android.hardware.Camera camera) {
        luxgramSoftBusy = true;
        luxgramSoftExecutor.execute(() -> {
            try {
                int w, h;
                try {
                    android.hardware.Camera.Size sz = camera.getParameters().getPreviewSize();
                    w = sz.width;
                    h = sz.height;
                } catch (Throwable t) {
                    w = h = 0;
                }
                if (w <= 0 || h <= 0 || data == null || data.length < w * h) {
                    return;
                }
                long now = android.os.SystemClock.elapsedRealtime();
                // LuxGram Fix 48: when a recent face is known, decode only the
                // ROI crop at a much higher effective resolution; full frame
                // otherwise (probe bumped 384 -> 448).
                float[] roi = luxgramSoftRoi;
                boolean roiFresh = now - luxgramSoftRoiAt < LUXGRAM_SOFT_ROI_TTL_MS
                        && roi[2] > 0.01f && roi[3] > 0.01f;
                android.graphics.Bitmap probe;
                float originX, originY, scaleX, scaleY;
                int x0 = 0, y0 = 0, cw = 0, ch = 0;
                if (roiFresh) {
                    int pcx = (int) (roi[0] * w), pcy = (int) (roi[1] * h);
                    int phw = (int) Math.min(w * 0.45f, roi[2] * w * 1.8f + 24);
                    int phh = (int) Math.min(h * 0.45f, roi[3] * h * 1.8f + 24);
                    x0 = Math.max(0, pcx - phw) & ~1;
                    y0 = Math.max(0, pcy - phh) & ~1;
                    cw = (Math.min(w, pcx + phw) - x0) & ~1;
                    ch = (Math.min(h, pcy + phh) - y0) & ~1;
                }
                if (roiFresh && cw >= 64 && ch >= 64) {
                    probe = luxgramNv21CropToBitmap(data, w, h, x0, y0, cw, ch, 320);
                    if (probe != null) {
                        originX = x0 / (float) w;
                        originY = y0 / (float) h;
                        scaleX = cw / (float) (probe.getWidth() * w);
                        scaleY = ch / (float) (probe.getHeight() * h);
                    } else {
                        probe = luxgramNv21ToBitmap(data, w, h, 448);
                        originX = originY = 0f;
                        scaleX = probe == null ? 1f : 1f / probe.getWidth();
                        scaleY = probe == null ? 1f : 1f / probe.getHeight();
                    }
                } else {
                    probe = luxgramNv21ToBitmap(data, w, h, 448);
                    originX = originY = 0f;
                    scaleX = probe == null ? 1f : 1f / probe.getWidth();
                    scaleY = probe == null ? 1f : 1f / probe.getHeight();
                }
                float[] f = new float[8], n = new float[4], m = new float[4], el = new float[4], er = new float[4];
                // LuxGram Fix 51: organ rect scratch (vec4 per face).
                float[] nRect = new float[8], mRect = new float[8], elRect = new float[8], erRect = new float[8];
                float[] faceOval = new float[64], nosePoly = new float[48], lipOuter = new float[48], eyeLPoly = new float[32], eyeRPoly = new float[32];
                int cnt = 0;
                boolean meshPrecise = false;

                // LuxGram Fix 50: MediaPipe 478-point mesh tracking first —
                // temporal tracking keeps the face centre stable and iris
                // points give exact eye anchors. null = engine unavailable.
                if (probe != null) {
                    org.telegram.messenger.LuxGramFaceMeshTracker.TrackResult tr =
                            org.telegram.messenger.LuxGramFaceMeshTracker.track(probe, now);
                    if (tr != null && tr.count > 0) {
                        // probe-normalized -> frame-normalized factors.
                        float mapNX = probe.getWidth() * scaleX;
                        float mapNY = probe.getHeight() * scaleY;
                        for (int i = 0; i < tr.count && cnt < 2; i++) {
                            f[cnt * 4] = originX + tr.faceData[cnt * 4] * mapNX;
                            f[cnt * 4 + 1] = originY + tr.faceData[cnt * 4 + 1] * mapNY;
                            f[cnt * 4 + 2] = tr.faceData[cnt * 4 + 2] * mapNX;
                            f[cnt * 4 + 3] = tr.faceData[cnt * 4 + 3] * mapNY;
                            n[cnt * 2] = originX + tr.noseData[cnt * 2] * mapNX;
                            n[cnt * 2 + 1] = originY + tr.noseData[cnt * 2 + 1] * mapNY;
                            m[cnt * 2] = originX + tr.mouthData[cnt * 2] * mapNX;
                            m[cnt * 2 + 1] = originY + tr.mouthData[cnt * 2 + 1] * mapNY;
                            el[cnt * 2] = originX + tr.eyeLData[cnt * 2] * mapNX;
                            el[cnt * 2 + 1] = originY + tr.eyeLData[cnt * 2 + 1] * mapNY;
                            er[cnt * 2] = originX + tr.eyeRData[cnt * 2] * mapNX;
                            er[cnt * 2 + 1] = originY + tr.eyeRData[cnt * 2 + 1] * mapNY;
                            // LuxGram Fix 51: real organ rects — centres map like
                            // positions, half-extents map like sizes.
                            nRect[cnt * 4] = originX + tr.noseRect[cnt * 4] * mapNX;
                            nRect[cnt * 4 + 1] = originY + tr.noseRect[cnt * 4 + 1] * mapNY;
                            nRect[cnt * 4 + 2] = tr.noseRect[cnt * 4 + 2] * mapNX;
                            nRect[cnt * 4 + 3] = tr.noseRect[cnt * 4 + 3] * mapNY;
                            mRect[cnt * 4] = originX + tr.mouthRect[cnt * 4] * mapNX;
                            mRect[cnt * 4 + 1] = originY + tr.mouthRect[cnt * 4 + 1] * mapNY;
                            mRect[cnt * 4 + 2] = tr.mouthRect[cnt * 4 + 2] * mapNX;
                            mRect[cnt * 4 + 3] = tr.mouthRect[cnt * 4 + 3] * mapNY;
                            elRect[cnt * 4] = originX + tr.eyeLRect[cnt * 4] * mapNX;
                            elRect[cnt * 4 + 1] = originY + tr.eyeLRect[cnt * 4 + 1] * mapNY;
                            elRect[cnt * 4 + 2] = tr.eyeLRect[cnt * 4 + 2] * mapNX;
                            elRect[cnt * 4 + 3] = tr.eyeLRect[cnt * 4 + 3] * mapNY;
                            erRect[cnt * 4] = originX + tr.eyeRRect[cnt * 4] * mapNX;
                            erRect[cnt * 4 + 1] = originY + tr.eyeRRect[cnt * 4 + 1] * mapNY;
                            erRect[cnt * 4 + 2] = tr.eyeRRect[cnt * 4 + 2] * mapNX;
                            erRect[cnt * 4 + 3] = tr.eyeRRect[cnt * 4 + 3] * mapNY;
                            for (int j = 0; j < 32; j++) {
                                float v = tr.faceOvalData[i * 32 + j];
                                faceOval[cnt * 32 + j] = (j & 1) == 0 ? originX + v * mapNX : originY + v * mapNY;
                            }
                            for (int j = 0; j < 24; j++) {
                                float nv = tr.nosePolyData[i * 24 + j];
                                nosePoly[cnt * 24 + j] = (j & 1) == 0 ? originX + nv * mapNX : originY + nv * mapNY;
                            }
                            for (int j = 0; j < 24; j++) {
                                float v = tr.lipOuterData[i * 24 + j];
                                lipOuter[cnt * 24 + j] = (j & 1) == 0 ? originX + v * mapNX : originY + v * mapNY;
                            }
                            for (int j = 0; j < 16; j++) {
                                float lv = tr.eyeLPolyData[i * 16 + j];
                                float rv = tr.eyeRPolyData[i * 16 + j];
                                eyeLPoly[cnt * 16 + j] = (j & 1) == 0 ? originX + lv * mapNX : originY + lv * mapNY;
                                eyeRPoly[cnt * 16 + j] = (j & 1) == 0 ? originX + rv * mapNX : originY + rv * mapNY;
                            }
                            cnt++;
                        }
                        meshPrecise = cnt > 0;
                    }
                }

                if (cnt == 0) {
                    // LuxGram Fix 45/46: bundled ML Kit fallback — accurate face
                    // rects with real nose/eye/mouth landmarks, fully offline.
                    ArrayList<org.telegram.messenger.LuxGramFaceEngine.FaceResult> found =
                            probe == null ? null : org.telegram.messenger.LuxGramFaceEngine.detectBlocking(probe, 1500);
                    if (found != null && !found.isEmpty()) {
                        // LuxGram Fix 48: stable left-to-right ordering so two-face
                        // slots can't flip between frames.
                        java.util.Collections.sort(found, (a, b) -> Float.compare(a.cx, b.cx));
                        for (int i = 0; i < found.size() && cnt < 2; i++) {
                            org.telegram.messenger.LuxGramFaceEngine.FaceResult r = found.get(i);
                            if (r == null) continue;
                            f[cnt * 4] = originX + r.cx * scaleX;
                            f[cnt * 4 + 1] = originY + r.cy * scaleY;
                            f[cnt * 4 + 2] = r.halfW * scaleX;
                            f[cnt * 4 + 3] = r.halfH * scaleY;
                            n[cnt * 2] = originX + (Float.isNaN(r.noseX) ? r.cx : r.noseX) * scaleX;
                            n[cnt * 2 + 1] = originY + (Float.isNaN(r.noseY) ? r.cy : r.noseY) * scaleY;
                            m[cnt * 2] = originX + (Float.isNaN(r.mouthX) ? r.cx : r.mouthX) * scaleX;
                            m[cnt * 2 + 1] = originY + (Float.isNaN(r.mouthY) ? (r.cy + r.halfH * 0.5f) : r.mouthY) * scaleY;
                            if (!Float.isNaN(r.leftEyeX)) {
                                el[cnt * 2] = originX + r.leftEyeX * scaleX;
                                el[cnt * 2 + 1] = originY + r.leftEyeY * scaleY;
                            } else {
                                el[cnt * 2] = originX + (r.cx - r.halfW * 0.27f) * scaleX;
                                el[cnt * 2 + 1] = originY + (r.cy - r.halfH * 0.20f) * scaleY;
                            }
                            if (!Float.isNaN(r.rightEyeX)) {
                                er[cnt * 2] = originX + r.rightEyeX * scaleX;
                                er[cnt * 2 + 1] = originY + r.rightEyeY * scaleY;
                            } else {
                                er[cnt * 2] = originX + (r.cx + r.halfW * 0.27f) * scaleX;
                                er[cnt * 2 + 1] = originY + (r.cy - r.halfH * 0.20f) * scaleY;
                            }
                            cnt++;
                        }
                        meshPrecise = false;
                    }
                }

                if (cnt == 0) {
                    luxgramSoftCount = 0;
                    luxgramSoftHadFaces = false;
                    luxgramSoftHasLastCenter = false;
                    luxgramSoftMeshPrecise = false;
                } else {
                    // LuxGram Fix 48: outlier gate — a single-face result that
                    // teleports while the previous one is still fresh is almost
                    // always a detector hiccup; keep the old anchors for up to
                    // 3 consecutive skips, then accept reality. (The mesh
                    // tracker already smooths, so this stays as a last resort.)
                    if (cnt == 1 && luxgramSoftCount == 1 && luxgramSoftHasLastCenter
                            && now - luxgramSoftAt < LUXGRAM_SOFT_OUTLIER_MS
                            && luxgramSoftSkipStreak < 3) {
                        float jump = (float) Math.hypot(
                                f[0] - luxgramSoftLastCenter[0],
                                f[1] - luxgramSoftLastCenter[1]);
                        if (jump > 0.45f) {
                            luxgramSoftSkipStreak++;
                            luxgramSoftHadFaces = true;
                            return;
                        }
                    }
                    if (cnt > 0) {
                        luxgramSoftLastCenter[0] = f[0];
                        luxgramSoftLastCenter[1] = f[1];
                        luxgramSoftHasLastCenter = true;
                        luxgramSoftSkipStreak = 0;
                        luxgramSoftRoi[0] = f[0];
                        luxgramSoftRoi[1] = f[1];
                        luxgramSoftRoi[2] = f[2];
                        luxgramSoftRoi[3] = f[3];
                        luxgramSoftRoiAt = now;
                    }
                    luxgramSoftFaceData = f;
                    luxgramSoftNoseData = n;
                    luxgramSoftMouthData = m;
                    luxgramSoftEyeLData = el;
                    luxgramSoftEyeRData = er;
                    luxgramSoftNoseRect = nRect;   // LuxGram Fix 51
                    luxgramSoftMouthRect = mRect;
                    luxgramSoftEyeLRect = elRect;
                    luxgramSoftEyeRRect = erRect;
                    luxgramSoftFaceOval = faceOval;
                    luxgramSoftNosePoly = nosePoly;
                    luxgramSoftLipOuter = lipOuter;
                    luxgramSoftEyeLPoly = eyeLPoly;
                    luxgramSoftEyeRPoly = eyeRPoly;
                    luxgramSoftCount = cnt;
                    luxgramSoftAt = now;
                    luxgramSoftHadFaces = cnt > 0;
                    luxgramSoftMeshPrecise = meshPrecise;
                }
            } catch (Throwable t) {
                FileLog.e(t);
            } finally {
                luxgramSoftBusy = false;
            }
        });
    }

    /** LuxGram Fix 48: NV21 crop + downscale — ROI re-detection at high effective resolution. */
    private static android.graphics.Bitmap luxgramNv21CropToBitmap(byte[] nv21, int w, int h,
                                                                  int x0, int y0, int cw, int ch, int maxSide) {
        try {
            if (x0 < 0 || y0 < 0 || cw <= 0 || ch <= 0 || x0 + cw > w || y0 + ch > h) {
                return null;
            }
            int stride = Math.max(1, Math.max(cw, ch) / maxSide);
            int dw = cw / stride, dh = ch / stride;
            if (dw <= 0 || dh <= 0) return null;
            int[] px = new int[dw * dh];
            int yPlane = w * h;
            for (int dy = 0; dy < dh; dy++) {
                int sy = y0 + Math.min(ch - 1, dy * stride);
                int uvRow = (sy / 2) * w;
                for (int dx = 0; dx < dw; dx++) {
                    int sx = x0 + Math.min(cw - 1, dx * stride);
                    int y = nv21[sy * w + sx] & 0xFF;
                    int uvIdx = yPlane + uvRow + (sx / 2) * 2;
                    int v = (nv21[uvIdx] & 0xFF) - 128;
                    int u = (nv21[uvIdx + 1] & 0xFF) - 128;
                    int r = (int) (y + 1.370705f * v);
                    int g = (int) (y - 0.698001f * v - 0.337633f * u);
                    int b = (int) (y + 1.732446f * u);
                    px[dy * dw + dx] = 0xFF000000
                            | (Math.max(0, Math.min(255, r)) << 16)
                            | (Math.max(0, Math.min(255, g)) << 8)
                            | Math.max(0, Math.min(255, b));
                }
            }
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(dw, dh, android.graphics.Bitmap.Config.ARGB_8888);
            bmp.setPixels(px, 0, dw, 0, 0, dw, dh);
            return bmp;
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    /** Nearest-neighbour NV21 -> RGB conversion, downscaled to keep ML Kit cheap. */
    private static android.graphics.Bitmap luxgramNv21ToBitmap(byte[] nv21, int w, int h, int maxSide) {
        try {
            int stride = Math.max(1, Math.max(w, h) / maxSide);
            int dw = w / stride, dh = h / stride;
            if (dw <= 0 || dh <= 0) return null;
            int[] px = new int[dw * dh];
            int yPlane = w * h;
            for (int dy = 0; dy < dh; dy++) {
                int sy = Math.min(h - 1, dy * stride);
                int uvRow = (sy / 2) * w;
                for (int dx = 0; dx < dw; dx++) {
                    int sx = Math.min(w - 1, dx * stride);
                    int y = nv21[sy * w + sx] & 0xFF;
                    int uvIdx = yPlane + uvRow + (sx / 2) * 2;
                    int v = (nv21[uvIdx] & 0xFF) - 128;
                    int u = (nv21[uvIdx + 1] & 0xFF) - 128;
                    int r = (int) (y + 1.370705f * v);
                    int g = (int) (y - 0.698001f * v - 0.337633f * u);
                    int b = (int) (y + 1.732446f * u);
                    px[dy * dw + dx] = 0xFF000000
                            | (Math.max(0, Math.min(255, r)) << 16)
                            | (Math.max(0, Math.min(255, g)) << 8)
                            | Math.max(0, Math.min(255, b));
                }
            }
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(dw, dh, android.graphics.Bitmap.Config.ARGB_8888);
            bmp.setPixels(px, 0, dw, 0, 0, dw, dh);
            return bmp;
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    /** Fresh software detection result (null when stale/empty). */
    public boolean luxgramSoftFacesFresh(float[] faceOut, float[] noseOut, float[] mouthOut, float[] eyeLOut, float[] eyeROut) {
        int count = luxgramSoftCount;
        if (count <= 0) {
            return false;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - luxgramSoftAt > LUXGRAM_SOFT_RESULT_TTL_MS) {
            return false;
        }
        System.arraycopy(luxgramSoftFaceData, 0, faceOut, 0, faceOut.length);
        System.arraycopy(luxgramSoftNoseData, 0, noseOut, 0, noseOut.length);
        System.arraycopy(luxgramSoftMouthData, 0, mouthOut, 0, mouthOut.length);
        System.arraycopy(luxgramSoftEyeLData, 0, eyeLOut, 0, eyeLOut.length);
        System.arraycopy(luxgramSoftEyeRData, 0, eyeROut, 0, eyeROut.length);
        return true;
    }

    /**
     * LuxGram Fix 51: real organ rectangles accompanying a fresh software
     * result (false = caller must keep its geometric estimates).
     */
    public boolean luxgramSoftOrganRectsFresh(float[] noseRectOut, float[] mouthRectOut,
                                             float[] eyeLRectOut, float[] eyeRRectOut) {
        int count = luxgramSoftCount;
        if (count <= 0) {
            return false;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - luxgramSoftAt > LUXGRAM_SOFT_RESULT_TTL_MS) {
            return false;
        }
        float[] nr = luxgramSoftNoseRect, mr = luxgramSoftMouthRect;
        float[] elr = luxgramSoftEyeLRect, err = luxgramSoftEyeRRect;
        if (nr == null || nr.length < 8 || mr == null || mr.length < 8) {
            return false;
        }
        System.arraycopy(nr, 0, noseRectOut, 0, noseRectOut.length);
        System.arraycopy(mr, 0, mouthRectOut, 0, mouthRectOut.length);
        if (elr != null && elr.length >= 8) System.arraycopy(elr, 0, eyeLRectOut, 0, eyeLRectOut.length);
        if (err != null && err.length >= 8) System.arraycopy(err, 0, eyeRRectOut, 0, eyeRRectOut.length);
        return true;
    }

    /** Exact mesh contours for shader masks; false on ML Kit/hardware fallback. */
    public boolean luxgramSoftPolygonsFresh(float[] faceOvalOut, float[] nosePolyOut, float[] lipOuterOut,
                                           float[] eyeLPolyOut, float[] eyeRPolyOut) {
        if (!luxgramSoftMeshPrecise || luxgramSoftCount <= 0) return false;
        if (android.os.SystemClock.elapsedRealtime() - luxgramSoftAt > LUXGRAM_SOFT_RESULT_TTL_MS) return false;
        float[] oval = luxgramSoftFaceOval, nose = luxgramSoftNosePoly, lips = luxgramSoftLipOuter;
        float[] left = luxgramSoftEyeLPoly, right = luxgramSoftEyeRPoly;
        if (oval == null || oval.length < 64 || nose == null || nose.length < 48 || lips == null || lips.length < 48
                || left == null || left.length < 32 || right == null || right.length < 32) return false;
        System.arraycopy(oval, 0, faceOvalOut, 0, faceOvalOut.length);
        System.arraycopy(nose, 0, nosePolyOut, 0, nosePolyOut.length);
        System.arraycopy(lips, 0, lipOuterOut, 0, lipOuterOut.length);
        System.arraycopy(left, 0, eyeLPolyOut, 0, eyeLPolyOut.length);
        System.arraycopy(right, 0, eyeRPolyOut, 0, eyeRPolyOut.length);
        return true;
    }

    /**
     * LuxGram Fix 50: true when a fresh, mesh-precise (MediaPipe) software
     * result should LEAD the shader feed instead of the coarse hardware
     * boxes. Hardware detection keeps the quality it always had; the mesh
     * simply raises the ceiling whenever it can see a face.
     */
    public boolean luxgramSoftwarePreferred() {
        return luxgramSoftMeshPrecise && luxgramSoftCount > 0
                && (android.os.SystemClock.elapsedRealtime() - luxgramSoftAt) < 400;
    }

    // ================= end Fix 46 software face detection =================

    // Low-cost temporal stabilization for live beauty masks. Camera face callbacks
    // can jitter between frames; an EMA dampens that movement without adding a
    // per-frame ML inference workload.
    private final float[] luxgramSmoothFaceData = new float[8];
    private final float[] luxgramSmoothNoseData = new float[4];
    private final float[] luxgramSmoothMouthData = new float[4];
    private final float[] luxgramSmoothEyeLData = new float[4]; // LuxGram Fix 46
    private final float[] luxgramSmoothEyeRData = new float[4]; // LuxGram Fix 46
    private final float[] luxgramSmoothNoseRect = new float[8]; // LuxGram Fix 51
    private final float[] luxgramSmoothMouthRect = new float[8];
    private final float[] luxgramSmoothEyeLRect = new float[8];
    private final float[] luxgramSmoothEyeRRect = new float[8];
    private final float[] luxgramSmoothFaceOval = new float[64]; // LuxGram Fix 52
    private final float[] luxgramSmoothNosePoly = new float[48]; // exact nose contour
    private final float[] luxgramSmoothLipOuter = new float[48];
    private final float[] luxgramSmoothEyeLPoly = new float[32];
    private final float[] luxgramSmoothEyeRPoly = new float[32];
    private int luxgramSmoothPolygonCount = -1;
    private int luxgramSmoothOrganCount = -1; // LuxGram Fix 51
    private int luxgramSmoothFaceCount = -1;

    public synchronized void smoothLuxGramBeautyLandmarks(float[] faceData, float[] noseData, float[] mouthData,
                                                         float[] eyeLData, float[] eyeRData, int count) {
        if (count <= 0) {
            luxgramSmoothFaceCount = 0;
            java.util.Arrays.fill(luxgramSmoothFaceData, 0f);
            java.util.Arrays.fill(luxgramSmoothNoseData, 0f);
            java.util.Arrays.fill(luxgramSmoothMouthData, 0f);
            java.util.Arrays.fill(luxgramSmoothEyeLData, 0f);
            java.util.Arrays.fill(luxgramSmoothEyeRData, 0f);
            return;
        }
        count = Math.min(2, count);
        if (luxgramSmoothFaceCount != count) {
            System.arraycopy(faceData, 0, luxgramSmoothFaceData, 0, faceData.length);
            System.arraycopy(noseData, 0, luxgramSmoothNoseData, 0, noseData.length);
            System.arraycopy(mouthData, 0, luxgramSmoothMouthData, 0, mouthData.length);
            System.arraycopy(eyeLData, 0, luxgramSmoothEyeLData, 0, eyeLData.length);
            System.arraycopy(eyeRData, 0, luxgramSmoothEyeRData, 0, eyeRData.length);
            luxgramSmoothFaceCount = count;
            return;
        }
        final float newWeight = 0.42f;
        for (int i = 0; i < faceData.length; i++) {
            luxgramSmoothFaceData[i] += (faceData[i] - luxgramSmoothFaceData[i]) * newWeight;
            faceData[i] = luxgramSmoothFaceData[i];
        }
        for (int i = 0; i < noseData.length; i++) {
            luxgramSmoothNoseData[i] += (noseData[i] - luxgramSmoothNoseData[i]) * newWeight;
            noseData[i] = luxgramSmoothNoseData[i];
        }
        for (int i = 0; i < mouthData.length; i++) {
            luxgramSmoothMouthData[i] += (mouthData[i] - luxgramSmoothMouthData[i]) * newWeight;
            mouthData[i] = luxgramSmoothMouthData[i];
        }
        for (int i = 0; i < eyeLData.length; i++) {
            luxgramSmoothEyeLData[i] += (eyeLData[i] - luxgramSmoothEyeLData[i]) * newWeight;
            eyeLData[i] = luxgramSmoothEyeLData[i];
        }
        for (int i = 0; i < eyeRData.length; i++) {
            luxgramSmoothEyeRData[i] += (eyeRData[i] - luxgramSmoothEyeRData[i]) * newWeight;
            eyeRData[i] = luxgramSmoothEyeRData[i];
        }
    }

    /**
     * LuxGram Fix 51: EMA smoothing for the organ rects, same cadence as the
     * anchor smoothing. Rect centres and half-extents are smoothed as one
     * 4-vector so the region never leads/lags its own anchor.
     */
    /** Temporal EMA for mesh contour points, preventing makeup-mask shimmer in video. */
    public synchronized void smoothLuxGramBeautyPolygons(float[] faceOval, float[] nosePoly, float[] lipOuter,
                                                          float[] eyeLPoly, float[] eyeRPoly, int count) {
        if (count <= 0) {
            luxgramSmoothPolygonCount = 0;
            java.util.Arrays.fill(luxgramSmoothFaceOval, 0f);
            java.util.Arrays.fill(luxgramSmoothNosePoly, 0f);
            java.util.Arrays.fill(luxgramSmoothLipOuter, 0f);
            java.util.Arrays.fill(luxgramSmoothEyeLPoly, 0f);
            java.util.Arrays.fill(luxgramSmoothEyeRPoly, 0f);
            return;
        }
        count = Math.min(2, count);
        if (luxgramSmoothPolygonCount != count) {
            System.arraycopy(faceOval, 0, luxgramSmoothFaceOval, 0, faceOval.length);
            System.arraycopy(nosePoly, 0, luxgramSmoothNosePoly, 0, nosePoly.length);
            System.arraycopy(lipOuter, 0, luxgramSmoothLipOuter, 0, lipOuter.length);
            System.arraycopy(eyeLPoly, 0, luxgramSmoothEyeLPoly, 0, eyeLPoly.length);
            System.arraycopy(eyeRPoly, 0, luxgramSmoothEyeRPoly, 0, eyeRPoly.length);
            luxgramSmoothPolygonCount = count;
            return;
        }
        final float weight = 0.42f;
        for (int i = 0; i < faceOval.length; i++) {
            luxgramSmoothFaceOval[i] += (faceOval[i] - luxgramSmoothFaceOval[i]) * weight;
            faceOval[i] = luxgramSmoothFaceOval[i];
        }
        for (int i = 0; i < nosePoly.length; i++) {
            luxgramSmoothNosePoly[i] += (nosePoly[i] - luxgramSmoothNosePoly[i]) * weight;
            nosePoly[i] = luxgramSmoothNosePoly[i];
        }
        for (int i = 0; i < lipOuter.length; i++) {
            luxgramSmoothLipOuter[i] += (lipOuter[i] - luxgramSmoothLipOuter[i]) * weight;
            lipOuter[i] = luxgramSmoothLipOuter[i];
        }
        for (int i = 0; i < eyeLPoly.length; i++) {
            luxgramSmoothEyeLPoly[i] += (eyeLPoly[i] - luxgramSmoothEyeLPoly[i]) * weight;
            eyeLPoly[i] = luxgramSmoothEyeLPoly[i];
        }
        for (int i = 0; i < eyeRPoly.length; i++) {
            luxgramSmoothEyeRPoly[i] += (eyeRPoly[i] - luxgramSmoothEyeRPoly[i]) * weight;
            eyeRPoly[i] = luxgramSmoothEyeRPoly[i];
        }
    }

    public synchronized void smoothLuxGramOrganRects(float[] noseRect, float[] mouthRect,
                                                    float[] eyeLRect, float[] eyeRRect, int count) {
        if (count <= 0) {
            java.util.Arrays.fill(luxgramSmoothNoseRect, 0f);
            java.util.Arrays.fill(luxgramSmoothMouthRect, 0f);
            java.util.Arrays.fill(luxgramSmoothEyeLRect, 0f);
            java.util.Arrays.fill(luxgramSmoothEyeRRect, 0f);
            luxgramSmoothOrganCount = 0;
            return;
        }
        if (luxgramSmoothOrganCount != count) {
            System.arraycopy(noseRect, 0, luxgramSmoothNoseRect, 0, noseRect.length);
            System.arraycopy(mouthRect, 0, luxgramSmoothMouthRect, 0, mouthRect.length);
            System.arraycopy(eyeLRect, 0, luxgramSmoothEyeLRect, 0, eyeLRect.length);
            System.arraycopy(eyeRRect, 0, luxgramSmoothEyeRRect, 0, eyeRRect.length);
            luxgramSmoothOrganCount = count;
            return;
        }
        final float w = 0.42f;
        float[][] all = {noseRect, mouthRect, eyeLRect, eyeRRect};
        float[][] state = {luxgramSmoothNoseRect, luxgramSmoothMouthRect, luxgramSmoothEyeLRect, luxgramSmoothEyeRRect};
        for (int a = 0; a < all.length; a++) {
            for (int i = 0; i < all[a].length; i++) {
                state[a][i] += (all[a][i] - state[a][i]) * w;
                all[a][i] = state[a][i];
            }
        }
    }

    private int infoCameraId = -1;
    Camera.CameraInfo info = new Camera.CameraInfo();

    public static final int ORIENTATION_HYSTERESIS = 5;

    private Camera.AutoFocusCallback autoFocusCallback = (success, camera) -> {
        if (success) {

        } else {

        }
    };
    private int displayOrientation;

    public CameraSession(CameraInfo info, Size preview, Size picture, int format, boolean round) {
        previewSize = preview;
        pictureSize = picture;
        pictureFormat = format;
        cameraInfo = info;
        isRound = round;

        SharedPreferences sharedPreferences = ApplicationLoader.applicationContext.getSharedPreferences("camera", Activity.MODE_PRIVATE);
        currentFlashMode = sharedPreferences.getString(cameraInfo.frontCamera != 0 ? "flashMode_front" : "flashMode", Camera.Parameters.FLASH_MODE_OFF);

        orientationEventListener = new OrientationEventListener(ApplicationLoader.applicationContext) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientationEventListener == null || !initied || orientation == ORIENTATION_UNKNOWN) {
                    return;
                }
                jpegOrientation = roundOrientation(orientation, jpegOrientation);
                WindowManager mgr = (WindowManager) ApplicationLoader.applicationContext.getSystemService(Context.WINDOW_SERVICE);
                int rotation = mgr.getDefaultDisplay().getRotation();
                if (lastOrientation != jpegOrientation || rotation != lastDisplayOrientation) {
                    if (!isVideo) {
                        configurePhotoCamera();
                    }
                    lastDisplayOrientation = rotation;
                    lastOrientation = jpegOrientation;
                }
            }
        };

        if (orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable();
        } else {
            orientationEventListener.disable();
            orientationEventListener = null;
        }
    }

    private void updateCameraInfo() {
        if (infoCameraId != cameraInfo.getCameraId()) {
            Camera.getCameraInfo(infoCameraId = cameraInfo.getCameraId(), this.info);
        }
    }

    private int roundOrientation(int orientation, int orientationHistory) {
        boolean changeOrientation;
        if (orientationHistory == OrientationEventListener.ORIENTATION_UNKNOWN) {
            changeOrientation = true;
        } else {
            int dist = Math.abs(orientation - orientationHistory);
            dist = Math.min(dist, 360 - dist);
            changeOrientation = (dist >= 45 + ORIENTATION_HYSTERESIS);
        }
        if (changeOrientation) {
            return ((orientation + 45) / 90 * 90) % 360;
        }
        return orientationHistory;
    }

    public void setOptimizeForBarcode(boolean value) {
        optimizeForBarcode = value;
        configurePhotoCamera();
    }

    public void checkFlashMode(String mode) {
        ArrayList<String> modes = availableFlashModes;
        if (modes.contains(currentFlashMode)) {
            return;
        }
        currentFlashMode = mode;
        if (isRound) {
            configureRoundCamera(false);
        } else {
            configurePhotoCamera();
            SharedPreferences sharedPreferences = ApplicationLoader.applicationContext.getSharedPreferences("camera", Activity.MODE_PRIVATE);
            sharedPreferences.edit().putString(cameraInfo.frontCamera != 0 ? "flashMode_front" : "flashMode", mode).commit();
        }
    }

    public void setCurrentFlashMode(String mode) {
        currentFlashMode = mode;
        if (isRound) {
            configureRoundCamera(false);
        } else {
            configurePhotoCamera();
            SharedPreferences sharedPreferences = ApplicationLoader.applicationContext.getSharedPreferences("camera", Activity.MODE_PRIVATE);
            sharedPreferences.edit().putString(cameraInfo.frontCamera != 0 ? "flashMode_front" : "flashMode", mode).commit();
        }
    }

    public void setTorchEnabled(boolean enabled) {
        try {
            String beforeFlashMode = currentFlashMode;
            currentFlashMode = enabled ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF;
            if (!TextUtils.equals(beforeFlashMode, currentFlashMode)) {
                if (isRound) {
                    configureRoundCamera(false);
                } else {
                    configurePhotoCamera();
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public String getCurrentFlashMode() {
        return currentFlashMode;
    }

    public String getNextFlashMode() {
        ArrayList<String> modes = availableFlashModes;
        for (int a = 0; a < modes.size(); a++) {
            String mode = modes.get(a);
            if (mode.equals(currentFlashMode)) {
                if (a < modes.size() - 1) {
                    return modes.get(a + 1);
                } else {
                    return modes.get(0);
                }
            }
        }
        return currentFlashMode;
    }

    public void setInitied() {
        initied = true;
    }

    public boolean isInitied() {
        return initied;
    }

    public int getCurrentOrientation() {
        return currentOrientation;
    }

    public boolean isFlipFront() {
        return flipFront;
    }

    public void setFlipFront(boolean value) {
        flipFront = value;
    }

    public int getWorldAngle() {
        return diffOrientation;
    }

    public boolean isSameTakePictureOrientation() {
        return sameTakePictureOrientation;
    }

    protected boolean configureRoundCamera(boolean initial) {
        try {
            isVideo = true;
            Camera camera = cameraInfo.camera;
            if (camera != null) {
                Camera.Parameters params = null;
                try {
                    params = camera.getParameters();
                } catch (Exception e) {
                    FileLog.e(e);
                }

                updateCameraInfo();
                updateRotation();

                if (params != null) {
                    if (initial && BuildVars.LOGS_ENABLED) {
                        FileLog.d("set preview size = " + previewSize.getWidth() + " " + previewSize.getHeight());
                    }
                    params.setPreviewSize(previewSize.getWidth(), previewSize.getHeight());
                    if (initial && BuildVars.LOGS_ENABLED) {
                        FileLog.d("set picture size = " + pictureSize.getWidth() + " " + pictureSize.getHeight());
                    }
                    params.setPictureSize(pictureSize.getWidth(), pictureSize.getHeight());
                    params.setPictureFormat(pictureFormat);
                    params.setRecordingHint(true);
                    maxZoom = params.getMaxZoom();

                    String desiredMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO;
                    if (params.getSupportedFocusModes().contains(desiredMode)) {
                        params.setFocusMode(desiredMode);
                    } else {
                        desiredMode = Camera.Parameters.FOCUS_MODE_AUTO;
                        if (params.getSupportedFocusModes().contains(desiredMode)) {
                            params.setFocusMode(desiredMode);
                        }
                    }

                    int outputOrientation = 0;
                    if (jpegOrientation != OrientationEventListener.ORIENTATION_UNKNOWN) {
                        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                            outputOrientation = (info.orientation - jpegOrientation + 360) % 360;
                        } else {
                            outputOrientation = (info.orientation + jpegOrientation) % 360;
                        }
                    }
                    try {
                        params.setRotation(outputOrientation);
                        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                            sameTakePictureOrientation = (360 - displayOrientation) % 360 == outputOrientation;
                        } else {
                            sameTakePictureOrientation = displayOrientation == outputOrientation;
                        }
                    } catch (Exception e) {
                        //
                    }
                    params.setFlashMode(currentFlashMode);
                    params.setZoom((int) (currentZoom * maxZoom));
                    try {
                        camera.setParameters(params);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                        //
                    }

                    if (params.getMaxNumMeteringAreas() > 0) {
                        meteringAreaSupported = true;
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
        return true;
    }

    public void updateRotation() {
        if (cameraInfo == null) {
            return;
        }

        try {
            updateCameraInfo();
        } catch (Throwable throwable) {
            FileLog.e(throwable);
            return;
        }
        Camera camera = destroyed ? null : cameraInfo.camera;

        displayOrientation = getDisplayOrientation(info, true);
        int cameraDisplayOrientation;

        int degrees = 0;
        if ("samsung".equals(Build.MANUFACTURER) && "sf2wifixx".equals(Build.PRODUCT)) {
            cameraDisplayOrientation = 0;
        } else {
            int temp = displayOrientation;
            switch (temp) {
                case Surface.ROTATION_0:
                    degrees = 0;
                    break;
                case Surface.ROTATION_90:
                    degrees = 90;
                    break;
                case Surface.ROTATION_180:
                    degrees = 180;
                    break;
                case Surface.ROTATION_270:
                    degrees = 270;
                    break;
            }
            if (info.orientation % 90 != 0) {
                info.orientation = 0;
            }
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                temp = (info.orientation + degrees) % 360;
                temp = (360 - temp) % 360;
            } else {
                temp = (info.orientation - degrees + 360) % 360;
            }
            cameraDisplayOrientation = temp;
        }
        currentOrientation = cameraDisplayOrientation;
        if (camera != null) {
            try {
                camera.setDisplayOrientation(currentOrientation);
            } catch (Throwable ignore) {}
        }
        diffOrientation = currentOrientation - displayOrientation;
        if (diffOrientation < 0) {
            diffOrientation += 360;
        }
    }

    protected void configurePhotoCamera() {
        try {
            Camera camera = cameraInfo.camera;
            if (camera != null) {
                Camera.Parameters params = null;
                try {
                    params = camera.getParameters();
                } catch (Exception e) {
                    FileLog.e(e);
                }

                updateCameraInfo();
                updateRotation();

                diffOrientation = currentOrientation - displayOrientation;
                if (diffOrientation < 0) {
                    diffOrientation += 360;
                }

                if (params != null) {
                    params.setPreviewSize(previewSize.getWidth(), previewSize.getHeight());
                    params.setPictureSize(pictureSize.getWidth(), pictureSize.getHeight());
                    params.setPictureFormat(pictureFormat);
                    params.setJpegQuality(100);
                    params.setJpegThumbnailQuality(100);
                    maxZoom = params.getMaxZoom();
                    params.setZoom((int) (currentZoom * maxZoom));

                    if (optimizeForBarcode) {
                        List<String> modes = params.getSupportedSceneModes();
                        if (modes != null && modes.contains(Camera.Parameters.SCENE_MODE_BARCODE)) {
                            params.setSceneMode(Camera.Parameters.SCENE_MODE_BARCODE);
                        }
                        String desiredMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO;
                        if (params.getSupportedFocusModes().contains(desiredMode)) {
                            params.setFocusMode(desiredMode);
                        }
                    } else {
                        String desiredMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE;
                        if (params.getSupportedFocusModes().contains(desiredMode)) {
                            params.setFocusMode(desiredMode);
                        }
                    }

                    int outputOrientation = 0;
                    if (jpegOrientation != OrientationEventListener.ORIENTATION_UNKNOWN) {
                        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                            outputOrientation = (info.orientation - jpegOrientation + 360) % 360;
                        } else {
                            outputOrientation = (info.orientation + jpegOrientation) % 360;
                        }
                    }
                    try {
                        params.setRotation(outputOrientation);
                        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                            sameTakePictureOrientation = (360 - displayOrientation) % 360 == outputOrientation;
                        } else {
                            sameTakePictureOrientation = displayOrientation == outputOrientation;
                        }
                    } catch (Exception e) {
                        //
                    }
                    params.setFlashMode(useTorch ? Camera.Parameters.FLASH_MODE_TORCH : currentFlashMode);

                    try {
                        camera.setParameters(params);
                    } catch (Exception e) {
                        //
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public void focusToRect(Rect focusRect, Rect meteringRect) {
        try {
            Camera camera = cameraInfo.camera;
            if (camera != null) {
                camera.cancelAutoFocus();
                Camera.Parameters parameters = null;
                try {
                    parameters = camera.getParameters();
                } catch (Exception e) {
                    FileLog.e(e);
                }

                if (parameters != null) {
                    parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                    ArrayList<Camera.Area> meteringAreas = new ArrayList<>();
                    meteringAreas.add(new Camera.Area(focusRect, 1000));
                    parameters.setFocusAreas(meteringAreas);

                    if (meteringAreaSupported) {
                        meteringAreas = new ArrayList<>();
                        meteringAreas.add(new Camera.Area(meteringRect, 1000));
                        parameters.setMeteringAreas(meteringAreas);
                    }

                    try {
                        camera.setParameters(parameters);
                        camera.autoFocus(autoFocusCallback);
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    protected int getMaxZoom() {
        return maxZoom;
    }

    public void onStartRecord() {
        isVideo = true;
    }

    public void setZoom(float value) {
        currentZoom = value;
        if (isVideo && Camera.Parameters.FLASH_MODE_ON.equals(currentFlashMode)) {
            useTorch = true;
        }
        if (isRound) {
            configureRoundCamera(false);
        } else {
            configurePhotoCamera();
        }
    }

    protected void configureRecorder(int quality, MediaRecorder recorder) {
        updateCameraInfo();

        int outputOrientation = 0;
        if (jpegOrientation != OrientationEventListener.ORIENTATION_UNKNOWN) {
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                outputOrientation = (info.orientation - jpegOrientation + 360) % 360;
            } else {
                outputOrientation = (info.orientation + jpegOrientation) % 360;
            }
        }
        recorder.setOrientationHint(outputOrientation);

        int highProfile = getHigh();
        boolean canGoHigh = CamcorderProfile.hasProfile(cameraInfo.cameraId, highProfile);
        boolean canGoLow = CamcorderProfile.hasProfile(cameraInfo.cameraId, CamcorderProfile.QUALITY_LOW);
        if (canGoHigh && (quality == 1 || !canGoLow)) {
            recorder.setProfile(CamcorderProfile.get(cameraInfo.cameraId, highProfile));
        } else if (canGoLow) {
            recorder.setProfile(CamcorderProfile.get(cameraInfo.cameraId, CamcorderProfile.QUALITY_LOW));
        } else {
            throw new IllegalStateException("cannot find valid CamcorderProfile");
        }
        isVideo = true;
    }

    public void stopVideoRecording() {
        isVideo = false;
        useTorch = false;
        configurePhotoCamera();
    }

    private int getHigh() {
        if ("LGE".equals(Build.MANUFACTURER) && "g3_tmo_us".equals(Build.PRODUCT)) {
            return CamcorderProfile.QUALITY_480P;
        }
        return CamcorderProfile.QUALITY_HIGH;
    }

    private int getDisplayOrientation(Camera.CameraInfo info, boolean isStillCapture) {
        WindowManager mgr = (WindowManager) ApplicationLoader.applicationContext.getSystemService(Context.WINDOW_SERVICE);
        int rotation = mgr.getDefaultDisplay().getRotation();
        int degrees = 0;

        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
        }

        int displayOrientation;

        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            displayOrientation = (info.orientation + degrees) % 360;
            displayOrientation = (360 - displayOrientation) % 360;

            if (!isStillCapture && displayOrientation == 90) {
                displayOrientation = 270;
            }
            if (!isStillCapture && "Huawei".equals(Build.MANUFACTURER) && "angler".equals(Build.PRODUCT) && displayOrientation == 270) {
                displayOrientation = 90;
            }
        } else {
            displayOrientation = (info.orientation - degrees + 360) % 360;
        }

        return displayOrientation;
    }

    public int getDisplayOrientation() {
        try {
            updateCameraInfo();
            return getDisplayOrientation(info, true);
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0;
    }

    public void setPreviewCallback(Camera.PreviewCallback callback){
        cameraInfo.camera.setPreviewCallback(callback);
    }

    public void setOneShotPreviewCallback(Camera.PreviewCallback callback) {
        if (cameraInfo != null && cameraInfo.camera != null) {
            try {
                cameraInfo.camera.setOneShotPreviewCallback(callback);
            } catch (Exception ignore) {

            }
        }
    }

    public void destroy() {
        initied = false;
        destroyed = true;
        if (orientationEventListener != null) {
            orientationEventListener.disable();
            orientationEventListener = null;
        }
    }

    public Camera.Size getCurrentPreviewSize() {
        return cameraInfo.camera.getParameters().getPreviewSize();
    }

    public Camera.Size getCurrentPictureSize() {
        return cameraInfo.camera.getParameters().getPictureSize();
    }
}
