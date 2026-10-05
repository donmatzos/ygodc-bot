package at.magi.ygodiscordbot.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Runs the bot in a child JVM and keeps it alive.
 *
 * <ul>
 *     <li><b>Memory:</b> JVM flags cannot be changed in a running JVM and hosting panels often lock the
 *     startup command, so the child gets flags that fit small containers (e.g. 300 MB on free hosting).</li>
 *     <li><b>Out of memory:</b> the child exits on {@link OutOfMemoryError} instead of running on in a
 *     broken state.</li>
 *     <li><b>Crashes:</b> when the child exits abnormally, it is restarted after a growing delay
 *     (see {@link RestartPolicy}).</li>
 * </ul>
 *
 * The parent only waits for the child and forwards stop signals to it. Start the JVM with
 * {@code -D}{@value #SUPERVISE_PROPERTY}{@code =false} to run the bot directly, e.g. for debugging in an IDE.
 */
public final class Supervisor {

    private static final Logger log = LoggerFactory.getLogger(Supervisor.class);

    static final String CHILD_PROPERTY = "ygodiscordbot.child";
    static final String SUPERVISE_PROPERTY = "ygodiscordbot.supervise";

    /** Exit code for errors a restart cannot fix, e.g. a missing or invalid token (sysexits EX_CONFIG). */
    public static final int EXIT_CONFIG_ERROR = 78;
    /** Exit code of a JVM stopped by -XX:+ExitOnOutOfMemoryError. */
    static final int EXIT_OUT_OF_MEMORY = 3;

    /**
     * Leaves room for metaspace, code cache, thread stacks and this parent JVM. The live heap is only about
     * 12 MB, so non-heap memory matters more.
     */
    static final double HEAP_SHARE = 0.3;
    static final long MAX_HEAP_MB = 512;

    private static final List<String> CHILD_FLAGS = List.of(
            "-XX:+ExitOnOutOfMemoryError",
            "-XX:+UseSerialGC",                // smallest GC footprint, fine for a single-CPU bot
            "-XX:TieredStopAtLevel=1",         // C1 JIT only: far less compiler memory and CPU, fine for an I/O-bound bot
            "-XX:MaxMetaspaceSize=128m",
            "-XX:ReservedCodeCacheSize=48m",
            "-Xss512k");

    private final List<String> command;
    private final RestartPolicy restartPolicy = new RestartPolicy();
    private final CountDownLatch stopRequested = new CountDownLatch(1);
    private Process child;

    private Supervisor(List<String> command) {
        this.command = command;
    }

    /**
     * Returns when running as the child (or unsupervised); the caller then starts the bot.
     * Otherwise supervises child JVMs and exits the process once supervision ends.
     */
    public static void superviseUnlessChild(Class<?> mainClass) throws InterruptedException {
        if (Boolean.getBoolean(CHILD_PROPERTY) || !Boolean.parseBoolean(System.getProperty(SUPERVISE_PROPERTY, "true"))) {
            log.info("Memory: max heap {} MB of {} MB total, GC: {}",
                    mb(Runtime.getRuntime().maxMemory()), mb(totalMemory()), gcNames());
            return;
        }

        long heapMb = heapMb(totalMemory());
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.addAll(childJvmArguments(ManagementFactory.getRuntimeMXBean().getInputArguments(), heapMb));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(mainClass.getName());

        log.info("Supervising bot process ({} MB heap of {} MB total memory)", heapMb, mb(totalMemory()));
        Supervisor supervisor = new Supervisor(command);
        // The host stops the server by signalling this process only, so pass it on to the bot.
        Runtime.getRuntime().addShutdownHook(new Thread(supervisor::stop, "supervisor-shutdown"));
        System.exit(supervisor.run());
    }

    private int run() throws InterruptedException {
        while (true) {
            Instant started = Instant.now();
            Process process = startChild();
            if (process == null) {
                return 0;
            }
            int exitCode = process.waitFor();
            if (stopRequested.getCount() == 0) {
                return 0;
            }
            Duration uptime = Duration.between(started, Instant.now());
            logExit(exitCode, uptime);

            Optional<Duration> delay = restartPolicy.afterExit(exitCode, uptime);
            if (delay.isEmpty()) {
                return exitCode;
            }
            log.warn("Restarting bot in {} s", delay.get().toSeconds());
            if (stopRequested.await(delay.get().toMillis(), TimeUnit.MILLISECONDS)) {
                return 0;
            }
        }
    }

    /** Returns null if a stop was requested, so no child is started during shutdown. */
    private synchronized Process startChild() {
        if (stopRequested.getCount() == 0) {
            return null;
        }
        try {
            child = new ProcessBuilder(command).inheritIO().start();
            return child;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start bot process", e);
        }
    }

    private void stop() {
        Process process;
        synchronized (this) {
            stopRequested.countDown();
            process = child;
        }
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static void logExit(int exitCode, Duration uptime) {
        switch (exitCode) {
            case 0 -> log.info("Bot stopped");
            case EXIT_CONFIG_ERROR -> log.error("Bot stopped because of a configuration error, not restarting");
            case EXIT_OUT_OF_MEMORY -> log.error("Bot ran out of memory after {} s", uptime.toSeconds());
            default -> log.error("Bot crashed with exit code {} after {} s", exitCode, uptime.toSeconds());
        }
    }

    static long heapMb(long totalMemory) {
        return Math.min(mb((long) (totalMemory * HEAP_SHARE)), MAX_HEAP_MB);
    }

    /** Keeps the parent's own flags (e.g. -D properties) but replaces all memory and GC settings. */
    static List<String> childJvmArguments(List<String> parentArguments, long heapMb) {
        List<String> arguments = new ArrayList<>();
        for (String argument : parentArguments) {
            if (!isMemoryFlag(argument)) {
                arguments.add(argument);
            }
        }
        arguments.add("-Xmx" + heapMb + "m");
        arguments.addAll(CHILD_FLAGS);
        arguments.add("-D" + CHILD_PROPERTY + "=true");
        return arguments;
    }

    private static boolean isMemoryFlag(String argument) {
        return argument.startsWith("-Xmx") || argument.startsWith("-Xms") || argument.startsWith("-Xss")
                || argument.contains("RAMPercentage") || argument.contains("GC")
                || argument.contains("OutOfMemoryError")
                || argument.startsWith("-XX:MaxMetaspaceSize") || argument.startsWith("-XX:ReservedCodeCacheSize");
    }

    private static long totalMemory() {
        // Container-aware: reports the container limit, not the host's RAM.
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        return os.getTotalMemorySize();
    }

    private static String javaExecutable() {
        return ProcessHandle.current().info().command()
                .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }

    private static String gcNames() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName)
                .collect(Collectors.joining(", "));
    }

    private static long mb(long bytes) {
        return bytes / (1024 * 1024);
    }
}
