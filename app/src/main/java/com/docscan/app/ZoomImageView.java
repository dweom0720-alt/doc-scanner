package com.docscan.app;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.appcompat.widget.AppCompatImageView;

/** 두 손가락으로 확대, 두 번 톡 치면 확대/원래대로, 확대 안 했을 때 좌우로 밀면 다음/이전 장 */
public class ZoomImageView extends AppCompatImageView {
    public interface Swipe { void onSwipe(int dir); } // +1 다음, -1 이전

    private final Matrix m = new Matrix();
    private final float[] v = new float[9];
    private float fitScale = 1f, scale = 1f;
    private final ScaleGestureDetector sg;
    private final GestureDetector gd;
    private Swipe swipe;

    public ZoomImageView(Context c) {
        super(c);
        setScaleType(ScaleType.MATRIX);
        sg = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                float ns = Math.max(fitScale, Math.min(fitScale * 6f, scale * d.getScaleFactor()));
                float f = ns / scale;
                scale = ns;
                m.postScale(f, f, d.getFocusX(), d.getFocusY());
                fixTrans();
                return true;
            }
        });
        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (zoomed()) { m.postTranslate(-dx, -dy); fixTrans(); }
                return true;
            }

            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (!zoomed() && swipe != null && Math.abs(vx) > Math.abs(vy) && Math.abs(vx) > 700) {
                    swipe.onSwipe(vx < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                if (zoomed()) fit();
                else {
                    float f = 2.5f;
                    scale = fitScale * f;
                    m.postScale(f, f, e.getX(), e.getY());
                    fixTrans();
                }
                return true;
            }
        });
    }

    public void setSwipe(Swipe s) { swipe = s; }

    private boolean zoomed() { return scale > fitScale * 1.02f; }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        sg.onTouchEvent(ev);
        if (!sg.isInProgress()) gd.onTouchEvent(ev);
        return true;
    }

    @Override public void setImageBitmap(android.graphics.Bitmap bm) {
        super.setImageBitmap(bm);
        fit();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        fit();
    }

    public void fit() {
        Drawable d = getDrawable();
        int w = getWidth(), h = getHeight();
        if (d == null || w == 0 || h == 0 || d.getIntrinsicWidth() <= 0) return;
        float dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
        fitScale = Math.min(w / dw, h / dh);
        scale = fitScale;
        m.setScale(fitScale, fitScale);
        m.postTranslate((w - dw * fitScale) / 2f, (h - dh * fitScale) / 2f);
        setImageMatrix(m);
    }

    private void fixTrans() {
        Drawable d = getDrawable();
        if (d == null) return;
        m.getValues(v);
        float tx = v[Matrix.MTRANS_X], ty = v[Matrix.MTRANS_Y];
        float cw = d.getIntrinsicWidth() * scale, ch = d.getIntrinsicHeight() * scale;
        float w = getWidth(), h = getHeight();
        float nx = cw <= w ? (w - cw) / 2f : Math.max(w - cw, Math.min(0, tx));
        float ny = ch <= h ? (h - ch) / 2f : Math.max(h - ch, Math.min(0, ty));
        m.postTranslate(nx - tx, ny - ty);
        setImageMatrix(m);
    }
}
