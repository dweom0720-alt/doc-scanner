package com.docscan.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/** 페이지 사진들 → 저용량 PDF → '다운로드/문서스캔' 폴더에 저장 */
public final class PdfMaker {
    public static final int MODE_BW = 0, MODE_GRAY = 1, MODE_COLOR = 2;

    public interface Progress { void on(int done, int total); }

    public static final class Result {
        public Uri uri; public String name; public long bytes; public int pages;
    }

    /** 모드별 긴 변 픽셀 수. 흑백은 1비트라 해상도를 높여도 용량이 작음 */
    static int targetLong(int mode) {
        return mode == MODE_BW ? 2200 : 1600;
    }

    public static Result make(Context ctx, List<File> pages, int mode, String name, Progress cb) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/문서스캔");
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IOException("다운로드 폴더에 파일을 만들 수 없습니다");

        Counting counting;
        try (OutputStream raw = cr.openOutputStream(uri)) {
            if (raw == null) throw new IOException("파일을 열 수 없습니다");
            counting = new Counting(new BufferedOutputStream(raw, 1 << 16));
            Core.PdfWriter pw = new Core.PdfWriter(counting);
            for (int i = 0; i < pages.size(); i++) {
                addPage(pw, pages.get(i), mode);
                if (cb != null) cb.on(i + 1, pages.size());
            }
            pw.finish();
            counting.flush();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            cr.delete(uri, null, null);
            if (e instanceof OutOfMemoryError) throw new IOException("메모리가 부족합니다");
            throw e;
        }

        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, done, null, null);

        Result r = new Result();
        r.uri = uri; r.name = name; r.bytes = counting.count; r.pages = pages.size();
        return r;
    }

    private static void addPage(Core.PdfWriter pw, File f, int mode) throws IOException {
        Bitmap bmp = decodeScaled(f, targetLong(mode));
        if (bmp == null) return; // 깨진 사진은 건너뜀
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);

        if (mode == MODE_BW) {
            bmp.recycle();
            int[] gray = Core.toGray(px);
            px = null;
            pw.addBilevel(Core.binarize(gray, w, h), w, h);
            return;
        }

        int quality;
        if (mode == MODE_GRAY) {
            int[] gray = Core.toGray(px);
            Core.flattenGray(gray, w, h);
            for (int i = 0; i < px.length; i++) px[i] = 0xFF000000 | (gray[i] * 0x010101);
            quality = 50;
        } else {
            Core.flattenColor(px, w, h);
            quality = 60;
        }
        Bitmap out = bmp.isMutable() ? bmp : bmp.copy(Bitmap.Config.ARGB_8888, true);
        if (out != bmp) bmp.recycle();
        out.setPixels(px, 0, w, 0, 0, w, h);
        ByteArrayOutputStream jpg = new ByteArrayOutputStream(256 * 1024);
        out.compress(Bitmap.CompressFormat.JPEG, quality, jpg);
        out.recycle();
        pw.addJpeg(jpg.toByteArray(), w, h);
    }

    /** 긴 변이 target 이하가 되도록 줄여서 읽기 */
    static Bitmap decodeScaled(File f, int target) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int longSide = Math.max(o.outWidth, o.outHeight);
        if (longSide <= 0) return null;
        int sample = 1;
        while (longSide / (sample * 2) >= target) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        o2.inMutable = true;
        o2.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o2);
        if (b == null) return null;
        int l = Math.max(b.getWidth(), b.getHeight());
        if (l > target) {
            float s = (float) target / l;
            Bitmap sc = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * s)),
                    Math.max(1, Math.round(b.getHeight() * s)), true);
            if (sc != b) b.recycle();
            b = sc;
        }
        return b;
    }

    private static final class Counting extends FilterOutputStream {
        long count;
        Counting(OutputStream o) { super(o); }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); count += len; }
    }
}
