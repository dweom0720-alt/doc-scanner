package com.docscan.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 이미 있는 PDF를 불러와 페이지마다 다시 그린 뒤 흑백·회색·컬러 저용량 PDF로 새로 저장 */
public final class PdfCompressor {
    private PdfCompressor() {}

    public static final class Result {
        public Uri uri;
        public String name, srcName;
        public long before, after;
        public int pages;
        /** 줄였더니 오히려 커져서 저장하지 않음 */
        public boolean notSmaller;
    }

    public static Result compress(Context ctx, Uri src, int mode, PdfMaker.Progress cb) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        Result res = new Result();
        res.srcName = queryName(cr, src);
        if (res.srcName == null) res.srcName = "문서.pdf";

        // 원본을 임시 파일로 복사 (다른 앱이 준 파일도 안정적으로 읽기 위해)
        File tmp = new File(ctx.getCacheDir(), "compress_src.pdf");
        try (InputStream in = cr.openInputStream(src); OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new IOException("PDF를 열 수 없습니다");
            byte[] buf = new byte[1 << 16];
            int k;
            while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
        }
        res.before = tmp.length();

        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer renderer;
        try {
            renderer = new PdfRenderer(pfd);
        } catch (SecurityException e) {
            pfd.close();
            tmp.delete();
            throw new IOException("암호가 걸린 PDF는 줄일 수 없어요");
        } catch (IOException e) {
            pfd.close();
            tmp.delete();
            throw new IOException("PDF 형식이 올바르지 않아요");
        }

        String base = res.srcName.replaceAll("(?i)\\.pdf$", "");
        String name = base + "_압축.pdf";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/문서스캔");
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri outUri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (outUri == null) { renderer.close(); tmp.delete(); throw new IOException("다운로드 폴더에 파일을 만들 수 없습니다"); }

        PdfMaker.Counting counting;
        int n = renderer.getPageCount();
        try (OutputStream raw = cr.openOutputStream(outUri)) {
            if (raw == null) throw new IOException("파일을 열 수 없습니다");
            counting = new PdfMaker.Counting(new BufferedOutputStream(raw, 1 << 16));
            Core.PdfWriter pw = new Core.PdfWriter(counting);
            int target = PdfMaker.targetLong(mode);
            for (int i = 0; i < n; i++) {
                PdfRenderer.Page page = renderer.openPage(i);
                float ptW = page.getWidth(), ptH = page.getHeight();
                double s = target / (double) Math.max(ptW, ptH);
                int bw = Math.max(16, (int) Math.round(ptW * s)), bh = Math.max(16, (int) Math.round(ptH * s));
                Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Color.WHITE); // 투명 배경을 흰 종이로
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                page.close();
                PdfMaker.Page p = PdfMaker.process(bmp, mode, false);
                if (p.bits != null) pw.addBilevel(p.bits, p.w, p.h, ptW, ptH);
                else pw.addJpeg(p.jpeg, p.w, p.h, ptW, ptH);
                if (cb != null) cb.on(i + 1, n);
            }
            pw.finish();
            counting.flush();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            cr.delete(outUri, null, null);
            renderer.close();
            tmp.delete();
            if (e instanceof OutOfMemoryError) throw new IOException("메모리가 부족합니다");
            throw e;
        }
        renderer.close();
        tmp.delete();

        res.after = counting.count;
        res.pages = n;
        res.name = name;
        if (res.after >= res.before) {
            cr.delete(outUri, null, null);
            res.notSmaller = true;
            return res;
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(outUri, done, null, null);
        res.uri = outUri;
        return res;
    }

    private static String queryName(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        String last = uri.getLastPathSegment();
        return last != null && last.toLowerCase().endsWith(".pdf") ? last : null;
    }
}
