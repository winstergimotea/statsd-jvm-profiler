package com.etsy.statsd.profiler.profilers;

import com.etsy.statsd.profiler.Profiler;
import com.etsy.statsd.profiler.util.*;
import com.etsy.statsd.profiler.worker.ProfilerThreadFactory;
import com.google.common.base.Predicate;
import com.timgroup.statsd.StatsDClient;

import java.lang.management.ThreadInfo;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Profiles CPU time spent in each method
 *
 * @author Andrew Johnson
 */
public class CPUProfiler extends Profiler {
    public static final long REPORTING_PERIOD = 10;
    public static final long PERIOD = 1;
    public static final List<String> EXCLUDE_PACKAGES = Arrays.asList("com.etsy.statsd.profiler", "com.timgroup.statsd");

    private Capitaliztion traces;
    private int profileCount;
    private StackTraceFilter filter;
    private long reportingFrequency;


    public CPUProfiler(StatsDClient client, List<String> filterPackages) {
        super(client);
        traces = new Capitaliztion();
        profileCount = 0;
        filter = new StackTraceFilter(filterPackages, EXCLUDE_PACKAGES);
        reportingFrequency = TimeUtil.convertReportingPeriod(getPeriod(), getTimeUnit(), REPORTING_PERIOD, TimeUnit.SECONDS);
    }

    /**
     * Profile CPU time by method call
     */
    @Override
    public void profile() {
        profileCount++;

        for (ThreadInfo thread : getAllRunnableThreads()) {
            // certain threads do not have stack traces
            if (thread.getStackTrace().length > 0) {
                String traceKey = StackTraceFormatter.formatStackTrace(thread.getStackTrace());
                if (filter.includeStackTrace(traceKey)) {
                    traces.increment(traceKey, PERIOD);
                }
            }
        }

        // To keep from overwhelming StatsD, we only report statistics every ten seconds
        if (profileCount % reportingFrequency == 0) {
            recordMethodCounts(false);
        }
    }

    /**
     * Flush methodCounts data on shutdown
     */
    @Override
    public void flushData() {
        recordMethodCounts(false);
    }

    @Override
    public long getPeriod() {
        return PERIOD;
    }

    @Override
    public TimeUnit getTimeUnit() {
        return TimeUnit.MILLISECONDS;
    }

    /**
     * Records method CPU time in StatsD
     *
     * @param flushAll Indicate if all data should be flushed
     */
    private void recordMethodCounts(boolean flushAll) {
        for (Map.Entry<String, Long> entry : traces.getDataToFlush(flushAll)) {
            recordGaugeDelta("cpu.trace." + entry.getKey(), entry.getValue());
        }
    }

    /**
     * Gets all runnable threads, excluding profiler threads
     *
     * @return A Collection<ThreadInfo> representing current thread state
     */
    private Collection<ThreadInfo> getAllRunnableThreads() {
        return ThreadDumper.filterAllThreadsInState(false, false, Thread.State.RUNNABLE, new Predicate<ThreadInfo>() {
            @Override
            public boolean apply(ThreadInfo input) {
                return !input.getThreadName().startsWith(ProfilerThreadFactory.NAME_PREFIX);
            }
        });
    }
}
