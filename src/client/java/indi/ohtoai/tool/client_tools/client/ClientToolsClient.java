package indi.ohtoai.tool.client_tools.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import indi.ohtoai.tool.client_tools.client.command.CchatCommand;
import indi.ohtoai.tool.client_tools.client.command.CcraftCommand;
import indi.ohtoai.tool.client_tools.client.command.CtimerCommand;
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
		});
	}
}
