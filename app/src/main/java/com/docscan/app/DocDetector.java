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
    public static Point[] detect(Mat rgb, int workSize) { return detect(rgb, workSize, false); }

    /**
     * @param screen 모니터 모드: 화면 안의 문서(더 하얀 사각형)를 우선하고,
     *               못 찾으면 모니터 화면 전체라도 잡는다
     */
    public static Point[] detect(Mat rgb, int workSize, boolean screen) {
        if (!screen) return core(rgb, workSize, false, true);
        Point[] q = core(rgb, workSize, true, true);
        if (q == null) q = core(rgb, workSize, true, false);
        return q;
    }

    private static Point[] core(Mat rgb, int workSize, boolean screen, boolean needPaper) {
        int W = rgb.cols(), H = rgb.rows();
        double s = Math.min(1.0, (double) workSize / Math.max(W, H));
        Mat sm = new Mat();
        if (s < 1.0) Imgproc.resize(rgb, sm, new Size(Math.round(W * s), Math.round(H * s)), 0, 0, Imgproc.INTER_AREA);
        else rgb.copyTo(sm);
        int w = sm.cols(), h = sm.rows();
        double amin = (screen ? 0.05 : 0.12) * w * h;

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
        byte[] mnBuf = new byte[w * h], mxBuf = new byte[w * h], pBuf = new byte[w * h];
        mn.get(0, 0, mnBuf);
        mx.get(0, 0, mxBuf);
        for (int i = 0; i < pBuf.length; i++) {
            int lo = mnBuf[i] & 255, hi = mxBuf[i] & 255;
            double satMax = screen ? Math.max(45, 0.22 * hi) : 45;
            pBuf[i] = (byte) ((hi - lo) < satMax && lo >= paperMin ? 1 : 0);
        }
        Mat lowSat = new Mat(), bright = new Mat();
        Mat paper = new Mat(h, w, CvType.CV_8UC1);
        paper.put(0, 0, pBuf);

        Mat pm = new Mat();
        Core.multiply(paper, new Scalar(255), pm);
        Imgproc.morphologyEx(pm, pm, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(15, 15)));
        Imgproc.morphologyEx(pm, pm, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(7, 7)));
        collect(pm, Imgproc.RETR_EXTERNAL, amin, cands);

        // 같은 후보 정리
        Set<String> seen = new HashSet<>();
        List<Cand> valid = new ArrayList<>();
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
            if (needPaper && pf < 0.5) continue;

            double avg = sum(sup) / 4;
            double score = a * avg * avg * avg * minSup * (needPaper ? pf * pf * pf : 1);
            boolean dup = false;
            for (Cand c : valid) {
                if (sameQuad(c.q, q, w, h)) { dup = true; if (score > c.score) { c.q = q; c.score = score; c.mask = maskBytes(mask, w, h); c.count = inside; } break; }
            }
            if (!dup) {
                Cand c = new Cand();
                c.q = q; c.score = score; c.mask = maskBytes(mask, w, h); c.count = inside;
                valid.add(c);
            }
        }

        // 모니터 모드: 큰 사각형(화면·창) 안에 경계가 또렷한 더 하얀 사각형(문서)이 있으면 바깥 것은 탈락
        if (screen && needPaper && valid.size() > 1) {
            Mat mnsM = new Mat();
            Imgproc.GaussianBlur(mn, mnsM, new Size(5, 5), 0);
            byte[] mns = new byte[w * h];
            mnsM.get(0, 0, mns);
            mnsM.release();
            for (Cand A : valid) {
                for (Cand B : valid) {
                    if (A == B) continue;
                    if (B.count > 0.9 * A.count || B.count < 0.15 * A.count) continue;
                    int out = 0, ring = 0;
                    int[] hb = new int[256], hr = new int[256];
                    for (int i = 0; i < w * h; i++) {
                        boolean ia = A.mask[i] != 0, ib = B.mask[i] != 0;
                        if (ib && !ia) out++;
                        if (ib) hb[mnBuf[i] & 255]++;
                        else if (ia) { ring++; hr[mnBuf[i] & 255]++; }
                    }
                    if (out > 0.08 * B.count || ring < 0.05 * A.count) continue;
                    if (histMedian(hb, B.count) > histMedian(hr, ring) * 1.06 + 2 && stepOk(B.q, A.mask, mns, w, h))
                        A.score *= 0.05;
                }
            }
        }
        Point[] best = null;
        double bestScore = 0;
        for (Cand c : valid) if (c.score > bestScore) { bestScore = c.score; best = c.q; }

        releaseAll(sm, gray, mn, mx, eg, em, tmp, raw, edges, nz, sat, lowSat, bright, paper, pm, mask, inter, k3, k5, k9);
        valid.clear();
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

    private static final class Cand { Point[] q; double score; byte[] mask; int count; }

    private static byte[] maskBytes(Mat m, int w, int h) {
        byte[] b = new byte[w * h];
        m.get(0, 0, b);
        return b;
    }

    private static boolean sameQuad(Point[] a, Point[] b, int w, int h) {
        double tol = 0.02 * Math.hypot(w, h);
        for (Point p : a) {
            double best = Double.MAX_VALUE;
            for (Point q : b) best = Math.min(best, Math.hypot(p.x - q.x, p.y - q.y));
            if (best > tol) return false;
        }
        return true;
    }

    private static double histMedian(int[] hist, int n) {
        long acc = 0, target = n / 2;
        for (int v = 0; v < 256; v++) { acc += hist[v]; if (acc > target) return v; }
        return 255;
    }

    private static double median(List<Double> v) {
        Collections.sort(v);
        int n = v.size();
        return n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2.0;
    }

    /** 안쪽 사각형의 변이 '또렷한' 밝기 계단인지(화면 속 문서 테두리) — 조명 얼룩처럼 서서히 변하면 아님 */
    private static boolean stepOk(Point[] q, byte[] inA, byte[] mns, int w, int h) {
        double cx = 0, cy = 0;
        for (Point p : q) { cx += p.x / 4; cy += p.y / 4; }
        int nSides = 0, nStep = 0;
        for (int i = 0; i < 4; i++) {
            Point p0 = q[i], p1 = q[(i + 1) % 4];
            double dx = p1.x - p0.x, dy = p1.y - p0.y, L = Math.hypot(dx, dy) + 1e-6;
            double nx = -dy / L, ny = dx / L;
            double mx = (p0.x + p1.x) / 2, my = (p0.y + p1.y) / 2;
            if ((cx - mx) * nx + (cy - my) * ny < 0) { nx = -nx; ny = -ny; }
            List<Double> i2 = new ArrayList<>(), o2 = new ArrayList<>(), i8 = new ArrayList<>(), o8 = new ArrayList<>();
            int shared = 0;
            for (int t = 0; t < 40; t++) {
                double f = 0.1 + 0.8 * (t + 0.5) / 40;
                double px = p0.x + dx * f, py = p0.y + dy * f;
                int obx = (int) (px - nx * 8), oby = (int) (py - ny * 8);
                if (obx < 0 || obx >= w || oby < 0 || oby >= h || inA[oby * w + obx] == 0) { shared++; continue; }
                int a = px(mns, w, h, px + nx * 3, py + ny * 3), b = px(mns, w, h, px - nx * 3, py - ny * 3);
                int c = px(mns, w, h, px + nx * 9, py + ny * 9), d = px(mns, w, h, px - nx * 9, py - ny * 9);
                if (a < 0 || b < 0 || c < 0 || d < 0) continue;
                i2.add((double) a); o2.add((double) b); i8.add((double) c); o8.add((double) d);
            }
            if (shared > 20 || i2.size() < 10) continue;
            nSides++;
            double mo2 = median(o2);
            double near = median(i2) - mo2, far = median(i8) - median(o8);
            if (near >= Math.max(6, 0.04 * mo2) && near >= 0.6 * far) nStep++;
        }
        return nSides >= 1 && nStep * 2 >= nSides;
    }

    private static int px(byte[] m, int w, int h, double x, double y) {
        int ix = (int) Math.round(x), iy = (int) Math.round(y);
        if (ix < 0 || ix >= w || iy < 0 || iy >= h) return -1;
        return m[iy * w + ix] & 255;
    }

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
