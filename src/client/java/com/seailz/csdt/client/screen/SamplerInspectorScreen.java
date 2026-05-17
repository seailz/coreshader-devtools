package com.seailz.csdt.client.screen;

import com.seailz.csdt.client.service.ClientToastService;
import com.seailz.csdt.client.service.SamplerInspectionService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

public final class SamplerInspectorScreen extends Screen {

    private static final int ENTRY_HEIGHT = 36;
    private static final int LINE_HEIGHT = 11;

    private final Screen parent;
    private final List<Button> entryButtons = new ArrayList<>();
    private final List<SamplerListEntry> visibleListEntries = new ArrayList<>();
    private List<SamplerInspectionService.SamplerBindingSnapshot> entries = List.of();
    private String selectedId;
    private String selectedGroupKey;
    private int page;
    private int scrollRows;
    private Button previousPageButton;
    private Button nextPageButton;
    private Button groupBackButton;
    private Button previewModeButton;
    private EditBox searchBox;
    private EditBox xBox;
    private EditBox yBox;
    private SamplerInspectionService.ReadbackResult lastReadback;
    private String searchQuery = "";
    private boolean useNearestPreview = true;

    public SamplerInspectorScreen(Screen parent) {
        super(Component.translatable("screen.coreshader-devtools.sampler_inspector"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int panelWidth = panelWidth();
        int left = this.width / 2 - panelWidth / 2;
        int listWidth = Math.min(300, panelWidth / 2 - 20);
        int listLeft = left + 10;
        int startY = listStartY();

        this.entryButtons.clear();
        for (int i = 0; i < pageSize(); i++) {
            final int index = i;
            Button entryButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> {
                if (index < this.visibleListEntries.size()) {
                    openListEntry(this.visibleListEntries.get(index));
                }
            }).bounds(listLeft, startY + i * ENTRY_HEIGHT, listWidth, 26).build());
            this.entryButtons.add(entryButton);
        }

        int controlsLeft = left + listWidth + 34;
        this.searchBox = this.addRenderableWidget(new EditBox(this.font, listLeft, 64, listWidth, 20, Component.literal("Search")));
        this.searchBox.setHint(Component.literal("Search samplers"));
        this.searchBox.setValue(this.searchQuery);
        this.searchBox.setResponder(text -> {
            this.searchQuery = text == null ? "" : text;
            this.page = 0;
            refreshVisibleEntries();
        });

        this.xBox = this.addRenderableWidget(new EditBox(this.font, controlsLeft + 18, 58, 54, 20, Component.literal("x")));
        this.yBox = this.addRenderableWidget(new EditBox(this.font, controlsLeft + 96, 58, 54, 20, Component.literal("y")));
        this.xBox.setValue("0");
        this.yBox.setValue("0");

        this.addRenderableWidget(Button.builder(Component.literal("Refresh"), button -> refreshEntries())
                .bounds(listLeft + listWidth - 72, 42, 72, 20)
                .build());
        this.groupBackButton = this.addRenderableWidget(Button.builder(Component.literal("Groups"), button -> {
            this.selectedGroupKey = null;
            this.page = 0;
            refreshVisibleEntries();
        }).bounds(listLeft, 42, 68, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Read"), button -> readPixel())
                .bounds(controlsLeft + 162, 58, 62, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.literal("Copy 16x16"), button -> copyRegion())
                .bounds(controlsLeft + 234, 58, 92, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.literal("Copy Result"), button -> copyResult())
                .bounds(controlsLeft + 336, 58, 98, 20)
                .build());
        this.previewModeButton = this.addRenderableWidget(Button.builder(previewModeLabel(), button -> {
            this.useNearestPreview = !this.useNearestPreview;
            updatePreviewModeButton();
        }).bounds(controlsLeft + 444, 58, 108, 20).build());

        this.previousPageButton = this.addRenderableWidget(Button.builder(Component.translatable("button.coreshader-devtools.previous_page"), button -> {
            if (this.page > 0) {
                this.page--;
                refreshVisibleEntries();
            }
        }).bounds(left + 10, this.height - 30, 100, 20).build());
        this.nextPageButton = this.addRenderableWidget(Button.builder(Component.translatable("button.coreshader-devtools.next_page"), button -> {
            this.page++;
            refreshVisibleEntries();
        }).bounds(left + 120, this.height - 30, 100, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> this.onClose())
                .bounds(this.width / 2 + panelWidth / 2 - 110, this.height - 30, 100, 20)
                .build());

        updatePreviewModeButton();
        refreshEntries();
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
        this.scrollRows = Math.clamp(this.scrollRows - (int) Math.signum(scrollY) * 3, 0, maxScrollRows());
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, this.width, this.height, 0xB010141A);
        renderPanels(guiGraphics);
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
        renderList(guiGraphics);
        renderDetails(guiGraphics);
    }

