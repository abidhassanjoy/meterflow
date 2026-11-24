package com.meterflow.invoicing;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

/**
 * Spring Batch-এর billing job চালায়।
 *
 * runKey: একই runKey আর asOf দিয়ে আবার ডাকলে, আগের চেষ্টা ব্যর্থ হয়ে থাকলে Spring Batch সেটাকে restart ধরে
 * (যে partition শেষ হয়নি শুধু সেটা)। runKey না দিলে প্রতিবার নতুন run।
 * customerPrefix: দিলে শুধু ওই নামে শুরু হওয়া customer-দের বিল হয় (পরীক্ষা বা আংশিক run-এর জন্য)।
 */
@Service
public class BatchBillingService {

    public record Request(LocalDate asOf, String runKey, String customerPrefix) {
    }

    public record Result(LocalDate asOf, String runKey, String status, String exitCode,
                         int partitions, long subscriptionsHandled, long subscriptionsSkipped) {
    }

    private final JobOperator jobOperator;
    private final Job billingRunJob;

    public BatchBillingService(JobOperator jobOperator, Job billingRunJob) {
        this.jobOperator = jobOperator;
        this.billingRunJob = billingRunJob;
    }

    public Result run(LocalDate asOf, String runKey) {
        return run(asOf, runKey, null);
    }

    public Result run(LocalDate asOf, String runKey, String customerPrefix) {
        if (asOf == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "asOf is required");
        }
        String key = runKey == null || runKey.isBlank() ? "run-" + System.nanoTime() : runKey.trim();

        JobParametersBuilder builder = new JobParametersBuilder()
                .addString("asOf", asOf.toString())
                .addString("runKey", key);
        if (customerPrefix != null && !customerPrefix.isBlank()) {
            builder.addString("customerPrefix", customerPrefix.trim());
        }
        JobParameters parameters = builder.toJobParameters();

        JobExecution execution;
        try {
            execution = jobOperator.start(billingRunJob, parameters);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "billing job could not start: " + e.getMessage());
        }

        int partitions = 0;
        long handled = 0;
        long skipped = 0;
        for (StepExecution step : execution.getStepExecutions()) {
            if (step.getStepName().startsWith(BillingBatchConfig.WORKER_STEP)) {
                partitions++;
                handled += step.getWriteCount();
                skipped += step.getSkipCount();
            }
        }
        return new Result(asOf, key, execution.getStatus().name(), execution.getExitStatus().getExitCode(),
                partitions, handled, skipped);
    }
}