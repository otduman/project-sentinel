package com.sentinel.lab_rat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

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
}
