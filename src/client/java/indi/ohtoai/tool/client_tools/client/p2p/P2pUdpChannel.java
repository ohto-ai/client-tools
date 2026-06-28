package indi.ohtoai.tool.client_tools.client.p2p;

import net.minecraft.client.Minecraft;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * A single UDP channel to a remote peer.
 *
 * <p>Packet format:
 * <pre>
 *   [4 bytes messageId] [1 byte type] [payload ...]
 *
 *   Type 1 (HOLE_PUNCH):   [8 bytes random nonce]
 *   Type 2 (HOLE_ACK):     [8 bytes random nonce]
 *   Type 3 (CHAT):         [encrypted payload — AES-256-GCM]
 *   Type 4 (HEARTBEAT):    [4 bytes random]
 *   Type 5 (HEARTBEAT_ACK):[4 bytes random]
 * </pre>
 *
 * <p>This class runs a daemon thread for receiving. All callbacks are posted
 * to the Minecraft render thread via {@link Minecraft#execute}.
 */
public class P2pUdpChannel {

    private static final byte TYPE_HOLE_PUNCH = 1;
    private static final byte TYPE_HOLE_ACK = 2;
    private static final byte TYPE_CHAT = 3;
    private static final byte TYPE_HEARTBEAT = 4;
    private static final byte TYPE_HEARTBEAT_ACK = 5;

    private static final int RECV_BUFFER = 2048;
    private static final int HOLE_PUNCH_RETRIES = 60;
    private static final long HOLE_PUNCH_INTERVAL_MS = 200;
    static final long HEARTBEAT_INTERVAL_MS = 15_000;
    static final long HEARTBEAT_TIMEOUT_MS = 45_000;

    private final DatagramSocket socket;
    private final int localPort;
    private final SecretKey encryptionKey;
    private final AtomicInteger messageIdCounter = new AtomicInteger(0);
    private final SecureRandom random = new SecureRandom();

    private InetSocketAddress remoteAddr;
    private volatile boolean running = true;
    private volatile boolean connected;
    private volatile long lastRecvTime;

    private final Thread recvThread;
    private final Thread heartbeatThread;

    // Callbacks (called on render thread)
    private Consumer<String> onChatReceived;
    private Runnable onConnectionEstablished;
    private Consumer<String> onErrorMessage;

    /**
     * Create and bind a UDP channel.
     *
     * @param preferredPort preferred local port (0 = any available)
     * @param encryptionKey the shared AES key (may be null for unencrypted mode)
     * @throws IOException if binding fails
     */
    public P2pUdpChannel(int preferredPort, SecretKey encryptionKey) throws IOException {
        this.encryptionKey = encryptionKey;
        this.socket = new DatagramSocket(null);
        this.socket.setReuseAddress(true);
        this.socket.bind(new InetSocketAddress(preferredPort));
        this.localPort = socket.getLocalPort();
        this.lastRecvTime = System.currentTimeMillis();

        // Start receive thread
        recvThread = new Thread(this::recvLoop, "P2P-UDP-Recv-" + localPort);
        recvThread.setDaemon(true);
        recvThread.start();

        // Start heartbeat thread
        heartbeatThread = new Thread(this::heartbeatLoop, "P2P-UDP-HB-" + localPort);
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    // ── Callback setters ──────────────────────────────────────

    public void onChatReceived(Consumer<String> callback) { this.onChatReceived = callback; }
    public void onConnectionEstablished(Runnable callback) { this.onConnectionEstablished = callback; }
    public void onErrorMessage(Consumer<String> callback) { this.onErrorMessage = callback; }

    // ── Getters ───────────────────────────────────────────────

    public int getLocalPort() { return localPort; }
    public boolean isConnected() { return connected; }
    public InetSocketAddress getRemoteAddr() { return remoteAddr; }

    // ── Hole punching ─────────────────────────────────────────

    /**
     * Set the remote address and start hole punching.
     * Call this from the main thread.
     */
    public void startHolePunch(InetSocketAddress remote) {
        this.remoteAddr = remote;
        sendHolePunchPacket();
    }

    /**
     * Called automatically when we receive a hole-punch packet and
     * haven't set a remote address yet. This sets the remote address
     * and starts punching back.
     */
    private void autoPunch(InetSocketAddress from) {
        if (this.remoteAddr == null) {
            this.remoteAddr = from;
        }
        // Send ACK
        sendRaw(TYPE_HOLE_ACK, randomBytes(8));

        // If not yet connected, start punching back in background
        if (!connected) {
            new Thread(() -> {
                for (int i = 0; i < HOLE_PUNCH_RETRIES && running && !connected; i++) {
                    sendRaw(TYPE_HOLE_PUNCH, randomBytes(8));
                    try { Thread.sleep(HOLE_PUNCH_INTERVAL_MS); } catch (InterruptedException ignored) {}
                }
            }, "P2P-AutoPunch-" + localPort).setDaemon(true);
            // We're connected as soon as we receive a hole-punch packet
            setConnected();
        }
    }

    private void sendHolePunchPacket() {
        sendRaw(TYPE_HOLE_PUNCH, randomBytes(8));
    }

    private boolean isHolePunching;

    /**
     * Run the hole-punch sequence (blocking, call from a background thread
     * or the tick handler in non-blocking fashion).
     */
    public void runHolePunch() {
        if (remoteAddr == null) return;
        isHolePunching = true;
        sendHolePunchPacket(); // initial punch

        // The rest is handled via the heartbeat/punch thread.
        // We'll send additional punches in the heartbeat thread if not yet connected.
        // After HOLE_PUNCH_RETRIES * HOLE_PUNCH_INTERVAL_MS, give up.
        long deadline = System.currentTimeMillis() + HOLE_PUNCH_RETRIES * HOLE_PUNCH_INTERVAL_MS;
        while (running && !connected && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(HOLE_PUNCH_INTERVAL_MS); } catch (InterruptedException ignored) {}
            if (connected) break;
            sendRaw(TYPE_HOLE_PUNCH, randomBytes(8));
        }
        isHolePunching = false;

        if (!connected) {
            postErrorMessage("client-tools.cchatp2p.punch_failed");
        }
    }

    // ── Sending ───────────────────────────────────────────────

    /**
     * Send an encrypted chat message.
     */
    public void sendChatMessage(String plaintext) {
        if (!connected) {
            postErrorMessage("client-tools.cchatp2p.not_connected");
            return;
        }
        if (encryptionKey == null) {
            postErrorMessage("client-tools.cchatp2p.key_not_set");
            return;
        }
        byte[] encrypted = P2pEncryption.encrypt(plaintext, encryptionKey);
        sendRaw(TYPE_CHAT, encrypted);
    }

    private void sendRaw(byte type, byte[] payload) {
        if (socket == null || socket.isClosed() || remoteAddr == null) return;
        try {
            int msgId = messageIdCounter.incrementAndGet();
            ByteBuffer buf = ByteBuffer.allocate(5 + payload.length);
            buf.putInt(msgId);
            buf.put(type);
            buf.put(payload);
            byte[] packet = buf.array();
            socket.send(new DatagramPacket(packet, packet.length, remoteAddr));
        } catch (IOException e) {
            // Silently drop — UDP is unreliable by nature
        }
    }

    // ── Receive loop ──────────────────────────────────────────

    private void recvLoop() {
        byte[] buf = new byte[RECV_BUFFER];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                handlePacket(packet);
            } catch (SocketTimeoutException ignored) {
            } catch (IOException e) {
                if (running) {
                    postErrorMessage("client-tools.cchatp2p.recv_error");
                }
            }
        }
    }

    private void handlePacket(DatagramPacket packet) {
        byte[] data = packet.getData();
        int len = packet.getLength();
        if (len < 5) return; // minimum: 4 bytes msgId + 1 byte type

        ByteBuffer buf = ByteBuffer.wrap(data, 0, len);
        /* int msgId = */ buf.getInt();
        byte type = buf.get();
        byte[] payload = new byte[buf.remaining()];
        buf.get(payload);

        InetSocketAddress from = (InetSocketAddress) packet.getSocketAddress();
        lastRecvTime = System.currentTimeMillis();

        switch (type) {
            case TYPE_HOLE_PUNCH -> handleHolePunch(from, payload);
            case TYPE_HOLE_ACK -> handleHoleAck(from);
            case TYPE_CHAT -> handleChat(payload);
            case TYPE_HEARTBEAT -> sendRaw(TYPE_HEARTBEAT_ACK, payload);
            case TYPE_HEARTBEAT_ACK -> {} // just confirms liveness
        }
    }

    private void handleHolePunch(InetSocketAddress from, byte[] payload) {
        if (!connected) {
            // Auto-punch: set remote address and start punching back
            autoPunch(from);
        }
        // Always ACK
        sendRaw(TYPE_HOLE_ACK, payload);
    }

    private void handleHoleAck(InetSocketAddress from) {
        if (!connected) {
            // Update remote address if we didn't know it
            if (remoteAddr == null) {
                remoteAddr = from;
            }
            setConnected();
        }
    }

    private void handleChat(byte[] encryptedPayload) {
        if (encryptionKey == null) {
            postErrorMessage("client-tools.cchatp2p.received_encrypted_no_key");
            return;
        }
        String plaintext = P2pEncryption.decrypt(encryptedPayload, encryptionKey);
        if (plaintext != null && onChatReceived != null) {
            Minecraft.getInstance().execute(() -> onChatReceived.accept(plaintext));
        }
    }

    // ── Heartbeat ─────────────────────────────────────────────

    private void heartbeatLoop() {
        while (running) {
            try { Thread.sleep(HEARTBEAT_INTERVAL_MS); } catch (InterruptedException ignored) {}
            if (!running) break;

            if (connected) {
                sendRaw(TYPE_HEARTBEAT, randomBytes(4));

                // Check for timeout
                if (System.currentTimeMillis() - lastRecvTime > HEARTBEAT_TIMEOUT_MS) {
                    connected = false;
                    postErrorMessage("client-tools.cchatp2p.connection_timeout");
                }
            }

            // Send additional hole-punch packets during connection phase
            if (!connected && remoteAddr != null && !isHolePunching) {
                sendRaw(TYPE_HOLE_PUNCH, randomBytes(8));
            }
        }
    }

    // ── Connection state ──────────────────────────────────────

    private void setConnected() {
        if (!connected) {
            connected = true;
            if (onConnectionEstablished != null) {
                Minecraft.getInstance().execute(onConnectionEstablished::run);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    private void postErrorMessage(String key) {
        if (onErrorMessage != null) {
            Minecraft.getInstance().execute(() -> onErrorMessage.accept(key));
        }
    }

    // ── Cleanup ───────────────────────────────────────────────

    public void close() {
        running = false;
        connected = false;
        try { socket.close(); } catch (Exception ignored) {}
    }
}
