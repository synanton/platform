package org.synanton.synflux;

import org.synanton.synflux.runner.IngestionJobRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class SynfluxCli implements ApplicationRunner {

    private final IngestionJobRunner runner;

    public SynfluxCli(IngestionJobRunner runner) {
        this.runner = runner;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption("cli")) return;

        String path = args.getOptionValues("path") != null && !args.getOptionValues("path").isEmpty()
            ? args.getOptionValues("path").get(0) : "/demo-data/documents";
        String tenant = args.getOptionValues("tenant") != null && !args.getOptionValues("tenant").isEmpty()
            ? args.getOptionValues("tenant").get(0) : "demo";
        String source = args.getOptionValues("source") != null && !args.getOptionValues("source").isEmpty()
            ? args.getOptionValues("source").get(0) : "filesystem";

        System.out.printf("Starting ingestion: tenant=%s, source=%s, path=%s%n", tenant, source, path);
        UUID jobId = runner.startJob(tenant, source, path);
        System.out.printf("Job started: %s%n", jobId);

        // Poll until done
        int maxWaitSeconds = 600;
        for (int i = 0; i < maxWaitSeconds; i++) {
            TimeUnit.SECONDS.sleep(2);
            var job = runner.getJob(tenant, jobId);
            if (job.isEmpty()) { System.err.println("Job not found"); System.exit(1); }
            String state = job.get().state();
            System.out.printf("\r[%ds] state=%s processed=%d errors=%d",
                i * 2, state, job.get().processedCount(), job.get().errorCount());
            if ("SUCCEEDED".equals(state) || "FAILED".equals(state)) {
                System.out.printf("%nIngested %d documents from %s (errors=%d)%n",
                    job.get().processedCount(), path, job.get().errorCount());
                System.exit("FAILED".equals(state) ? 1 : 0);
            }
        }
        System.err.println("Timed out waiting for job");
        System.exit(1);
    }
}
