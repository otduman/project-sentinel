package com.sentinel.lab_rat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ChaosController represents the "Lab Rat" endpoints that simulate typical microservice failures.
 * These endpoints purposefully break the application so our Sentinel AI Agent can detect
 * and investigate the root cause dynamically.
 */
@RestController
@RequestMapping("/chaos")
public class ChaosController {

    private static final Logger log = LoggerFactory.getLogger(ChaosController.class);

    // Static list acting as a GC root, ensuring stored objects are never garbage collected.
    private static final List<byte[]> memoryLeakList = new ArrayList<>();

    private final JdbcTemplate jdbcTemplate;

    public ChaosController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Endpoint: /chaos/leak
     * Effect: Allocates 10MB of memory and stores it in a static list.
     * Principle: By holding a strong reference to the byte arrays in a static list,
     * the Garbage Collector (GC) cannot reclaim the memory. As requests come in,
     * the heap fills up, eventually triggering an OutOfMemoryError or heavy GC pauses
     * (which manifest as latency).
     */
    @GetMapping("/leak")
    public String triggerMemoryLeak() {
        log.warn("Triggering Memory Leak: Allocating 10MB block of memory.");
        // Allocate 10 Megabytes continuously (10 * 1024 * 1024 bytes)
        byte[] chunk = new byte[10 * 1024 * 1024];
        memoryLeakList.add(chunk);
        
        long totalMegabytesLeak = (long) memoryLeakList.size() * 10;
        return "Simulated memory leak! Total leaked so far: " + totalMegabytesLeak + " MB\n";
    }

    /**
     * Endpoint: /chaos/latency
     * Effect: Spins the CPU for ~2-3 seconds using an inefficient recursive algorithm (Fibonacci).
     * Principle: Unlike Thread.sleep() which blocks a thread but frees the CPU, computation-heavy 
     * tasks peg the CPU to 100%. If multiple requests hit this endpoint, the CPU becomes a bottleneck,
     * increasing latency across the entire app.
     */
    @GetMapping("/latency")
    public String triggerLatency() {
        log.warn("Triggering CPU Latency Spike: Calculating Fibonacci(40)...");
        long start = System.currentTimeMillis();
        
        // Fib(40) is intentionally chosen because O(2^n) complexity takes a couple of seconds 
        // to complete on modern CPUs.
        long result = fibonacci(40); 
        
        long duration = System.currentTimeMillis() - start;
        log.info("Latency spike completed in {} ms", duration);
        return "Simulated CPU latency! Fibonacci(40) = " + result + " took " + duration + " ms\n";
    }

    // Deliberately highly inefficient recursive Fibonacci to waste CPU cycles.
    private long fibonacci(long n) {
        if (n <= 1) return n;
        return fibonacci(n - 1) + fibonacci(n - 2);
    }

    /**
     * Endpoint: /chaos/db-lock
     * Effect: Runs a massive Cartesian join in the H2 database, consuming thread pools and DB resources.
     * Principle: Missing indexes and cross joins are a common cause of DB outages.
     * SYSTEM_RANGE is an H2-specific function that generates a table of numbers on the fly.
     * Joining 10k x 10k rows creates a 100 million row working set for the DB engine to process.
     */
    @GetMapping("/db-lock")
    public String triggerDbLock() {
        log.warn("Triggering DB Lock/Heavy Query: Executing 100M row cartesian product...");
        long start = System.currentTimeMillis();
        
        // This query joins an imaginary table of 1 to 10,000 against another 1 to 10,000,
        // resulting in a 100,000,000 row result set inside the database engine.
        Integer result = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM SYSTEM_RANGE(1, 10000) a, SYSTEM_RANGE(1, 10000) b", 
            Integer.class
        );

