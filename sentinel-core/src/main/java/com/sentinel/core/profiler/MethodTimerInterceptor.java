package com.sentinel.core.profiler;

import net.bytebuddy.asm.Advice;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ByteBuddy Advice class that gets injected directly inside the target methods.
 */
public class MethodTimerInterceptor {

    // Metrics storage: Method Signature -> Statistics
    public static final ConcurrentHashMap<String, MethodStats> METRICS = new ConcurrentHashMap<>();

    public static class MethodStats {
        // volatile ensures the reporter thread sees the latest values written by advice threads.
        // Writes happen inside ConcurrentHashMap.compute (per-bucket lock), so they are
        // sequentially consistent within the compute call; volatile covers cross-thread visibility.
        public volatile long count = 0;
        public volatile long totalTime = 0; // in nanoseconds

        // Returns a consistent [count, totalTime] snapshot for the reporter thread.
        public long[] snapshot() {
            return new long[]{count, totalTime};
        }
    }

    /**
     * Executed right BEFORE the original method code runs.
     * @return the current start time in nanoseconds
     */
    @Advice.OnMethodEnter
    public static long enter() {
        return System.nanoTime();
    }

    /**
     * Executed right AFTER the original method code finishes.
     * @param enterTime The value returned by the @OnMethodEnter method.
     * @param origin The signature of the method being intercepted.
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.Enter long enterTime, @Advice.Origin String origin) {
        long duration = System.nanoTime() - enterTime;
        
        METRICS.compute(origin, (key, stats) -> {
            if (stats == null) stats = new MethodStats();
            stats.count++;
            stats.totalTime += duration;
            return stats;
        });
    }
}
