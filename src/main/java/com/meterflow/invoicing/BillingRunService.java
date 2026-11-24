package com.meterflow.invoicing;

import com.meterflow.subscriptions.SubscriptionService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * সব বিল-যোগ্য subscription-এর invoice একসাথে বানায়।
 * গুরুত্বপূর্ণ: এই class নিজে @Transactional নয়। প্রতিটা subscription InvoiceService.generate()-এ নিজের আলাদা
 * transaction-এ চলে। তাই একটা ব্যর্থ হলে শুধু সেটা ফিরে যায়, বাকিগুলো টিকে থাকে।
 * মাঝপথে বন্ধ হলে চলতে থাকা transaction rollback হয়, আবার চালালে বাকিগুলো সম্পূর্ণ হয়,
 * আর যেগুলো হয়ে গেছে সেগুলো আবার হয় না।
 */
@Service
public class BillingRunService {

    static final int DEFAULT_PARALLELISM = 4;
    static final int MAX_PARALLELISM = 8;
    static final int MAX_PERIODS_PER_RUN = 36;

    public record Request(LocalDate asOf, Integer parallelism) {
    }

    public record Failure(long subscriptionId, String reason) {
    }

    public record Result(LocalDate asOf, int subscriptionsSeen, int invoicesCreated, int alreadyInvoiced,
                         int failed, List<Failure> failures, long durationMillis) {
    }

    private final InvoiceService invoices;
    private final SubscriptionService subscriptions;

    public BillingRunService(InvoiceService invoices, SubscriptionService subscriptions) {
        this.invoices = invoices;
        this.subscriptions = subscriptions;
    }

    /** asOf তারিখের আগে period শেষ হয়ে যাওয়া সব চালু subscription। */
    public Result run(LocalDate asOf, int parallelism) {
        if (asOf == null) {
            throw bad("asOf is required");
        }
        return runFor(subscriptions.dueForBilling(asOf), asOf, parallelism, Integer.MAX_VALUE);
    }

    /**
     * নির্দিষ্ট subscription গুলো বিল করে। maxSubscriptions দিয়ে তালিকার প্রথম কয়েকটায় থামা যায়,
     * মানে "মাঝপথে বন্ধ হয়ে গেলো" অবস্থা বানিয়ে restart পরীক্ষা করা যায়।
     */
    public Result runFor(List<Long> subscriptionIds, LocalDate asOf, int parallelism, int maxSubscriptions) {
        if (asOf == null) {
            throw bad("asOf is required");
        }
        if (parallelism < 1 || parallelism > MAX_PARALLELISM) {
            throw bad("parallelism must be between 1 and " + MAX_PARALLELISM);
        }
        if (maxSubscriptions < 1) {
            throw bad("maxSubscriptions must be at least 1");
        }
        long started = System.nanoTime();
        List<Long> work = subscriptionIds.stream().distinct().limit(maxSubscriptions).toList();

        AtomicInteger created = new AtomicInteger();
        AtomicInteger already = new AtomicInteger();
        Queue<Failure> failures = new ConcurrentLinkedQueue<>();

        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (long id : work) {
                futures.add(pool.submit(() -> processOne(id, asOf, created, already, failures)));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    futures.get(i).get();
                } catch (ExecutionException e) {
                    failures.add(new Failure(work.get(i), "unexpected: " + e.getCause()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    pool.shutdownNow();
                    throw new IllegalStateException("billing run was interrupted");
                }
            }
        } finally {
            pool.shutdown();
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        return new Result(asOf, work.size(), created.get(), already.get(), failures.size(),
                List.copyOf(failures), millis);
    }

    /** একটা subscription-এর সব বাকি period বিল করে। কোনো ব্যর্থতা বাইরে ছড়ায় না, failures-এ লেখা হয়। */
    private void processOne(long id, LocalDate asOf, AtomicInteger created, AtomicInteger already,
                            Queue<Failure> failures) {
        for (int i = 0; i < MAX_PERIODS_PER_RUN; i++) {
            try {
                Optional<Invoice> invoice = invoices.generate(id, asOf);
                if (invoice.isEmpty()) {
                    return;                                  // আর কোনো শেষ হওয়া period নেই
                }
                created.incrementAndGet();
            } catch (ResponseStatusException e) {
                String reason = String.valueOf(e.getReason());
                if (reason.contains("already invoiced")) {
                    already.incrementAndGet();
                } else {
                    failures.add(new Failure(id, reason));
                }
                return;
            } catch (RuntimeException e) {
                failures.add(new Failure(id, e.getClass().getSimpleName() + ": " + e.getMessage()));
                return;
            }
        }
        failures.add(new Failure(id, "stopped after " + MAX_PERIODS_PER_RUN + " periods, run again to continue"));
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}