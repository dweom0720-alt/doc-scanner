package com.docscan.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** 스캔했지만 아직 PDF로 만들지 않은 페이지들 (앱이 꺼져도 남아 있음) */
public final class PageStore {
    private final File dir;
    private final SharedPreferences sp;

    public PageStore(Context ctx) {
        dir = new File(ctx.getFilesDir(), "pages");
        if (!dir.exists()) dir.mkdirs();
        sp = ctx.getSharedPreferences("store", Context.MODE_PRIVATE);
    }

    public synchronized List<File> list() {
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".jpg"));
        if (fs == null) return new ArrayList<>();
        Arrays.sort(fs, (a, b) -> a.getName().compareTo(b.getName()));
        return new ArrayList<>(Arrays.asList(fs));
    }

    public synchronized File add(ContentResolver cr, Uri src) throws IOException {
        long n = sp.getLong("seq", 0) + 1;
        sp.edit().putLong("seq", n).apply();
        File f = new File(dir, String.format(Locale.US, "%08d.jpg", n));
        try (InputStream in = cr.openInputStream(src); OutputStream out = new FileOutputStream(f)) {
            if (in == null) throw new IOException("페이지를 읽을 수 없습니다");
            byte[] buf = new byte[65536];
            int k;
            while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
        }
        return f;
    }

    public synchronized File save(android.graphics.Bitmap bmp) throws IOException {
        long n = sp.getLong("seq", 0) + 1;
        sp.edit().putLong("seq", n).apply();
        File f = new File(dir, String.format(Locale.US, "%08d.jpg", n));
        try (OutputStream out = new java.io.BufferedOutputStream(new FileOutputStream(f))) {
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out);
        }
        return f;
    }

    public synchronized int count() { return list().size(); }

    public synchronized void delete(File f) { f.delete(); }

    public synchronized void deleteAll(List<File> fs) { for (File f : fs) f.delete(); }
}
