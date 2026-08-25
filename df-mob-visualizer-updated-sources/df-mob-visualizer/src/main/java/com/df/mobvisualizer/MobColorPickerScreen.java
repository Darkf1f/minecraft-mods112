package com.df.mobvisualizer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

/**
 * Visual per-entity color editor. Users choose RGB with sliders and never
 * need to know or type a color code.
 */
public final class MobColorPickerScreen extends Screen {
    private final Screen parent;
    private final MobOverlayConfig config;
    private final String entityId;
    private int red, green, blue, alpha;

    public MobColorPickerScreen(Screen parent, MobOverlayConfig config, String entityId) {
        super(Text.literal("Цвет моба"));
        this.parent = parent;
        this.config = config;
        this.entityId = entityId;
        int color = MobColors.customColor(entityId, config) == null
                ? 0xFF55AAFF : MobColors.customColor(entityId, config);
        alpha = (color >>> 24) & 255;
        red = (color >>> 16) & 255;
        green = (color >>> 8) & 255;
        blue = color & 255;
    }

    @Override
    protected void init() {
        int left = width / 2 - 155;
        addDrawableChild(new Channel(this, left, 75, "Красный", red, 0));
        addDrawableChild(new Channel(this, left, 105, "Зелёный", green, 1));
        addDrawableChild(new Channel(this, left, 135, "Синий", blue, 2));
        addDrawableChild(new Channel(this, left, 165, "Прозрачность", alpha, 3));
        addDrawableChild(ButtonWidget.builder(Text.literal("Сохранить"), b -> {
            putColor();
            MinecraftClient.getInstance().setScreen(parent);
        }).dimensions(left, 210, 150, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Отмена"), b ->
                MinecraftClient.getInstance().setScreen(parent))
                .dimensions(left + 160, 210, 150, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Пресет: ярко-жёлтый"), b -> {
            red = 255; green = 220; blue = 40; alpha = 255; rebuildPicker();
        }).dimensions(left, 240, 310, 20).build());
    }

    private void rebuildPicker() { clearChildren(); init(); }

    private void putColor() {
        StringBuilder result = new StringBuilder();
        if (config.customMobColors != null) {
            for (String item : config.customMobColors.split(",")) {
                String[] pair = item.trim().split("=", 2);
                if (pair.length == 2 && !pair[0].trim().equalsIgnoreCase(entityId)) {
                    if (result.length() > 0) result.append(',');
                    result.append(item.trim());
                }
            }
        }
        if (result.length() > 0) result.append(',');
        result.append(entityId).append("=#").append(String.format("%08X", color()));
        config.customMobColors = result.toString();
        config.save();
    }

    private int color() { return (alpha << 24) | (red << 16) | (green << 8) | blue; }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Цвет: " + entityId),
                width / 2, 25, 0xFFE8D7FF);
        context.fill(width / 2 - 155, 45, width / 2 + 155, 65, color());
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(String.format(
                "Предпросмотр  #%08X", color())), width / 2, 275, 0xFFFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }

    private static final class Channel extends SliderWidget {
        private final MobColorPickerScreen screen;
        private final String label;
        private final int channel;

        Channel(MobColorPickerScreen screen, int x, int y, String label, int value, int channel) {
            super(x, y, 310, 20, Text.empty(), value / 255.0);
            this.screen = screen;
            this.label = label;
            this.channel = channel;
            updateMessage();
        }

        @Override protected void updateMessage() {
            setMessage(Text.literal(label + ": " + Math.round(value * 255)));
        }

        @Override protected void applyValue() {
            int current = (int) Math.round(value * 255);
            if (channel == 0) screen.red = current;
            else if (channel == 1) screen.green = current;
            else if (channel == 2) screen.blue = current;
            else screen.alpha = current;
        }
    }
}