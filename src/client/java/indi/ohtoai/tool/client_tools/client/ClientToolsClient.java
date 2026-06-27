package indi.ohtoai.tool.client_tools.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;
import indi.ohtoai.tool.client_tools.client.command.CchatCommand;
import indi.ohtoai.tool.client_tools.client.command.CcraftCommand;
import indi.ohtoai.tool.client_tools.client.command.CflyCommand;
import indi.ohtoai.tool.client_tools.client.command.CbuyCommand;
import indi.ohtoai.tool.client_tools.client.command.CplacementCommand;
import indi.ohtoai.tool.client_tools.client.command.CsellCommand;
import indi.ohtoai.tool.client_tools.client.command.CsequenceCommand;
import indi.ohtoai.tool.client_tools.client.command.CsweepCommand;
import indi.ohtoai.tool.client_tools.client.command.CtimerCommand;
import indi.ohtoai.tool.client_tools.client.bow.BowTrajectoryRenderer;
import indi.ohtoai.tool.client_tools.client.command.CbowCommand;
import indi.ohtoai.tool.client_tools.client.command.CriptideCommand;
import indi.ohtoai.tool.client_tools.client.command.CdollCommand;
import indi.ohtoai.tool.client_tools.client.craft.CcraftHighlightRenderer;
import indi.ohtoai.tool.client_tools.client.craft.CcraftState;
import indi.ohtoai.tool.client_tools.client.craft.CraftingExecutor;
import indi.ohtoai.tool.client_tools.client.doll.DollPackManager;
import indi.ohtoai.tool.client_tools.client.sequence.SequenceExecutor;
import indi.ohtoai.tool.client_tools.client.shop.ShopExecutor;
import indi.ohtoai.tool.client_tools.client.sweep.SweepExecutor;
import indi.ohtoai.tool.client_tools.client.sweep.SweepHighlightRenderer;
import indi.ohtoai.tool.client_tools.client.sweep.SweepState;
import indi.ohtoai.tool.client_tools.client.timer.TimerManager;
import net.minecraft.network.chat.Component;

public class ClientToolsClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Pre-create the doll resource pack directory so Minecraft's resource
		// pack scanner discovers it. Without this, the pack is only picked up
		// on the second reload after /cdoll add is first used.
		DollPackManager.initPackDirectory();

		// Register built-in resource pack (furina_doll)
		FabricLoader.getInstance().getModContainer("client-tools").ifPresent(modContainer -> {
			ResourceManagerHelper.registerBuiltinResourcePack(
				ResourceLocation.fromNamespaceAndPath("client-tools", "furina_doll"),
				modContainer,
				Component.translatable("resourcePack.client-tools.furina_doll.name"),
				ResourcePackActivationType.ALWAYS_ENABLED
			);
		});

		// Register client commands
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			CtimerCommand.register(dispatcher);
			CchatCommand.register(dispatcher);
			CcraftCommand.register(dispatcher);
			CflyCommand.register(dispatcher);
			CbuyCommand.register(dispatcher);
			CplacementCommand.register(dispatcher);
			CsellCommand.register(dispatcher);
			CsweepCommand.register(dispatcher);
			CsequenceCommand.register(dispatcher);
				CdollCommand.register(dispatcher);
				CbowCommand.register(dispatcher);
				CriptideCommand.register(dispatcher);
		});

		// Register per-tick callback to drive timers and crafting executor
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			TimerManager.tick(client);
			CraftingExecutor.getInstance().tick(client);
			SweepExecutor.getInstance().tick(client);
			SequenceExecutor.getInstance().tick(client);
			ShopExecutor.getInstance().tick(client);
			CcraftHighlightRenderer.tick();
			SweepHighlightRenderer.tick();
				BowTrajectoryRenderer.tick();
		});

		// Register world render callback for block highlight overlay
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context -> {
			CcraftHighlightRenderer.render(context);
			SweepHighlightRenderer.render(context);
				BowTrajectoryRenderer.render(context);
		});

		// Auto-show highlight on world join when positions are already configured
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			if (CcraftState.getInputPos() != null || CcraftState.getOutputPos() != null) {
				CcraftHighlightRenderer.trigger();
			}

			// Remind about unfinished sweep after a short delay
			// (so the player has fully loaded into the world)
			if (SweepState.isUnfinished()) {
				int savedStation = SweepState.getSavedStationIndex();
				client.execute(() -> {
					if (client.player != null) {
						client.player.displayClientMessage(
							Component.translatable("client-tools.csweep.reconnect_reminder",
								savedStation + 1), false);
					}
				});
				SweepState.clearUnfinished();
			}
		});

		// Auto-pause sweep on disconnect
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			SweepExecutor.getInstance().handleDisconnect();
		});
	}
}
