package com.df.mobvisualizer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.InputUtil;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.text.Style;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MobSettingsScreenV2 extends Screen {
    private final Screen parent;
    private final MobOverlayConfig config;
    private final MobOverlayState state;
    private String page = "main";
    private int listPage;
    private int waitingForKey;
    private final List<String> entityIds = new ArrayList<>();
    private TextFieldWidget entitySearchField;
    private TextFieldWidget idRulesField;
    private TextFieldWidget percentRulesField;
    private TextFieldWidget alertGapField;
    private TextFieldWidget alertPercentField;
    private TextFieldWidget alertTypesField;

    public MobSettingsScreenV2(Screen parent, MobOverlayConfig config, MobOverlayState state) {
        super(Text.literal("DF Mob Visualizer"));
        this.parent = parent;
        this.config = config;
        this.state = state;
        entityIds.add("minecraft:charged_creeper");
        Registries.ENTITY_TYPE.getIds().stream().map(Object::toString)
                .sorted().forEach(entityIds::add);
    }

    @Override
    protected void init() {
        clearChildren();
        int left = width / 2 - 155;
        if (page.equals("main")) {
            button(left, 55, 310, "Общие настройки", b -> open("general"));
            button(left, 80, 310, "HUD и отображение", b -> open("hud"));
            button(left, 105, 310, "Мобы сессии  → галочки", b -> open("session"));
            button(left, 130, 310, "LOW_ID в сессию  → выбор мобов", b -> open("lowid"));
            button(left, 155, 310, "Подсветка через блоки  → галочки", b -> open("highlight"));
            button(left, 180, 310, "Цвета каждого моба", b -> open("colors"));
            button(left, 205, 310, "Клавиши / бинды", b -> open("keys"));
            button(left, 230, 310, "Цвета по ID и проценту", b -> open("idcolors"));
            button(left, 255, 310, "ALERT", b -> open("alert"));
            button(left, 280, 310, "Очистка данных", b -> open("cleanup"));
            button(left, 315, 310, "Готово", b -> close());
            return;
        }
        button(left, 35, 90, "← Разделы", b -> open("main"));
        if (page.equals("session") || page.equals("lowid") || page.equals("highlight")
                || page.equals("colors") || page.equals("alertsession")) {
            buildEntityPage(left);
        } else if (page.equals("keys")) {
            buildKeys(left);
        } else if (page.equals("general")) {
            buildGeneral(left);
        } else if (page.equals("idcolors")) {
            buildIdColors(left);
        } else if (page.equals("hud")) {
            buildHud(left);
        } else if (page.equals("alert")) {
            buildAlert(left);
        } else {
            buildCleanup(left);
        }
    }

    private void buildEntityPage(int left) {
        List<String> filtered = filteredEntityIds();
        int start = listPage * 12;
        int end = Math.min(filtered.size(), start + 12);
        Set<String> selected = page.equals("session") ? parseSet(config.pinnedEntityTypes)
                : page.equals("lowid") ? parseSet(config.lowIdEntityTypes)
                : page.equals("highlight") ? parseSet(config.highlightEntityTypes)
                : parseSet(config.alertSessionEntityTypes);
        Map<String, Integer> colors = parseColors();
        for (int i = start; i < end; i++) {
            String id = filtered.get(i);
            int y = 95 + (i - start) * 22;
            boolean on = selected.contains(id);
            String name = displayName(id);
            int rowColor = colors.getOrDefault(id, defaultColor(id));
            String suffix = page.equals("colors") ? ""
                    : "  [" + (on ? "✓" : " ") + "]";
            if (page.equals("colors")) {
                addDrawableChild(ButtonWidget.builder(Text.literal(name + suffix)
                        .setStyle(Style.EMPTY.withColor(rowColor)), b -> {
                    MinecraftClient.getInstance().setScreen(new MobColorPickerScreen(this, config, id));
                }).dimensions(left, y, 310, 20).build());
            } else button(left, y, 310, name + suffix, b -> {
                if (page.equals("colors")) {
                    MinecraftClient.getInstance().setScreen(new MobColorPickerScreen(this, config, id));
                } else {
                    if (on) selected.remove(id); else selected.add(id);
                    if (page.equals("session")) config.pinnedEntityTypes = serializeSet(selected);
                    else if (page.equals("lowid")) config.lowIdEntityTypes = serializeSet(selected);
                    else if (page.equals("highlight")) config.highlightEntityTypes = serializeSet(selected);
                    else config.alertSessionEntityTypes = serializeSet(selected);
                }
                save();
                init();
            });
        }
        entitySearchField = new TextFieldWidget(textRenderer, left, 60, 310, 20, Text.literal("Поиск моба"));
        entitySearchField.setMaxLength(100);
        entitySearchField.setText(entitySearch == null ? "" : entitySearch);
        entitySearchField.setPlaceholder(Text.literal("английский ID или русское название"));
        addDrawableChild(entitySearchField);
        button(left - 80, 95 + 12 * 22, 70, "←", b -> {
            listPage = Math.max(0, listPage - 1); init();
        });
        button(left + 240, 95 + 12 * 22, 70, "→", b -> {
            if ((listPage + 1) * 12 < filtered.size()) listPage++; init();
        });
        drawPageInfo = true;
    }

    private boolean drawPageInfo;
    private String entitySearch = "";

    private void buildKeys(int left) {
        button(left, 65, 150, keyLabel("HUD", config.hudKey, config.hudScanCode, 1), b -> waitKey(1));
        button(left + 160, 65, 150, keyLabel("Мобы", config.mobHighlightsKey, config.mobHighlightsScanCode, 2), b -> waitKey(2));
        button(left, 90, 150, keyLabel("Настройки", config.settingsKey, config.settingsScanCode, 3), b -> waitKey(3));
        button(left + 160, 90, 150, keyLabel("Чанки", config.chunksKey, config.chunksScanCode, 4), b -> waitKey(4));
        button(left, 115, 150, keyLabel("Очистить сессию", config.clearSessionKey, config.clearSessionScanCode, 5), b -> waitKey(5));
        button(left + 160, 115, 150, keyLabel("Очистить чанки", config.clearChunksKey, config.clearChunksScanCode, 6), b -> waitKey(6));
        button(left, 150, 310, "Сбросить все клавиши", b -> {
            config.hudKey = GLFW.GLFW_KEY_F8; config.mobHighlightsKey = GLFW.GLFW_KEY_F7;
            config.settingsKey = GLFW.GLFW_KEY_F10; config.chunksKey = GLFW.GLFW_KEY_F9;
            config.clearSessionKey = GLFW.GLFW_KEY_F5; config.clearChunksKey = GLFW.GLFW_KEY_F6;
            config.hudScanCode = 0; config.mobHighlightsScanCode = 0;
            config.settingsScanCode = 0; config.chunksScanCode = 0;
            config.clearSessionScanCode = 0; config.clearChunksScanCode = 0;
            C2MEmod.applyKeyConfig(config); save(); init();
        });
        if (waitingForKey != 0) button(left, 185, 310, "Нажми клавишу (ESC — отмена)", b -> {});
    }

    private void buildGeneral(int left) {
        toggle(left, 65, "Мод", config.enabled, () -> config.enabled = !config.enabled);
        toggle(left, 90, "Сессия", config.sessionEnabled, () -> config.sessionEnabled = !config.sessionEnabled);
        toggle(left, 115, "LOW_ID в сессию", config.pinLowIds, () -> config.pinLowIds = !config.pinLowIds);
        toggle(left, 140, "Игроки в HUD", config.showPlayers, () -> config.showPlayers = !config.showPlayers);
        toggle(left, 165, "Только hostile", config.hostileOnly, () -> config.hostileOnly = !config.hostileOnly);
        toggle(left, 190, "Другие сущности", config.includeOtherEntities, () -> config.includeOtherEntities = !config.includeOtherEntities);
        toggle(left, 215, "Мобы после удара", config.pinHurtMobs, () -> config.pinHurtMobs = !config.pinHurtMobs);
        toggle(left, 240, "Сохранять сессию", config.persistSession, () -> config.persistSession = !config.persistSession);
    }

    private void buildIdColors(int left) {
        button(left, 65, 310, "Настроить цвета по ID (приоритет 1)", b ->
                MinecraftClient.getInstance().setScreen(new ColorRulesScreen(this, config, true)));
        button(left, 95, 310, "Настроить цвета по проценту (приоритет 2)", b ->
                MinecraftClient.getInstance().setScreen(new ColorRulesScreen(this, config, false)));
        button(left, 130, 310, "Сбросить правила цветов", b -> {
            config.idColorRules = "id<=10001=#C855E8FF";
            config.percentColorRules = "percent<30=#FFFF2020;percent<50=#FFFFB000";
            save();
        });
    }

    private void buildHud(int left) {
        toggle(left, 65, "HUD", config.showHud, () -> config.showHud = !config.showHud);
        toggle(left, 90, "Подсветка через блоки", config.seeThroughMobs, () -> config.seeThroughMobs = !config.seeThroughMobs);
        toggle(left, 115, "Карта поверхности чанков", config.showChunkOverlay,
                () -> config.showChunkOverlay = !config.showChunkOverlay);
        toggle(left, 140, "Показывать заливку чанков", config.showChunkFill,
                () -> config.showChunkFill = !config.showChunkFill);
        
        button(left, 165, 150, "Высота слоя: " + config.chunkYOffset + " блоков",
                b -> MinecraftClient.getInstance().setScreen(new HeightInputScreen(this, config, true)));
        
        button(left + 160, 165, 150, "Толщина: " + config.chunkHeight + " блок(ов)",
                b -> MinecraftClient.getInstance().setScreen(new HeightInputScreen(this, config, false)));
        
        button(left, 190, 310, "Прозрачность поверхности: " + percent(config.chunkOpacity),
                b -> { config.chunkOpacity = nextOpacity(config.chunkOpacity); save(); init(); });
        button(left, 215, 310, "Усиление заливки: " + String.format(Locale.ROOT, "%.1fx", config.chunkFillStrength),
                b -> { config.chunkFillStrength = nextStrength(config.chunkFillStrength); save(); init(); });
        button(left, 240, 310, "Прозрачность границы: " + percent(config.chunkBorderOpacity),
                b -> { config.chunkBorderOpacity = nextOpacity(config.chunkBorderOpacity); save(); init(); });
        toggle(left, 265, 310, "Показывать игроков", config.showPlayers, () -> config.showPlayers = !config.showPlayers);
        button(left, 290, 310, "Сбросить позицию HUD", b -> { config.hudX = 8; config.hudY = 8; save(); });
    }

    private static String percent(float value) {
        return Math.round(value * 100.0f) + "%";
    }

    private static float nextOpacity(float value) {
        float[] values = {0.15f, 0.30f, 0.45f, 0.60f, 0.75f, 0.90f, 1.0f};
        for (float candidate : values) {
            if (value < candidate - 0.001f) return candidate;
        }
        return values[0];
    }

    private static float nextStrength(float value) {
        float[] values = {0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f};
        for (float candidate : values) {
            if (value < candidate - 0.001f) return candidate;
        }
        return values[0];
    }

    private void buildAlert(int left) {
        toggle(left, 65, "ALERT", config.alertEnabled, () -> config.alertEnabled = !config.alertEnabled);
        toggle(left, 90, "Режим: " + (config.alertMode == 0 ? "разница ID" : "процент"),
                true, () -> config.alertMode = config.alertMode == 0 ? 1 : 0);
        toggle(left, 115, "Показывать в HUD", config.alertShowHud, () -> config.alertShowHud = !config.alertShowHud);
        toggle(left, 140, "Закреплять в сессии", config.alertPinSession, () -> config.alertPinSession = !config.alertPinSession);
        toggle(left, 165, "Подсвечивать", config.alertHighlight, () -> config.alertHighlight = !config.alertHighlight);
        toggle(left, 190, "Учитывать в центре", config.alertCenter, () -> config.alertCenter = !config.alertCenter);
        alertGapField = new TextFieldWidget(textRenderer, left, 220, 150, 20, Text.literal("Разница ID"));
        alertGapField.setText(Integer.toString(config.alertGap));
        addDrawableChild(alertGapField);
        alertPercentField = new TextFieldWidget(textRenderer, left + 160, 220, 150, 20, Text.literal("Процент"));
        alertPercentField.setText(Double.toString(config.alertPercent));
        addDrawableChild(alertPercentField);
        alertTypesField = new TextFieldWidget(textRenderer, left, 250, 310, 20, Text.literal("Разрешённые типы"));
        alertTypesField.setMaxLength(4000);
        alertTypesField.setText(config.alertEntityTypes == null ? "" : config.alertEntityTypes);
        alertTypesField.setPlaceholder(Text.literal("пусто = все; zombie, skeleton"));
        addDrawableChild(alertTypesField);
        button(left, 280, 310, "Выбрать ALERT для сессии  →", b -> open("alertsession"));
    }

    private void buildCleanup(int left) {
        button(left, 65, 310, "Очистить сессию (" + state.sessionCount() + ")", b -> { state.clearSession(); save(); });
        button(left, 90, 310, "Очистить историю чанков", b -> { state.clearChunks(); save(); });
        button(left, 115, 310, "Сбросить MAX ID", b -> { state.clearMaxId(); save(); });
    }

    private void toggle(int x, int y, String label, boolean value, Runnable action) {
        button(x, y, 310, label + ": " + (value ? "ВКЛ" : "ВЫКЛ"), b -> { action.run(); save(); init(); });
    }

    private void toggle(int x, int y, String label, boolean value, Runnable action, boolean dummy) {
        button(x, y, 310, label + ": " + (value ? "ВКЛ" : "ВЫКЛ"), b -> { action.run(); save(); init(); });
    }

    private void waitKey(int target) { waitingForKey = target; init(); }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (waitingForKey == 0) return super.keyPressed(keyCode, scanCode, modifiers);
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { waitingForKey = 0; init(); return true; }
        switch (waitingForKey) {
            case 1 -> { config.hudKey = keyCode; config.hudScanCode = scanCode; }
            case 2 -> { config.mobHighlightsKey = keyCode; config.mobHighlightsScanCode = scanCode; }
            case 3 -> { config.settingsKey = keyCode; config.settingsScanCode = scanCode; }
            case 4 -> { config.chunksKey = keyCode; config.chunksScanCode = scanCode; }
            case 5 -> { config.clearSessionKey = keyCode; config.clearSessionScanCode = scanCode; }
            case 6 -> { config.clearChunksKey = keyCode; config.clearChunksScanCode = scanCode; }
        }
        waitingForKey = 0;
        C2MEmod.applyKeyConfig(config);
        save(); init();
        return true;
    }

    private void open(String next) { page = next; listPage = 0; waitingForKey = 0; init(); }

    private void button(int x, int y, int w, String label, ButtonWidget.PressAction action) {
        addDrawableChild(ButtonWidget.builder(Text.literal(label), action).dimensions(x, y, w, 20).build());
    }

    private String pageTitle() {
        return switch (page) {
            case "session" -> "Мобы, закрепляемые в сессии";
            case "lowid" -> "Какие LOW_ID добавлять в сессию";
            case "highlight" -> "Мобы, подсвечиваемые через блоки";
            case "colors" -> "Индивидуальные цвета мобов";
            case "idcolors" -> "Цвета по ID и проценту";
            case "keys" -> "Настройка клавиш";
            case "general" -> "Общие настройки";
            case "hud" -> "HUD и отображение";
            case "alert" -> "Настройки ALERT";
            case "alertsession" -> "Какие ALERT добавлять в сессию";
            default -> "Очистка данных";
        };
    }

    private List<String> filteredEntityIds() {
        String query = entitySearchField == null ? entitySearch : entitySearchField.getText();
        entitySearch = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (entitySearch.isBlank()) return entityIds;
        return entityIds.stream().filter(id -> {
            String technical = id.toLowerCase(Locale.ROOT);
            String translated = displayName(id).toLowerCase(Locale.ROOT);
            return technical.contains(entitySearch) || translated.contains(entitySearch);
        }).toList();
    }

    private String displayName(String id) {
        String key = "entity." + id.replace(':', '.');
        String translated = Text.translatable(key).getString();
        if (!translated.equals(key)) return translated + " (" + id.substring(id.indexOf(':') + 1) + ")";
        return id.substring(id.indexOf(':') + 1);
    }

    private String keyLabel(String name, int key, int scanCode, int target) {
        return (waitingForKey == target ? "Нажми: " : name + ": ") + keyName(key, scanCode);
    }

    private String keyName(int key, int scanCode) {
        if ((key == GLFW.GLFW_KEY_UNKNOWN || key == 0) && scanCode == 0) return "не назначено";
        if ((key == GLFW.GLFW_KEY_UNKNOWN || key == 0) && scanCode > 0) {
            return InputUtil.Type.SCANCODE.createFromCode(scanCode).getLocalizedText().getString();
        }
        return InputUtil.Type.KEYSYM.createFromCode(key).getLocalizedText().getString();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(page.equals("main") ? "DF Mob Visualizer" : pageTitle()),
                width / 2, 15, 0xFFE8D7FF);
        if (drawPageInfo && !page.equals("main")) {
            context.drawCenteredTextWithShadow(textRenderer,
                        Text.literal("Страница " + (listPage + 1) + " / " + Math.max(1, (filteredEntityIds().size() + 11) / 12)),
                    width / 2, height - 28, 0xFFB9A7C9);
            drawPageInfo = false;
        }
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (page.equals("session") || page.equals("lowid") || page.equals("highlight")
                || page.equals("colors") || page.equals("alertsession")) {
            if (vertical < 0 && (listPage + 1) * 12 < filteredEntityIds().size()) listPage++;
            if (vertical > 0) listPage = Math.max(0, listPage - 1);
            init();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    private Set<String> parseSet(String raw) {
        Set<String> result = new HashSet<>();
        if (raw == null) return result;
        for (String item : raw.split(",")) if (!item.isBlank()) {
            String id = item.trim().toLowerCase(Locale.ROOT);
            if (!id.contains(":")) id = "minecraft:" + id;
            result.add(id);
        }
        return result;
    }

    private String serializeSet(Set<String> values) {
        return values.stream().sorted().reduce((a, b) -> a + "," + b).orElse("");
    }

    private Map<String, Integer> parseColors() {
        Map<String, Integer> result = new HashMap<>();
        if (config.customMobColors == null) return result;
        for (String item : config.customMobColors.split(",")) {
            String[] pair = item.trim().split("=", 2);
            if (pair.length != 2) continue;
            try { result.put(pair[0].toLowerCase(Locale.ROOT), (int) Long.parseLong(pair[1].replace("#", ""), 16)); }
            catch (NumberFormatException ignored) { }
        }
        return result;
    }

    private int defaultColor(String id) {
        return switch (id) {
            case "minecraft:zombie" -> 0xFF66CC66;
            case "minecraft:skeleton" -> 0xFFE6E6E6;
            case "minecraft:creeper" -> 0xFF55FF55;
            case "minecraft:spider" -> 0xFFAA2222;
            case "minecraft:enderman" -> 0xFFBB66FF;
            case "minecraft:player" -> 0xFF55CCFF;
            case "minecraft:villager" -> 0xFFFFBB55;
            default -> {
                int hash = id.hashCode() * 1103515245 + 12345;
                int r = 80 + ((hash >>> 16) & 127);
                int g = 80 + ((hash >>> 8) & 127);
                int b = 80 + (hash & 127);
                yield 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        };
    }

    private void save() {
        if (idRulesField != null) config.idColorRules = idRulesField.getText();
        if (percentRulesField != null) config.percentColorRules = percentRulesField.getText();
        if (alertGapField != null) {
            try { config.alertGap = Integer.parseInt(alertGapField.getText().trim()); }
            catch (NumberFormatException ignored) { }
        }
        if (alertPercentField != null) {
            try { config.alertPercent = Double.parseDouble(alertPercentField.getText().trim()); }
            catch (NumberFormatException ignored) { }
        }
        if (alertTypesField != null) config.alertEntityTypes = alertTypesField.getText();
        config.normalize();
        config.save();
    }

    @Override public void close() {
        if (idRulesField != null) config.idColorRules = idRulesField.getText();
        if (percentRulesField != null) config.percentColorRules = percentRulesField.getText();
        save();
        MinecraftClient.getInstance().setScreen(parent);
    }
}
