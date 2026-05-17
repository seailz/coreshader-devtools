package com.seailz.csdt.client.screen;

import com.seailz.csdt.client.service.UniformInspectorService;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class UniformInspectorScreen extends Screen {

    private static final int ENTRY_HEIGHT = 34;
    private static final int LINE_HEIGHT = 11;

    private final Screen parent;
    private final List<Button> entryButtons = new ArrayList<>();
    private final List<UniformInspectorService.UniformSnapshot> visibleEntries = new ArrayList<>();
    private EditBox searchBox;
    private Button previousPageButton;
    private Button nextPageButton;
    private Button copyButton;
    private int page;
    private int detailScrollRows;
    private String searchQuery = "";
    private String selectedKey;
    private String expandedValueKey;

    public UniformInspectorScreen(Screen parent) {
        super(Component.translatable("screen.coreshader-devtools.uniform_inspector"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        this.searchBox = this.addRenderableWidget(new EditBox(this.font, centerX - 170, 30, 340, 20, Component.literal("Search")));
        this.searchBox.setHint(Component.literal("Search uniforms"));
        this.searchBox.setValue(this.searchQuery);
        this.searchBox.setResponder(text -> {
            this.searchQuery = text == null ? "" : text;
            this.page = 0;
            this.detailScrollRows = 0;
            this.refreshView();
        });

        int panelWidth = panelWidth();
        int left = centerX - panelWidth / 2;
        int listWidth = listWidth(panelWidth);
        int startY = 82;
        for (int i = 0; i < pageSize(); i++) {
            final int index = i;
            Button entryButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> {
                if (index < this.visibleEntries.size()) {
                    this.selectedKey = this.visibleEntries.get(index).key();
                    this.expandedValueKey = null;
                    this.detailScrollRows = 0;
                    this.refreshView();
                }
            }).bounds(left + 10, startY + i * ENTRY_HEIGHT, listWidth - 20, 20).build());
            this.entryButtons.add(entryButton);
        }

        this.previousPageButton = this.addRenderableWidget(Button.builder(Component.translatable("button.coreshader-devtools.previous_page"), button -> {
            if (this.page > 0) {
                this.page--;
                this.refreshView();
            }
        }).bounds(centerX - 160, this.height - 30, 92, 20).build());
        this.nextPageButton = this.addRenderableWidget(Button.builder(Component.translatable("button.coreshader-devtools.next_page"), button -> {
            this.page++;
            this.refreshView();
        }).bounds(centerX - 58, this.height - 30, 92, 20).build());
        this.copyButton = this.addRenderableWidget(Button.builder(Component.literal("Copy"), button -> this.copySelected())
                .bounds(centerX + 44, this.height - 30, 70, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> this.onClose())
                .bounds(centerX + 124, this.height - 30, 92, 20)
                .build());

        this.refreshView();
    }

    @Override
    public void tick() {
        this.refreshView();
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
        UniformInspectorService.UniformSnapshot selected = selectedEntry();
        if (selected != null && mouseX >= detailLeft()) {
            this.detailScrollRows = Math.clamp(this.detailScrollRows - (int) Math.signum(scrollY) * 3, 0, maxDetailScrollRows(selected));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            UniformInspectorService.UniformSnapshot selected = selectedEntry();
            if (selected != null && event.x() >= detailLeft() + 10 && event.x() <= detailLeft() + detailWidth() - 10) {
                Row row = detailRowAt(selected, event.y());
                if (row != null && row.valueKey() != null) {
                    this.expandedValueKey = row.valueKey().equals(this.expandedValueKey) ? null : row.valueKey();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.refreshView();
        guiGraphics.fill(0, 0, this.width, this.height, 0xB010141A);
        renderPanels(guiGraphics);
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

        List<UniformInspectorService.UniformSnapshot> entries = filteredEntries();
        int totalPages = totalPages(entries.size());
        int safePage = Math.min(this.page, totalPages - 1);
        guiGraphics.centeredText(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);
        guiGraphics.centeredText(this.font, Component.literal(entries.size() + " observed bindings | Page " + (safePage + 1) + " / " + totalPages), this.width / 2, 58, 0xFFA8C2D8);

        renderEntries(guiGraphics);
        renderDetail(guiGraphics);
    }

    private void renderPanels(GuiGraphicsExtractor guiGraphics) {
        int panelWidth = panelWidth();
        int left = this.width / 2 - panelWidth / 2;
        int top = 76;
        int height = this.height - 118;
        int listWidth = listWidth(panelWidth);
        int detailLeft = left + listWidth + 10;

        guiGraphics.fill(left, top, left + listWidth, top + height, 0x2216202A);
        guiGraphics.outline(left, top, listWidth, height, 0x66445A6B);
        guiGraphics.fill(detailLeft, top, left + panelWidth, top + height, 0x2216202A);
        guiGraphics.outline(detailLeft, top, panelWidth - listWidth - 10, height, 0x66445A6B);
    }

    private void renderEntries(GuiGraphicsExtractor guiGraphics) {
        int centerX = this.width / 2;
        int panelWidth = panelWidth();
        int left = centerX - panelWidth / 2;
        int listWidth = listWidth(panelWidth);
        for (int i = 0; i < this.visibleEntries.size(); i++) {
            UniformInspectorService.UniformSnapshot entry = this.visibleEntries.get(i);
            Button button = this.entryButtons.get(i);
            boolean selected = entry.key().equals(this.selectedKey);
            guiGraphics.fill(button.getX(), button.getY(), button.getX() + button.getWidth(), button.getY() + button.getHeight(), selected ? 0x554CC9F0 : 0x2216202A);
            guiGraphics.centeredText(this.font, Component.literal(entry.name()), left + listWidth / 2, button.getY() + 6, selected ? 0xFFFFFFFF : 0xFFE6EEF7);
            guiGraphics.centeredText(this.font, Component.literal(trimToWidth(entry.subtitle(), listWidth - 26)), left + listWidth / 2, button.getY() + 22, 0xFFB8C7D9);
        }

        if (this.visibleEntries.isEmpty()) {
            guiGraphics.centeredText(this.font, Component.literal("No uniform bindings observed"), left + listWidth / 2, this.height / 2, 0xFFD7E3F0);
        }
    }

    private void renderDetail(GuiGraphicsExtractor guiGraphics) {
        UniformInspectorService.UniformSnapshot selected = selectedEntry();
        int left = detailLeft();
        int top = 86;
        int width = detailWidth();
        int height = this.height - 138;
        if (selected == null) {
            guiGraphics.centeredText(this.font, Component.literal("Select a uniform"), left + width / 2, this.height / 2, 0xFFD7E3F0);
            return;
        }

        guiGraphics.text(this.font, Component.literal(selected.name()), left + 12, top, 0xFFFFD166, false);
        guiGraphics.text(this.font, Component.literal(selected.type() + " | " + selected.backend()), left + 12, top + 12, 0xFFB8C7D9, false);
        guiGraphics.text(this.font, Component.literal(trimToWidth(selected.shortPipeline(), width - 24)), left + 12, top + 24, 0xFFE6EEF7, false);

        List<Row> rows = detailRows(selected);
        int contentTop = top + 44;
        int y = contentTop;
        int end = Math.min(rows.size(), this.detailScrollRows + visibleDetailRows() + 2);
        guiGraphics.enableScissor(left + 10, contentTop - 2, left + width - 10, this.height - 48);
        for (int i = this.detailScrollRows; i < end; i++) {
            Row row = rows.get(i);
            if (row.isSeparator()) {
                guiGraphics.horizontalLine(left + 12, left + width - 18, y + 4, 0x55445A6B);
                y += 8;
                continue;
            }
            if (y + LINE_HEIGHT >= contentTop) {
                if (row.valueKey() != null && row.valueKey().equals(this.expandedValueKey)) {
                    guiGraphics.fill(left + 10, y - 1, left + width - 10, y + LINE_HEIGHT, 0x224CC9F0);
                }
                guiGraphics.text(this.font, Component.literal(trimToWidth(row.text(), width - 26 - row.indent() * 12)), left + 12 + row.indent() * 12, y, row.color(), false);
            }
            y += LINE_HEIGHT;
            if (y > this.height - 48) {
                break;
            }
        }
        guiGraphics.disableScissor();

        String age = formatAge(System.currentTimeMillis() - selected.updatedAtMillis());
        int updatedCounterY = this.height - 46 - this.font.lineHeight / 2 - 16;
        guiGraphics.text(this.font, Component.literal("Updated " + age + " ago"), left + 12, updatedCounterY, 0xFF9AA8B8, false);
    }

    private void refreshView() {
        List<UniformInspectorService.UniformSnapshot> entries = filteredEntries();
        if (this.selectedKey == null || entries.stream().noneMatch(entry -> entry.key().equals(this.selectedKey))) {
            this.selectedKey = entries.isEmpty() ? null : entries.getFirst().key();
            this.expandedValueKey = null;
            this.detailScrollRows = 0;
        }

        int totalPages = totalPages(entries.size());
        this.page = Math.clamp(this.page, 0, totalPages - 1);
        int start = this.page * pageSize();
        int end = Math.min(start + pageSize(), entries.size());

        this.visibleEntries.clear();
        if (start < end) {
            this.visibleEntries.addAll(entries.subList(start, end));
        }

        for (int i = 0; i < this.entryButtons.size(); i++) {
            Button button = this.entryButtons.get(i);
            boolean visible = i < this.visibleEntries.size();
            button.visible = visible;
            button.active = visible;
            if (visible) {
                button.setMessage(Component.empty());
            }
        }
        if (this.previousPageButton != null) {
            this.previousPageButton.active = this.page > 0;
        }
        if (this.nextPageButton != null) {
            this.nextPageButton.active = this.page + 1 < totalPages;
        }
        if (this.copyButton != null) {
            this.copyButton.active = selectedEntry() != null;
        }
    }

    private List<UniformInspectorService.UniformSnapshot> filteredEntries() {
        List<UniformInspectorService.UniformSnapshot> entries = UniformInspectorService.snapshotUniforms();
        if (this.searchQuery.isBlank()) {
            return entries;
        }
        String needle = this.searchQuery.toLowerCase(Locale.ROOT);
        return entries.stream()
                .filter(entry -> entry.name().toLowerCase(Locale.ROOT).contains(needle)
                        || entry.pipeline().toLowerCase(Locale.ROOT).contains(needle)
                        || entry.backend().toLowerCase(Locale.ROOT).contains(needle)
                        || entry.type().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    private UniformInspectorService.UniformSnapshot selectedEntry() {
        if (this.selectedKey == null) {
            return null;
        }
        return UniformInspectorService.snapshotUniforms().stream()
                .filter(entry -> entry.key().equals(this.selectedKey))
                .findFirst()
                .orElse(null);
    }

    private List<Row> detailRows(UniformInspectorService.UniformSnapshot selected) {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Pipeline", 0xFF7AA2F7, 0, false));
        rows.add(new Row(selected.pipeline(), 0xFFE6EEF7, 1, false));
        rows.add(new Row("Vertex: " + selected.vertexShader(), 0xFFB8C7D9, 1, false));
        rows.add(new Row("Fragment: " + selected.fragmentShader(), 0xFFB8C7D9, 1, false));
        rows.add(Row.separator());
        rows.add(new Row("Buffer", 0xFF7AA2F7, 0, false));
        rows.add(new Row("Offset: " + selected.offset(), 0xFFE6EEF7, 1, false));
        rows.add(new Row("Length: " + selected.length(), 0xFFE6EEF7, 1, false));
        rows.add(new Row("Captured: " + (selected.valueAvailable() ? selected.capturedBytes() + " / " + selected.totalBytes() + " bytes" : "<not available>"), 0xFFE6EEF7, 1, false));
        rows.add(new Row("GPU format: " + selected.gpuFormat(), 0xFFE6EEF7, 1, false));
        rows.add(Row.separator());
        for (UniformInspectorService.ValueLine line : selected.valueLines()) {
            if (line.text().isEmpty()) {
                rows.add(Row.separator());
            } else {
                boolean expanded = line.expandable() && line.key().equals(this.expandedValueKey);
                int color = line.header()
                        ? 0xFFFFD166
                        : expanded
                        ? 0xFFFFFFFF
                        : 0xFFE6EEF7;
                String prefix = line.expandable() ? (expanded ? "- " : "+ ") : "";
                rows.add(new Row(prefix + line.text(), color, 0, false, line.expandable() ? line.key() : null));
                if (expanded) {
                    rows.add(new Row("Last 20 values", 0xFF7AA2F7, 1, false, null));
                    List<UniformInspectorService.ValueSample> history = UniformInspectorService.valueHistory(selected.key(), line.key());
                    if (history.isEmpty()) {
                        rows.add(new Row("<none>", 0xFF9AA8B8, 1, false, null));
                    } else {
                        long now = System.currentTimeMillis();
                        for (UniformInspectorService.ValueSample sample : history) {
                            rows.add(new Row(formatAge(now - sample.updatedAtMillis()) + " ago: " + sample.value(), 0xFF9AA8B8, 1, false, null));
                        }
                    }
                }
            }
        }
        return rows;
    }

    private void copySelected() {
        UniformInspectorService.UniformSnapshot selected = selectedEntry();
        if (selected == null) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        builder.append(selected.name()).append('\n');
        builder.append(selected.backend()).append(" | ").append(selected.pipeline()).append('\n');
        builder.append(selected.type()).append(" | ").append(selected.length()).append(" bytes").append('\n');
        for (UniformInspectorService.ValueLine line : selected.valueLines()) {
            builder.append(line.text()).append('\n');
        }
        this.minecraft.keyboardHandler.setClipboard(builder.toString());
    }

    private Row detailRowAt(UniformInspectorService.UniformSnapshot selected, double mouseY) {
        int contentTop = 86 + 44;
        if (mouseY < contentTop - 2 || mouseY > this.height - 48) {
            return null;
        }

        List<Row> rows = detailRows(selected);
        int y = contentTop;
        int end = Math.min(rows.size(), this.detailScrollRows + visibleDetailRows() + 2);
        for (int i = this.detailScrollRows; i < end; i++) {
            Row row = rows.get(i);
            int rowHeight = row.isSeparator() ? 8 : LINE_HEIGHT;
            if (mouseY >= y && mouseY < y + rowHeight) {
                return row;
            }
            y += rowHeight;
            if (y > this.height - 48) {
                break;
            }
        }
        return null;
    }

    private int panelWidth() {
        return Math.min(880, this.width - 40);
    }

    private int listWidth(int panelWidth) {
        return Math.min(320, Math.max(220, panelWidth / 3));
    }

    private int detailLeft() {
        int panelWidth = panelWidth();
        int left = this.width / 2 - panelWidth / 2;
        return left + listWidth(panelWidth) + 10;
    }

    private int detailWidth() {
        int panelWidth = panelWidth();
        return panelWidth - listWidth(panelWidth) - 10;
    }

    private int pageSize() {
        return Math.max(1, (this.height - 136) / ENTRY_HEIGHT);
    }

    private int totalPages(int entryCount) {
        return Math.max(1, (entryCount + pageSize() - 1) / pageSize());
    }

    private int visibleDetailRows() {
        return Math.max(1, (this.height - 184) / LINE_HEIGHT);
    }

    private int maxDetailScrollRows(UniformInspectorService.UniformSnapshot selected) {
        return Math.max(0, detailRows(selected).size() - visibleDetailRows() + 2);
    }

    private String trimToWidth(String text, int width) {
        if (this.font.width(text) <= width) {
            return text;
        }
        String ellipsis = "...";
        return this.font.plainSubstrByWidth(text, Math.max(1, width - this.font.width(ellipsis))) + ellipsis;
    }

    private static String formatAge(long ageMillis) {
        if (ageMillis < 1000L) {
            return ageMillis + " ms";
        }
        Duration duration = Duration.ofMillis(ageMillis);
        long minutes = duration.toMinutes();
        long seconds = duration.minusMinutes(minutes).toSeconds();
        return minutes > 0 ? minutes + "m " + seconds + "s" : seconds + "s";
    }

    private record Row(String text, int color, int indent, boolean isSeparator, String valueKey) {
        private Row(String text, int color, int indent, boolean isSeparator) {
            this(text, color, indent, isSeparator, null);
        }

        private static Row separator() {
            return new Row("", 0, 0, true, null);
        }
    }
}
