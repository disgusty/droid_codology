package com.disgusty.droid_codology.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import java.util.HashMap;

/**
 * Vector icons from the original icon sets ({@link IconData}), drawn with Canvas paths so they are
 * sharp at every density and need no image resources. Each style picks its set: Codicons for the
 * VS Code look, Material Icons for Holo / Material 1, Material Symbols for Material 3.
 */
public final class Icons {
    private Icons() {
    }

    public static final int SET_CODICON = 0, SET_MATERIAL = 1, SET_SYMBOLS = 2;

    private static final class Icon {
        final Path path;
        final RectF box;

        Icon(Path p, RectF b) {
            path = p;
            box = b;
        }
    }

    @SuppressWarnings("unchecked")
    private static final HashMap<String, String[]>[] ROWS = new HashMap[3];
    @SuppressWarnings("unchecked")
    private static final HashMap<String, Icon>[] CACHE = new HashMap[3];

    static {
        String[][][] src = {IconData.CODICON, IconData.MATERIAL, IconData.SYMBOLS};
        for (int s = 0; s < 3; s++) {
            ROWS[s] = new HashMap<String, String[]>();
            CACHE[s] = new HashMap<String, Icon>();
            for (String[] r : src[s]) ROWS[s].put(r[0], r);
        }
    }

    private static synchronized Icon icon(int set, String name) {
        Icon ic = CACHE[set].get(name);
        if (ic != null) return ic;
        String[] row = ROWS[set].get(name);
        if (row == null) {
            // Not in this set: borrow from the closest one.
            int alt = set == SET_CODICON ? SET_SYMBOLS : SET_CODICON;
            row = ROWS[alt].get(name);
            if (row == null) row = ROWS[SET_MATERIAL].get(name);
            if (row == null) return null;
        }
        String[] vb = row[1].split(" ");
        RectF box = new RectF(Float.parseFloat(vb[0]), Float.parseFloat(vb[1]),
                Float.parseFloat(vb[0]) + Float.parseFloat(vb[2]), Float.parseFloat(vb[1]) + Float.parseFloat(vb[3]));
        Path p = PathParser.parse(row[2]);
        if ("1".equals(row[3])) p.setFillType(Path.FillType.EVEN_ODD);
        ic = new Icon(p, box);
        CACHE[set].put(name, ic);
        return ic;
    }

    public static Drawable make(int set, String name, int color, int sizePx) {
        Icon ic = icon(set, name);
        return new IconDrawable(ic, color, sizePx);
    }

    public static boolean has(int set, String name) {
        return ROWS[set].containsKey(name);
    }

    /** Adds an icon at runtime (extension icons), for every style. */
    public static synchronized void register(String name, String viewBox, String pathData, boolean evenOdd) {
        for (int s = 0; s < 3; s++) {
            ROWS[s].put(name, new String[]{name, viewBox, pathData, evenOdd ? "1" : "0"});
            CACHE[s].remove(name);
        }
    }

    /**
     * Registers an SVG file's shapes (paths, circles, rects) under {@code name}. Returns false when
     * the file can't be used (not SVG, nothing drawable).
     */
    public static boolean registerSvg(String name, String svg) {
        java.util.regex.Matcher vb = java.util.regex.Pattern.compile("viewBox=\"([^\"]+)\"").matcher(svg);
        String viewBox;
        if (vb.find()) viewBox = vb.group(1).replace(',', ' ').trim().replaceAll("\\s+", " ");
        else {
            java.util.regex.Matcher w = java.util.regex.Pattern.compile("width=\"([\\d.]+)").matcher(svg);
            java.util.regex.Matcher h = java.util.regex.Pattern.compile("height=\"([\\d.]+)").matcher(svg);
            viewBox = "0 0 " + (w.find() ? w.group(1) : "24") + " " + (h.find() ? h.group(1) : "24");
        }
        StringBuilder d = new StringBuilder();
        java.util.regex.Matcher el = java.util.regex.Pattern.compile("<(path|circle|rect)\\b([^>]*)>").matcher(svg);
        boolean evenOdd = false;
        while (el.find()) {
            String tag = el.group(1), attrs = el.group(2);
            if (attrs.matches("(?s).*fill=\"none\".*") && !attrs.contains("stroke")) continue;
            if (attrs.contains("evenodd")) evenOdd = true;
            if (tag.equals("path")) {
                String v = attr(attrs, "d");
                if (v != null) d.append(v).append(' ');
            } else if (tag.equals("circle")) {
                float cx = num(attr(attrs, "cx")), cy = num(attr(attrs, "cy")), r = num(attr(attrs, "r"));
                d.append("M").append(cx - r).append(' ').append(cy).append("a").append(r).append(' ').append(r).append(" 0 1 0 ")
                        .append(2 * r).append(" 0a").append(r).append(' ').append(r).append(" 0 1 0 ").append(-2 * r).append(" 0z ");
            } else {
                float x = num(attr(attrs, "x")), y = num(attr(attrs, "y")), w = num(attr(attrs, "width")), h = num(attr(attrs, "height"));
                d.append("M").append(x).append(' ').append(y).append("h").append(w).append("v").append(h).append("h").append(-w).append("z ");
            }
        }
        if (d.length() == 0 || viewBox.split(" ").length != 4) return false;
        try {
            PathParser.parse(d.toString());
        } catch (RuntimeException e) {
            return false;
        }
        register(name, viewBox, d.toString().trim(), evenOdd);
        return true;
    }

