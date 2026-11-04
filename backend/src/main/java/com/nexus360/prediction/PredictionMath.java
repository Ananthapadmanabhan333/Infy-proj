package com.nexus360.prediction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The statistics behind SA Predictive Intelligence, implemented in plain Java (no external ML service) and deterministic:
 * <ul>
 *   <li>feature standardisation (z-scores with the training mean and standard deviation; constant features get σ = 1)</li>
 *   <li>L2-regularised logistic regression fitted by Newton–Raphson (IRLS): minimises Σ log-loss + λ/2·‖w‖² (intercept not penalised)</li>
 *   <li>classification metrics: AUC (Mann–Whitney, ties count ½), Brier score, precision/recall at a threshold, 10-bin calibration</li>
 *   <li>Holt's linear trend and additive Holt–Winters (season m) with one-step residual σ and h-step prediction intervals, seasonal
 *       naive for short series; forecast metrics MAE, MAPE (over non-zero actuals) and interval coverage</li>
 * </ul>
 */
public final class PredictionMath {

    /** Two-sided 95 % normal quantile used for every prediction interval. */
    public static final double Z95 = 1.959963984540054;

    private PredictionMath() {}

    // ------------------------------------------------------------------ standardisation

    public record Standardizer(double[] means, double[] stds) {

        public static Standardizer fit(double[][] x) {
            int d = x.length == 0 ? 0 : x[0].length;
            double[] mean = new double[d];
            double[] std = new double[d];
            for (double[] row : x) {
                for (int j = 0; j < d; j++) {
                    mean[j] += row[j];
                }
            }
            for (int j = 0; j < d; j++) {
                mean[j] /= Math.max(1, x.length);
            }
            for (double[] row : x) {
                for (int j = 0; j < d; j++) {
                    std[j] += (row[j] - mean[j]) * (row[j] - mean[j]);
                }
            }
            for (int j = 0; j < d; j++) {
                std[j] = Math.sqrt(std[j] / Math.max(1, x.length));
                if (std[j] < 1e-9) {
                    std[j] = 1.0;
                }
            }
            return new Standardizer(mean, std);
        }

        public double[] apply(double[] row) {
            double[] z = new double[row.length];
            for (int j = 0; j < row.length; j++) {
                z[j] = (row[j] - means[j]) / stds[j];
            }
            return z;
        }

        public double[][] apply(double[][] x) {
            double[][] out = new double[x.length][];
            for (int i = 0; i < x.length; i++) {
                out[i] = apply(x[i]);
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ logistic regression

    /** {@code weights[0]} is the intercept; {@code weights[j+1]} belongs to standardised feature j. */
    public record Logistic(double[] weights, int iterations, boolean converged) {

        public double probability(double[] z) {
            double s = weights[0];
            for (int j = 0; j < z.length; j++) {
                s += weights[j + 1] * z[j];
            }
            return sigmoid(s);
        }
    }

    public static double sigmoid(double s) {
        if (s >= 0) {
            return 1.0 / (1.0 + Math.exp(-s));
        }
        double e = Math.exp(s);
        return e / (1.0 + e);
    }

    /** Fits on standardised rows {@code z} with labels 0/1. */
    public static Logistic fitLogistic(double[][] z, int[] y, double l2, int maxIterations) {
        int d = z.length == 0 ? 0 : z[0].length;
        int k = d + 1;
        double[] w = new double[k];
        boolean converged = false;
        int it = 0;
        for (; it < maxIterations; it++) {
            double[] g = new double[k];
            double[][] h = new double[k][k];
            for (int i = 0; i < z.length; i++) {
                double[] xi = withBias(z[i]);
                double p = sigmoid(dot(w, xi));
                double r = p - y[i];
                double wt = Math.max(p * (1 - p), 1e-10);
                for (int a = 0; a < k; a++) {
                    g[a] += r * xi[a];
                    double wa = wt * xi[a];
                    for (int b = a; b < k; b++) {
                        h[a][b] += wa * xi[b];
                    }
                }
            }
            for (int a = 0; a < k; a++) {
                for (int b = 0; b < a; b++) {
                    h[a][b] = h[b][a];
                }
                double penalty = a == 0 ? 1e-8 : l2;
                g[a] += a == 0 ? 0 : l2 * w[a];
                h[a][a] += penalty;
            }
            double[] step = solve(h, g);
            double norm = 0;
            for (int a = 0; a < k; a++) {
                w[a] -= step[a];
                norm += step[a] * step[a];
            }
            if (Math.sqrt(norm) < 1e-7) {
                converged = true;
                it++;
                break;
            }
        }
        return new Logistic(w, it, converged);
    }

    private static double[] withBias(double[] z) {
        double[] x = new double[z.length + 1];
        x[0] = 1;
        System.arraycopy(z, 0, x, 1, z.length);
        return x;
    }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    /** Gaussian elimination with partial pivoting (the matrices here are small and positive definite). */
    static double[] solve(double[][] a, double[] b) {
        int n = b.length;
        double[][] m = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n] = b[i];
        }
        for (int c = 0; c < n; c++) {
            int pivot = c;
            for (int r = c + 1; r < n; r++) {
                if (Math.abs(m[r][c]) > Math.abs(m[pivot][c])) {
                    pivot = r;
                }
            }
            double[] tmp = m[c];
            m[c] = m[pivot];
            m[pivot] = tmp;
            if (Math.abs(m[c][c]) < 1e-14) {
                m[c][c] = 1e-14;
            }
            for (int r = c + 1; r < n; r++) {
                double f = m[r][c] / m[c][c];
                if (f != 0) {
                    for (int k = c; k <= n; k++) {
                        m[r][k] -= f * m[c][k];
                    }
                }
            }
        }
        double[] x = new double[n];
        for (int r = n - 1; r >= 0; r--) {
            double s = m[r][n];
            for (int k = r + 1; k < n; k++) {
                s -= m[r][k] * x[k];
            }
            x[r] = s / m[r][r];
        }
        return x;
    }

