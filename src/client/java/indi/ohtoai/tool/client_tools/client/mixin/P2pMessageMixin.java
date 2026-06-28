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
 * <p>Whispers in 1.21 may arrive via these packet types:
 * <ul>
 *   <li>{@code ClientboundDisguisedChatPacket} (signed) — handleDisguisedChat</li>
 *   <li>{@code ClientboundPlayerChatPacket} (unsigned/not-secure) — handlePlayerChat</li>
 *   <li>{@code ClientboundSystemChatPacket} (system/overlay) — handleSystemChat</li>
 * </ul>
 * Outgoing echoes are deduplicated by msgId inside P2pChatManager.
 */
@Mixin(ClientPacketListener.class)
public class P2pMessageMixin {

    @Unique
    private static final Logger LOGGER = LogUtils.getLogger();

    @Inject(method = "handleDisguisedChat", at = @At("HEAD"), cancellable = true)
    private void onDisguisedChat(ClientboundDisguisedChatPacket packet, CallbackInfo ci) {
        Component message = packet.message();
        if (!message.getString().contains("[CTP2P]")) return;
        ci.cancel();

        String sender = packet.chatType().name().getString();
        String localName = Minecraft.getInstance().getUser().getName();

        LOGGER.info("[P2P-MIXIN] handleDisguisedChat sender='{}' local='{}'", sender, localName);

        if (sender.equalsIgnoreCase(localName)) {
            LOGGER.info("[P2P-MIXIN] handleDisguisedChat — outgoing echo, suppressed");
            return;
        }

        processAndDisplay(message, sender);
    }

    @Inject(method = "handlePlayerChat", at = @At("HEAD"), cancellable = true)
    private void onPlayerChat(ClientboundPlayerChatPacket packet, CallbackInfo ci) {
        Component unsigned = packet.unsignedContent();
        if (unsigned == null || !unsigned.getString().contains("[CTP2P]")) return;
        ci.cancel();

        String sender = packet.chatType().name().getString();
        String localName = Minecraft.getInstance().getUser().getName();

        LOGGER.info("[P2P-MIXIN] handlePlayerChat sender='{}' local='{}'", sender, localName);

        if (sender.equalsIgnoreCase(localName)) {
            LOGGER.info("[P2P-MIXIN] handlePlayerChat — outgoing echo, suppressed");
            return;
        }

        processAndDisplay(unsigned, sender);
    }

    @Inject(method = "handleSystemChat", at = @At("HEAD"), cancellable = true)
    private void onSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        Component message = packet.content();
        if (!message.getString().contains("[CTP2P]")) return;
        ci.cancel();

        // System messages don't carry sender metadata — pass null.
        // Outgoing dedup is handled by msgId check in processIncomingMessage.
        processAndDisplay(message, null);
    }

    private static void processAndDisplay(Component raw, String sender) {
        P2pChatManager mgr = P2pChatManager.getInstance();
        Component modified = mgr.processIncomingMessage(raw, sender);

        if (modified != raw) {
            Minecraft client = Minecraft.getInstance();
            client.gui.getChat().addMessage(modified);
            client.getNarrator().sayNow(modified);
        }
    }
}