    private static String attr(String attrs, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|\\s)" + name + "=\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : null;
    }

    private static float num(String s) {
        try {
            return s == null ? 0 : Float.parseFloat(s.replaceAll("[^\\d.\\-]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static final class IconDrawable extends Drawable {
        private final Icon icon;
        private final Path scaled = new Path();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size;
        private int lastW = -1, lastH = -1;

        IconDrawable(Icon icon, int color, int size) {
            this.icon = icon;
            this.size = size;
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override
        public void draw(Canvas canvas) {
            if (icon == null) return;
            Rect b = getBounds();
            if (b.width() != lastW || b.height() != lastH) {
                // Icons keep their nominal size centered in larger bounds.
                float side = Math.min(Math.min(b.width(), b.height()), size);
                float s = side / Math.max(icon.box.width(), icon.box.height());
                Matrix m = new Matrix();
                m.setTranslate(-icon.box.left, -icon.box.top);
                m.postScale(s, s);
                m.postTranslate(b.left + (b.width() - icon.box.width() * s) / 2f, b.top + (b.height() - icon.box.height() * s) / 2f);
                scaled.reset();
                icon.path.transform(m, scaled);
                scaled.setFillType(icon.path.getFillType());
                lastW = b.width();
                lastH = b.height();
            }
            canvas.drawPath(scaled, paint);
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            lastW = -1;
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** SVG path-data parser: all commands including elliptical arcs. */
    static final class PathParser {
        private final String s;
        private int i;

        private PathParser(String s) {
            this.s = s;
        }

        static Path parse(String d) {
            return new PathParser(d).run();
        }

        private Path run() {
            Path p = new Path();
            float x = 0, y = 0, sx = 0, sy = 0, cx = 0, cy = 0;
            char cmd = 'M', prev = ' ';
            while (true) {
                skip();
                if (i >= s.length()) break;
                char c = s.charAt(i);
                if (Character.isLetter(c)) {
                    cmd = c;
                    i++;
                } else if (cmd == 'M') {
                    cmd = 'L';
                } else if (cmd == 'm') {
                    cmd = 'l';
                } else if (cmd == 'Z' || cmd == 'z') {
                    break; // garbage after close
                }
                boolean rel = Character.isLowerCase(cmd);
                float ox = rel ? x : 0, oy = rel ? y : 0;
                switch (Character.toUpperCase(cmd)) {
                    case 'M':
                        x = ox + num();
                        y = oy + num();
                        p.moveTo(x, y);
                        sx = x;
                        sy = y;
                        cx = x;
                        cy = y;
                        break;
                    case 'L':
                        x = ox + num();
                        y = oy + num();
                        p.lineTo(x, y);
                        cx = x;
                        cy = y;
                        break;
                    case 'H':
                        x = ox + num();
                        p.lineTo(x, y);
                        cx = x;
                        cy = y;
                        break;
                    case 'V':
                        y = (rel ? y : 0) + num();
                        p.lineTo(x, y);
                        cx = x;
                        cy = y;
                        break;
                    case 'C': {
                        float x1 = ox + num(), y1 = oy + num(), x2 = ox + num(), y2 = oy + num();
                        x = ox + num();
                        y = oy + num();
                        p.cubicTo(x1, y1, x2, y2, x, y);
                        cx = x2;
                        cy = y2;
                        break;
                    }
                    case 'S': {
                        boolean smooth = "CcSs".indexOf(prev) >= 0;
                        float x1 = smooth ? 2 * x - cx : x, y1 = smooth ? 2 * y - cy : y;
                        float x2 = ox + num(), y2 = oy + num();
                        x = ox + num();
                        y = oy + num();
                        p.cubicTo(x1, y1, x2, y2, x, y);
                        cx = x2;
                        cy = y2;
                        break;
                    }
                    case 'Q': {
                        float x1 = ox + num(), y1 = oy + num();
                        x = ox + num();
                        y = oy + num();
                        p.quadTo(x1, y1, x, y);
                        cx = x1;
                        cy = y1;
                        break;
                    }
                    case 'T': {
                        boolean smooth = "QqTt".indexOf(prev) >= 0;
                        float x1 = smooth ? 2 * x - cx : x, y1 = smooth ? 2 * y - cy : y;
                        x = ox + num();
                        y = oy + num();
                        p.quadTo(x1, y1, x, y);
                        cx = x1;
                        cy = y1;
                        break;
                    }
                    case 'A': {
                        float rx = num(), ry = num(), rot = num();
                        boolean large = flag(), sweep = flag();
                        float nx = ox + num(), ny = oy + num();
                        arc(p, x, y, nx, ny, rx, ry, rot, large, sweep);
                        x = nx;
                        y = ny;
                        cx = x;
                        cy = y;
                        break;
                    }
                    case 'Z':
                        p.close();
                        x = sx;
                        y = sy;
                        cx = x;
                        cy = y;
                        break;
                    default:
                        return p;
                }
                prev = cmd;
            }
            return p;
        }

        private void skip() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == ',' || c == '\n' || c == '\t' || c == '\r') i++;
                else break;
            }
        }

        private boolean flag() {
            skip();
            char c = s.charAt(i++);
            return c == '1';
        }

        private float num() {
            skip();
            int st = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            boolean dot = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') i++;
                else if (c == '.' && !dot) {
                    dot = true;
                    i++;
                } else if (c == 'e' || c == 'E') {
                    i++;
                    if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
                } else break;
            }
            if (st == i) {
                i++;
                return 0;
            }
            return Float.parseFloat(s.substring(st, i));
        }

        /** SVG elliptical arc → cubic Béziers (endpoint to center parameterization, SVG spec F.6). */
        static void arc(Path p, float x0, float y0, float x, float y, float rx, float ry, float angle, boolean large, boolean sweep) {
            if (rx == 0 || ry == 0) {
                p.lineTo(x, y);
                return;
            }
            rx = Math.abs(rx);
            ry = Math.abs(ry);
            double phi = Math.toRadians(angle % 360);
            double cos = Math.cos(phi), sin = Math.sin(phi);
            double dx2 = (x0 - x) / 2.0, dy2 = (y0 - y) / 2.0;
            double x1 = cos * dx2 + sin * dy2, y1 = -sin * dx2 + cos * dy2;
            double rx2 = rx * rx, ry2 = ry * ry, x12 = x1 * x1, y12 = y1 * y1;
            double lambda = x12 / rx2 + y12 / ry2;
            if (lambda > 1) {
                double sl = Math.sqrt(lambda);
                rx *= sl;
                ry *= sl;
                rx2 = rx * rx;
                ry2 = ry * ry;
            }
            double sign = (large == sweep) ? -1 : 1;
            double sq = (rx2 * ry2 - rx2 * y12 - ry2 * x12) / (rx2 * y12 + ry2 * x12);
            double coef = sign * Math.sqrt(Math.max(0, sq));
            double cx1 = coef * (rx * y1 / ry), cy1 = coef * -(ry * x1 / rx);
            double cxx = cos * cx1 - sin * cy1 + (x0 + x) / 2.0, cyy = sin * cx1 + cos * cy1 + (y0 + y) / 2.0;
            double ux = (x1 - cx1) / rx, uy = (y1 - cy1) / ry, vx = (-x1 - cx1) / rx, vy = (-y1 - cy1) / ry;
            double start = Math.atan2(uy, ux);
            double sweepAngle = Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
            if (!sweep && sweepAngle > 0) sweepAngle -= 2 * Math.PI;
            else if (sweep && sweepAngle < 0) sweepAngle += 2 * Math.PI;
            int segs = (int) Math.ceil(Math.abs(sweepAngle) / (Math.PI / 2));
            double delta = sweepAngle / segs;
            double t = 4.0 / 3.0 * Math.tan(delta / 4);
            double a = start;
            for (int k = 0; k < segs; k++) {
                double c1 = Math.cos(a), s1 = Math.sin(a), c2 = Math.cos(a + delta), s2 = Math.sin(a + delta);
                double ex1 = c1 - t * s1, ey1 = s1 + t * c1, ex2 = c2 + t * s2, ey2 = s2 - t * c2;
                p.cubicTo(
                        (float) (cxx + rx * (cos * ex1) - ry * (sin * ey1)), (float) (cyy + rx * (sin * ex1) + ry * (cos * ey1)),
                        (float) (cxx + rx * (cos * ex2) - ry * (sin * ey2)), (float) (cyy + rx * (sin * ex2) + ry * (cos * ey2)),
                        (float) (cxx + rx * (cos * c2) - ry * (sin * s2)), (float) (cyy + rx * (sin * c2) + ry * (cos * s2)));
                a += delta;
            }
        }
    }
}
