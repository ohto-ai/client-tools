package indi.ohtoai.tool.client_tools.client.sequence;

import indi.ohtoai.tool.client_tools.client.command.CtimerCommand;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Tick-driven state machine that reads a .mcfunction file and executes
 * its commands sequentially — one command per N ticks, with optional looping.
 *
 * <p>Supports <b>nested sequences</b>: when a command in the sequence is
 * {@code /csequence run <name> [delay] [loop]}, the current sequence pauses,
 * the nested sequence executes to completion, then the parent resumes.
 * Maximum nesting depth is {@value #MAX_DEPTH} to prevent infinite recursion.
 *
 * <p>mcfunction format:
 * <ul>
 *   <li>Lines starting with {@code #} are comments (skipped)</li>
 *   <li>Blank lines are skipped</li>
 *   <li>{@code /csequence run <name> ...} triggers a nested call</li>
 *   <li>All other lines are sent as chat commands via {@code player.connection.sendCommand()}</li>
 * </ul>
 */
public class SequenceExecutor {

    public enum State {
        IDLE, RUNNING, DONE, STOPPED, ERROR
    }

    /** Maximum nesting depth to prevent infinite recursion. */
    private static final int MAX_DEPTH = 10;

    // --- Frame: one level of the call stack ---

    private static class Frame {
        final List<String> commands;
        int currentIndex;
        final int delayTicks;
        int ticksElapsed;
        final boolean loop;
        final String name;

        Frame(List<String> commands, int delayTicks, boolean loop, String name) {
            this.commands = commands;
            this.currentIndex = 0;
            this.delayTicks = Math.max(1, delayTicks);
            this.ticksElapsed = 0;
            this.loop = loop;
            this.name = name;
        }
    }

    // --- Singleton ---

    private static SequenceExecutor instance;

    public static SequenceExecutor getInstance() {
        if (instance == null) instance = new SequenceExecutor();
        return instance;
    }

    private SequenceExecutor() {}

    // --- State fields ---

    private State state = State.IDLE;
    private final Deque<Frame> stack = new ArrayDeque<>();
    private String errorMessage = "";

    // --- Public API ---

    /**
     * Load the given sequence file and start execution.
     * Clears any existing call stack — use only for top-level invocation.
     *
     * @param name       sequence name (without .mcfunction extension)
     * @param delayTicks ticks to wait between each command execution (minimum 1)
     * @param loop       if true, restart from the beginning after the last command
     * @return true if started successfully, false on error
     */
    public boolean start(String name, int delayTicks, boolean loop) {
        stack.clear();
        state = State.IDLE;
        errorMessage = "";
        return pushFrame(name, delayTicks, loop);
    }

    /**
     * Push a nested sequence onto the call stack without clearing the parent.
     * Called internally when a {@code /csequence run} command is detected,
     * but also available for programmatic nesting.
     *
     * @param name       sequence name
     * @param delayTicks ticks between commands
     * @param loop       whether this nested sequence loops
     * @return true if the frame was pushed successfully
     */
    public boolean call(String name, int delayTicks, boolean loop) {
        if (stack.size() >= MAX_DEPTH) {
            errorMessage = "Max nesting depth (" + MAX_DEPTH + ") exceeded at: " + name;
            state = State.ERROR;
            return false;
        }
        return pushFrame(name, delayTicks, loop);
    }

    /**
     * Stop execution immediately and clear the entire call stack.
     */
    public void stop() {
        state = State.STOPPED;
        stack.clear();
    }

    /**
     * Reset to idle state (clears call stack).
     */
    public void reset() {
        state = State.IDLE;
        stack.clear();
        errorMessage = "";
    }

    // --- Tick ---

    /**
     * Called every client tick. Advances the top frame by one tick.
     *
     * <p>Uses a {@code consumed} flag to prevent double-execution when
     * {@code sendCommand} triggers synchronous callbacks (e.g. in
     * singleplayer integrated server).
     */
    public void tick(Minecraft client) {
        if (state != State.RUNNING) return;
        if (client.player == null || client.player.connection == null) return;
        if (stack.isEmpty()) {
            state = State.IDLE;
            return;
        }

        Frame frame = stack.peek();

        // Defensive: if frame index is already past the end, clean up
        if (frame.currentIndex >= frame.commands.size()) {
            popFrame();
            return;
        }

        frame.ticksElapsed++;

        if (frame.ticksElapsed >= frame.delayTicks) {
            frame.ticksElapsed = 0;

            // Guard: re-check bounds since sendCommand may have caused re-entry
            if (frame.currentIndex >= frame.commands.size()) {
                popFrame();
                return;
            }

            String command = frame.commands.get(frame.currentIndex);

            // Check for nested /csequence run command
            if (isNestedCsequenceCall(command)) {
                ParsedNestedCall parsed = parseNestedCallInstance(command);
                if (parsed != null) {
                    // Advance past the call command so parent resumes after it
                    frame.currentIndex++;
                    // Push nested frame — if it fails, stop with error
                    if (!pushFrame(parsed.name, parsed.delayTicks, parsed.loop)) {
                        return; // pushFrame already set ERROR state
                    }
                    return;
                }
                // If parsing fails, fall through and send as normal command
            }

            // Execute current command and advance atomically (advance BEFORE send
            // so re-entrant tick sees the updated state, not the same command)
            int idx = frame.currentIndex++;
            command = frame.commands.get(idx);
            client.player.connection.sendCommand(command);

            // Handle frame completion
            if (frame.currentIndex >= frame.commands.size()) {
                if (frame.loop) {
                    frame.currentIndex = 0;
                } else {
                    popFrame();
                }
            }
        }
    }

    // --- Getters (reflect top frame for status display) ---

    public State getState() { return state; }
    public boolean isRunning() { return state == State.RUNNING; }
    public boolean isDone() { return state == State.DONE; }
    public boolean isStopped() { return state == State.STOPPED; }
    public boolean isError() { return state == State.ERROR; }
    public boolean isIdle() { return state == State.IDLE; }
    public String getErrorMessage() { return errorMessage; }

    /** Returns the name of the top frame (currently executing sequence). */
    public String getSequenceName() {
        Frame top = stack.peek();
        return top != null ? top.name : "";
    }

    /** Returns the current command index within the top frame. */
    public int getCurrentIndex() {
        Frame top = stack.peek();
        return top != null ? top.currentIndex : 0;
    }

    /** Returns the total command count of the top frame. */
    public int getTotalCommands() {
        Frame top = stack.peek();
        return top != null ? top.commands.size() : 0;
    }

    /** Returns the delay in ticks for the top frame. */
    public int getDelayTicks() {
        Frame top = stack.peek();
        return top != null ? top.delayTicks : 1;
    }

    /** Returns whether the top frame loops. */
    public boolean isLoop() {
        Frame top = stack.peek();
        return top != null && top.loop;
    }

    /** Returns the nesting depth (number of frames on the stack). */
    public int getDepth() {
        return stack.size();
    }

    /**
     * Returns the command at the given index in the top frame, or null.
     */
    public String getCommandAt(int index) {
        Frame top = stack.peek();
        if (top != null && index >= 0 && index < top.commands.size()) {
            return top.commands.get(index);
        }
        return null;
    }

    /**
     * Returns a copy of the call stack names, from bottom (root) to top (current).
     * Useful for displaying nesting information.
     */
    public List<String> getCallStackNames() {
        List<String> names = new ArrayList<>();
        for (Frame f : stack) {
            names.add(f.name);
        }
        return names;
    }

    // --- Internal helpers ---

    /**
     * Load a sequence file and push a new frame onto the stack.
     * Sets state to RUNNING on success, ERROR on failure.
     */
    private boolean pushFrame(String name, int delayTicks, boolean loop) {
        Path file = CsequenceState.getSequenceFile(name);
        if (!Files.exists(file)) {
            error("Sequence file not found: " + name);
            return false;
        }

        List<String> loaded = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    loaded.add(trimmed);
                }
            }
        } catch (IOException e) {
            error("Failed to read sequence file: " + e.getMessage());
            return false;
        }

        if (loaded.isEmpty()) {
            error("Sequence file is empty (no commands found): " + name);
            return false;
        }

        stack.push(new Frame(loaded, delayTicks, loop, name));
        state = State.RUNNING;
        return true;
    }

    /**
     * Pop the top frame. If the stack is now empty → DONE.
     * Otherwise the parent frame resumes from its saved position.
     */
    private void popFrame() {
        stack.pop();
        if (stack.isEmpty()) {
            state = State.DONE;
        }
        // Parent frame naturally resumes on the next tick
    }

    private void error(String msg) {
        errorMessage = msg;
        state = State.ERROR;
    }

    // --- Nested csequence command detection and parsing ---

    /**
     * Checks whether a command is a nested {@code /csequence run} call.
     */
    private static boolean isNestedCsequenceCall(String command) {
        String cmd = command.trim();
        return cmd.equals("csequence run") || cmd.startsWith("csequence run ")
            || cmd.equals("/csequence run") || cmd.startsWith("/csequence run ");
    }

    /** Parsed result of a nested csequence call. */
    private static class ParsedNestedCall {
        final String name;
        final int delayTicks;
        final boolean loop;

        ParsedNestedCall(String name, int delayTicks, boolean loop) {
            this.name = name;
            this.delayTicks = delayTicks;
            this.loop = loop;
        }
    }

    /**
     * Instance-aware parser for nested {@code /csequence run <name> [delay] [loop]} commands.
     *
     * @return the parsed call, or null if parsing fails
     */
    private ParsedNestedCall parseNestedCallInstance(String command) {
        // Strip leading / and split
        String cleaned = command.trim().replaceFirst("^/", "");
        String[] parts = cleaned.split("\\s+");

        if (parts.length < 3 || !"csequence".equals(parts[0]) || !"run".equals(parts[1])) {
            return null;
        }

        String name = parts[2];
        int delayTicks = 1;
        boolean loop = false;

        if (parts.length >= 4) {
            if ("loop".equalsIgnoreCase(parts[3])) {
                loop = true;
            } else {
                delayTicks = CtimerCommand.parseDuration(parts[3]);
                if (delayTicks <= 0) delayTicks = 1;
                if (parts.length >= 5 && "loop".equalsIgnoreCase(parts[4])) {
                    loop = true;
                }
            }
        }

        // Prevent cycles: reject if the target name already appears in the call stack.
        // This catches self-call (A→A), mutual recursion (A→B→A), and longer cycles.
        for (Frame f : stack) {
            if (f.name.equals(name)) {
                return null; // cycle detected, treat as non-nested command
            }
        }

        return new ParsedNestedCall(name, delayTicks, loop);
    }
}
