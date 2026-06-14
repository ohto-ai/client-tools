package indi.ohtoai.tool.client_tools.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import indi.ohtoai.tool.client_tools.client.command.CchatCommand;
import indi.ohtoai.tool.client_tools.client.command.CcraftCommand;
import indi.ohtoai.tool.client_tools.client.command.CtimerCommand;
import indi.ohtoai.tool.client_tools.client.craft.CcraftHighlightRenderer;
import indi.ohtoai.tool.client_tools.client.craft.CcraftState;
import indi.ohtoai.tool.client_tools.client.craft.CraftingExecutor;
import indi.ohtoai.tool.client_tools.client.timer.TimerManager;

public class ClientToolsClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Register client commands
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			CtimerCommand.register(dispatcher);
			CchatCommand.register(dispatcher);
			CcraftCommand.register(dispatcher);
		});

		// Register per-tick callback to drive timers and crafting executor
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			TimerManager.tick(client);
			CraftingExecutor.getInstance().tick(client);
			CcraftHighlightRenderer.tick();
		});

		// Register world render callback for block highlight overlay
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(CcraftHighlightRenderer::render);

		// Auto-show highlight on world join when positions are already configured
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			if (CcraftState.getInputPos() != null || CcraftState.getOutputPos() != null) {
				CcraftHighlightRenderer.trigger();
			}
		});
	}
}