    private void refreshEntries() {
        this.entries = SamplerInspectionService.snapshotBindings();
        if (this.selectedGroupKey != null && this.entries.stream().noneMatch(entry -> groupKey(entry).equals(this.selectedGroupKey))) {
            this.selectedGroupKey = null;
        }
        if (this.entries.stream().noneMatch(entry -> entry.id().equals(this.selectedId))) {
            this.selectedId = this.entries.stream()
                    .filter(SamplerInspectionService.SamplerBindingSnapshot::lightmapLike)
                    .findFirst()
                    .or(() -> this.entries.stream().findFirst())
                    .map(SamplerInspectionService.SamplerBindingSnapshot::id)
                    .orElse(null);
            this.lastReadback = null;
            setDefaultCoordinate(selected());
        }
        this.page = Math.min(this.page, totalPages() - 1);
        refreshVisibleEntries();
    }

    private void refreshVisibleEntries() {
        List<SamplerListEntry> listEntries = listEntries();
        this.page = Math.min(this.page, totalPages(listEntries.size()) - 1);
        int start = this.page * pageSize();
        int end = Math.min(start + pageSize(), listEntries.size());
        this.visibleListEntries.clear();
        if (start < end) {
            this.visibleListEntries.addAll(listEntries.subList(start, end));
        }

        for (int i = 0; i < this.entryButtons.size(); i++) {
            Button button = this.entryButtons.get(i);
            boolean visible = i < this.visibleListEntries.size();
            button.visible = visible;
            button.active = visible;
            if (visible) {
                button.setMessage(Component.empty());
            }
        }

        this.previousPageButton.active = this.page > 0;
        this.nextPageButton.active = this.page + 1 < totalPages(listEntries.size());
        this.groupBackButton.visible = this.selectedGroupKey != null;
        this.groupBackButton.active = this.selectedGroupKey != null;
    }

    private void select(SamplerInspectionService.SamplerBindingSnapshot entry) {
        this.selectedId = entry.id();
        this.scrollRows = 0;
        this.lastReadback = null;
        setDefaultCoordinate(entry);
    }

    private void setDefaultCoordinate(SamplerInspectionService.SamplerBindingSnapshot entry) {
        if (entry == null || this.xBox == null || this.yBox == null) {
            return;
        }
        int defaultX = entry.lightmapLike() && entry.width() > 15 ? 15 : 0;
        this.xBox.setValue(String.valueOf(defaultX));
        this.yBox.setValue("0");
    }

    private void readPixel() {
        SamplerInspectionService.SamplerBindingSnapshot entry = selected();
        if (entry == null) {
            return;
        }
        Integer x = parseInteger(this.xBox.getValue());
        Integer y = parseInteger(this.yBox.getValue());
        if (x == null || y == null) {
            ClientToastService.showInfo("Invalid sampler coordinate", "x and y must be integers");
            return;
        }
        this.scrollRows = 0;
        this.lastReadback = SamplerInspectionService.readPixel(entry.id(), x, y);
    }

