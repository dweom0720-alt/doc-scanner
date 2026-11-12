package com.docscan.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import org.opencv.android.OpenCVLoader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int BLUE = 0xFF2447C9, BG = 0xFFF2F3F7, INK = 0xFF1A1C22, SUB = 0xFF6B7280;

    private PageStore store;
    private int mode = PdfMaker.MODE_BW;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout thumbs;
    private TextView countLabel, emptyHint;
    private Button[] modeBtns;
    private Button makeBtn;

    private ActivityResultLauncher<String> pickLauncher;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        OpenCVLoader.initLocal();
        store = new PageStore(this);
        mode = getSharedPreferences("pdf", MODE_PRIVATE).getInt("mode", PdfMaker.MODE_BW);
        pickLauncher = registerForActivityResult(new ActivityResultContracts.GetMultipleContents(), this::importImages);
        setContentView(buildUi());
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    // ---------- 화면 ----------
    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(BLUE);
        bar.setPadding(dp(20), dp(16), dp(20), dp(16));
        TextView title = new TextView(this);
        title.setText("문서스캔");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView imp = new TextView(this);
        imp.setText("사진 불러오기");
        imp.setTextColor(Color.WHITE);
        imp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        imp.setPadding(dp(10), dp(6), 0, dp(6));
        imp.setOnClickListener(v -> pickLauncher.launch("image/*"));
        bar.addView(imp);
        root.addView(bar);

        // 모드 선택
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setPadding(dp(16), dp(16), dp(16), dp(6));
        String[] names = {"흑백(가장 작음)", "회색", "컬러"};
        modeBtns = new Button[3];
        for (int i = 0; i < 3; i++) {
            Button mb = new Button(this);
            mb.setAllCaps(false);
            mb.setText(names[i]);
            mb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            final int m = i;
            mb.setOnClickListener(v -> {
                mode = m;
                getSharedPreferences("pdf", MODE_PRIVATE).edit().putInt("mode", m).apply();
                paintModes();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.setMargins(dp(4), 0, dp(4), 0);
            modeRow.addView(mb, lp);
            modeBtns[i] = mb;
        }
        root.addView(modeRow);

        TextView modeHint = new TextView(this);
        modeHint.setText("글자 문서는 흑백이 가장 작고 또렷합니다. 도장·서명·표의 색이 필요하면 컬러를 쓰세요.");
        modeHint.setTextColor(SUB);
        modeHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        modeHint.setPadding(dp(22), 0, dp(22), dp(12));
        root.addView(modeHint);

        countLabel = new TextView(this);
        countLabel.setTextColor(INK);
        countLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        countLabel.setTypeface(countLabel.getTypeface(), android.graphics.Typeface.BOLD);
        countLabel.setPadding(dp(22), dp(4), dp(22), dp(8));
        root.addView(countLabel);

        ScrollView sv = new ScrollView(this);
        thumbs = new LinearLayout(this);
        thumbs.setOrientation(LinearLayout.VERTICAL);
        thumbs.setPadding(dp(16), 0, dp(16), dp(16));
        emptyHint = new TextView(this);
        emptyHint.setText("\n아래 '사진 찍기'를 누르고 문서를 비추기만 하세요.\n테두리를 자동으로 잡아 저절로 찍고, 문서만 잘라 담습니다.\n다음 장을 올려놓으면 또 찍습니다.\n");
        emptyHint.setTextColor(SUB);
        emptyHint.setGravity(Gravity.CENTER);
        emptyHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        thumbs.addView(emptyHint);
        sv.addView(thumbs);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // 하단 버튼
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setPadding(dp(16), dp(10), dp(16), dp(16));
        btnRow.setBackgroundColor(Color.WHITE);
        Button scan = bigButton("사진 찍기", BLUE, Color.WHITE);
        scan.setOnClickListener(v -> startScan());
        makeBtn = bigButton("PDF 만들기", 0xFF17A34A, Color.WHITE);
        makeBtn.setOnClickListener(v -> makePdf());
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        l1.setMargins(dp(4), 0, dp(4), 0);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        l2.setMargins(dp(4), 0, dp(4), 0);
        btnRow.addView(scan, l1);
        btnRow.addView(makeBtn, l2);
        root.addView(btnRow);

        paintModes();
        return root;
    }

    private void paintModes() {
        for (int i = 0; i < 3; i++) {
            boolean on = i == mode;
            modeBtns[i].setBackgroundColor(on ? BLUE : 0xFFE5E7EB);
            modeBtns[i].setTextColor(on ? Color.WHITE : INK);
        }
    }

    // ---------- 촬영 / 불러오기 ----------
    private void startScan() {
        startActivity(new Intent(this, CameraActivity.class));
    }

    private void importImages(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("문서 자르는 중…")
                .setMessage("0 / " + uris.size())
                .setCancelable(false).create();
        dlg.show();
        io.execute(() -> {
            int ok = 0, i = 0;
            for (Uri u : uris) {
                i++;
                try {
                    Bitmap src = readImage(u);
                    if (src != null) {
                        Bitmap page = Cropper.crop(src, null, getSharedPreferences("camera", MODE_PRIVATE).getBoolean("screen", false));
                        if (page != src) src.recycle();
                        store.save(page);
                        page.recycle();
                        ok++;
                    }
                } catch (Throwable ignored) {}
                final int done = i;
                ui.post(() -> dlg.setMessage(done + " / " + uris.size()));
            }
            final int n = ok;
            ui.post(() -> { dlg.dismiss(); refresh(); toast(n + "장 추가됨"); });
        });
    }

    /** 갤러리 사진 읽기 (사진 방향 정보 반영) */
    private Bitmap readImage(Uri u) throws java.io.IOException {
        byte[] data;
        try (java.io.InputStream in = getContentResolver().openInputStream(u)) {
            if (in == null) return null;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int k;
            while ((k = in.read(buf)) > 0) bos.write(buf, 0, k);
            data = bos.toByteArray();
        }
        int rot = 0;
        try {
            android.media.ExifInterface ex = new android.media.ExifInterface(new java.io.ByteArrayInputStream(data));
            int o = ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
            rot = o == 6 ? 90 : o == 3 ? 180 : o == 8 ? 270 : 0;
        } catch (Exception ignored) {}
        return Cropper.decode(data, rot, 3200);
    }

    // ---------- 목록 ----------
    private final android.util.LruCache<String, Bitmap> thumbCache = new android.util.LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap v) { return v.getByteCount(); }
    };
    private final ExecutorService thumbIo = Executors.newSingleThreadExecutor();

    private void refresh() {
        List<File> pages = store.list();
        countLabel.setText(pages.isEmpty() ? "담긴 페이지 0장"
                : "담긴 페이지 " + pages.size() + "장 · 사진을 누르면 PDF 결과를 미리 볼 수 있어요");
        makeBtn.setEnabled(!pages.isEmpty());
        makeBtn.setAlpha(pages.isEmpty() ? 0.4f : 1f);
        thumbs.removeAllViews();
        if (pages.isEmpty()) { thumbs.addView(emptyHint); return; }
        LinearLayout row = null;
        for (int i = 0; i < pages.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.bottomMargin = dp(10);
                thumbs.addView(row, rp);
            }
            row.addView(cell(i, pages.get(i)), cellParams());
        }
        int rest = (3 - pages.size() % 3) % 3;
        for (int k = 0; k < rest; k++) row.addView(new View(this), cellParams());
    }

    private LinearLayout.LayoutParams cellParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(150), 1);
        lp.setMargins(dp(5), 0, dp(5), 0);
        return lp;
    }

    private View cell(int idx, File f) {
        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(Color.WHITE);
        box.setPadding(dp(3), dp(3), dp(3), dp(3));
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setBackgroundColor(0xFFE5E7EB);
        box.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        TextView no = new TextView(this);
        no.setText(String.valueOf(idx + 1));
        no.setTextColor(Color.WHITE);
        no.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        no.setBackgroundColor(0xCC2447C9);
        no.setPadding(dp(7), dp(2), dp(7), dp(2));
        box.addView(no, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.BOTTOM));

        final String key = f.getPath() + "#" + f.lastModified();
        Bitmap cached = thumbCache.get(key);
        if (cached != null) iv.setImageBitmap(cached);
        else {
            iv.setTag(key);
            thumbIo.execute(() -> {
                Bitmap t = PdfMaker.decodeScaled(f, 360);
                if (t == null) return;
                thumbCache.put(key, t);
                ui.post(() -> { if (key.equals(iv.getTag())) iv.setImageBitmap(t); });
            });
        }
        box.setOnClickListener(v -> startActivity(PageViewerActivity.open(this, idx)));
        box.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setMessage((idx + 1) + "쪽을 지울까요?")
                    .setPositiveButton("삭제", (d, w) -> { store.delete(f); refresh(); })
                    .setNegativeButton("취소", null)
                    .show();
            return true;
        });
        return box;
    }

    // ---------- PDF ----------
    private void makePdf() {
        List<File> pages = store.list();
        if (pages.isEmpty()) return;
        String name = "스캔_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(new java.util.Date()) + ".pdf";

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("PDF 만드는 중…")
                .setMessage("0 / " + pages.size())
                .setCancelable(false).create();
        dlg.show();

        io.execute(() -> {
            try {
                PdfMaker.Result res = PdfMaker.make(this, pages, mode, name,
                        (done, total) -> ui.post(() -> dlg.setMessage(done + " / " + total)));
                store.deleteAll(pages);
                ui.post(() -> { dlg.dismiss(); refresh(); showDone(res); });
            } catch (Exception e) {
                ui.post(() -> { dlg.dismiss(); toast("실패: " + e.getMessage()); });
            }
        });
    }

    private void showDone(PdfMaker.Result res) {
        String size = res.bytes < 1024 * 1024
                ? (res.bytes / 1024) + " KB"
                : String.format(Locale.US, "%.1f MB", res.bytes / 1024f / 1024f);
        new AlertDialog.Builder(this)
                .setTitle("저장 완료")
                .setMessage(res.name + "\n\n" + res.pages + "쪽 · " + size + "\n위치: 다운로드 / 문서스캔")
                .setPositiveButton("공유", (d, w) -> share(res.uri))
                .setNegativeButton("열기", (d, w) -> open(res.uri))
                .setNeutralButton("닫기", null)
                .show();
    }

    private void share(Uri uri) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("application/pdf");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "PDF 공유"));
    }

    private void open(Uri uri) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/pdf");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(i); } catch (Exception e) { toast("PDF 뷰어가 없습니다"); }
    }

    // ---------- 도우미 ----------
    private Button bigButton(String text, int bg, int fg) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextColor(fg);
        b.setBackgroundColor(bg);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setPadding(0, dp(14), 0, dp(14));
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
