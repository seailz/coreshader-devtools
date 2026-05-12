package com.seailz.csdt.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.seailz.csdt.client.screen.ShaderDevToolsScreen;
import com.seailz.csdt.client.service.ForcedPostEffectService;
import com.seailz.csdt.client.service.McpControlServerService;
import com.seailz.csdt.client.service.ShaderReloadService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class CoreShaderDevToolsClient implements ClientModInitializer {

    private static KeyMapping reloadCoreShadersKey;
    private static KeyMapping openShaderDevToolsMenuKey;

    @Override
    public void onInitializeClient() {
        McpControlServerService.start();

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.parse("coreshader-devtools:main"));
        reloadCoreShadersKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.coreshader-devtools.reload_core_shaders",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                category
        ));
        openShaderDevToolsMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.coreshader-devtools.shader_dev_tools_menu",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (reloadCoreShadersKey.consumeClick()) {
                ShaderReloadService.reloadCoreShadersOnly();
            }

            while (openShaderDevToolsMenuKey.consumeClick()) {
                client.gui.setScreen(new ShaderDevToolsScreen(client.gui.screen()));
            }

            ForcedPostEffectService.applyForcedPostEffect();
        });
    }
}
