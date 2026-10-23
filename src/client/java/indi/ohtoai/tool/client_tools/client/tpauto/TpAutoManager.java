package indi.ohtoai.tool.client_tools.client.tpauto;

import indi.ohtoai.tool.client_tools.client.config.ClientToolsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Manages automatic response to /tpa and /tpahere teleport requests.
 *
 * <p>Detects incoming teleport request messages (system chat from server plugins
 * like EssentialsX) and automatically sends {@code /tpaccept} or {@code /tpdeny}
 * based on the configured mode for each request type.
 *
 * <p>Modes per request type:
 * <ul>
 *   <li>{@code ALLOW_ALL} — always accept</li>
 *   <li>{@code DENY_ALL} — always deny</li>
 *   <li>{@code WHITELIST} — accept only from whitelisted players</li>
 * </ul>
 */
public final class TpAutoManager {

    // ── Constants ───────────────────────────────────────────────

    /** Timeout for pending teleport requests (120 seconds, matching EssentialsX default). */
    private static final long REQUEST_TIMEOUT_MS = 120_000L;

    /** Tick delay before sending auto-response (avoids race with server request setup). */
    private static final int RESPONSE_DELAY_TICKS = 10; // ~0.5 seconds

    // ── Detection patterns ──────────────────────────────────────

    // Chinese patterns — allow optional whitespace between name and request text
    private static final Pattern CN_TPA = Pattern.compile(
        "^(?<sender>\\S{2,16})\\s*请求传送到你(?:这里|这儿)?。");
    private static final Pattern CN_TPAHERE = Pattern.compile(
        "^(?<sender>\\S{2,16})\\s*请求你传送到[它他她]那(?:里|儿)。");

    // English patterns (EssentialsX) — allow optional whitespace
    private static final Pattern EN_TPA = Pattern.compile(
        "^(?<sender>\\S{2,16})\\s+has requested to teleport to you\\.",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern EN_TPAHERE = Pattern.compile(
        "^(?<sender>\\S{2,16})\\s+has requested (?:that )?you teleport to (?:them|him|her)\\.",
        Pattern.CASE_INSENSITIVE);

    // ── Singleton ───────────────────────────────────────────────

    private static TpAutoManager instance;

    public static TpAutoManager getInstance() {
        if (instance == null) {
            instance = new TpAutoManager();
        }
        return instance;
    }

    // ── State ───────────────────────────────────────────────────

    /** Current pending teleport request, or null. */
    private PendingTpRequest activeRequest;

    /** Countdown ticks until the scheduled response is sent. 0 = no pending response. */
    private int responseCountdown;

    /** The command to send when the countdown reaches 0 ("tpaccept" or "tpdeny"). */
    private String scheduledCommand;

    /** The sender of the scheduled response (for feedback message). */
    private String scheduledSender;

    /** The type of the scheduled response (for feedback message). */
    private TpRequestType scheduledType;

    public record PendingTpRequest(String sender, TpRequestType type, long receivedAt) {}

    // ── Enums ───────────────────────────────────────────────────

    public enum TpRequestType {
        TPA,     // Someone wants to teleport to me
        TPAHERE  // Someone wants me to teleport to them
    }

    private TpAutoManager() {}

    // ── Message detection ───────────────────────────────────────

    /**
     * Examines an incoming system chat message to see if it is a
     * teleport request notification. If so, schedules an auto-response
     * based on the configured mode.
     *
     * @param message the system chat message component
     */
    public void processIncomingSystemChat(Component message) {
        if (!ClientToolsConfig.isTpAutoEnabled()) return;

        String text = message.getString();
        if (text == null || text.isEmpty()) return;

        // Quick check: must mention both tpaccept and tpdeny
        String lower = text.toLowerCase();
        if (!lower.contains("tpaccept") && !lower.contains("/tpaccept")) return;
        if (!lower.contains("tpdeny") && !lower.contains("/tpdeny")) return;

        // Try to match against known patterns
        TpRequestType type = null;
        String sender = null;

        // Try Chinese TPA
        var m = CN_TPA.matcher(text);
        if (m.find()) {
            sender = m.group("sender");
            type = TpRequestType.TPA;
        }

        // Try Chinese TPAHERE
        if (type == null) {
            m = CN_TPAHERE.matcher(text);
            if (m.find()) {
                sender = m.group("sender");
                type = TpRequestType.TPAHERE;
            }
        }

        // Try English TPA
        if (type == null) {
            m = EN_TPA.matcher(text);
            if (m.find()) {
                sender = m.group("sender");
                type = TpRequestType.TPA;
            }
        }

        // Try English TPAHERE
        if (type == null) {
            m = EN_TPAHERE.matcher(text);
            if (m.find()) {
                sender = m.group("sender");
                type = TpRequestType.TPAHERE;
            }
        }

        // Fallback: detect type from directional keywords when patterns don't match
        if (type == null || sender == null) {
            sender = extractSenderFallback(text);
            if (sender == null) return;
            type = detectTypeFallback(lower);
        }

        // Validate sender
        if (sender == null || sender.isEmpty()) return;
        if (!isValidPlayerName(sender)) return;

        // Skip self-requests
        Minecraft client = Minecraft.getInstance();
        if (client.getUser() != null && client.getUser().getName().equalsIgnoreCase(sender)) {
            return;
        }

        // Create / replace pending request
        activeRequest = new PendingTpRequest(sender, type, System.currentTimeMillis());

        // Schedule auto-response
        autoRespond(sender, type);
    }

    // ── Auto-response ───────────────────────────────────────────

    private void autoRespond(String sender, TpRequestType type) {
        String modeStr = (type == TpRequestType.TPA)
            ? ClientToolsConfig.getTpAutoMode()
            : ClientToolsConfig.getTpahereAutoMode();

        if (modeStr == null) modeStr = "deny_all";

        switch (modeStr.toLowerCase()) {
            case "allow_all" -> scheduleCommand("tpaccept", sender, type);
            case "deny_all" -> scheduleCommand("tpdeny", sender, type);
            case "whitelist" -> {
                if (isWhitelisted(sender, type)) {
                    scheduleCommand("tpaccept", sender, type);
                }
                // Not whitelisted: do nothing, player handles manually
            }
            default -> {} // Unknown mode: do nothing
        }
    }

    // ── Scheduling ──────────────────────────────────────────────

    private void scheduleCommand(String command, String sender, TpRequestType type) {
        this.responseCountdown = RESPONSE_DELAY_TICKS;
        this.scheduledCommand = command;
        this.scheduledSender = sender;
        this.scheduledType = type;
    }

    // ── Tick ────────────────────────────────────────────────────

    /**
     * Called every client tick. Checks for expired requests and
     * scheduled responses that are ready to send.
     */
    public void tick() {
        // Clear expired requests
        if (activeRequest != null) {
            long elapsed = System.currentTimeMillis() - activeRequest.receivedAt();
            if (elapsed > REQUEST_TIMEOUT_MS) {
                activeRequest = null;
            }
        }

        // Handle delayed response
        if (responseCountdown > 0) {
            responseCountdown--;
            if (responseCountdown == 0) {
                sendScheduledResponse();
            }
        }
    }

    private void sendScheduledResponse() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.player.connection == null) return;

