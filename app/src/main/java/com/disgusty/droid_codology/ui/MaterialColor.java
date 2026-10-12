package com.disgusty.droid_codology.ui;

/**
 * Material You color: a port of Google's material-color-utilities (Apache License 2.0) — the
 * CAM16 color appearance model, the HCT color space (hue, chroma, tone), tonal palettes and the
 * Material 3 color roles generated from a single seed color, as Android 12+ does.
 */
public final class MaterialColor {
    private MaterialColor() {
    }

    // ------------------------------------------------------------------ color utils

    private static final double[][] SRGB_TO_XYZ = {
            {0.41233895, 0.35762064, 0.18051042},
            {0.2126, 0.7152, 0.0722},
            {0.01932141, 0.11916382, 0.95034478}};
    private static final double[][] XYZ_TO_SRGB = {
            {3.2413774792388685, -1.5376652402851851, -0.49885366846268053},
            {-0.9691452513005321, 1.8758853451067872, 0.04156585616912061},
            {0.05562093689691305, -0.20395524564742123, 1.0571799111220335}};
    private static final double[] WHITE_POINT_D65 = {95.047, 100.0, 108.883};

    static double linearized(int c) {
        double n = c / 255.0;
        return n <= 0.040449936 ? n / 12.92 * 100.0 : Math.pow((n + 0.055) / 1.055, 2.4) * 100.0;
    }

    static int delinearized(double c) {
        double n = c / 100.0;
        double d = n <= 0.0031308 ? n * 12.92 : 1.055 * Math.pow(n, 1.0 / 2.4) - 0.055;
        int v = (int) Math.round(d * 255.0);
        return v < 0 ? 0 : Math.min(255, v);
    }