    private void copyRegion() {
        SamplerInspectionService.SamplerBindingSnapshot entry = selected();
        if (entry == null) {
            return;
        }
        this.scrollRows = 0;
        this.lastReadback = SamplerInspectionService.readRegion(entry.id(), 0, 0, Math.min(16, entry.width()), Math.min(16, entry.height()));
        copyResult();
    }

    private void copyResult() {
        if (this.lastReadback == null) {
            readPixel();
        }
        if (this.lastReadback == null) {
            return;
        }
        Minecraft.getInstance().keyboardHandler.setClipboard(this.lastReadback.dumpText());
        ClientToastService.showInfo(this.lastReadback.success() ? "Sampler value copied" : "Sampler readback copied", this.lastReadback.message());
    }

    private void renderPanels(GuiGraphicsExtractor guiGraphics) {
        int panelWidth = panelWidth();
        int left = this.width / 2 - panelWidth / 2;
        int top = 36;
        int height = this.height - 76;
        int listWidth = Math.min(300, panelWidth / 2 - 20);

        guiGraphics.fill(left, top, left + listWidth + 20, top + height, 0x2216202A);
        guiGraphics.outline(left, top, listWidth + 20, height, 0x66445A6B);
        guiGraphics.fill(left + listWidth + 28, top, left + panelWidth, top + height, 0x2216202A);
        guiGraphics.outline(left + listWidth + 28, top, panelWidth - listWidth - 28, height, 0x66445A6B);
    }

    private void renderList(GuiGraphicsExtractor guiGraphics) {
        int panelWidth = panelWidth();
        int left = this.width / 2 - panelWidth / 2;
        int listWidth = Math.min(300, panelWidth / 2 - 20);
        String title = this.selectedGroupKey == null ? "Captured samplers" : shortGroupTitle(this.selectedGroupKey);
        int titleLeft = this.selectedGroupKey == null ? left + 12 : left + 84;
        guiGraphics.text(this.font, this.font.plainSubstrByWidth(title, listWidth - (titleLeft - left) - 8), titleLeft, 50, 0xFFFFD166, false);

        for (int i = 0; i < this.visibleListEntries.size(); i++) {
            SamplerListEntry entry = this.visibleListEntries.get(i);
            Button button = this.entryButtons.get(i);
            int tint = entry.selected(this.selectedId, this.selectedGroupKey) ? 0x5532546B : entry.lightmapLike() ? 0x44FFD166 : 0x2216202A;
            guiGraphics.fill(button.getX(), button.getY(), button.getX() + button.getWidth(), button.getY() + button.getHeight(), tint);
            guiGraphics.outline(button.getX(), button.getY(), button.getWidth(), button.getHeight(), entry.selected(this.selectedId, this.selectedGroupKey) ? 0xFF7AA2F7 : 0x55445A6B);
            guiGraphics.text(this.font, this.font.plainSubstrByWidth(entry.title(), listWidth - 24), button.getX() + 9, button.getY() + 5, entry.lightmapLike() ? 0xFFFFD166 : 0xFFE6EEF7, false);
            guiGraphics.text(this.font, this.font.plainSubstrByWidth(entry.subtitle(), listWidth - 24), button.getX() + 9, button.getY() + 16, 0xFFB8C7D9, false);
        }

        List<SamplerListEntry> listEntries = listEntries();
        if (this.entries.isEmpty()) {
            guiGraphics.text(this.font, "No sampler bindings captured", left + 12, 104, 0xFFD7E3F0, false);
        } else if (listEntries.isEmpty()) {
            guiGraphics.text(this.font, "No matching samplers", left + 12, 104, 0xFFD7E3F0, false);
        } else {
            guiGraphics.text(this.font, "Page %d / %d".formatted(this.page + 1, totalPages(listEntries.size())), left + 12, this.height - 52, 0xFFD7E3F0, false);
        }
    }

