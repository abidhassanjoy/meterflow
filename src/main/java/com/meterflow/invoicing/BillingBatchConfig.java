package com.meterflow.invoicing;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.item.ItemStream;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Spring Batch দিয়ে billing run: একটা Job, তার একটা partitioned Step।
 * Master step কাজ ভাগ করে GRID_SIZE টা partition-এ (subscription id-র ভাগশেষ দিয়ে, প্রতি customer-এর একটাই
 * চালু subscription থাকে, তাই এটা customer অনুযায়ী ভাগ)। প্রতিটা partition একটা chunk-oriented worker step:
 * reader বিল-যোগ্য subscription পড়ে, writer প্রতিটার invoice বানায়।
 *
 * Restart-safe: reader শুধু "এখনও বিল হয়নি" এমন subscription খোঁজে। তাই মাঝপথে থামলে আবার চালালে
 * বাকিগুলো হয়, আর যেগুলো হয়ে গেছে সেগুলো আর পড়াই হয় না। উপরন্তু (customer, period) unique constraint
 * আর InvoiceService.generate()-এর "already invoiced" পাহারা তো আছেই।
 */
@Configuration(proxyBeanMethods = false)
class BillingBatchConfig {

    static final String JOB_NAME = "billingRunJob";
    static final String WORKER_STEP = "billingWorkerStep";
    static final int GRID_SIZE = 4;
    static final int CHUNK_SIZE = 20;
    static final int MAX_PERIODS = 36;

    @Bean
    Job billingRunJob(JobRepository jobRepository, Step billingMasterStep) {
        return new JobBuilder(JOB_NAME, jobRepository).start(billingMasterStep).build();
    }

    @Bean
    Step billingMasterStep(JobRepository jobRepository, Partitioner billingPartitioner, Step billingWorkerStep,
                           @Qualifier("billingTaskExecutor") TaskExecutor billingTaskExecutor) {
        return new StepBuilder("billingMasterStep", jobRepository)
                .partitioner(WORKER_STEP, billingPartitioner)
                .step(billingWorkerStep)
                .gridSize(GRID_SIZE)
                .taskExecutor(billingTaskExecutor)
                .build();
    }

    @Bean
    Step billingWorkerStep(JobRepository jobRepository, DueSubscriptionReader billingReader,
                           BillingWriter billingWriter) {
        return new StepBuilder(WORKER_STEP, jobRepository)
                .<Long, Long>chunk(CHUNK_SIZE)
                .reader(billingReader)
                .writer(billingWriter)
                .stream(billingReader)
                .faultTolerant()
                .skip(BillingFailedException.class)
                .skipLimit(1_000_000)
                .build();
    }

