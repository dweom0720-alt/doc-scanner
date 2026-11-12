package com.docscan.app;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 사진 속 문서(종이)의 네 모서리를 자동으로 찾는다.
 * 여러 방법(밝기 경계·색 경계·종이색 영역)으로 후보 사각형을 만든 뒤
 * ① 네 변이 실제 경계선 위에 있는지 ② 안쪽이 종이처럼 밝고 무채색인지로 점수를 매겨 가장 좋은 것을 고른다.
 */
public final class DocDetector {
    private DocDetector() {}

    /** @return 좌상·우상·우하·좌하 순서의 네 점(입력 이미지 좌표), 못 찾으면 null */
    public static Point[] detect(Mat rgb, int workSize) {
        int W = rgb.cols(), H = rgb.rows();
        double s = Math.min(1.0, (double) workSize / Math.max(W, H));
        Mat sm = new Mat();
        if (s < 1.0) Imgproc.resize(rgb, sm, new Size(Math.round(W * s), Math.round(H * s)), 0, 0, Imgproc.INTER_AREA);
        else rgb.copyTo(sm);
        int w = sm.cols(), h = sm.rows();
        double amin = 0.12 * w * h;

        Mat gray = new Mat();
        Imgproc.cvtColor(sm, gray, Imgproc.COLOR_RGB2GRAY);
        List<Mat> ch = new ArrayList<>();
        Core.split(sm, ch);
        Mat mn = new Mat(), mx = new Mat();
        Core.min(ch.get(0), ch.get(1), mn);
        Core.min(mn, ch.get(2), mn);
        Core.max(ch.get(0), ch.get(1), mx);
        Core.max(mx, ch.get(2), mx);

        List<Point[]> cands = new ArrayList<>();
        Mat k3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
        Mat k5 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5));
        Mat k9 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(9, 9));

        for (Mat src : new Mat[]{gray, mn}) {
            Mat b = new Mat();
            Imgproc.GaussianBlur(src, b, new Size(5, 5), 0);
            double med = percentile(b, 0.5);
            double[][] th = {{0.66 * med, 1.33 * med}, {20, 60}, {40, 120}};
            for (double[] t : th) {
                Mat e = new Mat();
                Imgproc.Canny(b, e, t[0], t[1]);
                Imgproc.dilate(e, e, k3);
                collect(e, Imgproc.RETR_LIST, amin, cands);
                e.release();
            }
            Mat bin = new Mat();
            Imgproc.threshold(b, bin, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
            Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_CLOSE, k9);
            Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_OPEN, k5);
            collect(bin, Imgproc.RETR_EXTERNAL, amin, cands);
            bin.release(); b.release();
        }

        // 경계선 지도 (점수 매기기·모서리 다듬기용)
        Mat eg = new Mat(), em = new Mat(), tmp = new Mat();
        Imgproc.GaussianBlur(gray, tmp, new Size(3, 3), 0);
        Imgproc.Canny(tmp, eg, 15, 45);
        Imgproc.GaussianBlur(mn, tmp, new Size(3, 3), 0);
        Imgproc.Canny(tmp, em, 15, 45);
        Mat raw = new Mat();
        Core.bitwise_or(eg, em, raw);
        Mat edges = new Mat();
        Imgproc.dilate(raw, edges, k5);
        byte[] edgeBuf = new byte[w * h];
        edges.get(0, 0, edgeBuf);

        MatOfPoint nz = new MatOfPoint();
        Core.findNonZero(raw, nz);
        Point[] ep = nz.rows() > 0 ? nz.toArray() : new Point[0];
        float[] ex = new float[ep.length], ey = new float[ep.length];
        for (int i = 0; i < ep.length; i++) { ex[i] = (float) ep[i].x; ey[i] = (float) ep[i].y; }

        // 종이처럼 보이는 영역: 무채색이면서 밝음
        Mat sat = new Mat();
        Core.subtract(mx, mn, sat);
        double thr = percentile(mn, 0.6);
        double paperMin = Math.min(thr, 170) * 0.75;
        Mat lowSat = new Mat(), bright = new Mat(), paper = new Mat();
        Imgproc.threshold(sat, lowSat, 44, 1, Imgproc.THRESH_BINARY_INV);
        Imgproc.threshold(mn, bright, Math.ceil(paperMin) - 1, 1, Imgproc.THRESH_BINARY);
        Core.bitwise_and(lowSat, bright, paper);

        Mat pm = new Mat();
        Core.multiply(paper, new Scalar(255), pm);
        Imgproc.morphologyEx(pm, pm, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(15, 15)));
        Imgproc.morphologyEx(pm, pm, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(7, 7)));
        collect(pm, Imgproc.RETR_EXTERNAL, amin, cands);

        // 같은 후보 정리
        Set<String> seen = new HashSet<>();
        Point[] best = null;
        double bestScore = 0;
        Mat mask = Mat.zeros(h, w, CvType.CV_8UC1);
        Mat inter = new Mat();
        for (Point[] q0 : cands) {
            StringBuilder key = new StringBuilder();
            for (Point p : q0) key.append(Math.round(p.x)).append(',').append(Math.round(p.y)).append(';');
            if (!seen.add(key.toString())) continue;

            Point[] r = refine(refine(q0, ex, ey, 14), ex, ey, 5);
            double[] sq = support(q0, edgeBuf, w, h), sr = support(r, edgeBuf, w, h);
            Point[] q; double[] sup;
            if (sum(sr) > sum(sq) + 0.02) { q = r; sup = sr; } else { q = q0; sup = sq; }

            double a = Imgproc.contourArea(new MatOfPoint2f(q)) / (w * h);
            if (a > 0.97) continue;
            double minSup = Math.min(Math.min(sup[0], sup[1]), Math.min(sup[2], sup[3]));
            if (minSup < 0.55) continue;

            mask.setTo(new Scalar(0));
            List<MatOfPoint> poly = new ArrayList<>();
            poly.add(new MatOfPoint(roundAll(q)));
            Imgproc.fillPoly(mask, poly, new Scalar(1));
            int inside = Core.countNonZero(mask);
            Core.bitwise_and(paper, mask, inter);
            double pf = Core.countNonZero(inter) / (double) Math.max(1, inside);
            if (pf < 0.5) continue;

            double avg = sum(sup) / 4;
            double score = a * avg * avg * avg * minSup * pf * pf * pf;
            if (score > bestScore) { bestScore = score; best = q; }
        }

        releaseAll(sm, gray, mn, mx, eg, em, tmp, raw, edges, nz, sat, lowSat, bright, paper, pm, mask, inter, k3, k5, k9);
        for (Mat m : ch) m.release();

        if (best == null) return null;
        Point[] out = new Point[4];
        for (int i = 0; i < 4; i++) out[i] = new Point(best[i].x / s, best[i].y / s);
        return order(out);
    }

    /** 문서 영역을 반듯하게 펴서 잘라냄 */
    public static Mat warp(Mat src, Point[] q, int maxLong) {
        double wTop = dist(q[0], q[1]), wBot = dist(q[3], q[2]);
        double hL = dist(q[0], q[3]), hR = dist(q[1], q[2]);
        double ow = Math.max(wTop, wBot), oh = Math.max(hL, hR);
        double k = Math.min(1.0, maxLong / Math.max(ow, oh));
        int W = Math.max(8, (int) Math.round(ow * k)), H = Math.max(8, (int) Math.round(oh * k));
        MatOfPoint2f from = new MatOfPoint2f(q);
        MatOfPoint2f to = new MatOfPoint2f(new Point(0, 0), new Point(W - 1, 0), new Point(W - 1, H - 1), new Point(0, H - 1));
        Mat M = Imgproc.getPerspectiveTransform(from, to);
        Mat out = new Mat();
        Imgproc.warpPerspective(src, out, M, new Size(W, H), Imgproc.INTER_LINEAR);
        M.release(); from.release(); to.release();
        return out;
    }

    /** 같은 장인지 비교하기 위한 작은 지문(48x64, 정규화) */
    public static float[] fingerprint(Mat rgb, Point[] q) {
        MatOfPoint2f from = new MatOfPoint2f(q);
        MatOfPoint2f to = new MatOfPoint2f(new Point(0, 0), new Point(47, 0), new Point(47, 63), new Point(0, 63));
        Mat M = Imgproc.getPerspectiveTransform(from, to);
        Mat small = new Mat(), g = new Mat();
        Imgproc.warpPerspective(rgb, small, M, new Size(48, 64), Imgproc.INTER_AREA);
        Imgproc.cvtColor(small, g, Imgproc.COLOR_RGB2GRAY);
        Imgproc.GaussianBlur(g, g, new Size(3, 3), 0);
        byte[] b = new byte[48 * 64];
        g.get(0, 0, b);
        float[] f = new float[b.length];
        double mean = 0;
        for (int i = 0; i < b.length; i++) { f[i] = b[i] & 255; mean += f[i]; }
        mean /= f.length;
        double norm = 0;
        for (int i = 0; i < f.length; i++) { f[i] -= (float) mean; norm += f[i] * f[i]; }
        norm = Math.sqrt(norm) + 1e-6;
        for (int i = 0; i < f.length; i++) f[i] /= (float) norm;
        releaseAll(M, small, g, from, to);
        return f;
    }

    public static double similarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    // ---------- 내부 ----------

    private static void collect(Mat bin, int mode, double amin, List<Point[]> out) {
        List<MatOfPoint> cs = new ArrayList<>();
        Mat hier = new Mat();
        Imgproc.findContours(bin.clone(), cs, hier, mode, Imgproc.CHAIN_APPROX_SIMPLE);
        hier.release();
        for (MatOfPoint c : cs) {
            Rect br = Imgproc.boundingRect(c);
            if (br.area() >= amin) {
                Point[] q = quad(c, amin);
                if (q != null) out.add(q);
            }
            c.release();
        }
    }

    private static Point[] quad(MatOfPoint c, double amin) {
        MatOfInt hi = new MatOfInt();
        Imgproc.convexHull(c, hi);
        Point[] pts = c.toArray();
        int[] idx = hi.toArray();
        hi.release();
        if (idx.length < 4) return null;
        Point[] hp = new Point[idx.length];
        for (int i = 0; i < idx.length; i++) hp[i] = pts[idx[i]];
        MatOfPoint2f hull = new MatOfPoint2f(hp);
        double ha = Imgproc.contourArea(hull);
        if (ha < amin) { hull.release(); return null; }
        double peri = Imgproc.arcLength(hull, true);
        double[] eps = {0.015, 0.02, 0.03, 0.04, 0.05, 0.06, 0.08};
        Point[] res = null;
        for (double e : eps) {
            MatOfPoint2f ap = new MatOfPoint2f();
            Imgproc.approxPolyDP(hull, ap, e * peri, true);
            int n = ap.rows();
            if (n == 4) {
                Point[] q = ap.toArray();
                boolean ok = Imgproc.isContourConvex(new MatOfPoint(roundAll(q)))
                        && Imgproc.contourArea(ap) >= 0.85 * ha
                        && anglesOk(q);
                ap.release();
                res = ok ? q : null;
                break;
            }
            ap.release();
            if (n < 4) break;
        }
        hull.release();
        return res;
    }

    private static boolean anglesOk(Point[] q) {
        for (int i = 0; i < 4; i++) {
            Point a = q[(i + 3) % 4], b = q[i], c = q[(i + 1) % 4];
            double v1x = a.x - b.x, v1y = a.y - b.y, v2x = c.x - b.x, v2y = c.y - b.y;
            double cos = (v1x * v2x + v1y * v2y) / (Math.hypot(v1x, v1y) * Math.hypot(v2x, v2y) + 1e-6);
            double ang = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
            if (ang < 55 || ang > 125) return false;
        }
        return true;
    }

    /** 네 변 각각이 실제 경계선 위에 놓인 비율 */
    private static double[] support(Point[] q, byte[] edges, int w, int h) {
        double[] out = new double[4];
        final int n = 40;
        for (int i = 0; i < 4; i++) {
            Point p0 = q[i], p1 = q[(i + 1) % 4];
            int hit = 0;
            for (int t = 0; t < n; t++) {
                double f = (t + 0.5) / n;
                int x = (int) (p0.x + (p1.x - p0.x) * f), y = (int) (p0.y + (p1.y - p0.y) * f);
                if (x >= 0 && x < w && y >= 0 && y < h && edges[y * w + x] != 0) hit++;
            }
            out[i] = hit / (double) n;
        }
        return out;
    }

    /** 각 변 근처의 경계 점들로 직선을 다시 맞추고, 이웃 직선의 교점을 새 모서리로 */
    private static Point[] refine(Point[] q, float[] ex, float[] ey, double dist) {
        double[][] lines = new double[4][]; // x0,y0,vx,vy
        for (int i = 0; i < 4; i++) {
            Point p0 = q[i], p1 = q[(i + 1) % 4];
            double dx = p1.x - p0.x, dy = p1.y - p0.y, L = Math.hypot(dx, dy) + 1e-6;
            double ux = dx / L, uy = dy / L, nx = -uy, ny = ux;
            List<Point> sel = new ArrayList<>();
            for (int k = 0; k < ex.length; k++) {
                double rx = ex[k] - p0.x, ry = ey[k] - p0.y;
                double t = rx * ux + ry * uy, off = rx * nx + ry * ny;
                if (Math.abs(off) < dist && t > 0.05 * L && t < 0.95 * L) sel.add(new Point(ex[k], ey[k]));
            }
            if (sel.size() < 15) return q;
            MatOfPoint2f pts = new MatOfPoint2f();
            pts.fromList(sel);
            Mat line = new Mat();
            Imgproc.fitLine(pts, line, Imgproc.DIST_HUBER, 0, 0.01, 0.01);
            float[] lv = new float[4];
            line.get(0, 0, lv);
            lines[i] = new double[]{lv[2], lv[3], lv[0], lv[1]};
            pts.release(); line.release();
        }
        Point[] out = new Point[4];
        double maxMove = 0;
        for (int i = 0; i < 4; i++) {
            double[] A = lines[(i + 3) % 4], B = lines[i];
            double det = -A[2] * B[3] + B[2] * A[3];
            if (Math.abs(det) < 1e-6) return q;
            double dx = B[0] - A[0], dy = B[1] - A[1];
            double t0 = (-dx * B[3] + B[2] * dy) / det;
            out[i] = new Point(A[0] + A[2] * t0, A[1] + A[3] * t0);
            maxMove = Math.max(maxMove, Math.max(Math.abs(out[i].x - q[i].x), Math.abs(out[i].y - q[i].y)));
        }
        return maxMove > dist * 2.5 ? q : out;
    }

    private static Point[] order(Point[] p) {
        Point tl = p[0], tr = p[0], br = p[0], bl = p[0];
        for (Point q : p) {
            if (q.x + q.y < tl.x + tl.y) tl = q;
            if (q.x + q.y > br.x + br.y) br = q;
            if (q.y - q.x < tr.y - tr.x) tr = q;
            if (q.y - q.x > bl.y - bl.x) bl = q;
        }
        Set<Point> uniq = new HashSet<>();
        Collections.addAll(uniq, tl, tr, br, bl);
        if (uniq.size() < 4) { // 45도 가까이 기운 경우: 중심 기준 각도로 정렬
            double cx = 0, cy = 0;
            for (Point q : p) { cx += q.x / 4; cy += q.y / 4; }
            final double fx = cx, fy = cy;
            Point[] s = p.clone();
            java.util.Arrays.sort(s, (a, b) -> Double.compare(Math.atan2(a.y - fy, a.x - fx), Math.atan2(b.y - fy, b.x - fx)));
            // atan2 오름차순: 좌상(-135°)부터 시계방향
            return s;
        }
        return new Point[]{tl, tr, br, bl};
    }

    private static Point[] roundAll(Point[] q) {
        Point[] r = new Point[q.length];
        for (int i = 0; i < q.length; i++) r[i] = new Point(Math.round(q[i].x), Math.round(q[i].y));
        return r;
    }

    private static double percentile(Mat m8, double q) {
        int n = m8.rows() * m8.cols();
        byte[] buf = new byte[n];
        m8.get(0, 0, buf);
        int[] hist = new int[256];
        for (byte v : buf) hist[v & 255]++;
        long target = (long) (n * q), acc = 0;
        for (int v = 0; v < 256; v++) { acc += hist[v]; if (acc > target) return v; }
        return 255;
    }

    private static double dist(Point a, Point b) { return Math.hypot(a.x - b.x, a.y - b.y); }
    private static double sum(double[] a) { double s = 0; for (double v : a) s += v; return s; }
    private static void releaseAll(Mat... ms) { for (Mat m : ms) if (m != null) m.release(); }
}
