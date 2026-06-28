package indi.ohtoai.tool.client_tools.client.mixin;

import com.mojang.logging.LogUtils;
import indi.ohtoai.tool.client_tools.client.p2p.P2pChatManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts P2P encrypted whisper messages at the network packet level.
 *
 * <p>Whispers in 1.21 arrive via three possible packet types depending on
 * chat signing state:
 * <ul>
 *   <li>{@code ClientboundDisguisedChatPacket} (signed) —
 *       {@code handleDisguisedChat}</li>
 *   <li>{@code ClientboundSystemChatPacket} (overlay/system) —
 *       {@code handleSystemChat}</li>
 *   <li>{@code ClientboundPlayerChatPacket} (unsigned/not-secure) —
 *       {@code handlePlayerChat}</li>
 * </ul>
 */
@Mixin(ClientPacketListener.class)
public class P2pMessageMixin {

    @Unique
    private static final Logger LOGGER = LogUtils.getLogger();

    @Inject(method = "handleDisguisedChat", at = @At("HEAD"), cancellable = true)
    private void onDisguisedChat(ClientboundDisguisedChatPacket packet, CallbackInfo ci) {
        Component message = packet.message();
        String text = message.getString();

        if (!text.contains("[CTP2P]")) return;

        ci.cancel();

        // Sender name comes from the chat type metadata — the message text
        // is just the raw [CTP2P]... payload without a "whispers to you" prefix.
        String sender = packet.chatType().name().getString();
        String localName = Minecraft.getInstance().getUser().getName();

        LOGGER.info("[P2P-MIXIN] handleDisguisedChat sender='{}' local='{}'", sender, localName);

        if (sender.equalsIgnoreCase(localName)) {
            LOGGER.info("[P2P-MIXIN] handleDisguisedChat — outgoing echo, suppressed");
            return; // sendLocalMessage in sendToPlayer already displayed the clean version
        }

        processAndDisplay(message, sender);
    }

    @Inject(method = "handleSystemChat", at = @At("HEAD"), cancellable = true)
    private void onSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        Component message = packet.content();
        String text = message.getString();

        if (!text.contains("[CTP2P]")) return;

        ci.cancel();
        LOGGER.info("[P2P-MIXIN] handleSystemChat raw text='{}'", text);

        // System chat messages don't carry sender metadata — fall back to
        // text-based extraction (may show "???" if the text has no prefix).
        processAndDisplay(message, null);
    }

    @Inject(method = "handlePlayerChat", at = @At("HEAD"), cancellable = true)
    private void onPlayerChat(ClientboundPlayerChatPacket packet, CallbackInfo ci) {
        Component unsigned = packet.unsignedContent();
        if (unsigned == null) return;

        String text = unsigned.getString();
        if (!text.contains("[CTP2P]")) return;

        ci.cancel();
        LOGGER.info("[P2P-MIXIN] handlePlayerChat unsigned text='{}'", text);

        // Unsigned player chat may carry sender metadata — try to extract it.
        String sender = packet.chatType().name().getString();
        String localName = Minecraft.getInstance().getUser().getName();

        LOGGER.info("[P2P-MIXIN] handlePlayerChat sender='{}' local='{}'", sender, localName);

        if (sender.equalsIgnoreCase(localName)) {
            LOGGER.info("[P2P-MIXIN] handlePlayerChat — outgoing echo, suppressed");
            return;
        }

        processAndDisplay(unsigned, sender);
    }

    /**
     * Decrypt and display a P2P message.  The vanilla handler has already
     * been cancelled by the caller.
     *
     * @param raw    the raw chat component whose {@code getString()} contains
     *               {@code [CTP2P]}… (just the payload, no whisper prefix)
     * @param sender the sender's player name, or {@code null} to fall back to
     *               text-based extraction inside {@code processIncomingMessage}
     */
    private static void processAndDisplay(Component raw, String sender) {
        P2pChatManager mgr = P2pChatManager.getInstance();
        Component modified = mgr.processIncomingMessage(raw, sender);

        if (modified != raw) {
            Minecraft client = Minecraft.getInstance();
            client.gui.getChat().addMessage(modified);
            client.getNarrator().sayNow(modified);
        }
        // If modified == raw, it's a decryption failure — suppress the raw
        // ciphertext (showing unreadable base64 is useless to the user).
    }
}
