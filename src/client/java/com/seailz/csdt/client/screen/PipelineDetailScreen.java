package com.seailz.csdt.client.screen;

import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.seailz.csdt.client.service.ClientToastService;
import com.seailz.csdt.client.service.PipelineInventoryService;
import com.seailz.csdt.client.service.ShaderInventoryService;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public final class PipelineDetailScreen extends Screen {

    private static final int LINE_HEIGHT = 11;

    private final Screen parent;
    private final ShaderInventoryScreen.ViewMode returnViewMode;
    private final int returnPage;
    private final String returnSearchQuery;
    private final PipelineInventoryService.PipelineEntry pipeline;
    private final List<Row> rows = new ArrayList<>();
    private int scrollRows;

    public PipelineDetailScreen(Screen parent, ShaderInventoryScreen.ViewMode returnViewMode, int returnPage, PipelineInventoryService.PipelineEntry pipeline) {
        this(parent, returnViewMode, returnPage, pipeline, "");
    }

    public PipelineDetailScreen(Screen parent, ShaderInventoryScreen.ViewMode returnViewMode, int returnPage, PipelineInventoryService.PipelineEntry pipeline, String returnSearchQuery) {
        super(Component.literal("Pipeline"));
        this.parent = parent;
        this.returnViewMode = returnViewMode;
        this.returnPage = returnPage;
        this.returnSearchQuery = returnSearchQuery == null ? "" : returnSearchQuery;
        this.pipeline = pipeline;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        buildRows();

        this.addRenderableWidget(Button.builder(Component.literal("Vertex"), button -> openShader(false))
                .bounds(centerX - 154, this.height - 32, 70, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.literal("Fragment"), button -> openShader(true))
                .bounds(centerX - 74, this.height - 32, 78, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.literal("Copy"), button -> {
            this.minecraft.keyboardHandler.setClipboard(PipelineInventoryService.describe(this.pipeline));
            ClientToastService.showInfo("Pipeline copied", shortId(this.pipeline.location()));
        }).bounds(centerX + 14, this.height - 32, 70, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> this.onClose())
                .bounds(centerX + 94, this.height - 32, 100, 20)
                .build());
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(new ShaderInventoryScreen(this.parent, this.returnViewMode, this.returnPage, this.returnSearchQuery));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        this.scrollRows = Math.clamp(this.scrollRows - (int) Math.signum(scrollY) * 2, 0, maxScrollRows());
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, this.width, this.height, 0xB010141A);
        renderPanels(guiGraphics);
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

        String location = shortId(this.pipeline.location());
        guiGraphics.centeredText(this.font, Component.literal(location), this.width / 2, 16, 0xFFFFFFFF);
        guiGraphics.centeredText(this.font, Component.literal("RenderPipeline bindings and state"), this.width / 2, 34, 0xFFA8C2D8);

        renderShaderCards(guiGraphics);
        renderBody(guiGraphics);
    }

    private void renderPanels(GuiGraphicsExtractor guiGraphics) {
        int panelWidth = Math.min(760, this.width - 60);
        int left = this.width / 2 - panelWidth / 2;
        int top = 72;
        int height = this.height - 120;

        guiGraphics.fill(left, top, left + panelWidth, top + 74, 0x33192229);
        guiGraphics.outline(left, top, panelWidth, 74, 0x88445A6B);
        guiGraphics.fill(left, top + 84, left + panelWidth, top + height, 0x2216202A);
        guiGraphics.outline(left, top + 84, panelWidth, height - 84, 0x66445A6B);
    }

    private void renderShaderCards(GuiGraphicsExtractor guiGraphics) {
        int panelWidth = Math.min(760, this.width - 60);
        int left = this.width / 2 - panelWidth / 2;
        int top = 84;
        int cardWidth = (panelWidth - 30) / 2;

        renderCard(guiGraphics, left + 10, top, cardWidth, "Vertex Shader", shortId(this.pipeline.pipeline().getVertexShader()), 0xFF4CC9F0);
        renderCard(guiGraphics, left + 20 + cardWidth, top, cardWidth, "Fragment Shader", shortId(this.pipeline.pipeline().getFragmentShader()), 0xFFFFD166);

        int badgeY = top + 40;
        renderBadge(guiGraphics, left + 12, badgeY, this.pipeline.pipeline().isCull() ? "Cull On" : "Cull Off", this.pipeline.pipeline().isCull() ? 0xFF7FB685 : 0xFFF4A261);
        renderBadge(guiGraphics, left + 100, badgeY, this.pipeline.pipeline().wantsDepthTexture() ? "Depth Read" : "No Depth", 0xFF7AA2F7);
        renderBadge(guiGraphics, left + 205, badgeY, String.valueOf(this.pipeline.pipeline().getPolygonMode()), 0xFFC0CAD5);
    }

    private void renderBody(GuiGraphicsExtractor guiGraphics) {
        int panelWidth = Math.min(760, this.width - 60);
        int left = this.width / 2 - panelWidth / 2;
        int top = 166;
        int innerLeft = left + 16;
        int width = panelWidth - 32;
        int height = this.height - 208;
        int y = top + 8;
        int end = Math.min(this.rows.size(), this.scrollRows + visibleRowCount() + 2);

        guiGraphics.enableScissor(innerLeft, top + 4, innerLeft + width - 8, top + height - 4);
        for (int i = this.scrollRows; i < end; i++) {
            Row row = this.rows.get(i);
            if (row.isSeparator()) {
                guiGraphics.horizontalLine(innerLeft, innerLeft + width - 24, y + 4, 0x55445A6B);
                y += 8;
                continue;
            }
            if (y + LINE_HEIGHT >= top) {
                guiGraphics.text(this.font, row.text(), innerLeft + row.indent() * 12, y, row.color(), false);
            }
            y += LINE_HEIGHT;
            if (y > top + height) {
                break;
            }
        }
        guiGraphics.disableScissor();
    }

    private void renderCard(GuiGraphicsExtractor guiGraphics, int x, int y, int width, String title, String value, int accent) {
        guiGraphics.fill(x, y, x + width, y + 30, 0x44223038);
        guiGraphics.outline(x, y, width, 30, 0x88445A6B);
        guiGraphics.text(this.font, title, x + 8, y + 6, accent, false);
        guiGraphics.text(this.font, value, x + 8, y + 17, 0xFFE6EEF7, false);
    }

    private void renderBadge(GuiGraphicsExtractor guiGraphics, int x, int y, String text, int color) {
        int width = this.font.width(text) + 12;
        guiGraphics.fill(x, y, x + width, y + 14, 0x33212D38);
        guiGraphics.outline(x, y, width, 14, color);
        guiGraphics.text(this.font, text, x + 6, y + 3, color, false);
    }

    private void buildRows() {
        this.rows.clear();
        var pipeline = this.pipeline.pipeline();

        addHeader("Geometry");
        addList("Vertex Formats", List.of(pipeline.getVertexFormatBindings()).stream().map(String::valueOf).toList());
        addDetail("Primitive Topology", String.valueOf(pipeline.getPrimitiveTopology()));
        addDetail("Polygon Mode", String.valueOf(pipeline.getPolygonMode()));

        addHeader("Resources");
        addList("Samplers", BindGroupLayout.flattenSamplers(pipeline.getBindGroupLayouts()).stream().map(String::valueOf).toList());
        addList("Uniforms", BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts()).stream().map(String::valueOf).toList());
        addList("Defines", List.of(String.valueOf(pipeline.getShaderDefines())));

        addHeader("Targets");
        addDetail("Color Target", String.valueOf(pipeline.getColorTargetState()));
        addDetail("Depth/Stencil", pipeline.getDepthStencilState() == null ? "<none>" : String.valueOf(pipeline.getDepthStencilState()));
    }

    private void addHeader(String text) {
        if (!this.rows.isEmpty()) {
            this.rows.add(Row.separator());
        }
        this.rows.add(new Row(text, 0xFFFFD166, 0, false));
    }

    private void addDetail(String label, String value) {
        this.rows.add(new Row(label, 0xFF7AA2F7, 0, false));
        addWrappedRows(value, 1, 0xFFE6EEF7);
    }

    private void addList(String label, List<String> values) {
        this.rows.add(new Row(label, 0xFF7AA2F7, 0, false));
        if (values.isEmpty() || (values.size() == 1 && "<none>".equals(values.getFirst()))) {
            this.rows.add(new Row("<none>", 0xFF9AA8B8, 1, false));
            return;
        }
        for (String value : values) {
            addWrappedRows("* " + value, 1, 0xFFE6EEF7);
        }
    }

    private void addWrappedRows(String text, int indent, int color) {
        int maxWidth = Math.min(760, this.width - 60) - 64 - indent * 12;
        String remaining = text;
        while (!remaining.isEmpty()) {
            String fitting = this.font.plainSubstrByWidth(remaining, maxWidth);
            if (fitting.isEmpty()) {
                fitting = remaining.substring(0, 1);
            }
            this.rows.add(new Row(fitting, color, indent, false));
            remaining = remaining.substring(fitting.length());
        }
    }

    private int visibleRowCount() {
        return Math.max(1, (this.height - 216) / LINE_HEIGHT);
    }

    private int maxScrollRows() {
        return Math.max(0, this.rows.size() - visibleRowCount() + 2);
    }

    private void openShader(boolean fragment) {
        ShaderInventoryService.ShaderResourceEntry entry = ShaderInventoryService.findPipelineShaderEntry(
                fragment ? this.pipeline.pipeline().getFragmentShader() : this.pipeline.pipeline().getVertexShader(),
                fragment
        );
        if (entry == null) {
            ClientToastService.showInfo("Shader not found", fragment ? "Fragment shader entry missing" : "Vertex shader entry missing");
            return;
        }
        this.minecraft.gui.setScreen(new ShaderResourceDetailScreen(this.parent, this.returnViewMode, this.returnPage, entry, this.returnSearchQuery));
    }

    private static String shortId(Object value) {
        String text = String.valueOf(value);
        return text.startsWith("minecraft:") ? text.substring("minecraft:".length()) : text;
    }

    private record Row(String text, int color, int indent, boolean isSeparator) {
        private static Row separator() {
            return new Row("", 0, 0, true);
        }
    }
}