    private void renderDetails(GuiGraphicsExtractor guiGraphics) {
        SamplerInspectionService.SamplerBindingSnapshot entry = selected();
        int panelWidth = panelWidth();
        int left = this.width / 2 - panelWidth / 2;
        int listWidth = Math.min(300, panelWidth / 2 - 20);
        int detailLeft = left + listWidth + 44;
        int detailTop = 96;
        int detailWidth = panelWidth - listWidth - 60;

        guiGraphics.text(this.font, "x", detailLeft, 64, 0xFFA8C2D8, false);
        guiGraphics.text(this.font, "y", detailLeft + 78, 64, 0xFFA8C2D8, false);

        if (entry == null) {
            guiGraphics.text(this.font, "Select a captured sampler", detailLeft, detailTop, 0xFFD7E3F0, false);
            return;
        }

        int previewSize = Math.min(180, Math.max(96, detailWidth / 3));
        int previewBottom = renderPreview(guiGraphics, entry, detailLeft, detailTop, previewSize);
        int infoLeft = detailLeft + previewSize + 14;
        int infoWidth = detailWidth - previewSize - 22;
        int y = detailTop;
        if (infoWidth < 220) {
            infoLeft = detailLeft;
            infoWidth = detailWidth - 12;
            y = previewBottom + 10;
        }
        y = text(guiGraphics, entry.shortTitle(), infoLeft, y, infoWidth, 0xFFFFD166);
        y = text(guiGraphics, "Pipeline: " + entry.pipelineLocation(), infoLeft, y, infoWidth, 0xFFE6EEF7);
        y = text(guiGraphics, "Shaders: " + entry.vertexShader() + " -> " + entry.fragmentShader(), infoLeft, y, infoWidth, 0xFFB8C7D9);
        y = text(guiGraphics, "Texture: %s %dx%d %s pixel=%d usage=0x%X".formatted(entry.textureLabel(), entry.width(), entry.height(), entry.format(), entry.pixelSize(), entry.usage()), infoLeft, y, infoWidth, 0xFFE6EEF7);
        y = text(guiGraphics, "View: mip=%d levels=%d depth/layers=%d".formatted(entry.baseMipLevel(), entry.viewMipLevels(), entry.depthOrLayers()), infoLeft, y, infoWidth, 0xFFB8C7D9);
        y = text(guiGraphics, "Sampler: " + entry.samplerState(), infoLeft, y, infoWidth, 0xFFB8C7D9);
        y = text(guiGraphics, "State: " + (entry.textureClosed() ? "closed" : "live") + " | last bind " + entry.ageMillis() + " ms ago" + (entry.copySrc() ? "" : " | no copy-src"), infoLeft, y, infoWidth, entry.copySrc() ? 0xFFB8C7D9 : 0xFFF4A261);
        y = Math.max(y + 8, previewBottom + 12);

        List<String> readbackLines = this.lastReadback == null ? List.of("No readback yet") : this.lastReadback.displayLines();
        int bottom = this.height - 42;
        guiGraphics.enableScissor(detailLeft, y, detailLeft + detailWidth - 8, bottom);
        int rowY = y;
        int end = Math.min(readbackLines.size(), this.scrollRows + visibleReadbackRows(y));
        for (int i = this.scrollRows; i < end; i++) {
            guiGraphics.text(this.font, this.font.plainSubstrByWidth(readbackLines.get(i), detailWidth - 16), detailLeft, rowY, this.lastReadback != null && !this.lastReadback.success() ? 0xFFF4A261 : 0xFFE6EEF7, false);
            rowY += LINE_HEIGHT;
        }
        guiGraphics.disableScissor();
    }

    private Component previewModeLabel() {
        return Component.literal(this.useNearestPreview ? "Prev: Nearest" : "Prev: Default");
    }

    private void updatePreviewModeButton() {
        if (this.previewModeButton != null) {
            this.previewModeButton.setMessage(previewModeLabel());
        }
    }

