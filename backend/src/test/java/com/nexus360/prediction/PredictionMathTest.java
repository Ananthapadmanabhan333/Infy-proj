package com.nexus360.prediction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.nexus360.prediction.PredictionMath.CalibrationBin;
import com.nexus360.prediction.PredictionMath.Confusion;
import com.nexus360.prediction.PredictionMath.ForecastErrors;
import com.nexus360.prediction.PredictionMath.Logistic;
import com.nexus360.prediction.PredictionMath.Smoothing;
import com.nexus360.prediction.PredictionMath.Standardizer;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Deterministic unit tests for SA Predictive Intelligence statistical routines ({@link PredictionMath}).
 */
class PredictionMathTest {

    @Test
    void standardizerAndLogisticRegressionLearnSeparableSignalAndComputeClassificationMetrics() {
        int n = 120;
        double[][] x = new double[n][3];
        int[] y = new int[n];
        for (int i = 0; i < n; i++) {
            boolean pos = i >= n / 2;
            x[i][0] = pos ? 2.0 + (i % 5) * 0.2 : -2.0 - (i % 5) * 0.2;
            x[i][1] = pos ? 1.0 : 0.0;
            x[i][2] = 5.0; // constant feature -> std falls back to 1.0
            y[i] = pos ? 1 : 0;
        }
        Standardizer std = Standardizer.fit(x);
        assertThat(std.stds()[2]).isEqualTo(1.0);
        double[][] z = std.apply(x);

        Logistic model = PredictionMath.fitLogistic(z, y, 1.0, 50);
        assertThat(model.converged()).isTrue();
        assertThat(model.weights()[1]).isPositive();

        double[] p = new double[n];
        for (int i = 0; i < n; i++) {
            p[i] = model.probability(z[i]);
        }
        Double auc = PredictionMath.auc(p, y);
        assertThat(auc).isNotNull().isGreaterThan(0.98);
        double brier = PredictionMath.brier(p, y);
        assertThat(brier).isLessThan(0.05);

        double threshold = PredictionMath.bestF1Threshold(p, y);
        Confusion conf = PredictionMath.confusion(p, y, threshold);
        assertThat(conf.precision()).isGreaterThan(0.95);
        assertThat(conf.recall()).isGreaterThan(0.95);

        List<CalibrationBin> bins = PredictionMath.calibration(p, y);
        assertThat(bins).hasSize(10);
        assertThat(bins.stream().mapToInt(CalibrationBin::count).sum()).isEqualTo(n);
        assertThat(bins.get(0).observedRate()).isEqualTo(0.0);
        assertThat(bins.get(9).observedRate()).isEqualTo(1.0);
    }

    @Test
    void holtAndHoltWintersAndSeasonalNaiveProduceForecastsAndPredictionIntervals() {
        // Linear upward trend for Holt
        double[] trendSeries = new double[30];
        for (int t = 0; t < trendSeries.length; t++) {
            trendSeries[t] = 40.0 + 1.5 * t + (t % 2 == 0 ? 0.2 : -0.2);
        }
        double[] ab = PredictionMath.bestHolt(List.of(trendSeries));
        Smoothing holt = PredictionMath.holt(trendSeries, ab[0], ab[1]);
        assertThat(holt.method()).isEqualTo("HOLT");
        double h1 = holt.forecast(1, trendSeries.length - 1);
        double h10 = holt.forecast(10, trendSeries.length - 1);
        assertThat(h10).isGreaterThan(h1);
        assertThat(holt.sd(10)).isGreaterThan(holt.sd(1));

        // 7-day seasonal series for Holt-Winters (>= 2 seasons) and Seasonal Naive (< 2 seasons)
        double[] pattern = {10, 12, 11, 13, 15, 4, 3};
        double[] seasonal = new double[28];
        for (int t = 0; t < seasonal.length; t++) {
            seasonal[t] = pattern[t % 7] + 0.1 * (t / 7);
        }
        double[] abg = PredictionMath.bestHoltWinters(List.of(seasonal), 7);
        Smoothing hw = PredictionMath.holtWinters(seasonal, 7, abg[0], abg[1], abg[2]);
        assertThat(hw.method()).isEqualTo("HOLT_WINTERS");
        assertThat(hw.forecast(1, seasonal.length - 1)).isCloseTo(10.4, within(2.0));
        assertThat(hw.forecast(6, seasonal.length - 1)).isCloseTo(4.4, within(2.0));

        Smoothing sn = PredictionMath.seasonalNaive(new double[]{10, 12, 11, 13, 15, 4, 3, 10, 12, 11}, 7);
        assertThat(sn.method()).isEqualTo("SEASONAL_NAIVE");
        assertThat(sn.forecast(1, 9)).isEqualTo(13.0);

        ForecastErrors errors = new ForecastErrors();
        for (int h = 1; h <= 7; h++) {
            double pred = hw.forecast(h, seasonal.length - 1);
            double sd = Math.max(0.5, hw.sd(h));
            double actual = pattern[(h - 1) % 7] + 0.4;
            errors.add(actual, pred, pred - PredictionMath.Z95 * sd, pred + PredictionMath.Z95 * sd);
        }
        assertThat(errors.count()).isEqualTo(7);
        assertThat(errors.mae()).isLessThan(1.5);
        assertThat(errors.mape()).isLessThan(20.0);
        assertThat(errors.coverage()).isGreaterThanOrEqualTo(0.85);
    }
}
