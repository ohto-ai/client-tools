package indi.ohtoai.tool.client_tools.client.p2p;

import indi.ohtoai.tool.client_tools.client.config.ClientToolsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Central manager for encrypted chat.
 *
 * <p>Messages use a simple framed protocol inside the AES-256-GCM payload:
 * <pre>
 *   M&lt;msgId&gt;&lt;plaintext&gt;   — chat message (msgId = 16 hex chars)
 *   A&lt;msgId&gt;               — delivery acknowledgement
 * </pre>
 * When a message is received, an ACK is sent back automatically.
 * The sender tracks pending messages and warns on timeout (~30 s).
 */
public class P2pChatManager {

    private static P2pChatManager instance;

    public static P2pChatManager getInstance() {
        if (instance == null) {
            instance = new P2pChatManager();
        }
        return instance;
    }

    // ── State ─────────────────────────────────────────────────

    private SecretKey encryptionKey;
    private final List<String> groupMembers = new CopyOnWriteArrayList<>();
    private final List<P2pMessage> messageHistory = new ArrayList<>();
    private static final int MAX_HISTORY = 100;

    // Marker prefix in the /msg payload
    private static final String MARKER = "[CTP2P]";
    private static final int MSG_ID_HEX_LEN = 16; // 8 bytes → 16 hex
    private static final SecureRandom RNG = new SecureRandom();

    // Language-independent outgoing echo detection
    private static final Pattern[] OUTGOING_ECHO_PATTERNS = {
        Pattern.compile("^You\\s", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^\\[?You\\s*->"),
        Pattern.compile("^你"),
        Pattern.compile("^\\[?你"),
    };

    // ── Delivery tracking ─────────────────────────────────────

    private static final long ACK_TIMEOUT_MS = 30_000;

    private record Pending(String msgId, String target, String plaintext, long sentAt) {}

    private final Map<String, Pending> pendingMessages = new ConcurrentHashMap<>();

    private P2pChatManager() {
        groupMembers.addAll(ClientToolsConfig.getP2pGroupMembers());
        // Restore persisted key on startup
        String saved = ClientToolsConfig.getP2pPassword();
        if (!saved.isEmpty()) {
            this.encryptionKey = P2pEncryption.deriveKey(saved);
        }
    }

    // ── Key management ────────────────────────────────────────

    public void setPassword(String password) {
        this.encryptionKey = P2pEncryption.deriveKey(password);
        ClientToolsConfig.setP2pPassword(password);
    }

    public boolean isKeySet() {
        return encryptionKey != null;
    }

    public void clearKey() {
        encryptionKey = null;
        pendingMessages.clear();
        ClientToolsConfig.setP2pPassword("");
    }

    // ── Sending ───────────────────────────────────────────────

    /** Generate a random 16-char hex message ID. */
    private static String newMsgId() {
        byte[] b = new byte[8];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder(16);
        for (byte v : b) sb.append(String.format("%02x", v & 0xff));
        return sb.toString();
    }

    /**
     * Encrypt a message and send it to a player via {@code /msg}.
     */
    public void sendToPlayer(String targetPlayer, String plaintext) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.player.connection == null) return;
        if (encryptionKey == null) {
            sendLocalMessage("client-tools.cencrypt.key_not_set");
            return;
        }

        String msgId = newMsgId();
        String framed = "M" + msgId + plaintext;
        byte[] encrypted = P2pEncryption.encrypt(framed, encryptionKey);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);

        String payload = MARKER + encoded;
        String command = "msg " + targetPlayer + " " + payload;
        if (command.length() > 256) {
            sendLocalMessage("client-tools.cencrypt.message_too_long");
            return;
        }

        client.player.connection.send(new ServerboundChatCommandPacket(command));

        // Track for delivery confirmation
        pendingMessages.put(msgId, new Pending(msgId, targetPlayer, plaintext, System.currentTimeMillis()));

        // Show local outgoing message
        sendLocalMessage("client-tools.cencrypt.outgoing", plaintext);