    @Bean("billingTaskExecutor")
    TaskExecutor billingTaskExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("billing-");
        executor.setConcurrencyLimit(GRID_SIZE);
        return executor;
    }

    @Bean
    Partitioner billingPartitioner() {
        return gridSize -> {
            Map<String, ExecutionContext> partitions = new LinkedHashMap<>();
            for (int i = 0; i < gridSize; i++) {
                ExecutionContext context = new ExecutionContext();
                context.putInt("partition", i);
                context.putInt("gridSize", gridSize);
                partitions.put("partition" + i, context);
            }
            return partitions;
        };
    }

    @Bean
    @StepScope
    DueSubscriptionReader billingReader(JdbcTemplate jdbc,
                                        @Value("#{jobParameters['asOf']}") String asOf,
                                        @Value("#{jobParameters['customerPrefix']}") String customerPrefix,
                                        @Value("#{stepExecutionContext['partition']}") Integer partition,
                                        @Value("#{stepExecutionContext['gridSize']}") Integer gridSize) {
        return new DueSubscriptionReader(jdbc, LocalDate.parse(asOf), customerPrefix, partition, gridSize);
    }

    @Bean
    @StepScope
    BillingWriter billingWriter(InvoiceService invoices, PlatformTransactionManager txManager,
                                @Value("#{jobParameters['asOf']}") String asOf) {
        TransactionTemplate template = new TransactionTemplate(txManager);
        // প্রতিটা subscription নিজের আলাদা transaction-এ, তাই একটা ব্যর্থ হলে বাকিরা ফেরত যায় না
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new BillingWriter(invoices, template, LocalDate.parse(asOf));
    }

    /** একটা partition-এর বিল-যোগ্য subscription গুলো একবার (step শুরুতে) খুঁজে এনে একটা একটা করে দেয়। */
    static class DueSubscriptionReader implements ItemReader<Long>, ItemStream {

        private final JdbcTemplate jdbc;
        private final LocalDate asOf;
        private final String customerPrefix;
        private final int partition;
        private final int gridSize;
        private Deque<Long> queue;

        DueSubscriptionReader(JdbcTemplate jdbc, LocalDate asOf, String customerPrefix, int partition, int gridSize) {
            this.jdbc = jdbc;
            this.asOf = asOf;
            this.customerPrefix = customerPrefix == null || customerPrefix.isBlank() ? null : customerPrefix;
            this.partition = partition;
            this.gridSize = gridSize;
        }

        @Override
        public void open(ExecutionContext executionContext) {
            StringBuilder sql = new StringBuilder(
                    "SELECT s.id FROM subscription s JOIN customer c ON c.id = s.customer_id "
                            + "WHERE s.status = 'ACTIVE' AND s.current_period_end < ? AND MOD(s.id, ?) = ?");
            List<Object> args = new ArrayList<>(List.of(Date.valueOf(asOf), gridSize, partition));
            if (customerPrefix != null) {
                sql.append(" AND starts_with(c.external_id, ?)");
                args.add(customerPrefix);
            }
            sql.append(" ORDER BY s.id");
            queue = new ArrayDeque<>(jdbc.query(sql.toString(), (rs, n) -> rs.getLong(1), args.toArray()));
        }

        @Override
        public Long read() {
            return queue == null ? null : queue.pollFirst();
        }

        @Override
        public void close() {
            queue = null;
        }
    }

    /**
     * chunk-এর প্রতিটা subscription-এর সব বাকি period বিল করে। প্রতিটা নিজের transaction-এ।
     * সবগুলো চেষ্টা করার পর কোনোটা ব্যর্থ হলে একটা BillingFailedException ছোঁড়ে, যেটা step skip করে এগোয়।
     */
    static class BillingWriter implements ItemWriter<Long> {

        private final InvoiceService invoices;
        private final TransactionTemplate transaction;
        private final LocalDate asOf;

        BillingWriter(InvoiceService invoices, TransactionTemplate transaction, LocalDate asOf) {
            this.invoices = invoices;
            this.transaction = transaction;
            this.asOf = asOf;
        }

        @Override
        public void write(Chunk<? extends Long> chunk) {
            List<String> failures = new ArrayList<>();
            for (Long id : chunk) {
                String failure = billOne(id);
                if (failure != null) {
                    failures.add("subscription " + id + ": " + failure);
                }
            }
            if (!failures.isEmpty()) {
                throw new BillingFailedException(failures);
            }
        }

        /** সফল (বা আগেই হয়ে গেছে) হলে null, নইলে ব্যর্থতার কারণ। */
        private String billOne(long id) {
            for (int i = 0; i < MAX_PERIODS; i++) {
                try {
                    Optional<Invoice> invoice = transaction.execute(status -> invoices.generate(id, asOf));
                    if (invoice == null || invoice.isEmpty()) {
                        return null;
                    }
                } catch (ResponseStatusException e) {
                    String reason = String.valueOf(e.getReason());
                    return reason.contains("already invoiced") ? null : reason;
                } catch (RuntimeException e) {
                    return e.getClass().getSimpleName() + ": " + e.getMessage();
                }
            }
            return "stopped after " + MAX_PERIODS + " periods, run again to continue";
        }
    }
}