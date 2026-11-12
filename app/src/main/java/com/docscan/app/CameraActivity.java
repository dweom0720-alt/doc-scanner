package com.docscan.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 연속 자동 촬영 화면.
 * 문서가 화면에 잡혀 잠깐 멈춰 있으면 저절로 찍고, 문서만 잘라 저장한다.
 * 방금 찍은 장과 같은 장이면 다시 찍지 않고, 다음 장을 올려놓으면 또 찍는다.
 */
public class CameraActivity extends AppCompatActivity {
    private static final int REQ_CAM = 7;
    private static final long STABLE_MS = 700, COOLDOWN_MS = 1200;
    private static final double SAME_PAGE = 0.90;

    private PreviewView preview;
    private Overlay overlay;
    private TextView status, doneBtn;
    private ImageView lastThumb;
    private Button torchBtn;
    private TextView modeAuto, modeManual, kindPaper, kindScreen, rangeAuto, rangeFixed, snapBtn;
    private android.content.SharedPreferences prefs;

    private ImageCapture capture;
    private Camera camera;
    private final ExecutorService analysisExec = Executors.newSingleThreadExecutor();
    private final ExecutorService workExec = Executors.newSingleThreadExecutor();
    private PageStore store;

    // 분석 스레드 상태
    private volatile boolean auto = true, screen = false, capturing = false, torch = false;
    // 범위 고정
    private volatile boolean fixed = false, dragging = false, snapReq = false, rebase = false;
    private volatile double[] fixedRel;
    private volatile long editQuietUntil = 0;
    private float[] prevPrint;
    private double[] prevRel;
    private volatile long stableSince = -1, cooldownUntil = 0;
    private volatile float[] lastPrint;
    private volatile double[] latestRel;
    private volatile float[] latestPrint;
    private int added = 0, base = 0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        OpenCVLoader.initLocal();
        store = new PageStore(this);
        prefs = getSharedPreferences("camera", MODE_PRIVATE);
        auto = prefs.getBoolean("auto", true);
        screen = prefs.getBoolean("screen", false);
        fixed = prefs.getBoolean("fixed", false);
        fixedRel = loadFixed();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        base = store.count();
        setContentView(buildUi());
        if (base > 0) {
            status.setText("이어서 찍기 · 지금 " + base + "장, " + (base + 1) + "쪽부터 붙어요");
            Toast.makeText(this, (base + 1) + "쪽부터 이어서 찍습니다", Toast.LENGTH_SHORT).show();
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        else requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
    }