    static int argbFromXyz(double x, double y, double z) {
        double[][] m = XYZ_TO_SRGB;
        int r = delinearized(m[0][0] * x + m[0][1] * y + m[0][2] * z);
        int g = delinearized(m[1][0] * x + m[1][1] * y + m[1][2] * z);
        int b = delinearized(m[2][0] * x + m[2][1] * y + m[2][2] * z);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    static double[] xyzFromArgb(int argb) {
        double r = linearized((argb >> 16) & 0xFF), g = linearized((argb >> 8) & 0xFF), b = linearized(argb & 0xFF);
        double[][] m = SRGB_TO_XYZ;
        return new double[]{m[0][0] * r + m[0][1] * g + m[0][2] * b, m[1][0] * r + m[1][1] * g + m[1][2] * b,
                m[2][0] * r + m[2][1] * g + m[2][2] * b};
    }

    private static double labF(double t) {
        double e = 216.0 / 24389.0, kappa = 24389.0 / 27.0;
        return t > e ? Math.cbrt(t) : (kappa * t + 16) / 116;
    }

    private static double labInvf(double ft) {
        double e = 216.0 / 24389.0, kappa = 24389.0 / 27.0;
        double ft3 = ft * ft * ft;
        return ft3 > e ? ft3 : (116 * ft - 16) / kappa;
    }

    static double yFromLstar(double lstar) {
        return 100.0 * labInvf((lstar + 16.0) / 116.0);
    }

    public static double lstarFromArgb(int argb) {
        double y = xyzFromArgb(argb)[1];
        return 116.0 * labF(y / 100.0) - 16.0;
    }

    static int argbFromLstar(double lstar) {
        int c = delinearized(yFromLstar(lstar));
        return 0xFF000000 | (c << 16) | (c << 8) | c;
    }

    // ------------------------------------------------------------------ viewing conditions

    static final class ViewingConditions {
        final double n, aw, nbb, ncb, c, nc, fl, flRoot, z;
        final double[] rgbD;

        ViewingConditions(double n, double aw, double nbb, double ncb, double c, double nc, double[] rgbD, double fl, double flRoot, double z) {
            this.n = n;
            this.aw = aw;
            this.nbb = nbb;
            this.ncb = ncb;
            this.c = c;
            this.nc = nc;
            this.rgbD = rgbD;
            this.fl = fl;
            this.flRoot = flRoot;
            this.z = z;
        }

        static final ViewingConditions DEFAULT = make(WHITE_POINT_D65, 200.0 / Math.PI * yFromLstar(50.0) / 100.0, 50.0, 2.0, false);

        static ViewingConditions make(double[] wp, double adaptingLuminance, double backgroundLstar, double surround, boolean discounting) {
            double[][] m = Cam16.XYZ_TO_CAM16RGB;
            double rW = wp[0] * m[0][0] + wp[1] * m[0][1] + wp[2] * m[0][2];
            double gW = wp[0] * m[1][0] + wp[1] * m[1][1] + wp[2] * m[1][2];
            double bW = wp[0] * m[2][0] + wp[1] * m[2][1] + wp[2] * m[2][2];
            double f = 0.8 + surround / 10.0;
            double c = f >= 0.9 ? lerp(0.59, 0.69, (f - 0.9) * 10.0) : lerp(0.525, 0.59, (f - 0.8) * 10.0);
            double d = discounting ? 1.0 : f * (1.0 - (1.0 / 3.6) * Math.exp((-adaptingLuminance - 42.0) / 92.0));
            d = d > 1 ? 1 : d < 0 ? 0 : d;
            double[] rgbD = {d * (100.0 / rW) + 1.0 - d, d * (100.0 / gW) + 1.0 - d, d * (100.0 / bW) + 1.0 - d};
            double k = 1.0 / (5.0 * adaptingLuminance + 1.0);
            double k4 = k * k * k * k, k4F = 1.0 - k4;
            double fl = k4 * adaptingLuminance + 0.1 * k4F * k4F * Math.cbrt(5.0 * adaptingLuminance);
            double n = yFromLstar(backgroundLstar) / wp[1];
            double z = 1.48 + Math.sqrt(n);
            double nbb = 0.725 / Math.pow(n, 0.2);
            double[] af = {Math.pow(fl * rgbD[0] * rW / 100.0, 0.42), Math.pow(fl * rgbD[1] * gW / 100.0, 0.42), Math.pow(fl * rgbD[2] * bW / 100.0, 0.42)};
            double[] a = {400.0 * af[0] / (af[0] + 27.13), 400.0 * af[1] / (af[1] + 27.13), 400.0 * af[2] / (af[2] + 27.13)};
            double aw = (2.0 * a[0] + a[1] + 0.05 * a[2]) * nbb;
            return new ViewingConditions(n, aw, nbb, nbb, c, f, rgbD, fl, Math.pow(fl, 0.25), z);
        }

        private static double lerp(double a, double b, double t) {
            return (1.0 - t) * a + t * b;
        }
    }

    // ------------------------------------------------------------------ CAM16

    static final class Cam16 {
        static final double[][] XYZ_TO_CAM16RGB = {
                {0.401288, 0.650173, -0.051461},
                {-0.250268, 1.204414, 0.045854},
                {-0.002079, 0.048952, 0.953127}};

        final double hue, chroma, j, jstar, astar, bstar;

        Cam16(double hue, double chroma, double j, double jstar, double astar, double bstar) {
            this.hue = hue;
            this.chroma = chroma;
            this.j = j;
            this.jstar = jstar;
            this.astar = astar;
            this.bstar = bstar;
        }

        double distance(Cam16 o) {
            double dJ = jstar - o.jstar, dA = astar - o.astar, dB = bstar - o.bstar;
            return 1.41 * Math.pow(Math.sqrt(dJ * dJ + dA * dA + dB * dB), 0.63);
        }

        static Cam16 fromInt(int argb) {
            ViewingConditions vc = ViewingConditions.DEFAULT;
            double[] xyz = xyzFromArgb(argb);
            double x = xyz[0], y = xyz[1], z = xyz[2];
            double rC = 0.401288 * x + 0.650173 * y - 0.051461 * z;
            double gC = -0.250268 * x + 1.204414 * y + 0.045854 * z;
            double bC = -0.002079 * x + 0.048952 * y + 0.953127 * z;
            double rD = vc.rgbD[0] * rC, gD = vc.rgbD[1] * gC, bD = vc.rgbD[2] * bC;
            double rAF = Math.pow(vc.fl * Math.abs(rD) / 100.0, 0.42);
            double gAF = Math.pow(vc.fl * Math.abs(gD) / 100.0, 0.42);
            double bAF = Math.pow(vc.fl * Math.abs(bD) / 100.0, 0.42);
            double rA = Math.signum(rD) * 400.0 * rAF / (rAF + 27.13);
            double gA = Math.signum(gD) * 400.0 * gAF / (gAF + 27.13);
            double bA = Math.signum(bD) * 400.0 * bAF / (bAF + 27.13);
            double a = (11.0 * rA + -12.0 * gA + bA) / 11.0;
            double b = (rA + gA - 2.0 * bA) / 9.0;
            double u = (20.0 * rA + 20.0 * gA + 21.0 * bA) / 20.0;
            double p2 = (40.0 * rA + 20.0 * gA + bA) / 20.0;
            double hue = Math.toDegrees(Math.atan2(b, a));
            if (hue < 0) hue += 360.0;
            else if (hue >= 360) hue -= 360.0;
            double hueRadians = Math.toRadians(hue);
            double ac = p2 * vc.nbb;
            double j = 100.0 * Math.pow(ac / vc.aw, vc.c * vc.z);
            double huePrime = hue < 20.14 ? hue + 360 : hue;
            double eHue = 0.25 * (Math.cos(Math.toRadians(huePrime) + 2.0) + 3.8);
            double p1 = 50000.0 / 13.0 * eHue * vc.nc * vc.ncb;
            double t = p1 * Math.hypot(a, b) / (u + 0.305);
            double alpha = Math.pow(1.64 - Math.pow(0.29, vc.n), 0.73) * Math.pow(t, 0.9);
            double c = alpha * Math.sqrt(j / 100.0);
            double m = c * vc.flRoot;
            double jstar = (1.0 + 100.0 * 0.007) * j / (1.0 + 0.007 * j);
            double mstar = 1.0 / 0.0228 * Math.log1p(0.0228 * m);
            return new Cam16(hue, c, j, jstar, mstar * Math.cos(hueRadians), mstar * Math.sin(hueRadians));
        }

        static Cam16 fromJch(double j, double c, double h) {
            ViewingConditions vc = ViewingConditions.DEFAULT;
            double m = c * vc.flRoot;
            double hueRadians = Math.toRadians(h);
            double jstar = (1.0 + 100.0 * 0.007) * j / (1.0 + 0.007 * j);
            double mstar = 1.0 / 0.0228 * Math.log1p(0.0228 * m);
            return new Cam16(h, c, j, jstar, mstar * Math.cos(hueRadians), mstar * Math.sin(hueRadians));
        }

        int viewed() {
            ViewingConditions vc = ViewingConditions.DEFAULT;
            double alpha = (chroma == 0.0 || j == 0.0) ? 0.0 : chroma / Math.sqrt(j / 100.0);
            double t = Math.pow(alpha / Math.pow(1.64 - Math.pow(0.29, vc.n), 0.73), 1.0 / 0.9);
            double hRad = Math.toRadians(hue);
            double eHue = 0.25 * (Math.cos(hRad + 2.0) + 3.8);
            double ac = vc.aw * Math.pow(j / 100.0, 1.0 / vc.c / vc.z);
            double p1 = eHue * (50000.0 / 13.0) * vc.nc * vc.ncb;
            double p2 = ac / vc.nbb;
            double hSin = Math.sin(hRad), hCos = Math.cos(hRad);
            double gamma = 23.0 * (p2 + 0.305) * t / (23.0 * p1 + 11.0 * t * hCos + 108.0 * t * hSin);
            double a = gamma * hCos, b = gamma * hSin;
            double rA = (460.0 * p2 + 451.0 * a + 288.0 * b) / 1403.0;
            double gA = (460.0 * p2 - 891.0 * a - 261.0 * b) / 1403.0;
            double bA = (460.0 * p2 - 220.0 * a - 6300.0 * b) / 1403.0;
            double rC = Math.signum(rA) * (100.0 / vc.fl) * Math.pow(Math.max(0, 27.13 * Math.abs(rA) / (400.0 - Math.abs(rA))), 1.0 / 0.42);
            double gC = Math.signum(gA) * (100.0 / vc.fl) * Math.pow(Math.max(0, 27.13 * Math.abs(gA) / (400.0 - Math.abs(gA))), 1.0 / 0.42);
            double bC = Math.signum(bA) * (100.0 / vc.fl) * Math.pow(Math.max(0, 27.13 * Math.abs(bA) / (400.0 - Math.abs(bA))), 1.0 / 0.42);
            double rF = rC / vc.rgbD[0], gF = gC / vc.rgbD[1], bF = bC / vc.rgbD[2];
            double x = 1.86206786 * rF - 1.01125463 * gF + 0.14918677 * bF;
            double y = 0.38752654 * rF + 0.62144744 * gF - 0.00897398 * bF;
            double z = -0.01584150 * rF - 0.03412294 * gF + 1.04996444 * bF;
            return argbFromXyz(x, y, z);
        }
    }

    // ------------------------------------------------------------------ HCT

    /** Hue and chroma of a color (tone is lstarFromArgb). */
    public static double[] hueChroma(int argb) {
        Cam16 c = Cam16.fromInt(argb);
        return new double[]{c.hue, c.chroma};
    }

    /** The sRGB color closest to the given hue, chroma and tone (reducing chroma to stay in gamut). */
    public static int hct(double hue, double chroma, double tone) {
        if (chroma < 1.0 || Math.round(tone) <= 0.0 || Math.round(tone) >= 100.0) return argbFromLstar(tone);
        hue = hue < 0 ? hue % 360 + 360 : hue % 360;
        double high = chroma, mid = chroma, low = 0.0;
        boolean first = true;
        Cam16 answer = null;
        while (Math.abs(low - high) >= 0.4) {
            Cam16 possible = findCamByJ(hue, mid, tone);
            if (first) {
                if (possible != null) return possible.viewed();
                first = false;
                mid = low + (high - low) / 2.0;
                continue;
            }
            if (possible == null) high = mid;
            else {
                answer = possible;
                low = mid;
            }
            mid = low + (high - low) / 2.0;
        }
        return answer == null ? argbFromLstar(tone) : answer.viewed();
    }

    private static Cam16 findCamByJ(double hue, double chroma, double tone) {
        double low = 0.0, high = 100.0, bestdL = 1000.0, bestdE = 1000.0;
        Cam16 best = null;
        while (Math.abs(low - high) > 0.01) {
            double mid = low + (high - low) / 2;
            int clipped = Cam16.fromJch(mid, chroma, hue).viewed();
            double clippedL = lstarFromArgb(clipped);
            double dL = Math.abs(tone - clippedL);
            if (dL < 0.2) {
                Cam16 camClipped = Cam16.fromInt(clipped);
                double dE = camClipped.distance(Cam16.fromJch(camClipped.j, camClipped.chroma, hue));
                if (dE <= 1.0) {
                    bestdL = dL;
                    bestdE = dE;
                    best = camClipped;
                }
            }
            if (bestdL == 0 && bestdE == 0) break;
            if (clippedL < tone) low = mid;
            else high = mid;
        }
        return best;
    }

    // ------------------------------------------------------------------ palettes & scheme

    /** A hue/chroma pair from which any tone can be taken (results cached). */
    public static final class TonalPalette {
        final double hue, chroma;
        private final java.util.HashMap<Integer, Integer> cache = new java.util.HashMap<Integer, Integer>();

        public TonalPalette(double hue, double chroma) {
            this.hue = hue;
            this.chroma = chroma;
        }

        public int tone(int t) {
            Integer c = cache.get(t);
            if (c == null) {
                c = hct(hue, chroma, t);
                cache.put(t, c);
            }
            return c;
        }
    }

    /** Material 3 color roles (names as in the spec / Android's dynamic colors). */
    public static final class Scheme {
        public int primary, onPrimary, primaryContainer, onPrimaryContainer, secondary, onSecondary,
                secondaryContainer, onSecondaryContainer, tertiary, onTertiary, tertiaryContainer,
                onTertiaryContainer, error, onError, errorContainer, onErrorContainer, surface, onSurface,
                surfaceVariant, onSurfaceVariant, surfaceDim, surfaceBright, surfaceContainerLowest,
                surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest, outline,
                outlineVariant, inverseSurface, inverseOnSurface, inversePrimary, scrim;
    }

    /**
     * The scheme Android 12's Material You builds from a seed color (CorePalette: a1 = seed hue with
     * chroma ≥ 48, a2 = chroma 16, a3 = hue + 60 chroma 24, n1 = chroma 4, n2 = chroma 8).
     */
    public static Scheme scheme(int seed, boolean dark) {
        double[] hc = hueChroma(seed);
        double hue = hc[0];
        TonalPalette a1 = new TonalPalette(hue, Math.max(48, hc[1]));
        TonalPalette a2 = new TonalPalette(hue, 16);
        TonalPalette a3 = new TonalPalette(hue + 60, 24);
        TonalPalette n1 = new TonalPalette(hue, 4);
        TonalPalette n2 = new TonalPalette(hue, 8);
        TonalPalette er = new TonalPalette(25, 84);
        Scheme s = new Scheme();
        if (!dark) {
            s.primary = a1.tone(40);
            s.onPrimary = a1.tone(100);
            s.primaryContainer = a1.tone(90);
            s.onPrimaryContainer = a1.tone(10);
            s.secondary = a2.tone(40);
            s.onSecondary = a2.tone(100);
            s.secondaryContainer = a2.tone(90);
            s.onSecondaryContainer = a2.tone(10);
            s.tertiary = a3.tone(40);
            s.onTertiary = a3.tone(100);
            s.tertiaryContainer = a3.tone(90);
            s.onTertiaryContainer = a3.tone(10);
            s.error = er.tone(40);
            s.onError = er.tone(100);
            s.errorContainer = er.tone(90);
            s.onErrorContainer = er.tone(10);
            s.surface = n1.tone(98);
            s.onSurface = n1.tone(10);
            s.surfaceVariant = n2.tone(90);
            s.onSurfaceVariant = n2.tone(30);
            s.surfaceDim = n1.tone(87);
            s.surfaceBright = n1.tone(98);
            s.surfaceContainerLowest = n1.tone(100);
            s.surfaceContainerLow = n1.tone(96);
            s.surfaceContainer = n1.tone(94);
            s.surfaceContainerHigh = n1.tone(92);
            s.surfaceContainerHighest = n1.tone(90);
            s.outline = n2.tone(50);
            s.outlineVariant = n2.tone(80);
            s.inverseSurface = n1.tone(20);
            s.inverseOnSurface = n1.tone(95);
            s.inversePrimary = a1.tone(80);
        } else {
            s.primary = a1.tone(80);
            s.onPrimary = a1.tone(20);
            s.primaryContainer = a1.tone(30);
            s.onPrimaryContainer = a1.tone(90);
            s.secondary = a2.tone(80);
            s.onSecondary = a2.tone(20);
            s.secondaryContainer = a2.tone(30);
            s.onSecondaryContainer = a2.tone(90);
            s.tertiary = a3.tone(80);
            s.onTertiary = a3.tone(20);
            s.tertiaryContainer = a3.tone(30);
            s.onTertiaryContainer = a3.tone(90);
            s.error = er.tone(80);
            s.onError = er.tone(20);
            s.errorContainer = er.tone(30);
            s.onErrorContainer = er.tone(90);
            s.surface = n1.tone(6);
            s.onSurface = n1.tone(90);
            s.surfaceVariant = n2.tone(30);
            s.onSurfaceVariant = n2.tone(80);
            s.surfaceDim = n1.tone(6);
            s.surfaceBright = n1.tone(24);
            s.surfaceContainerLowest = n1.tone(4);
            s.surfaceContainerLow = n1.tone(10);
            s.surfaceContainer = n1.tone(12);
            s.surfaceContainerHigh = n1.tone(17);
            s.surfaceContainerHighest = n1.tone(22);
            s.outline = n2.tone(60);
            s.outlineVariant = n2.tone(30);
            s.inverseSurface = n1.tone(90);
            s.inverseOnSurface = n1.tone(20);
            s.inversePrimary = a1.tone(40);
        }
        s.scrim = 0xFF000000;
        return s;
    }
}
