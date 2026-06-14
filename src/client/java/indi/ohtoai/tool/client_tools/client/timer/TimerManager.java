package indi.ohtoai.tool.client_tools.client.timer;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Static manager for all active client-side timers.
 * Thread-safe via {@link CopyOnWriteArrayList}.
 */
public class TimerManager {
    private static final CopyOnWriteArrayList<TimerInstance> timers = new CopyOnWriteArrayList<>();

    private TimerManager() {
        // static utility class
    }

    /**
     * Create and register a new timer.
     *
     * @param times         execution count; -1 for infinite
     * @param intervalTicks interval between executions in ticks
     * @param command       the chat command to execute
     * @return the created TimerInstance
     */
    public static TimerInstance addTimer(int times, int intervalTicks, String command) {
        TimerInstance timer = new TimerInstance(times, intervalTicks, command);
        timers.add(timer);
        return timer;
    }

    /**
     * Tick all active timers. Removes any that have finished.
     * Called once per client tick.
     *
     * @param client the Minecraft client instance
     */
    public static void tick(Minecraft client) {
        if (timers.isEmpty()) return;

        // removeIf is atomic on CopyOnWriteArrayList and safe during iteration
        timers.removeIf(timer -> timer.tick(client));
    }

    /**
     * Stop and remove all active timers.
     *
     * @return the number of timers stopped
     */
    public static int stopAll() {
        int count = timers.size();
        timers.clear();
        return count;
    }

    /**
     * Stop timers matching the given pattern.
     * Matches by exact numeric ID or by containing command substring.
     *
     * @param pattern the pattern to match against (timer ID or command substring)
     * @return the number of timers stopped
     */
    public static int stop(String pattern) {
        int[] count = {0};

        timers.removeIf(timer -> {
            boolean matches = false;
            // Match by numeric ID
            try {
                int patternId = Integer.parseInt(pattern);
                if (timer.getId() == patternId) {
                    matches = true;
                }
            } catch (NumberFormatException ignored) {
                // Not a number, try command substring match
            }
            // Match by command substring (case-insensitive)
            if (!matches && timer.getCommand().toLowerCase().contains(pattern.toLowerCase())) {
                matches = true;
            }
            if (matches) {
                count[0]++;
            }
            return matches;
        });

        return count[0];
    }

    /**
     * @return an unmodifiable snapshot of all active timers
     */
    public static List<TimerInstance> getTimers() {
        return Collections.unmodifiableList(new ArrayList<>(timers));
    }

    /**
     * @return the number of currently active timers
     */
    public static int getActiveCount() {
        return timers.size();
    }
}
