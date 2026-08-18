package org.synanton.controlplane.service;

import java.util.Map;

public class ForecastEngine {

    public record Forecast(double daysRemaining, double projectedUsd) {}

    public Forecast forecast(double usedUsd, double dailyRunRate, double capUsd) {
        if (dailyRunRate <= 0) {
            return new Forecast(Double.POSITIVE_INFINITY, usedUsd);
        }
        double remaining = Math.max(0, capUsd - usedUsd);
        return new Forecast(remaining / dailyRunRate, usedUsd + dailyRunRate * 30);
    }

    public Map<String, Double> asGauge(Forecast forecast) {
        return Map.of("control_forecast_budget_days_remaining", forecast.daysRemaining());
    }
}