    @Override public void onRequestPermissionsResult(int rc, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(rc, p, r);
        if (rc == REQ_CAM && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else { Toast.makeText(this, "카메라 권한이 있어야 찍을 수 있어요", Toast.LENGTH_LONG).show(); finish(); }
    }

    // ---------- 화면 ----------
    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        preview = new PreviewView(this);
        preview.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));

        overlay = new Overlay(this);
        overlay.listener = (r, done) -> {
            if (!done) { dragging = true; fixedRel = r; return; }
            fixedRel = orderRel(r);
            dragging = false;
            saveFixed();
            editQuietUntil = SystemClock.uptimeMillis() + 1200;
            stableSince = -1;
            if (added > 0) rebase = true; // 범위만 바꾼 것 — 지금 화면은 이미 찍은 것으로 간주
        };
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(0x99000000);
        status.setPadding(dp(16), dp(14), dp(16), dp(14));
        status.setText("문서를 화면에 비추세요");

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.addView(status, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout switches = new LinearLayout(this);
        switches.setGravity(Gravity.CENTER);
        switches.setPadding(dp(10), dp(8), dp(10), dp(8));
        switches.setBackgroundColor(0x66000000);
        modeAuto = seg("자동 촬영"); modeManual = seg("수동 촬영");
        kindPaper = seg("종이"); kindScreen = seg("모니터");
        modeAuto.setOnClickListener(v -> setAuto(true));
        modeManual.setOnClickListener(v -> setAuto(false));
        kindPaper.setOnClickListener(v -> setScreen(false));
        kindScreen.setOnClickListener(v -> setScreen(true));
        switches.addView(group(modeAuto, modeManual));
        View gap = new View(this);
        switches.addView(gap, new LinearLayout.LayoutParams(dp(14), 1));
        switches.addView(group(kindPaper, kindScreen));
        top.addView(switches, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row2 = new LinearLayout(this);
        row2.setGravity(Gravity.CENTER);
        row2.setPadding(dp(10), 0, dp(10), dp(8));
        row2.setBackgroundColor(0x66000000);
        rangeAuto = seg("범위 자동 인식"); rangeFixed = seg("범위 고정");
        rangeAuto.setOnClickListener(v -> setFixed(false));
        rangeFixed.setOnClickListener(v -> setFixed(true));
        row2.addView(group(rangeAuto, rangeFixed));
        snapBtn = seg("자동 맞춤");
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(0xFF16A34A);
        sb.setCornerRadius(dp(18));
        snapBtn.setBackground(sb);
        snapBtn.setTextColor(Color.WHITE);
        snapBtn.setOnClickListener(v -> { snapReq = true; status.setText("범위 찾는 중…"); });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.leftMargin = dp(10);
        row2.addView(snapBtn, sp);
        top.addView(row2, new LinearLayout.LayoutParams(-1, -2));
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xCC000000);
        bar.setPadding(dp(14), dp(14), dp(14), dp(22));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        torchBtn = smallBtn("조명 끔");
        torchBtn.setBackgroundColor(0xFF4B5563);
        torchBtn.setOnClickListener(v -> toggleTorch());
        left.addView(torchBtn);
        bar.addView(left, new LinearLayout.LayoutParams(0, -2, 1));

        View shutter = new View(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.WHITE);
        ring.setStroke(dp(5), 0xFF9CA3AF);
        shutter.setBackground(ring);
        shutter.setOnClickListener(v -> manualShot());
        bar.addView(shutter, new LinearLayout.LayoutParams(dp(74), dp(74)));

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        lastThumb = new ImageView(this);
        lastThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        lastThumb.setBackgroundColor(0xFF374151);
        right.addView(lastThumb, new LinearLayout.LayoutParams(dp(44), dp(58)));
        lastThumb.setOnClickListener(v -> {
            int n = store.count();
            if (n > 0) startActivity(PageViewerActivity.open(this, n - 1));
        });
        doneBtn = new TextView(this);
        doneBtn.setTextColor(Color.WHITE);
        doneBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        doneBtn.setPadding(dp(12), dp(10), dp(4), dp(10));
        doneBtn.setOnClickListener(v -> finish());
        right.addView(doneBtn);
        bar.addView(right, new LinearLayout.LayoutParams(0, -2, 1));

        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        paintModes();
        updateDone();
        return root;
    }

    private Button smallBtn(String t) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(t);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTextColor(Color.WHITE);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
        return b;
    }

    private TextView seg(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(14), dp(8), dp(14), dp(8));
        return v;
    }

    private LinearLayout group(TextView a, TextView b) {
        LinearLayout g = new LinearLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1F2937);
        bg.setCornerRadius(dp(20));
        g.setBackground(bg);
        g.setPadding(dp(3), dp(3), dp(3), dp(3));
        g.addView(a);
        g.addView(b);
        return g;
    }

    private void paintSeg(TextView v, boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(on ? 0xFF2447C9 : 0x00000000);
        bg.setCornerRadius(dp(18));
        v.setBackground(bg);
        v.setTextColor(on ? Color.WHITE : 0xFFD1D5DB);
        v.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private void paintModes() {
        paintSeg(modeAuto, auto); paintSeg(modeManual, !auto);
        paintSeg(kindPaper, !screen); paintSeg(kindScreen, screen);
        paintSeg(rangeAuto, !fixed); paintSeg(rangeFixed, fixed);
        snapBtn.setVisibility(fixed ? View.VISIBLE : View.GONE);
        overlay.editable = fixed;
        if (fixed) overlay.set(fixedRel != null ? fixedRel : defaultRel(), overlay.fw, overlay.fh, 0f);
    }

    private void setFixed(boolean f) {
        if (f && fixedRel == null) fixedRel = latestRel != null ? latestRel.clone() : defaultRel();
        fixed = f;
        prevPrint = null;
        stableSince = -1;
        prefs.edit().putBoolean("fixed", f).apply();
        saveFixed();
        paintModes();
        status.setText(f ? "범위 고정: 네 모서리를 끌어 문서에 맞추세요 (또는 '자동 맞춤')" : "범위 자동 인식: 매번 테두리를 찾아요");
    }

    private static double[] defaultRel() { return new double[]{0.08, 0.08, 0.92, 0.08, 0.92, 0.92, 0.08, 0.92}; }

    private double[] loadFixed() {
        String s = prefs.getString("fixedRel", null);
        if (s == null) return null;
        try {
            String[] p = s.split(",");
            double[] r = new double[8];
            for (int i = 0; i < 8; i++) r[i] = Double.parseDouble(p[i]);
            return r;
        } catch (Exception e) { return null; }
    }

    private void saveFixed() {
        double[] r = fixedRel;
        if (r == null) return;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 8; i++) { if (i > 0) b.append(','); b.append(r[i]); }
        prefs.edit().putString("fixedRel", b.toString()).apply();
    }

    /** 모서리를 엇갈리게 끌어도 좌상·우상·우하·좌하 순서로 정리 */
    private static double[] orderRel(double[] r) {
        double cx = 0, cy = 0;
        for (int i = 0; i < 4; i++) { cx += r[i * 2] / 4; cy += r[i * 2 + 1] / 4; }
        Integer[] idx = {0, 1, 2, 3};
        final double fx = cx, fy = cy;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(Math.atan2(r[a * 2 + 1] - fy, r[a * 2] - fx), Math.atan2(r[b * 2 + 1] - fy, r[b * 2] - fx)));
        double[] o = new double[8];
        for (int i = 0; i < 4; i++) { o[i * 2] = r[idx[i] * 2]; o[i * 2 + 1] = r[idx[i] * 2 + 1]; }
        return o;
    }

    private void setAuto(boolean a) {
        auto = a;
        stableSince = -1;
        prefs.edit().putBoolean("auto", a).apply();
        paintModes();
        status.setText(a ? "자동 촬영: 문서를 비추고 잠깐 멈추면 저절로 찍어요" : "수동 촬영: 가운데 버튼을 누를 때만 찍어요");
    }

    private void setScreen(boolean s) {
        screen = s;
        stableSince = -1;
        prevRel = null;
        prefs.edit().putBoolean("screen", s).apply();
        paintModes();
        status.setText(s ? "모니터 모드: 화면 속 문서를 찾아요 (못 찾으면 화면 전체)" : "종이 모드: 책상 위 종이 문서를 찾아요");
    }

    private void toggleTorch() {
        torch = !torch;
        if (camera != null) camera.getCameraControl().enableTorch(torch);
        torchBtn.setText(torch ? "조명 켬" : "조명 끔");
        torchBtn.setBackgroundColor(torch ? 0xFFF59E0B : 0xFF4B5563);
    }

    private void updateDone() {
        doneBtn.setText("완료\n총 " + (base + added) + "장");
    }

    // ---------- 카메라 ----------
    private void startCamera() {
        final var future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                ResolutionSelector previewSel = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build();
                ResolutionSelector analysisSel = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(new ResolutionStrategy(new Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build();
                ResolutionSelector captureSel = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        .build();

                Preview pv = new Preview.Builder().setResolutionSelector(previewSel).build();
                pv.setSurfaceProvider(preview.getSurfaceProvider());

                ImageAnalysis an = new ImageAnalysis.Builder()
                        .setResolutionSelector(analysisSel)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();
                an.setAnalyzer(analysisExec, this::analyze);

                capture = new ImageCapture.Builder()
                        .setResolutionSelector(captureSel)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                provider.unbindAll();
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, an, capture);
            } catch (Exception e) {
                Toast.makeText(this, "카메라를 열 수 없습니다: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ---------- 실시간 분석 ----------
    private void analyze(ImageProxy img) {
        try {
            int w = img.getWidth(), h = img.getHeight();
            ImageProxy.PlaneProxy pl = img.getPlanes()[0];
            ByteBuffer buf = pl.getBuffer();
            int rs = pl.getRowStride();
            byte[] px = new byte[w * h * 4];
            if (rs == w * 4) {
                buf.rewind();
                buf.get(px, 0, Math.min(px.length, buf.remaining()));
            } else {
                for (int y = 0; y < h; y++) { buf.position(y * rs); buf.get(px, y * w * 4, w * 4); }
            }
            Mat rgba = new Mat(h, w, CvType.CV_8UC4);
            rgba.put(0, 0, px);
            Mat rgb = new Mat();
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);
            rgba.release();
            int rot = img.getImageInfo().getRotationDegrees();
            if (rot != 0) {
                Mat r = new Mat();
                Core.rotate(rgb, r, rot == 90 ? Core.ROTATE_90_CLOCKWISE : rot == 180 ? Core.ROTATE_180 : Core.ROTATE_90_COUNTERCLOCKWISE);
                rgb.release();
                rgb = r;
            }
            int fw = rgb.cols(), fh = rgb.rows();
            final boolean scr = screen;
            final boolean fix = fixed;
            long now = SystemClock.uptimeMillis();
            double[] rel = null;
            float[] print = null;

            if (fix && snapReq) {
                snapReq = false;
                Point[] d = DocDetector.detect(rgb, 640, scr);
                if (d != null) {
                    double[] r = new double[8];
                    for (int i = 0; i < 4; i++) { r[i * 2] = d[i].x / fw; r[i * 2 + 1] = d[i].y / fh; }
                    fixedRel = r;
                    saveFixed();
                    editQuietUntil = now + 1200;
                    if (added > 0) rebase = true;
                    runOnUiThread(() -> Toast.makeText(this, "범위를 맞췄어요 · 필요하면 모서리를 끌어 다듬으세요", Toast.LENGTH_SHORT).show());
                } else {
                    runOnUiThread(() -> Toast.makeText(this, "범위를 못 찾았어요 · 모서리를 직접 끌어 맞춰주세요", Toast.LENGTH_LONG).show());
                }
            }

            boolean stable;
            if (fix) {
                double[] fr = fixedRel != null ? fixedRel : defaultRel();
                rel = fr.clone();
                Point[] q = new Point[4];
                for (int i = 0; i < 4; i++) q[i] = new Point(rel[i * 2] * fw, rel[i * 2 + 1] * fh);
                print = DocDetector.fingerprint(rgb, q);
                rgb.release();
                // 상자 안 내용이 그대로 멈춰 있는지(다음 페이지로 넘어간 뒤 화면이 안정됐는지)
                if (dragging || now < editQuietUntil) stableSince = -1;
                else if (prevPrint == null || DocDetector.similarity(prevPrint, print) < 0.95 || stableSince < 0) stableSince = now;
                prevPrint = print;
                stable = stableSince >= 0 && now - stableSince >= STABLE_MS;
                if (rebase && !dragging && now >= editQuietUntil) { lastPrint = print; rebase = false; }
            } else {
                Point[] q = DocDetector.detect(rgb, 480, scr);
                if (q != null) {
                    rel = new double[8];
                    for (int i = 0; i < 4; i++) { rel[i * 2] = q[i].x / fw; rel[i * 2 + 1] = q[i].y / fh; }
                    print = DocDetector.fingerprint(rgb, q);
                }
                rgb.release();
                if (rel == null) { stableSince = -1; stable = false; }
                else {
                    if (prevRel == null || maxDelta(prevRel, rel) > 0.025 || stableSince < 0) stableSince = now;
                    stable = now - stableSince >= STABLE_MS;
                }
                prevRel = rel;
            }
            latestRel = rel;
            latestPrint = print;
            float progress = rel == null || stableSince < 0 ? 0f : Math.min(1f, (now - stableSince) / (float) STABLE_MS);

            String msg;
            boolean shoot = false;
            if (capturing) msg = "찍는 중…";
            else if (fix && dragging) msg = "모서리를 맞추는 중…";
            else if (fix && !auto) msg = "범위 고정 · 가운데 버튼으로 찍으세요";
            else if (rel == null) msg = scr ? (auto ? "모니터 화면을 비추세요" : "수동 촬영 — 가운데 버튼으로 찍으세요")
                                            : (auto ? "문서를 화면에 비추세요" : "수동 촬영 — 가운데 버튼으로 찍으세요");
            else if (!auto) msg = "범위 인식됨 — 가운데 버튼으로 찍으세요";
            else if (now < cooldownUntil) msg = fix ? "저장했습니다 · 다음 페이지로 넘기세요" : "저장했습니다 · 다음 장을 놓아주세요";
            else if (lastPrint != null && DocDetector.similarity(lastPrint, print) > SAME_PAGE)
                msg = fix ? "같은 화면이에요 · 다음 페이지로 넘기면 자동으로 찍어요" : "방금 찍은 장이에요 · 다음 장을 놓아주세요";
            else if (!stable) msg = fix ? "화면이 멈추면 찍어요…" : "그대로 잠깐만 멈춰 주세요";
            else { msg = "찍는 중…"; shoot = true; }

            final double[] relF = rel;
            final float prog = progress;
            final String m = msg;
            runOnUiThread(() -> {
                overlay.set(relF, fw, fh, prog);
                status.setText(m);
            });
            if (shoot) takeShot(rel, print);
        } catch (Throwable t) {
            // 분석 한 프레임 실패는 무시하고 다음 프레임으로
        } finally {
            img.close();
        }
    }

    private static double maxDelta(double[] a, double[] b) {
        double m = 0;
        for (int i = 0; i < 8; i++) m = Math.max(m, Math.abs(a[i] - b[i]));
        return m;
    }

    private void manualShot() {
        if (capturing) return;
        takeShot(latestRel, latestPrint);
    }

    private void takeShot(double[] rel, float[] print) {
        if (capture == null || capturing) return;
        final boolean scr = screen;
        capturing = true;
        runOnUiThread(() -> overlay.flash());
        capture.takePicture(workExec, new ImageCapture.OnImageCapturedCallback() {
            @Override public void onCaptureSuccess(@NonNull ImageProxy image) {
                Bitmap page = null;
                try {
                    ImageProxy.PlaneProxy pl = image.getPlanes()[0];
                    ByteBuffer bb = pl.getBuffer();
                    bb.rewind();
                    byte[] jpeg = new byte[bb.remaining()];
                    bb.get(jpeg);
                    int rot = image.getImageInfo().getRotationDegrees();
                    image.close();
                    Bitmap full = Cropper.decode(jpeg, rot, 3200);
                    page = Cropper.crop(full, relShot, scr, fix);
                    if (page != full) full.recycle();
                    store.save(page);
                    lastPrint = print;
                    final Bitmap thumb = Bitmap.createScaledBitmap(page, 88,
                            Math.max(1, Math.round(88f * page.getHeight() / page.getWidth())), true);
                    page.recycle();
                    runOnUiThread(() -> {
                        added++;
                        updateDone();
                        lastThumb.setImageBitmap(thumb);
                        buzz();
                    });
                } catch (Throwable t) {
                    try { image.close(); } catch (Throwable ignored) {}
                    runOnUiThread(() -> Toast.makeText(CameraActivity.this, "저장 실패: " + t.getMessage(), Toast.LENGTH_SHORT).show());
                } finally {
                    cooldownUntil = SystemClock.uptimeMillis() + COOLDOWN_MS;
                    stableSince = -1;
                    capturing = false;
                }
            }

            @Override public void onError(@NonNull ImageCaptureException e) {
                capturing = false;
                runOnUiThread(() -> Toast.makeText(CameraActivity.this, "촬영 실패: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void buzz() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(40);
        } catch (Throwable ignored) {}
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        analysisExec.shutdown();
        workExec.shutdown();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ---------- 테두리 표시 ----------
    interface EditListener { void onEdit(double[] rel, boolean done); }

    private static final class Overlay extends View {
        private double[] rel;
        int fw = 3, fh = 4;
        private float progress;
        private long flashUntil;
        boolean editable;
        EditListener listener;
        private int drag = -1;
        private final float density;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knobRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Overlay(Context c) {
            super(c);
            density = c.getResources().getDisplayMetrics().density;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(4 * density);
            line.setStrokeJoin(Paint.Join.ROUND);
            fill.setStyle(Paint.Style.FILL);
            knob.setStyle(Paint.Style.FILL);
            knob.setColor(Color.WHITE);
            knobRing.setStyle(Paint.Style.STROKE);
            knobRing.setStrokeWidth(4 * density);
            knobRing.setColor(0xFF2447C9);
        }

        void set(double[] r, int w, int h, float p) {
            fw = w; fh = h; progress = p;
            if (drag < 0) rel = r == null ? null : r.clone();
            invalidate();
        }

        void flash() { flashUntil = SystemClock.uptimeMillis() + 150; invalidate(); }

        private float sc() { return Math.min(getWidth() / (float) fw, getHeight() / (float) fh); }
        private float ox() { return (getWidth() - fw * sc()) / 2f; }
        private float oy() { return (getHeight() - fh * sc()) / 2f; }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            if (!editable || rel == null) return false;
            float s = sc(), ox = ox(), oy = oy();
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: {
                    float best = 48 * density;
                    drag = -1;
                    for (int i = 0; i < 4; i++) {
                        float x = ox + (float) rel[i * 2] * fw * s, y = oy + (float) rel[i * 2 + 1] * fh * s;
                        float d = (float) Math.hypot(e.getX() - x, e.getY() - y);
                        if (d < best) { best = d; drag = i; }
                    }
                    if (drag < 0) return false;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                case android.view.MotionEvent.ACTION_MOVE: {
                    if (drag < 0) return false;
                    double rx = Math.max(0, Math.min(1, (e.getX() - ox) / (fw * s)));
                    double ry = Math.max(0, Math.min(1, (e.getY() - oy) / (fh * s)));
                    rel[drag * 2] = rx;
                    rel[drag * 2 + 1] = ry;
                    invalidate();
                    if (listener != null) listener.onEdit(rel.clone(), false);
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    if (drag < 0) return false;
                    drag = -1;
                    if (listener != null) listener.onEdit(rel.clone(), true);
                    return true;
                }
            }
            return false;
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float sc = sc(), ox = ox(), oy = oy();
            if (rel != null) {
                path.reset();
                for (int i = 0; i < 4; i++) {
                    float x = ox + (float) rel[i * 2] * fw * sc, y = oy + (float) rel[i * 2 + 1] * fh * sc;
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                path.close();
                int col = editable ? (progress >= 1f ? 0xFF22C55E : 0xFF60A5FA) : (progress >= 1f ? 0xFF22C55E : 0xFFFACC15);
                fill.setColor((col & 0x00FFFFFF) | 0x26000000);
                line.setColor(col);
                c.drawPath(path, fill);
                c.drawPath(path, line);
                if (editable) {
                    for (int i = 0; i < 4; i++) {
                        float x = ox + (float) rel[i * 2] * fw * sc, y = oy + (float) rel[i * 2 + 1] * fh * sc;
                        float r = (i == drag ? 20 : 14) * density;
                        c.drawCircle(x, y, r, knob);
                        c.drawCircle(x, y, r, knobRing);
                    }
                }
            }
            if (SystemClock.uptimeMillis() < flashUntil) {
                c.drawColor(0x88FFFFFF);
                postInvalidateDelayed(160);
            }
        }
    }
}