    private int renderPreview(GuiGraphicsExtractor guiGraphics, SamplerInspectionService.SamplerBindingSnapshot entry, int x, int y, int size) {
        guiGraphics.fill(x, y, x + size, y + size, 0x44223038);
        SamplerInspectionService.PreviewBinding preview = SamplerInspectionService.previewBinding(entry.id(), this.useNearestPreview);
        if (preview == null) {
            guiGraphics.outline(x, y, size, size, 0x88F4A261);
            guiGraphics.centeredText(this.font, Component.literal("Preview unavailable"), x + size / 2, y + size / 2 - 4, 0xFFF4A261);
            return y + size;
        }

        int drawWidth = size;
        int drawHeight = size;
        if (entry.width() > 0 && entry.height() > 0) {
            float scale = Math.min(size / (float) entry.width(), size / (float) entry.height());
            drawWidth = Math.max(1, Math.round(entry.width() * scale));
            drawHeight = Math.max(1, Math.round(entry.height() * scale));
        }
        int drawX = x + (size - drawWidth) / 2;
        int drawY = y + (size - drawHeight) / 2;
        guiGraphics.enableScissor(drawX, drawY, drawX + drawWidth, drawY + drawHeight);
        try {
            guiGraphics.blit(preview.view(), preview.sampler(), drawX, drawY, drawX + drawWidth, drawY + drawHeight, 0.0F, 1.0F, 0.0F, 1.0F);
        } finally {
            guiGraphics.disableScissor();
        }
        guiGraphics.outline(drawX, drawY, drawWidth, drawHeight, 0x88445A6B);
        renderCoordinateMarker(guiGraphics, entry, drawX, drawY, drawWidth, drawHeight);
        return y + size;
    }

    private void renderCoordinateMarker(GuiGraphicsExtractor guiGraphics, SamplerInspectionService.SamplerBindingSnapshot entry, int x, int y, int width, int height) {
        Integer selectedX = parseInteger(this.xBox.getValue());
        Integer selectedY = parseInteger(this.yBox.getValue());
        if (selectedX == null || selectedY == null || entry.width() <= 0 || entry.height() <= 0) {
            return;
        }
        if (selectedX < 0 || selectedY < 0 || selectedX >= entry.width() || selectedY >= entry.height()) {
            return;
        }
        int markerX = x + Math.clamp(Math.round((selectedX + 0.5F) * width / entry.width()), 0, Math.max(0, width - 1));
        int markerY = y + Math.clamp(Math.round((selectedY + 0.5F) * height / entry.height()), 0, Math.max(0, height - 1));
        guiGraphics.verticalLine(markerX, y, y + height - 1, 0xFFFFD166);
        guiGraphics.horizontalLine(x, x + width - 1, markerY, 0xFFFFD166);
    }

    private int text(GuiGraphicsExtractor guiGraphics, String text, int x, int y, int maxWidth, int color) {
        guiGraphics.text(this.font, this.font.plainSubstrByWidth(text, maxWidth), x, y, color, false);
        return y + LINE_HEIGHT;
    }

    private void openListEntry(SamplerListEntry entry) {
        if (entry.group()) {
            this.selectedGroupKey = entry.groupKey();
            this.page = 0;
            entry.firstSampler().ifPresent(this::select);
            refreshVisibleEntries();
            return;
        }
        select(entry.sampler());
    }

    private SamplerInspectionService.SamplerBindingSnapshot selected() {
        if (this.selectedId == null) {
            return null;
        }
        return this.entries.stream()
                .filter(entry -> entry.id().equals(this.selectedId))
                .findFirst()
                .orElseGet(() -> SamplerInspectionService.findSnapshot(this.selectedId));
    }

    private int pageSize() {
        return Math.max(1, (this.height - 152) / ENTRY_HEIGHT);
    }

    private int totalPages() {
        return totalPages(listEntries().size());
    }

    private int totalPages(int entryCount) {
        return Math.max(1, (entryCount + pageSize() - 1) / pageSize());
    }

    private int listStartY() {
        return 94;
    }

