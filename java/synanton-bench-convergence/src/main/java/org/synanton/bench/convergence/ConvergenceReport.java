package org.synanton.bench.convergence;

/** Human-readable markdown rendering of a convergence {@link ConvergenceRunner.Report}. */
public final class ConvergenceReport {

    private ConvergenceReport() {}

    public static String render(
            String baselineRun, String candidateRun, ConvergenceRunner.Report report) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Convergence report\n\n");
        sb.append("baseline=").append(baselineRun).append(" candidate=").append(candidateRun).append("\n\n");
        sb.append("| mode | filter | selectivity | check | value | threshold | verdict |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (ConvergenceRunner.LegVerdict v : report.legs()) {
            sb.append("| ").append(v.mode())
                    .append(" | ").append(v.filter())
                    .append(" | ").append(v.selectivity())
                    .append(" | ").append(v.check())
                    .append(" | ").append(String.format("%.3f", v.value()))
                    .append(" | ").append(String.format("%.2f", v.threshold()))
                    .append(" | ").append(v.pass() ? "PASS" : "FAIL")
                    .append(" |\n");
            if (!v.pass()) {
                sb.append("\n> ").append(v.detail()).append("\n\n");
            }
        }
        sb.append("\nOverall: ").append(report.overallPass() ? "PASS" : "FAIL").append("\n");
        return sb.toString();
    }
}
