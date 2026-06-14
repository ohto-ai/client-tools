package indi.ohtoai.tool.client_tools.client.timer;

import net.minecraft.client.Minecraft;

public class TimerInstance {
    private static int nextId = 1;

    private final int id;
    private final int totalTimes;       // -1 means infinite
    private int remainingTimes;         // remaining execution count
    private final int intervalTicks;    // interval in ticks (≥ 1)
    private int ticksElapsed;           // ticks elapsed since last execution
    private final String command;       // the command to execute

    /**
     * @param times          total execution count; -1 for infinite
     * @param intervalTicks  interval between executions in ticks (minimum 1)
     * @param command        the chat command to execute
     */
    public TimerInstance(int times, int intervalTicks, String command) {
        synchronized (TimerInstance.class) {
            this.id = nextId++;
        }
        this.totalTimes = times;
        this.remainingTimes = times;
        this.intervalTicks = Math.max(1, intervalTicks);
        this.ticksElapsed = 0;
        this.command = command;
    }

    /**
     * Called every client tick.
     *
     * @param client the Minecraft client instance
     * @return true if this timer has finished and should be removed
     */
    public boolean tick(Minecraft client) {
        ticksElapsed++;

        if (ticksElapsed >= intervalTicks) {
            ticksElapsed = 0;
            execute(client);

            if (totalTimes != -1) {
                remainingTimes--;
                if (remainingTimes <= 0) {
                    return true; // timer finished
                }
            }
        }
        return false;
    }

    /**
     * Execute the stored command via the player's network handler.
     * Silently skips if the player is not in a world.
     */
    private void execute(Minecraft client) {
        if (client.player != null && client.player.connection != null) {
            client.player.connection.sendCommand(command);
        }
    }

    // --- Getters ---

    public int getId() {
        return id;
    }

    public int getTotalTimes() {
        return totalTimes;
    }

    public int getRemainingTimes() {
        return remainingTimes;
    }

    public int getIntervalTicks() {
        return intervalTicks;
    }

    public String getCommand() {
        return command;
    }

    public boolean isInfinite() {
        return totalTimes == -1;
    }
}
