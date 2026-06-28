package indi.ohtoai.tool.client_tools.client.p2p;

import java.time.Instant;

/**
 * Immutable record for a single P2P chat message.
 */
public record P2pMessage(
    String sender,
    String content,
    Instant timestamp,
    Direction direction
) {
    public enum Direction { INCOMING, OUTGOING }
}
