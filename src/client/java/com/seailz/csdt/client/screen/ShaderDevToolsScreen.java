package com.seailz.csdt.client.screen;

import com.seailz.csdt.client.service.ShaderDebugInfoService;
import com.seailz.csdt.client.service.ShaderReloadService;
import com.seailz.csdt.client.state.GlobalsOverrideState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class ShaderDevToolsScreen extends Screen {

    private final Screen parent;

    public ShaderDevToolsScreen(Screen parent) {
        super(Component.translatable("screen.coreshader-devtools.shader_dev_tools"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = this.width / 2 - 155;
        int top = 34;
        int buttonWidth = 150;
        int buttonHeight = 20;
        int gap = 24;

        this.addRenderableWidget(Button.builder(Component.translatable("button.coreshader-devtools.shader_inventory"), button ->
                this.minecraft.gui.setScreen(new ShaderInventoryScreen(this))
        ).bounds(left, top, buttonWidth, buttonHeight).build());
        top += gap;
        this.addRenderableWidget(Button.builder(Component.translatable("button.coreshader-devtools.globals_overrides"), button ->
                this.minecraft.gui.setScreen(new GlobalsOverrideScreen(this))
        ).bounds(left, top, buttonWidth, buttonHeight).build());
        top += gap;
        this.addRenderableWidget(Button.builder(Component.translatable("button.coreshader-devtools.log_viewer"), button ->
                this.minecraft.gui.setScreen(new LogViewerScreen(this))
        ).bounds(left, top, buttonWidth, buttonHeight).build());
        top += gap;

        top += 24;

        this.addRenderableWidget(Button.builder(Component.translatable("button.coreshader-devtools.reload_core"), button ->
                ShaderReloadService.reloadCoreShadersOnly()
        ).bounds(left, top, buttonWidth, buttonHeight).build());
        top += gap;

        this.addRenderableWidget(Button.builder(Component.translatable("button.coreshader-devtools.reload_post"), button ->
                ShaderReloadService.reloadPostShadersOnly()
        ).bounds(left, top, buttonWidth, buttonHeight).build());
        top += gap;

        this.addRenderableWidget(Button.builder(Component.translatable("button.coreshader-devtools.reload_all"), button ->
                ShaderReloadService.reloadAllShadersFromHub()
        ).bounds(left, top, buttonWidth, buttonHeight).build());
        top += gap;

        top += 24;

        this.addRenderableWidget(Button.builder(Component.translatable("button.coreshader-devtools.reset_overrides"), button ->
                GlobalsOverrideState.getInstance().clearAll()
        ).bounds(left, top, buttonWidth, buttonHeight).build());

        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> this.onClose())
                .bounds(this.width / 2 - 50, this.height - 28, 100, 20)
                .build());
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, this.width, this.height, 0xB010141A);
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);

        int debugLeft = this.width / 2 + 10;
        int y = 36;
        List<String> lines = ShaderDebugInfoService.buildDebugLines(Minecraft.getInstance());
        for (String line : lines) {
            int color = line.startsWith("[") ? 0xFFFFD166 : 0xFFE0E0E0;
            guiGraphics.text(this.font, line, debugLeft, y, color, false);
            y += 10;
        }
    }
}
