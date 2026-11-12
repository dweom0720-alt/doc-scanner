package com.docscan.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.imgproc.Imgproc;

/** 찍은 사진 한 장 → 문서만 반듯하게 잘라낸 비트맵 */
public final class Cropper {
    private Cropper() {}

    /** 저장용 결과 해상도(긴 변). PDF 단계에서 다시 줄이므로 넉넉하게 */
    static final int OUT_LONG = 2600;

    /**
     * @param fallbackRel 사진에서 못 찾았을 때 쓸 미리보기 모서리(0~1 비율 좌표), 없으면 null
     * @return 잘라낸 비트맵. 문서를 끝내 못 찾으면 원본을 줄여서 그대로 돌려줌
     */
    public static Bitmap crop(Bitmap src, double[] fallbackRel) { return crop(src, fallbackRel, false); }

    /** @param screen 모니터 촬영: 화면 속 문서(없으면 화면 전체)를 잡고, 물결무늬(모아레)를 살짝 눌러 줌 */
    public static Bitmap crop(Bitmap src, double[] fallbackRel, boolean screen) { return crop(src, fallbackRel, screen, false); }

    /** @param fixed true면 인식 없이 fallbackRel(고정 범위) 그대로 잘라냄 */
    public static Bitmap crop(Bitmap src, double[] fallbackRel, boolean screen, boolean fixed) {
        Mat rgba = new Mat();
        Utils.bitmapToMat(src, rgba);
        Mat rgb = new Mat();
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);
        rgba.release();

        Point[] q = (fixed && fallbackRel != null) ? null : DocDetector.detect(rgb, 800, screen);
        if (q == null && fallbackRel != null) {
            q = new Point[4];
            for (int i = 0; i < 4; i++)
                q[i] = new Point(fallbackRel[i * 2] * rgb.cols(), fallbackRel[i * 2 + 1] * rgb.rows());
        }

        Mat out;
        if (q != null) {
            out = DocDetector.warp(rgb, q, OUT_LONG);
            rgb.release();
        } else {
            out = rgb;
        }
        if (screen) Imgproc.GaussianBlur(out, out, new org.opencv.core.Size(3, 3), 0);
        Bitmap bmp = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(out, bmp);
        out.release();
        return bmp;
    }

    /** JPEG 바이트를 적당히 줄여서 읽고, 필요한 만큼 회전 */
    public static Bitmap decode(byte[] jpeg, int rotation, int maxLong) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxLong) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o2);
        return rotate(b, rotation);
    }

    public static Bitmap rotate(Bitmap b, int deg) {
        if (b == null || deg % 360 == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(deg);
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (r != b) b.recycle();
        return r;
    }
}
