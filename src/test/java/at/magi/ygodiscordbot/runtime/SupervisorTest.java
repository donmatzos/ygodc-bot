package at.magi.ygodiscordbot.runtime;

import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class SupervisorTest {

    private static final long MB = 1024 * 1024;

    @Test
    public void heapIsShareOfContainerMemory() {
        assertEquals(Supervisor.heapMb(300 * MB), 90);
    }

    @Test
    public void heapIsCappedOnLargeMachines() {
        assertEquals(Supervisor.heapMb(64L * 1024 * MB), Supervisor.MAX_HEAP_MB);
    }

    @Test
    public void replacesMemoryFlagsButKeepsOthers() {
        List<String> arguments = Supervisor.childJvmArguments(
                List.of("-Xmx300M", "-XX:MaxRAMPercentage=95.0", "-XX:+UseG1GC", "-XX:+HeapDumpOnOutOfMemoryError",
                        "-Dterminal.ansi=true"), 120);

        assertTrue(arguments.contains("-Dterminal.ansi=true"));
        assertTrue(arguments.contains("-Xmx120m"));
        assertTrue(arguments.contains("-XX:+UseSerialGC"));
        assertTrue(arguments.contains("-XX:+ExitOnOutOfMemoryError"));
        assertTrue(arguments.contains("-D" + Supervisor.CHILD_PROPERTY + "=true"));
        assertFalse(arguments.contains("-Xmx300M"));
        assertFalse(arguments.contains("-XX:MaxRAMPercentage=95.0"));
        assertFalse(arguments.contains("-XX:+UseG1GC"));
        assertFalse(arguments.contains("-XX:+HeapDumpOnOutOfMemoryError"));
    }
}