        // Send the command
        client.player.connection.send(
            new ServerboundChatCommandPacket(scheduledCommand));

        // Show feedback message
        String modeStr = (scheduledType == TpRequestType.TPA)
            ? ClientToolsConfig.getTpAutoMode()
            : ClientToolsConfig.getTpahereAutoMode();

        boolean isDeny = "tpdeny".equals(scheduledCommand);
        if (isDeny) {
            if ("whitelist".equalsIgnoreCase(modeStr)) {
                client.player.displayClientMessage(
                    Component.translatable("client-tools.ctpauto.auto_denied_whitelist",
                        scheduledSender), false);
            } else {
                client.player.displayClientMessage(
                    Component.translatable("client-tools.ctpauto.auto_denied",
                        scheduledSender), false);
            }
        } else {
            client.player.displayClientMessage(
                Component.translatable("client-tools.ctpauto.auto_accepted",
                    scheduledSender), false);
        }

        // Clear state
        activeRequest = null;
        scheduledCommand = null;
        scheduledSender = null;
        scheduledType = null;
    }

    // ── Disconnect ──────────────────────────────────────────────

    public void onDisconnect() {
        activeRequest = null;
        responseCountdown = 0;
        scheduledCommand = null;
        scheduledSender = null;
        scheduledType = null;
    }

    // ── Whitelist ───────────────────────────────────────────────

    private boolean isWhitelisted(String sender, TpRequestType type) {
        List<String> whitelist = (type == TpRequestType.TPA)
            ? ClientToolsConfig.getTpAutoWhitelist()
            : ClientToolsConfig.getTpahereWhitelist();
        if (whitelist == null) return false;
        for (String name : whitelist) {
            if (name.equalsIgnoreCase(sender)) return true;
        }
        return false;
    }

    // ── Helpers ─────────────────────────────────────────────────

    /**
     * Fallback sender extraction: take the first word-like token
     * before the request phrasing.
     */
    private static String extractSenderFallback(String text) {
        // Strip leading brackets/punctuation, take first word
        String cleaned = text.trim()
            .replaceAll("^[\\[（(\\s]+", "");
        String[] words = cleaned.split("[\\s：:，,。.!！?？]+", 2);
        if (words.length > 0) {
            String candidate = words[0].replaceAll("[^a-zA-Z0-9_]", "");
            if (candidate.length() >= 2 && candidate.length() <= 16) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Fallback type detection from directional keywords.
     */
    private static TpRequestType detectTypeFallback(String lowerText) {
        // TPAHERE indicators: you going to them
        if (lowerText.contains("传送到它") || lowerText.contains("传送到他")
            || lowerText.contains("传送到她") || lowerText.contains("teleport to them")
            || lowerText.contains("teleport to him") || lowerText.contains("teleport to her")
            || lowerText.contains("you teleport to")) {
            return TpRequestType.TPAHERE;
        }
        // Default: assume TPA (them coming to us — safer default)
        return TpRequestType.TPA;
    }

    /**
     * Validates that a string looks like a Minecraft player name.
     */
    private static boolean isValidPlayerName(String name) {
        return name != null && name.matches("[a-zA-Z0-9_]{2,16}");
    }

    // ── Status queries (for /ctpauto command) ───────────────────

    public PendingTpRequest getActiveRequest() {
        return activeRequest;
    }

    public boolean hasPendingResponse() {
        return responseCountdown > 0;
    }
}
