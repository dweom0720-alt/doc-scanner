package com.docscan.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 담긴 페이지를 PDF로 만들기 전에 한 장씩 크게 확인하는 화면.
 * '원본'은 잘라낸 사진 그대로, 'PDF 결과'는 고른 모드(흑백·회색·컬러)로 실제 PDF에 들어갈 모습.
 */
public class PageViewerActivity extends AppCompatActivity {
    public static final String EXTRA_INDEX = "index";

    private PageStore store;
    private SharedPreferences prefs;
    private List<File> pages;
    private int index;
    private boolean showResult = true;
    private int loadSeq = 0;

    private ZoomImageView image;
    private TextView title, info, origBtn, resultBtn;
    private ProgressBar spinner;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new PageStore(this);
        prefs = getSharedPreferences("pdf", MODE_PRIVATE);
        showResult = prefs.getBoolean("previewResult", true);
        pages = store.list();
        index = Math.max(0, Math.min(pages.size() - 1, getIntent().getIntExtra(EXTRA_INDEX, 0)));
        setContentView(buildUi());
        if (pages.isEmpty()) { finish(); return; }
        show();
    }

    private int mode() { return prefs.getInt("mode", PdfMaker.MODE_BW); }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF111317);

        // 상단: 쪽수 + 원본/PDF 결과 전환
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(12), dp(12), dp(12));
        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        origBtn = seg("원본");
        resultBtn = seg("PDF 결과");
        origBtn.setOnClickListener(v -> setResult(false));
        resultBtn.setOnClickListener(v -> setResult(true));
        LinearLayout g = new LinearLayout(this);
        GradientDrawable gb = new GradientDrawable();
        gb.setColor(0xFF1F2937);
        gb.setCornerRadius(dp(20));
        g.setBackground(gb);
        g.setPadding(dp(3), dp(3), dp(3), dp(3));
        g.addView(origBtn);
        g.addView(resultBtn);
        top.addView(g);
        root.addView(top);

        info = new TextView(this);
        info.setTextColor(0xFF9CA3AF);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        info.setPadding(dp(16), 0, dp(16), dp(8));
        root.addView(info);

        FrameLayout mid = new FrameLayout(this);
        image = new ZoomImageView(this);
        image.setSwipe(dir -> go(index + dir));
        mid.addView(image, new FrameLayout.LayoutParams(-1, -1));
        spinner = new ProgressBar(this);
        mid.addView(spinner, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        root.addView(mid, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView hint = new TextView(this);
        hint.setText("두 손가락으로 확대 · 두 번 톡 = 확대/원래대로 · 좌우로 밀면 다음 장");
        hint.setTextColor(0xFF6B7280);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(6), 0, dp(6));
        root.addView(hint);

        // 하단: 이전 · 회전 · 삭제 · 다음
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(0xFF1A1D23);
        bar.setPadding(dp(8), dp(10), dp(8), dp(16));
        bar.addView(barBtn("◀ 이전", Color.WHITE, v -> go(index - 1)), weight());
        bar.addView(barBtn("↻ 회전", Color.WHITE, v -> rotate()), weight());
        bar.addView(barBtn("삭제", 0xFFF87171, v -> delete()), weight());
        bar.addView(barBtn("다음 ▶", Color.WHITE, v -> go(index + 1)), weight());
        root.addView(bar);

        paintSeg();
        return root;
    }

    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1); }

    private TextView barBtn(String t, int color, View.OnClickListener l) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        v.setGravity(Gravity.CENTER);
        v.setPadding(0, dp(12), 0, dp(12));
        v.setOnClickListener(l);
        return v;
    }

    private TextView seg(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        v.setPadding(dp(12), dp(7), dp(12), dp(7));
        return v;
    }

    private void paintSeg() {
        for (TextView v : new TextView[]{origBtn, resultBtn}) {
            boolean on = (v == resultBtn) == showResult;
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(on ? 0xFF2447C9 : 0x00000000);
            bg.setCornerRadius(dp(18));
            v.setBackground(bg);
            v.setTextColor(on ? Color.WHITE : 0xFFD1D5DB);
        }
    }

    private void setResult(boolean r) {
        if (showResult == r) return;
        showResult = r;
        prefs.edit().putBoolean("previewResult", r).apply();
        paintSeg();
        show();
    }

    private void go(int i) {
        if (i < 0 || i >= pages.size()) {
            Toast.makeText(this, i < 0 ? "첫 장입니다" : "마지막 장입니다", Toast.LENGTH_SHORT).show();
            return;
        }
        index = i;
        show();
    }

    private static final String[] MODE_NAMES = {"흑백", "회색", "컬러"};

    private void show() {
        final File f = pages.get(index);
        final int mode = mode();
        final boolean result = showResult;
        final int seq = ++loadSeq;
        title.setText((index + 1) + " / " + pages.size() + "쪽");
        info.setText(result ? MODE_NAMES[mode] + " 모드로 PDF에 들어갈 모습 · 계산 중…" : "잘라낸 사진 그대로");
        spinner.setVisibility(View.VISIBLE);
        io.execute(() -> {
            Bitmap bmp;
            String line;
            try {
                if (result) {
                    PdfMaker.Page p = PdfMaker.process(f, mode);
                    if (p == null) throw new IllegalStateException("사진을 읽을 수 없습니다");
                    long sz = p.size();
                    bmp = PdfMaker.render(p);
                    line = MODE_NAMES[mode] + " 모드로 PDF에 들어갈 모습 · 이 장 약 " + human(sz)
                            + "  (모드는 첫 화면에서 바꿀 수 있어요)";
                } else {
                    bmp = PdfMaker.decodeScaled(f, 2400);
                    line = "잘라낸 사진 그대로 · " + (bmp == null ? "" : bmp.getWidth() + "×" + bmp.getHeight());
                }
            } catch (Throwable t) {
                bmp = null;
                line = "불러오기 실패: " + t.getMessage();
            }
            final Bitmap fb = bmp;
            final String fl = line;
            ui.post(() -> {
                if (seq != loadSeq || isFinishing()) { if (fb != null) fb.recycle(); return; }
                spinner.setVisibility(View.GONE);
                if (fb != null) image.setImageBitmap(fb);
                info.setText(fl);
            });
        });
    }

    private static String human(long b) {
        return b < 1024 * 1024 ? Math.max(1, b / 1024) + "KB" : String.format(Locale.US, "%.1fMB", b / 1048576f);
    }

    private void rotate() {
        final File f = pages.get(index);
        spinner.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                Bitmap src = android.graphics.BitmapFactory.decodeFile(f.getPath());
                Matrix mx = new Matrix();
                mx.postRotate(90);
                Bitmap r = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), mx, true);
                src.recycle();
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(f))) {
                    r.compress(Bitmap.CompressFormat.JPEG, 92, out);
                }
                r.recycle();
                ui.post(this::show);
            } catch (Throwable t) {
                ui.post(() -> { spinner.setVisibility(View.GONE); Toast.makeText(this, "회전 실패", Toast.LENGTH_SHORT).show(); });
            }
        });
    }

    private void delete() {
        new AlertDialog.Builder(this)
                .setMessage((index + 1) + "쪽을 지울까요?")
                .setPositiveButton("삭제", (d, w) -> {
                    store.delete(pages.get(index));
                    pages = store.list();
                    if (pages.isEmpty()) { finish(); return; }
                    index = Math.min(index, pages.size() - 1);
                    show();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    public static Intent open(android.content.Context c, int index) {
        return new Intent(c, PageViewerActivity.class).putExtra(EXTRA_INDEX, index);
    }
}