    private List<SamplerInspectionService.SamplerBindingSnapshot> filteredEntries() {
        if (this.searchQuery.isBlank()) {
            return this.entries;
        }
        String needle = this.searchQuery.toLowerCase(Locale.ROOT);
        return this.entries.stream()
                .filter(entry -> entry.shortTitle().toLowerCase(Locale.ROOT).contains(needle)
                        || entry.pipelineLocation().toLowerCase(Locale.ROOT).contains(needle)
                        || entry.vertexShader().toLowerCase(Locale.ROOT).contains(needle)
                        || entry.fragmentShader().toLowerCase(Locale.ROOT).contains(needle)
                        || entry.samplerState().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    private List<SamplerListEntry> listEntries() {
        List<SamplerInspectionService.SamplerBindingSnapshot> filteredEntries = filteredEntries();
        if (this.selectedGroupKey != null) {
            return filteredEntries.stream()
                    .filter(entry -> groupKey(entry).equals(this.selectedGroupKey))
                    .map(SamplerListEntry::sampler)
                    .toList();
        }

        return groupedEntries(filteredEntries);
    }

    private List<SamplerListEntry> groupedEntries(List<SamplerInspectionService.SamplerBindingSnapshot> filteredEntries) {
        LinkedHashMap<String, ArrayList<SamplerInspectionService.SamplerBindingSnapshot>> groups = new LinkedHashMap<>();
        for (SamplerInspectionService.SamplerBindingSnapshot entry : filteredEntries) {
            groups.computeIfAbsent(groupKey(entry), ignored -> new ArrayList<>()).add(entry);
        }
        return groups.entrySet().stream()
                .map(entry -> SamplerListEntry.group(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static String groupKey(SamplerInspectionService.SamplerBindingSnapshot entry) {
        return entry.pipelineLocation();
    }

    private static String shortGroupTitle(String groupKey) {
        String text = groupKey.startsWith("pipeline/") ? groupKey.substring("pipeline/".length()) : groupKey;
        return text.isBlank() ? "<unknown group>" : text;
    }

    private int panelWidth() {
        return Math.min(900, this.width - 40);
    }

    private int visibleReadbackRows(int startY) {
        return Math.max(1, (this.height - 42 - startY) / LINE_HEIGHT);
    }

    private int maxScrollRows() {
        int lineCount = this.lastReadback == null ? 1 : this.lastReadback.displayLines().size();
        return Math.max(0, lineCount - 8);
    }

    private static Integer parseInteger(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private record SamplerListEntry(
            boolean group,
            String groupKey,
            String title,
            String subtitle,
            boolean lightmapLike,
            SamplerInspectionService.SamplerBindingSnapshot sampler,
            List<SamplerInspectionService.SamplerBindingSnapshot> members
    ) {
        private static SamplerListEntry group(String groupKey, List<SamplerInspectionService.SamplerBindingSnapshot> members) {
            boolean lightmapLike = members.stream().anyMatch(SamplerInspectionService.SamplerBindingSnapshot::lightmapLike);
            SamplerInspectionService.SamplerBindingSnapshot first = members.getFirst();
            String title = shortGroupTitle(groupKey) + " (" + members.size() + ")";
            String subtitle = first.vertexShader() + " -> " + first.fragmentShader();
            return new SamplerListEntry(true, groupKey, title, subtitle, lightmapLike, null, List.copyOf(members));
        }

        private static SamplerListEntry sampler(SamplerInspectionService.SamplerBindingSnapshot sampler) {
            return new SamplerListEntry(false, SamplerInspectorScreen.groupKey(sampler), sampler.shortTitle(), sampler.pipelineLocation(), sampler.lightmapLike(), sampler, List.of(sampler));
        }

        private boolean selected(String selectedId, String selectedGroupKey) {
            return this.group ? this.groupKey.equals(selectedGroupKey) : this.sampler.id().equals(selectedId);
        }

        private java.util.Optional<SamplerInspectionService.SamplerBindingSnapshot> firstSampler() {
            return this.members.stream().findFirst();
        }
    }
}
