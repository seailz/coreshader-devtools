package com.seailz.csdt.client.screen;

import com.seailz.csdt.client.service.ClientLogTailService;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public final class LogViewerScreen extends Screen {

    private static final long REFRESH_INTERVAL_NANOS = 250_000_000L;
    private static final int LINE_HEIGHT = 10;

    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();

    private Button followButton;
    private boolean followTail = true;
    private int scrollRows;
    private String status = "Loading...";

    public LogViewerScreen(Screen parent) {
        super(Component.translatable("screen.coreshader-devtools.log_viewer"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int bottomY = this.height - 30;

        this.addRenderableWidget(Button.builder(Component.translatable("button.coreshader-devtools.refresh_logs"), button -> this.refreshLogs(true))
                .bounds(centerX - 154, bottomY, 90, 20)
                .build());
        this.followButton = this.addRenderableWidget(Button.builder(followLabel(), button -> {
            this.followTail = !this.followTail;
            this.followButton.setMessage(followLabel());
            if (this.followTail) {
                this.scrollRows = maxScrollRows();
            }
        }).bounds(centerX - 54, bottomY, 98, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> this.onClose())
                .bounds(centerX + 54, bottomY, 100, 20)
                .build());

        this.refreshLogs(true);
    }

    @Override
    public void tick() {
        if (ClientLogTailService.shouldRefresh(REFRESH_INTERVAL_NANOS)) {
            this.refreshLogs(false);
        }
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
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        this.followTail = false;
        this.followButton.setMessage(followLabel());
        this.scrollRows = Math.clamp(this.scrollRows - (int) Math.signum(scrollY) * 3, 0, maxScrollRows());
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        switch (event.key()) {
            case GLFW.GLFW_KEY_END -> {
                this.followTail = true;
                this.followButton.setMessage(followLabel());
                this.scrollRows = maxScrollRows();
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                this.followTail = false;
                this.followButton.setMessage(followLabel());
                this.scrollRows = 0;
                return true;
            }
            case GLFW.GLFW_KEY_PAGE_DOWN -> {
                this.followTail = false;
                this.followButton.setMessage(followLabel());
                this.scrollRows = Math.clamp(this.scrollRows + visibleRowCount(), 0, maxScrollRows());
                return true;
            }
            case GLFW.GLFW_KEY_PAGE_UP -> {
                this.followTail = false;
                this.followButton.setMessage(followLabel());
                this.scrollRows = Math.clamp(this.scrollRows - visibleRowCount(), 0, maxScrollRows());
                return true;
            }
            default -> {
                return super.keyPressed(event);
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, this.width, this.height, 0xB010141A);
        renderPanels(guiGraphics);
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.centeredText(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);
        guiGraphics.centeredText(this.font, Component.literal(this.status), this.width / 2, 30, 0xFFA8C2D8);
        guiGraphics.centeredText(this.font, Component.literal("Latest 200 lines from logs/latest.log"), this.width / 2, 42, 0xFFB8C7D9);

        renderLogRows(guiGraphics);
        renderFooter(guiGraphics);
    }

    private void refreshLogs(boolean force) {
        boolean stickToBottom = this.followTail || this.scrollRows >= maxScrollRows();
        ClientLogTailService.Snapshot snapshot = ClientLogTailService.refresh(force);
        this.status = snapshot.status();
        rebuildRows(snapshot.lines());
        if (stickToBottom) {
            this.scrollRows = maxScrollRows();
        } else {
            this.scrollRows = Math.clamp(this.scrollRows, 0, maxScrollRows());
        }
    }

    private void rebuildRows(List<String> lines) {
        this.rows.clear();
        int maxWidth = logPanelWidth() - 18;
        for (String line : lines) {
            addWrappedRow(line == null ? "" : line, maxWidth);
        }
        if (this.rows.isEmpty()) {
            this.rows.add(new Row("<no log lines>", 0xFF9AA8B8));
        }
    }

    private void addWrappedRow(String text, int maxWidth) {
        String remaining = text;
        int color = colorFor(text);
        if (remaining.isEmpty()) {
            this.rows.add(new Row("", color));
            return;
        }
        while (!remaining.isEmpty()) {
            String fitting = this.font.plainSubstrByWidth(remaining, maxWidth);
            if (fitting.isEmpty()) {
                fitting = remaining.substring(0, 1);
            }
            this.rows.add(new Row(fitting, color));
            remaining = remaining.substring(fitting.length());
        }
    }

    private void renderPanels(GuiGraphicsExtractor guiGraphics) {
        int left = logPanelLeft();
        int top = 60;
        int width = logPanelWidth();
        int height = logPanelHeight();

        guiGraphics.fill(left, top, left + width, top + height, 0x2216202A);
        guiGraphics.outline(left, top, width, height, 0x66445A6B);
    }

    private void renderLogRows(GuiGraphicsExtractor guiGraphics) {
        int left = logPanelLeft() + 8;
        int top = 68;
        int width = logPanelWidth() - 16;
        int height = logPanelHeight() - 16;
        int y = top;
        int end = Math.min(this.rows.size(), this.scrollRows + visibleRowCount() + 1);

        guiGraphics.enableScissor(left, top, left + width, top + height);
        for (int index = this.scrollRows; index < end; index++) {
            Row row = this.rows.get(index);
            guiGraphics.text(this.font, row.text(), left, y, row.color(), false);
            y += LINE_HEIGHT;
            if (y > top + height) {
                break;
            }
        }
        guiGraphics.disableScissor();
    }

    private void renderFooter(GuiGraphicsExtractor guiGraphics) {
        String footer = "Rows %d-%d / %d%s".formatted(
                this.rows.isEmpty() ? 0 : this.scrollRows + 1,
                Math.min(this.rows.size(), this.scrollRows + visibleRowCount()),
                this.rows.size(),
                this.followTail ? " | Follow tail" : ""
        );
        guiGraphics.centeredText(this.font, Component.literal(footer), this.width / 2, this.height - 44, 0xFFB8C7D9);
    }

    private int logPanelLeft() {
        return this.width / 2 - logPanelWidth() / 2;
    }

    private int logPanelWidth() {
        return Math.min(760, this.width - 40);
    }

    private int logPanelHeight() {
        return this.height - 104;
    }

    private int visibleRowCount() {
        return Math.max(1, (logPanelHeight() - 16) / LINE_HEIGHT);
    }

    private int maxScrollRows() {
        return Math.max(0, this.rows.size() - visibleRowCount());
    }

    private Component followLabel() {
        return Component.translatable(
                this.followTail ? "button.coreshader-devtools.follow_tail_on" : "button.coreshader-devtools.follow_tail_off"
        );
    }

    private static int colorFor(String line) {
        String upper = line.toUpperCase();
        if (upper.contains("ERROR")) {
            return 0xFFFF8A80;
        }
        if (upper.contains("WARN")) {
            return 0xFFFFD166;
        }
        if (upper.contains("DEBUG")) {
            return 0xFF7AA2F7;
        }
        return 0xFFE6EEF7;
    }

    private record Row(String text, int color) {
    }
}