        long duration = System.currentTimeMillis() - start;
        log.info("DB query completed in {} ms", duration);
        return "Simulated DB unoptimized query! Count = " + result + " took " + duration + " ms\n";
    }

    /**
     * Endpoint: /chaos/cpu-spike
     * Effect: Spawns one busy-spin thread per CPU core, pegging all cores to 100% for the given duration.
     * Principle: Unlike the /latency endpoint which blocks a single request thread, this saturates the
     * entire CPU by using a thread pool sized to the available processors. Threads self-terminate at
     * the deadline so no manual cleanup is required.
     */
    @PostMapping("/cpu-spike")
    public String triggerCpuSpike(@RequestParam(defaultValue = "30") int durationSeconds) {
        int threadCount = Runtime.getRuntime().availableProcessors();
        long deadline = System.currentTimeMillis() + durationSeconds * 1000L;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                while (System.currentTimeMillis() < deadline) {
                    // busy spin — pegs CPU core to 100%
                    Math.pow(Math.random(), Math.random());
                }
            });
        }
        executor.shutdown(); // tasks self-terminate at deadline
        log.warn("CPU spike started on {} threads for {}s", threadCount, durationSeconds);
        return "CPU spike started on " + threadCount + " threads for " + durationSeconds + "s\n";
    }

    /**
     * Endpoint: /chaos/thread-deadlock
     * Effect: Creates a classic two-thread deadlock using ReentrantLock. t1 holds lockA and waits for
     * lockB; t2 holds lockB and waits for lockA. A separate releaser thread interrupts both after the
     * duration so the JVM is not permanently stuck.
     * Principle: Deadlocks cause thread pool exhaustion — requests queue up and the service becomes
     * unresponsive. Using lockInterruptibly() lets us recover cleanly.
     */
    @PostMapping("/thread-deadlock")
    public String triggerThreadDeadlock(@RequestParam(defaultValue = "30") int durationSeconds) {
        ReentrantLock lockA = new ReentrantLock();
        ReentrantLock lockB = new ReentrantLock();

        Thread t1 = new Thread(() -> {
            try {
                lockA.lockInterruptibly();
                try {
                    Thread.sleep(100);
                    lockB.lockInterruptibly(); // will deadlock here
                    lockB.unlock();
                } finally { if (lockA.isHeldByCurrentThread()) lockA.unlock(); }
            } catch (InterruptedException ignored) {}
        }, "deadlock-t1");

        Thread t2 = new Thread(() -> {
            try {
                lockB.lockInterruptibly();
                try {
                    Thread.sleep(100);
                    lockA.lockInterruptibly(); // will deadlock here
                    lockA.unlock();
                } finally { if (lockB.isHeldByCurrentThread()) lockB.unlock(); }
            } catch (InterruptedException ignored) {}
        }, "deadlock-t2");

        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();

        // Release both threads after duration
        Thread releaser = new Thread(() -> {
            try { Thread.sleep(durationSeconds * 1000L); } catch (InterruptedException ignored) {}
            t1.interrupt();
            t2.interrupt();
            log.info("Thread deadlock released after {}s", durationSeconds);
        }, "deadlock-releaser");
        releaser.setDaemon(true);
        releaser.start();

        log.warn("Thread deadlock simulated for {}s", durationSeconds);
        return "Thread deadlock started for " + durationSeconds + "s — threads named deadlock-t1, deadlock-t2\n";
    }

    /**
     * Endpoint: /chaos/disk-fill
     * Effect: Writes a configurable number of megabytes to a temp file, then deletes it after the
     * duration via a background daemon thread.
     * Principle: Disk saturation causes log rotation failures, write errors, and cascading service
     * failures in shared-volume environments. The auto-cleanup prevents permanent disk exhaustion.
     */
    @PostMapping("/disk-fill")
    public String triggerDiskFill(
            @RequestParam(defaultValue = "30") int durationSeconds,
            @RequestParam(defaultValue = "100") int fileSizeMB) throws java.io.IOException {
        Path tempFile = Files.createTempFile("sentinel-chaos-disk-", ".bin");
        byte[] oneMb = new byte[1024 * 1024];
        try (var out = Files.newOutputStream(tempFile)) {
            for (int i = 0; i < fileSizeMB; i++) {
                out.write(oneMb);
            }
        }
        log.warn("Disk fill: wrote {}MB to {} for {}s", fileSizeMB, tempFile, durationSeconds);

        Thread cleaner = new Thread(() -> {
            try {
                Thread.sleep(durationSeconds * 1000L);
                Files.deleteIfExists(tempFile);
                log.info("Disk fill cleaned up: {}", tempFile);
            } catch (Exception ignored) {}
        }, "disk-fill-cleaner");
        cleaner.setDaemon(true);
        cleaner.start();

        return "Disk fill: " + fileSizeMB + "MB written, auto-deleted after " + durationSeconds + "s\n";
    }
}