    // ------------------------------------------------------------------ classification metrics

    /** Area under the ROC curve; null when only one class is present. */
    public static Double auc(double[] p, int[] y) {
        int n = p.length;
        long pos = Arrays.stream(y).filter(v -> v == 1).count();
        long neg = n - pos;
        if (pos == 0 || neg == 0) {
            return null;
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingDouble(i -> p[i]));
        double rankSumPos = 0;
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && p[order[j + 1]] == p[order[i]]) {
                j++;
            }
            double rank = (i + j) / 2.0 + 1;
            for (int k = i; k <= j; k++) {
                if (y[order[k]] == 1) {
                    rankSumPos += rank;
                }
            }
            i = j + 1;
        }
        return (rankSumPos - pos * (pos + 1) / 2.0) / ((double) pos * neg);
    }

    public static double brier(double[] p, int[] y) {
        double s = 0;
        for (int i = 0; i < p.length; i++) {
            s += (p[i] - y[i]) * (p[i] - y[i]);
        }
        return p.length == 0 ? 0 : s / p.length;
    }

    public record Confusion(int tp, int fp, int tn, int fn, Double precision, Double recall) {}

    public static Confusion confusion(double[] p, int[] y, double threshold) {
        int tp = 0;
        int fp = 0;
        int tn = 0;
        int fn = 0;
        for (int i = 0; i < p.length; i++) {
            boolean predicted = p[i] >= threshold;
            if (predicted && y[i] == 1) {
                tp++;
            } else if (predicted) {
                fp++;
            } else if (y[i] == 1) {
                fn++;
            } else {
                tn++;
            }
        }
        return new Confusion(tp, fp, tn, fn, tp + fp == 0 ? null : (double) tp / (tp + fp), tp + fn == 0 ? null : (double) tp / (tp + fn));
    }

    /** The threshold among the observed probabilities that maximises F1 (ties: the higher threshold); 0.5 when undefined. */
    public static double bestF1Threshold(double[] p, int[] y) {
        double[] candidates = Arrays.stream(p).distinct().sorted().toArray();
        double best = 0.5;
        double bestF1 = -1;
        for (double t : candidates) {
            Confusion c = confusion(p, y, t);
            if (c.precision() == null || c.recall() == null || c.precision() + c.recall() == 0) {
                continue;
            }
            double f1 = 2 * c.precision() * c.recall() / (c.precision() + c.recall());
            if (f1 >= bestF1) {
                bestF1 = f1;
                best = t;
            }
        }
        return best;
    }

    public record CalibrationBin(int bin, double from, double to, int count, Double meanPredicted, Double observedRate) {}

    /** Ten equal-width probability bins [0, 0.1), …, [0.9, 1.0]. */
    public static List<CalibrationBin> calibration(double[] p, int[] y) {
        int[] count = new int[10];
        double[] sumP = new double[10];
        int[] pos = new int[10];
        for (int i = 0; i < p.length; i++) {
            int b = Math.min(9, (int) Math.floor(p[i] * 10));
            count[b]++;
            sumP[b] += p[i];
            pos[b] += y[i];
        }
        List<CalibrationBin> bins = new ArrayList<>();
        for (int b = 0; b < 10; b++) {
            bins.add(new CalibrationBin(b + 1, b / 10.0, (b + 1) / 10.0, count[b], count[b] == 0 ? null : round4(sumP[b] / count[b]),
                    count[b] == 0 ? null : round4((double) pos[b] / count[b])));
        }
        return bins;
    }

    /** The standard set of classification metrics at {@code threshold}. */
    public static Map<String, Object> classificationMetrics(double[] p, int[] y, double threshold) {
        Map<String, Object> m = new LinkedHashMap<>();
        int positives = Arrays.stream(y).sum();
        Confusion c = confusion(p, y, threshold);
        m.put("samples", p.length);
        m.put("positives", positives);
        m.put("negatives", p.length - positives);
        Double auc = auc(p, y);
        m.put("auc", auc == null ? null : round4(auc));
        m.put("brier", round4(brier(p, y)));
        m.put("threshold", round4(threshold));
        m.put("precision", c.precision() == null ? null : round4(c.precision()));
        m.put("recall", c.recall() == null ? null : round4(c.recall()));
        m.put("truePositives", c.tp());
        m.put("falsePositives", c.fp());
        m.put("trueNegatives", c.tn());
        m.put("falseNegatives", c.fn());
        m.put("baseRate", p.length == 0 ? null : round4((double) positives / p.length));
        m.put("calibration", calibration(p, y));
        return m;
    }

    // ------------------------------------------------------------------ forecasting

    /** A fitted exponential-smoothing model: final level/trend/seasonal state and the one-step residual σ. */
    public record Smoothing(String method, double alpha, double beta, double gamma, int season, double level, double trend, double[] seasonal,
                            double sigma, int fitted, double sse) {

        /** Point forecast h steps (h ≥ 1) after the last observation; {@code lastIndex} is the index of that observation. */
        public double forecast(int h, int lastIndex) {
            return switch (method) {
                case "HOLT" -> level + h * trend;
                case "HOLT_WINTERS" -> level + h * trend + seasonal[Math.floorMod(lastIndex + h, season)];
                default -> seasonal[Math.floorMod(lastIndex + h, season)];
            };
        }

        /** Standard deviation of the h-step forecast error (exponential-smoothing state-space approximation). */
        public double sd(int h) {
            double v = 1;
            for (int j = 1; j < h; j++) {
                double c = switch (method) {
                    case "HOLT" -> alpha * (1 + j * beta);
                    case "HOLT_WINTERS" -> alpha * (1 + j * beta) + (j % season == 0 ? gamma : 0);
                    default -> j % season == 0 ? 1 : 0;
                };
                v += c * c;
            }
            return sigma * Math.sqrt(v);
        }
    }

    /** Holt's linear trend; needs at least 3 points. */
    public static Smoothing holt(double[] y, double alpha, double beta) {
        if (y.length < 3) {
            throw new IllegalArgumentException("Holt needs at least 3 points");
        }
        double level = y[0];
        double trend = y[1] - y[0];
        double sse = 0;
        int n = 0;
        for (int t = 1; t < y.length; t++) {
            double forecast = level + trend;
            double e = y[t] - forecast;
            if (t >= 2) {
                sse += e * e;
                n++;
            }
            double prevLevel = level;
            level = alpha * y[t] + (1 - alpha) * (level + trend);
            trend = beta * (level - prevLevel) + (1 - beta) * trend;
        }
        double sigma = Math.sqrt(sse / Math.max(1, n));
        return new Smoothing("HOLT", alpha, beta, 0, 1, level, trend, new double[]{0}, sigma, n, sse);
    }

    /** Additive Holt–Winters with season {@code m}; needs at least two full seasons. */
    public static Smoothing holtWinters(double[] y, int m, double alpha, double beta, double gamma) {
        if (y.length < 2 * m) {
            throw new IllegalArgumentException("Holt-Winters needs at least two seasons");
        }
        double mean1 = 0;
        double mean2 = 0;
        for (int i = 0; i < m; i++) {
            mean1 += y[i];
            mean2 += y[m + i];
        }
        mean1 /= m;
        mean2 /= m;
        double level = mean1;
        double trend = (mean2 - mean1) / m;
        double[] s = new double[m];
        for (int i = 0; i < m; i++) {
            s[i] = y[i] - mean1;
        }
        double sse = 0;
        int n = 0;
        for (int t = m; t < y.length; t++) {
            int idx = t % m;
            double forecast = level + trend + s[idx];
            double e = y[t] - forecast;
            sse += e * e;
            n++;
            double prevLevel = level;
            level = alpha * (y[t] - s[idx]) + (1 - alpha) * (level + trend);
            trend = beta * (level - prevLevel) + (1 - beta) * trend;
            s[idx] = gamma * (y[t] - level) + (1 - gamma) * s[idx];
        }
        double sigma = Math.sqrt(sse / Math.max(1, n));
        return new Smoothing("HOLT_WINTERS", alpha, beta, gamma, m, level, trend, s, sigma, n, sse);
    }

    /** Seasonal naive: tomorrow's value = the value one season earlier; needs one full season (+1 point for a residual). */
    public static Smoothing seasonalNaive(double[] y, int m) {
        if (y.length < m) {
            throw new IllegalArgumentException("Seasonal naive needs one season");
        }
        double[] s = new double[m];
        for (int t = y.length - m; t < y.length; t++) {
            s[Math.floorMod(t, m)] = y[t];
        }
        double sse = 0;
        int n = 0;
        for (int t = m; t < y.length; t++) {
            double e = y[t] - y[t - m];
            sse += e * e;
            n++;
        }
        double sigma = n == 0 ? stdev(y) : Math.sqrt(sse / n);
        return new Smoothing("SEASONAL_NAIVE", 0, 0, 0, m, 0, 0, s, sigma, n, sse);
    }

    static double stdev(double[] y) {
        double mean = Arrays.stream(y).average().orElse(0);
        return Math.sqrt(Arrays.stream(y).map(v -> (v - mean) * (v - mean)).average().orElse(0));
    }

    public static final double[] ALPHAS = {0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9};
    public static final double[] BETAS = {0.01, 0.05, 0.1, 0.2, 0.3};
    public static final double[] GAMMAS = {0.05, 0.1, 0.2, 0.3};

    /** Grid search of (α, β) minimising the pooled one-step SSE of Holt over all series with ≥ 3 points. */
    public static double[] bestHolt(List<double[]> series) {
        double best = Double.MAX_VALUE;
        double[] params = {0.5, 0.1};
        for (double a : ALPHAS) {
            for (double b : BETAS) {
                double sse = 0;
                int n = 0;
                for (double[] y : series) {
                    if (y.length >= 3) {
                        Smoothing s = holt(y, a, b);
                        sse += s.sse();
                        n += s.fitted();
                    }
                }
                if (n > 0 && sse < best) {
                    best = sse;
                    params = new double[]{a, b};
                }
            }
        }
        return params;
    }

    /** Grid search of (α, β, γ) minimising the pooled one-step SSE of additive Holt–Winters over series with ≥ 2 seasons. */
    public static double[] bestHoltWinters(List<double[]> series, int m) {
        double best = Double.MAX_VALUE;
        double[] params = {0.3, 0.05, 0.1};
        for (double a : new double[]{0.1, 0.2, 0.3, 0.5, 0.7}) {
            for (double b : new double[]{0.01, 0.05, 0.1}) {
                for (double g : GAMMAS) {
                    double sse = 0;
                    int n = 0;
                    for (double[] y : series) {
                        if (y.length >= 2 * m) {
                            Smoothing s = holtWinters(y, m, a, b, g);
                            sse += s.sse();
                            n += s.fitted();
                        }
                    }
                    if (n > 0 && sse < best) {
                        best = sse;
                        params = new double[]{a, b, g};
                    }
                }
            }
        }
        return params;
    }

    /** Accumulates forecast errors: MAE, MAPE over non-zero actuals, and the share of actuals inside the 95 % interval. */
    public static final class ForecastErrors {
        private int n;
        private double absSum;
        private double apeSum;
        private int apeN;
        private int covered;

        public void add(double actual, double predicted, double lower, double upper) {
            n++;
            absSum += Math.abs(actual - predicted);
            if (Math.abs(actual) > 1e-9) {
                apeSum += Math.abs((actual - predicted) / actual);
                apeN++;
            }
            if (actual >= lower - 1e-9 && actual <= upper + 1e-9) {
                covered++;
            }
        }

        public int count() {
            return n;
        }

        public Double mae() {
            return n == 0 ? null : round4(absSum / n);
        }

        /** Percent; null when every actual was zero. */
        public Double mape() {
            return apeN == 0 ? null : round4(apeSum / apeN * 100);
        }

        public Double coverage() {
            return n == 0 ? null : round4((double) covered / n);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("points", n);
            m.put("mae", mae());
            m.put("mape", mape());
            m.put("intervalCoverage", coverage());
            m.put("nominalCoverage", 0.95);
            return m;
        }
    }

    public static double round4(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }
}
