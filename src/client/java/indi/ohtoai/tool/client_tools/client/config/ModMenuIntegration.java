package indi.ohtoai.tool.client_tools.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu integration entry point.
 * <p>
 * Registered in fabric.mod.json under the {@code modmenu} entrypoint,
 * this class provides a config screen factory that ModMenu calls
 * when the user clicks the config button on the mods list.
 */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ClientToolsConfigScreen::create;
    }
}