        // Record in history
        addToHistory(new P2pMessage(targetPlayer, plaintext, Instant.now(),
            P2pMessage.Direction.OUTGOING));
    }

    /**
     * Send an encrypted message to all ONLINE group members.
     *
     * @return number of members the message was actually sent to
     */
    public int sendToGroup(String plaintext) {
        if (groupMembers.isEmpty()) {
            sendLocalMessage("client-tools.cencrypt.group_empty");
            return 0;
        }
        Minecraft client = Minecraft.getInstance();
        java.util.Set<String> online = new java.util.HashSet<>();
        if (client.getConnection() != null) {
            for (var info : client.getConnection().getOnlinePlayers()) {
                online.add(info.getProfile().getName());
            }
        }
        int sent = 0;
        for (String member : groupMembers) {
            if (online.contains(member)) {
                sendToPlayer(member, plaintext);
                sent++;
            }
        }
        return sent;
    }

    /**
     * Send a raw encrypted payload directly (used for ACKs — bypasses
     * the "M" framing and pending tracking).
     */
    private void sendRaw(String targetPlayer, String payload) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.player.connection == null) return;
        if (encryptionKey == null) return;

        byte[] encrypted = P2pEncryption.encrypt(payload, encryptionKey);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
        String command = "msg " + targetPlayer + " " + MARKER + encoded;
        if (command.length() > 256) return;

        client.player.connection.send(new ServerboundChatCommandPacket(command));
    }

    // ── Receiving (called from Mixin) ─────────────────────────

    public Component processIncomingMessage(Component message) {
        return processIncomingMessage(message, null);
    }

    /**
     * @param sender  player name from packet metadata, or {@code null}
     */
    public Component processIncomingMessage(Component message, String sender) {
        if (encryptionKey == null) return message;

        String text = message.getString();
        int markerIdx = text.indexOf(MARKER);
        if (markerIdx < 0) return message;

        // Fallback sender extraction from text
        if (sender == null) {
            String trimmed = text.trim();
            for (Pattern p : OUTGOING_ECHO_PATTERNS) {
                if (p.matcher(trimmed).find()) return message;
            }
            String prefix = text.substring(0, markerIdx).trim();
            String cleaned = prefix.replaceAll("[\\[\\]（）()：:\\->\\s]+", " ");
            String[] words = cleaned.trim().split("\\s+");
            for (String w : words) {
                if (w.matches("[a-zA-Z0-9_]{2,16}")) {
                    sender = w;
                    break;
                }
            }
            if (sender == null && words.length > 0) sender = words[0];
        }

        if (sender == null || sender.isEmpty()) sender = "???";

        // Skip outgoing echo
        Minecraft client = Minecraft.getInstance();
        if (client.getUser().getName().equalsIgnoreCase(sender)) {
            return message;
        }

        String encoded = text.substring(markerIdx + MARKER.length()).trim();
        if (encoded.isEmpty()) return message;

        try {
            byte[] encrypted = Base64.getUrlDecoder().decode(encoded);
            String decrypted = P2pEncryption.decrypt(encrypted, encryptionKey);
            if (decrypted == null || decrypted.length() < 1 + MSG_ID_HEX_LEN) {
                sendLocalMessage("client-tools.cencrypt.decrypt_failed", sender);
                return message;
            }

            char type = decrypted.charAt(0);
            String msgId = decrypted.substring(1, 1 + MSG_ID_HEX_LEN);

            if (type == 'M') {
                // ── Incoming message ──────────────────────────
                String plaintext = decrypted.substring(1 + MSG_ID_HEX_LEN);
                addToHistory(new P2pMessage(sender, plaintext, Instant.now(),
                    P2pMessage.Direction.INCOMING));

                // Send acknowledgement back
                sendRaw(sender, "A" + msgId);

                return Component.translatable("client-tools.cencrypt.incoming", sender, plaintext);

            } else if (type == 'A') {
                // ── Delivery acknowledgement ──────────────────
                Pending pending = pendingMessages.remove(msgId);
                if (pending != null) {
                    sendLocalMessage("client-tools.cencrypt.delivered", pending.target(), pending.plaintext());
                }
                return message; // don't display ACKs in chat
            }

            // Unknown type — report as undecryptable
            sendLocalMessage("client-tools.cencrypt.decrypt_failed", sender);

        } catch (Exception ignored) {
            sendLocalMessage("client-tools.cencrypt.decrypt_failed", sender);
        }
        return message;
    }

    // ── Tick ──────────────────────────────────────────────────

    /**
     * Called every client tick.  Checks for pending messages that
     * haven't been acknowledged within the timeout.
     */
    public void tick() {
        if (pendingMessages.isEmpty()) return;

        long now = System.currentTimeMillis();
        var iter = pendingMessages.values().iterator();
        while (iter.hasNext()) {
            Pending p = iter.next();
            if (now - p.sentAt() > ACK_TIMEOUT_MS) {
                iter.remove();
                sendLocalMessage("client-tools.cencrypt.not_delivered", p.target(), p.plaintext());
            }
        }
    }

    // ── Group management ──────────────────────────────────────

    public List<String> getGroupMembers() {
        return Collections.unmodifiableList(groupMembers);
    }

    public void addGroupMember(String name) {
        if (!groupMembers.contains(name)) {
            groupMembers.add(name);
            ClientToolsConfig.setP2pGroupMembers(new ArrayList<>(groupMembers));
        }
    }

    public boolean removeGroupMember(String name) {
        boolean removed = groupMembers.remove(name);
        if (removed) {
            ClientToolsConfig.setP2pGroupMembers(new ArrayList<>(groupMembers));
        }
        return removed;
    }

    public void clearGroup() {
        groupMembers.clear();
        ClientToolsConfig.setP2pGroupMembers(new ArrayList<>());
    }

    // ── Status ────────────────────────────────────────────────

    public List<P2pMessage> getMessageHistory() {
        return Collections.unmodifiableList(messageHistory);
    }

    private void addToHistory(P2pMessage msg) {
        messageHistory.add(msg);
        while (messageHistory.size() > MAX_HISTORY) {
            messageHistory.remove(0);
        }
    }

    // ── Cleanup ───────────────────────────────────────────────

    public void onDisconnect() {
        pendingMessages.clear();
    }

    // ── Helpers ───────────────────────────────────────────────

    private void sendLocalMessage(String key, Object... args) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.displayClientMessage(
                Component.translatable(key, args), false);
        }
    }
}
