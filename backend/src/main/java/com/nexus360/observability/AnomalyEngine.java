package com.nexus360.observability;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Statistical anomaly detection on a series of per-minute values (documented model, no machine learning service):
 *
 * <ul>
 *   <li><b>EWMA</b>: exponentially weighted mean and variance with {@code alpha = 2 / (window + 1)}, initialised from the first
 *       {@code minSamples} points. A point's score is {@code z = (x − mean) / σ} against the baseline <i>before</i> the point. Points
 *       that exceed the threshold do not update the baseline (so a spike does not mask itself) unless the excursion lasts longer
 *       than {@code window} points (the baseline then adapts to the new level).</li>
 *   <li><b>MEDIAN_MAD</b>: robust baseline over the previous {@code window} points: median and {@code σ = 1.4826 · MAD}.</li>
 *   <li><b>Seasonality</b> (hour of week): when enabled, the baseline of a point is the median/MAD of the earlier points with the same
 *       hour of week (other weeks) if there are at least {@code minSamples} of them, else the non-seasonal baseline.</li>
 *   <li>σ has a floor of {@code max(1e-6, 1 % of |baseline|)} so flat series do not flag sub-percent jitter.</li>
 *   <li>A point <i>exceeds</i> when {@code z ≥ threshold} (UP), {@code z ≤ −threshold} (DOWN) or either (BOTH). An <b>anomaly</b> is a run
 *       of at least {@code consecutive} exceeding points; it lasts until the first non-exceeding point. Its expected range is
 *       {@code baseline ± threshold · σ} at the peak point.</li>
 * </ul>
 * No point is scored before {@code minSamples} baseline points exist ("insufficient data").
 */
public final class AnomalyEngine {

    private AnomalyEngine() {}

    public enum Algorithm { EWMA, MEDIAN_MAD }

    public enum Direction { UP, DOWN, BOTH }

    public record Params(Algorithm algorithm, int window, double threshold, int consecutive, int minSamples, Direction direction, boolean seasonal) {

