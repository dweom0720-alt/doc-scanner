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
    private Button autoBtn, torchBtn;

    private ImageCapture capture;
    private Camera camera;
    private final ExecutorService analysisExec = Executors.newSingleThreadExecutor();
    private final ExecutorService workExec = Executors.newSingleThreadExecutor();
    private PageStore store;

    // 분석 스레드 상태
    private volatile boolean auto = true, capturing = false, torch = false;
    private double[] prevRel;
    private volatile long stableSince = -1, cooldownUntil = 0;
    private volatile float[] lastPrint;
    private volatile double[] latestRel;
    private volatile float[] latestPrint;
    private int added = 0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        OpenCVLoader.initLocal();
        store = new PageStore(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
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
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(0x99000000);
        status.setPadding(dp(16), dp(14), dp(16), dp(14));
        status.setText("문서를 화면에 비추세요");
        root.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xCC000000);
        bar.setPadding(dp(14), dp(14), dp(14), dp(22));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        autoBtn = smallBtn("자동 켬");
        autoBtn.setOnClickListener(v -> { auto = !auto; paintAuto(); });
        torchBtn = smallBtn("조명 끔");
        torchBtn.setOnClickListener(v -> toggleTorch());
        left.addView(autoBtn);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = dp(8);
        left.addView(torchBtn, tl);
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
        doneBtn = new TextView(this);
        doneBtn.setTextColor(Color.WHITE);
        doneBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        doneBtn.setPadding(dp(12), dp(10), dp(4), dp(10));
        doneBtn.setOnClickListener(v -> finish());
        right.addView(doneBtn);
        bar.addView(right, new LinearLayout.LayoutParams(0, -2, 1));

        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        paintAuto();
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

    private void paintAuto() {
        autoBtn.setText(auto ? "자동 켬" : "자동 끔");
        autoBtn.setBackgroundColor(auto ? 0xFF16A34A : 0xFF4B5563);
        if (!auto) status.setText("자동 촬영 꺼짐 — 가운데 버튼으로 찍으세요");
    }

    private void toggleTorch() {
        torch = !torch;
        if (camera != null) camera.getCameraControl().enableTorch(torch);
        torchBtn.setText(torch ? "조명 켬" : "조명 끔");
        torchBtn.setBackgroundColor(torch ? 0xFFF59E0B : 0xFF4B5563);
    }

    private void updateDone() {
        doneBtn.setText("완료\n" + added + "장");
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
            Point[] q = DocDetector.detect(rgb, 480);
            long now = SystemClock.uptimeMillis();

            double[] rel = null;
            float[] print = null;
            if (q != null) {
                rel = new double[8];
                for (int i = 0; i < 4; i++) { rel[i * 2] = q[i].x / fw; rel[i * 2 + 1] = q[i].y / fh; }
                print = DocDetector.fingerprint(rgb, q);
            }
            rgb.release();
            latestRel = rel;
            latestPrint = print;

            boolean stable;
            if (rel == null) { stableSince = -1; stable = false; }
            else {
                if (prevRel == null || maxDelta(prevRel, rel) > 0.025 || stableSince < 0) stableSince = now;
                stable = now - stableSince >= STABLE_MS;
            }
            prevRel = rel;
            float progress = rel == null || stableSince < 0 ? 0f : Math.min(1f, (now - stableSince) / (float) STABLE_MS);

            String msg;
            boolean shoot = false;
            if (capturing) msg = "찍는 중…";
            else if (rel == null) msg = auto ? "문서를 화면에 비추세요" : "자동 촬영 꺼짐 — 가운데 버튼으로 찍으세요";
            else if (!auto) msg = "문서 인식됨 — 가운데 버튼으로 찍으세요";
            else if (now < cooldownUntil) msg = "저장했습니다 · 다음 장을 놓아주세요";
            else if (lastPrint != null && DocDetector.similarity(lastPrint, print) > SAME_PAGE) msg = "방금 찍은 장이에요 · 다음 장을 놓아주세요";
            else if (!stable) msg = "그대로 잠깐만 멈춰 주세요";
            else { msg = "찍는 중…"; shoot = true; }

            final double[] relF = rel;
            final float prog = progress;
            final String m = msg;
            runOnUiThread(() -> {
                overlay.set(relF, fw, fh, prog);
                if (auto || relF != null) status.setText(m);
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
                    page = Cropper.crop(full, rel);
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
    private static final class Overlay extends View {
        private double[] rel;
        private int fw = 3, fh = 4;
        private float progress;
        private long flashUntil;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Overlay(Context c) {
            super(c);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(6 * c.getResources().getDisplayMetrics().density);
            line.setStrokeJoin(Paint.Join.ROUND);
            fill.setStyle(Paint.Style.FILL);
        }

        void set(double[] r, int w, int h, float p) {
            rel = r; fw = w; fh = h; progress = p;
            invalidate();
        }

        void flash() { flashUntil = SystemClock.uptimeMillis() + 150; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            int vw = getWidth(), vh = getHeight();
            float sc = Math.min(vw / (float) fw, vh / (float) fh);
            float ox = (vw - fw * sc) / 2f, oy = (vh - fh * sc) / 2f;
            if (rel != null) {
                path.reset();
                for (int i = 0; i < 4; i++) {
                    float x = ox + (float) rel[i * 2] * fw * sc, y = oy + (float) rel[i * 2 + 1] * fh * sc;
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                path.close();
                int col = progress >= 1f ? 0xFF22C55E : 0xFFFACC15;
                fill.setColor((col & 0x00FFFFFF) | 0x33000000);
                line.setColor(col);
                c.drawPath(path, fill);
                c.drawPath(path, line);
            }
            if (SystemClock.uptimeMillis() < flashUntil) {
                c.drawColor(0x88FFFFFF);
                postInvalidateDelayed(160);
            }
        }
    }
}
