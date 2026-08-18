package org.synanton.gateway.budget;

public class BudgetGuard {

    public record Decision(boolean allowed, int retryAfterSeconds, String reason) {}

    public Decision check(double usedUsd, double capUsd) {
        if (capUsd <= 0) {
            return new Decision(true, 0, "unlimited");
        }
        double ratio = usedUsd / capUsd;
        if (ratio >= 1.0) {
            return new Decision(false, 86400, "monthly_usd_cap exhausted");
        }
        if (ratio >= 0.9) {
            return new Decision(true, 0, "ForecastCostOverrunCritical");
        }
        if (ratio >= 0.7) {
            return new Decision(true, 0, "ForecastCostOverrunWarning");
        }
        return new Decision(true, 0, "ok");
    }
}
