package at.magi.ygodiscordbot.utils.concurrent;

import java.util.concurrent.ThreadFactory;

/** Thread factory for background workers that must never keep the JVM alive. */
public final class DaemonThreads {

    private DaemonThreads() {
    }

    /** Creates named daemon threads. */
    public static ThreadFactory named(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
