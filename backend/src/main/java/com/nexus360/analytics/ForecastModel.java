package com.nexus360.analytics;

import java.util.ArrayList;
import java.util.List;

/**
 * Ordinary least-squares linear trend over a daily series with a ~95% prediction band
 * (±1.96 × residual standard error). Deliberately simple and explainable — labelled as a statistical
 * forecast, never as AI.
 */
public final class ForecastModel {

    public record Fit(double intercept, double slope, Double rSquared, double residualStdError) {}

    public record Point(int offset, double predicted, double lower, double upper) {}

    private ForecastModel() {}

    public static Fit fit(List<Long> values) {
        int n = values.size();
        if (n == 0) {
            return new Fit(0, 0, null, 0);
        }
        if (n == 1) {
            return new Fit(values.get(0), 0, null, 0);
        }
        double meanX = (n - 1) / 2.0;
        double meanY = values.stream().mapToLong(Long::longValue).average().orElse(0);
        double sxy = 0;
        double sxx = 0;
        for (int i = 0; i < n; i++) {
            sxy += (i - meanX) * (values.get(i) - meanY);
            sxx += (i - meanX) * (i - meanX);
        }
        double slope = sxx == 0 ? 0 : sxy / sxx;
        double intercept = meanY - slope * meanX;
        double ssRes = 0;
        double ssTot = 0;
        for (int i = 0; i < n; i++) {
            double predicted = intercept + slope * i;
            ssRes += Math.pow(values.get(i) - predicted, 2);
            ssTot += Math.pow(values.get(i) - meanY, 2);
        }
        Double r2 = ssTot == 0 ? null : 1 - ssRes / ssTot;
        double se = n > 2 ? Math.sqrt(ssRes / (n - 2)) : 0;
        return new Fit(intercept, slope, r2, se);
    }

    public static List<Point> project(Fit fit, int historyLength, int horizon) {
        List<Point> points = new ArrayList<>();
        for (int h = 1; h <= horizon; h++) {
            double x = historyLength - 1 + h;
            double predicted = Math.max(0, fit.intercept() + fit.slope() * x);
            double band = 1.96 * fit.residualStdError();
            points.add(new Point(h, round(predicted), round(Math.max(0, predicted - band)), round(predicted + band)));
        }
        return points;
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