        public Map<String, Object> describe() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("algorithm", algorithm.name());
            m.put("window", window);
            m.put("threshold", threshold);
            m.put("consecutive", consecutive);
            m.put("minSamples", minSamples);
            m.put("direction", direction.name());
            m.put("seasonality", seasonal ? "HOUR_OF_WEEK" : "NONE");
            m.put("sigmaFloor", "max(1e-6, 0.01*|baseline|)");
            return m;
        }
    }

    public record Point(Instant t, double v) {}

    /** {@code expected}/{@code sigma}/{@code z} are null for warm-up points. */
    public record Scored(Instant t, double value, Double expected, Double sigma, Double z, boolean exceeds) {}

    public record Window(Instant start, Instant end, int points, double peakScore, String direction, double observed, double expected, double lower,
                         double upper, boolean open) {}

    /** {@code scoredPoints}: points that had a baseline; {@code sufficient}: at least one point could be scored. */
    public record Result(List<Scored> scored, List<Window> windows, int scoredPoints, boolean sufficient) {}

    public static Result detect(List<Point> points, Params p) {
        int n = points.size();
        List<Scored> scored = new ArrayList<>(n);
        if (n <= p.minSamples()) {
            for (Point pt : points) {
                scored.add(new Scored(pt.t(), pt.v(), null, null, null, false));
            }
            return new Result(scored, List.of(), 0, false);
        }
        Map<Integer, List<Point>> byHourOfWeek = new HashMap<>();
        double mean = 0;
        double var = 0;
        if (p.algorithm() == Algorithm.EWMA) {
            for (int i = 0; i < p.minSamples(); i++) {
                mean += points.get(i).v();
            }
            mean /= p.minSamples();
            for (int i = 0; i < p.minSamples(); i++) {
                double d = points.get(i).v() - mean;
                var += d * d;
            }
            var /= Math.max(1, p.minSamples() - 1);
        }
        double alpha = 2.0 / (p.window() + 1.0);
        int exceedRun = 0;
        int scoredCount = 0;
        for (int i = 0; i < n; i++) {
            Point pt = points.get(i);
            int how = p.seasonal() ? hourOfWeek(pt.t()) : -1;
            if (i < p.minSamples()) {
                scored.add(new Scored(pt.t(), pt.v(), null, null, null, false));
                if (p.seasonal()) {
                    byHourOfWeek.computeIfAbsent(how, k -> new ArrayList<>()).add(pt);
                }
                continue;
            }
            double expected;
            double sigma;
            // Same hour of week in earlier weeks only (at least six days before this point).
            Instant cutoff = pt.t().minus(java.time.Duration.ofDays(6));
            double[] seasonalValues = p.seasonal() ? byHourOfWeek.getOrDefault(how, List.of()).stream().filter(x -> !x.t().isAfter(cutoff))
                    .mapToDouble(Point::v).toArray() : new double[0];
            if (p.seasonal() && seasonalValues.length >= p.minSamples()) {
                double[] values = seasonalValues;
                expected = median(values);
                sigma = 1.4826 * mad(values, expected);
            } else if (p.algorithm() == Algorithm.EWMA) {
                expected = mean;
                sigma = Math.sqrt(Math.max(0, var));
            } else {
                int from = Math.max(0, i - p.window());
                double[] values = new double[i - from];
                for (int k = from; k < i; k++) {
                    values[k - from] = points.get(k).v();
                }
                expected = median(values);
                sigma = 1.4826 * mad(values, expected);
            }
            sigma = Math.max(sigma, Math.max(1e-6, 0.01 * Math.abs(expected)));
            double z = (pt.v() - expected) / sigma;
            boolean exceeds = switch (p.direction()) {
                case UP -> z >= p.threshold();
                case DOWN -> z <= -p.threshold();
                case BOTH -> Math.abs(z) >= p.threshold();
            };
            scored.add(new Scored(pt.t(), pt.v(), expected, sigma, z, exceeds));
            scoredCount++;
            exceedRun = exceeds ? exceedRun + 1 : 0;
            if (p.algorithm() == Algorithm.EWMA && (!exceeds || exceedRun > p.window())) {
                double d = pt.v() - mean;
                mean += alpha * d;
                var = (1 - alpha) * (var + alpha * d * d);
            }
            if (p.seasonal()) {
                byHourOfWeek.computeIfAbsent(how, k -> new ArrayList<>()).add(pt);
            }
        }
        return new Result(scored, windows(scored, p), scoredCount, scoredCount > 0);
    }

    static List<Window> windows(List<Scored> scored, Params p) {
        List<Window> out = new ArrayList<>();
        int i = 0;
        while (i < scored.size()) {
            if (!scored.get(i).exceeds()) {
                i++;
                continue;
            }
            int start = i;
            while (i < scored.size() && scored.get(i).exceeds()) {
                i++;
            }
            int end = i - 1;
            if (end - start + 1 >= p.consecutive()) {
                Scored peak = scored.get(start);
                for (int k = start; k <= end; k++) {
                    if (Math.abs(scored.get(k).z()) > Math.abs(peak.z())) {
                        peak = scored.get(k);
                    }
                }
                double band = p.threshold() * peak.sigma();
                out.add(new Window(scored.get(start).t(), scored.get(end).t(), end - start + 1, Math.abs(peak.z()), peak.z() >= 0 ? "UP" : "DOWN",
                        peak.value(), peak.expected(), peak.expected() - band, peak.expected() + band, end == scored.size() - 1));
            }
        }
        return out;
    }

    static int hourOfWeek(Instant t) {
        ZonedDateTime z = t.atZone(ZoneOffset.UTC);
        return (z.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue()) * 24 + z.getHour();
    }

    static double median(double[] values) {
        if (values.length == 0) {
            return 0;
        }
        double[] v = values.clone();
        Arrays.sort(v);
        int m = v.length / 2;
        return v.length % 2 == 1 ? v[m] : (v[m - 1] + v[m]) / 2.0;
    }

    static double mad(double[] values, double median) {
        if (values.length == 0) {
            return 0;
        }
        double[] d = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            d[i] = Math.abs(values[i] - median);
        }
        return median(d);
    }
}
