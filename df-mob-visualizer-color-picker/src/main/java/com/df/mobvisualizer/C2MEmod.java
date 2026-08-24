package com.df.mobvisualizer;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.text.Text;
import net.minecraft.world.Heightmap;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Client visualizer layer for the existing entity transmitter.
 *
 * The original transmitter can remain responsible for its socket/console
 * protocol; this class reads the same entity IDs directly from the client
 * world and turns them into HUD and chunk marks.
 */
public final class C2MEmod implements ClientModInitializer {
    private static final String CATEGORY = "category.df_mob_visualizer";
    private static final KeyBinding TOGGLE_HUD = new KeyBinding(
            "key.df_mob_visualizer.toggle_hud",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            CATEGORY);
    private static final KeyBinding TOGGLE_CHUNKS = new KeyBinding(
            "key.df_mob_visualizer.toggle_chunks",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_F9,
            CATEGORY);
    private static final KeyBinding TOGGLE_MOB_HIGHLIGHTS = new KeyBinding(
            "key.df_mob_visualizer.toggle_mob_highlights",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_F7,
            CATEGORY);
    private static final KeyBinding OPEN_SETTINGS = new KeyBinding(
            "key.df_mob_visualizer.open_settings",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_F10,
            CATEGORY);
    private static final KeyBinding CLEAR_SESSION = new KeyBinding(
            "key.df_mob_visualizer.clear_session",
            InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
    private static final KeyBinding CLEAR_CHUNKS = new KeyBinding(
            "key.df_mob_visualizer.clear_chunks",
            InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);

    private MobOverlayConfig config;
    private MobOverlayState state;
    private boolean hudOpen;
    private boolean chunksOpen;
    private boolean mobHighlightsOpen;
    private int scanCooldown;
    private int centerChunkX = Integer.MIN_VALUE;
    private int centerChunkZ = Integer.MIN_VALUE;
    private int centerMarkerCount;

    @Override
    public void onInitializeClient() {
        config = MobOverlayConfig.load();
        config.normalize();
        applyKeyConfig(config);
        state = new MobOverlayState(config);
        hudOpen = config.showHud;
        chunksOpen = config.showChunkOverlay;
        mobHighlightsOpen = config.seeThroughMobs;
        KeyBindingHelper.registerKeyBinding(TOGGLE_HUD);
        KeyBindingHelper.registerKeyBinding(TOGGLE_CHUNKS);
        KeyBindingHelper.registerKeyBinding(TOGGLE_MOB_HIGHLIGHTS);
        KeyBindingHelper.registerKeyBinding(OPEN_SETTINGS);
        KeyBindingHelper.registerKeyBinding(CLEAR_SESSION);
        KeyBindingHelper.registerKeyBinding(CLEAR_CHUNKS);

        ClientTickEvents.END_CLIENT_TICK.register(client -> tick(client));
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> renderHud(drawContext));
        // Sodium can flush shared consumers during AFTER_ENTITIES.
    }

    private void tick(MinecraftClient client) {
        while (TOGGLE_HUD.wasPressed()) hudOpen = !hudOpen;
        while (TOGGLE_CHUNKS.wasPressed()) chunksOpen = !chunksOpen;
        while (TOGGLE_MOB_HIGHLIGHTS.wasPressed()) mobHighlightsOpen = !mobHighlightsOpen;
        while (OPEN_SETTINGS.wasPressed()) {
            client.setScreen(new MobSettingsScreenV2(client.currentScreen, config, state));
        }
        while (CLEAR_SESSION.wasPressed()) state.clearSession();
        while (CLEAR_CHUNKS.wasPressed()) state.clearChunks();
        if (!config.enabled) return;
        if (client.world == null || client.player == null || --scanCooldown > 0) return;
        scanCooldown = config.scanIntervalTicks;

        int currentMaxId = 0;
        for (Entity entity : client.world.getEntities()) {
            currentMaxId = Math.max(currentMaxId, entity.getId());
        }
        state.beginLiveScan(currentMaxId);
        List<TrackedMob> centerMarkers = new ArrayList<>();
        for (Entity entity : client.world.getEntities()) {
            // Clear the client-side glow first so changing filters/settings
            // cannot leave stale outlines on entities.
            entity.setGlowing(false);
            if (entity == client.player) continue;
            if (!config.showPlayers && entity.isPlayer()) continue;
            int id = entity.getId();
            int color = MobColors.forEntity(entity.getType().toString(), id, currentMaxId, config);
            boolean alert = currentMaxId > 100_000
                    && currentMaxId - id > 100_000
                    && !entity.isPlayer()
                    && !entity.getType().toString().endsWith("enderman");
            boolean player = entity.isPlayer();
            boolean hostile = isHostile(entity.getType().toString());
            // A mob that was just damaged is always eligible for the HUD,
            // even when the general hostile-only filter is enabled.
            if (config.hostileOnly && !hostile && !(entity instanceof LivingEntity living && living.hurtTime > 0)) continue;
            if (!config.includeOtherEntities && !(entity instanceof LivingEntity)) continue;
            boolean hurt = entity instanceof LivingEntity living && living.hurtTime > 0;
            boolean chargedCreeper = entity instanceof CreeperEntity creeper && creeper.isCharged();
            if (chargedCreeper && MobColors.customColor("minecraft:charged_creeper", config) == null) {
                color = config.chargedCreeperColor;
            }
            if (hurt) color = config.hurtColor;
            state.accept(new TrackedMob(id, entity.getType().toString(), entity.getName().getString(),
                    entity.getBlockX(), entity.getBlockY(), entity.getBlockZ(), alert, color,
                    player, hostile, hurt, chargedCreeper));
            TrackedMob tracked = new TrackedMob(id, entity.getType().toString(), entity.getName().getString(),
                    entity.getBlockX(), entity.getBlockY(), entity.getBlockZ(), alert, color,
                    player, hostile, hurt, chargedCreeper);
            entity.setGlowing(mobHighlightsOpen && config.seeThroughMobs
                    && shouldHighlight(tracked));
            if (centerMatches(tracked)) centerMarkers.add(tracked);
        }
        calculateCenter(centerMarkers);
        if (client.world.getTime() % 100 == 0) {
            state.saveSessionIfDirty();
            state.saveChunks();
        }
    }

    public static void applyKeyConfig(MobOverlayConfig config) {
        TOGGLE_HUD.setBoundKey(InputUtil.fromKeyCode(config.hudKey, 0));
        TOGGLE_CHUNKS.setBoundKey(InputUtil.fromKeyCode(config.chunksKey, 0));
        TOGGLE_MOB_HIGHLIGHTS.setBoundKey(InputUtil.fromKeyCode(config.mobHighlightsKey, 0));
        OPEN_SETTINGS.setBoundKey(InputUtil.fromKeyCode(config.settingsKey, 0));
        CLEAR_SESSION.setBoundKey(InputUtil.fromKeyCode(config.clearSessionKey, 0));
        CLEAR_CHUNKS.setBoundKey(InputUtil.fromKeyCode(config.clearChunksKey, 0));
    }

    private void renderHud(net.minecraft.client.gui.DrawContext drawContext) {
        if (!hudOpen || state == null) return;
        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer textRenderer = client.textRenderer;
        int x = config.hudX;
        int y = config.hudY;
        int lineCount = state.currentMobCount() + state.sessionCount()
                + state.visiblePlayers().size() + 9;
        drawContext.fill(x - 4, y - 4, x + config.hudWidth, y + lineCount * 11 + 6,
                0x880B0710);
        drawContext.drawTextWithShadow(textRenderer, Text.literal("DF Mob Visualizer  [F8]"), x, y, 0xFFE8D7FF);
        y += 12;
        drawContext.getMatrices().push();
        drawContext.getMatrices().scale(config.hudScale, config.hudScale, 1.0f);
        drawContext.getMatrices().scale(config.hudTextScale, config.hudTextScale, 1.0f);
        x = Math.round(x / (config.hudScale * config.hudTextScale));
        y = Math.round(y / (config.hudScale * config.hudTextScale));
        drawContext.drawTextWithShadow(textRenderer, Text.literal("Мобов сейчас: " + state.currentMobCount()
                + "   Сессия: " + state.sessionCount() + "   Чанков: " + state.visibleChunks().size()), x, y, 0xFFFFFFFF);
        y += 12;
        drawContext.drawTextWithShadow(textRenderer, Text.literal("MAX ID сейчас: " + state.currentMaxId()
                + "   MAX ID за историю: " + state.maxSeenId()), x, y, 0xFFFFFFFF);
        y += 12;
        drawContext.drawTextWithShadow(textRenderer, Text.literal("F7 — мобы | F9 — чанки | F10 — настройки | F8 — HUD"), x, y, 0xFFB9A7C9);
        y += 14;
        drawContext.drawTextWithShadow(textRenderer,
                Text.literal(centerChunkX == Integer.MIN_VALUE ? "ЦЕНТР: нет подходящих alert-мобов"
                        : "ЦЕНТР: X " + (centerChunkX * 16 + 8) + " Z " + (centerChunkZ * 16 + 8)
                        + " (" + centerMarkerCount + " мобов)"),
                x, y, centerChunkX == Integer.MIN_VALUE ? 0xFFB9A7C9 : config.alertColor);
        y += 14;

        for (TrackedMob mob : state.visibleMobs()) {
            int color = mob.alert() ? config.alertColor : mob.color();
            drawContext.drawTextWithShadow(textRenderer, Text.literal((mob.alert() ? "[ALERT] " : "")
                    + (mob.chargedCreeper() ? "[CHARGED] " : "")
                    + mob.name() + "  ID-" + mob.id()
                    + "  [" + mob.chunkX() + ", " + mob.chunkZ() + "]"), x, y, color);
            y += 11;
        }
        y += 3;
        drawContext.drawTextWithShadow(textRenderer,
                Text.literal("ИГРОКИ (" + state.visiblePlayers().size() + ")"), x, y, 0xFF9EDBFF);
        y += 12;
        for (TrackedMob mob : state.visiblePlayers()) {
            drawContext.drawTextWithShadow(textRenderer,
                    Text.literal(mob.name() + " ID-" + mob.id()
                            + " [" + mob.chunkX() + ", " + mob.chunkZ() + "]"),
                    x, y, mob.color());
            y += 11;
        }
        y += 3;
        drawContext.drawTextWithShadow(textRenderer, Text.literal("СЕССИЯ (" + state.sessionCount() + ")"), x, y, config.alertColor);
        y += 12;
        for (TrackedMob mob : state.visibleSession()) {
            int color = mob.alert() ? config.alertColor : mob.color();
            String reason = mob.player() ? "PLAYER" : mob.hurt() ? "HURT" : mob.hostile() ? "HOSTILE" : "LOW_ID";
            drawContext.drawTextWithShadow(textRenderer, Text.literal("[" + reason + "] "
                    + mob.name() + "  ID-" + mob.id()
                    + "  [" + mob.chunkX() + ", " + mob.chunkZ() + "]"), x, y, color);
            y += 11;
        }
        drawContext.getMatrices().pop();
    }

    private void renderChunks(WorldRenderContext context) {
        if (!chunksOpen || state == null || context.consumers() == null) return;
        Camera camera = context.camera();
        Vec3d cameraPos = camera.getPos();
        try {
            VertexConsumer vertices = context.consumers().getBuffer(RenderLayer.getLines());
            RenderSystem.lineWidth(2.0f);

            for (ChunkMark mark : state.visibleChunks()) {
                double centerX = mark.chunkX() * 16.0 + 8.0;
                double centerZ = mark.chunkZ() * 16.0 + 8.0;
                if (Math.abs(centerX - cameraPos.x) > config.renderDistanceChunks * 16
                        || Math.abs(centerZ - cameraPos.z) > config.renderDistanceChunks * 16) continue;
                float r = ((mark.color() >>> 16) & 255) / 255f;
                float g = ((mark.color() >>> 8) & 255) / 255f;
                float b = (mark.color() & 255) / 255f;
                MinecraftClient client = MinecraftClient.getInstance();
                int surfaceY = client.world == null ? 0
                        : client.world.getTopY(Heightmap.Type.WORLD_SURFACE,
                        mark.chunkX() * 16 + 8, mark.chunkZ() * 16 + 8);
                if (config.showChunkFill) {
                    VertexConsumer fillVertices = context.consumers()
                            .getBuffer(RenderLayer.getDebugQuads());
                    drawFill(fillVertices, centerX - 8 - cameraPos.x, surfaceY - cameraPos.y + 0.03,
                            centerZ - 8 - cameraPos.z, r, g, b, clampOpacity(config.chunkOpacity));
                }
                drawBox(vertices, centerX - 8 - cameraPos.x, 0 - cameraPos.y,
                        centerZ - 8 - cameraPos.z, r, g, b,
                        clampOpacity(config.chunkBorderOpacity));
            }
        } catch (IllegalStateException ignored) {
            // A stale/closed shared buffer must not crash the render thread.
        }
    }

    private static void drawFill(VertexConsumer v, double x, double y, double z,
                                 float r, float g, float b, float a) {
        double x2 = x + 16;
        double z2 = z + 16;
        v.vertex((float) x, (float) y, (float) z).color(r, g, b, a);
        v.vertex((float) x2, (float) y, (float) z).color(r, g, b, a);
        v.vertex((float) x2, (float) y, (float) z2).color(r, g, b, a);
        v.vertex((float) x, (float) y, (float) z2).color(r, g, b, a);
    }

    private static float clampOpacity(float opacity) {
        return Math.max(0.0f, Math.min(1.0f, opacity));
    }

    private static void drawBox(VertexConsumer v, double x, double y, double z,
                                float r, float g, float b, float a) {
        double x2 = x + 16;
        double z2 = z + 16;
        line(v, x, y, z, x2, y, z2, r, g, b, a);
        line(v, x2, y, z, x2, y, z2, r, g, b, a);
        line(v, x2, y, z2, x, y, z2, r, g, b, a);
        line(v, x, y, z2, x, y, z, r, g, b, a);
    }

    private static void line(VertexConsumer v, double x1, double y1, double z1,
                             double x2, double y2, double z2,
                             float r, float g, float b, float a) {
        v.vertex((float) x1, (float) y1, (float) z1).color(r, g, b, a).normal(0, 1, 0);
        v.vertex((float) x2, (float) y2, (float) z2).color(r, g, b, a).normal(0, 1, 0);
    }

    private static boolean isHostile(String type) {
        return type.contains("zombie") || type.contains("skeleton") || type.contains("creeper")
                || type.contains("spider") || type.contains("enderman") || type.contains("blaze")
                || type.contains("witch") || type.contains("phantom") || type.contains("pillager")
                || type.contains("vindicator") || type.contains("evoker") || type.contains("ravager")
                || type.contains("guardian") || type.contains("shulker") || type.contains("hoglin")
                || type.contains("piglin") || type.contains("breeze") || type.contains("slime");
    }

    private boolean shouldHighlight(TrackedMob mob) {
        if (config.highlightEntityTypes != null && !config.highlightEntityTypes.isBlank()
                && !matchesConfiguredType(mob, config.highlightEntityTypes)) {
            return false;
        }
        boolean lowId = state.currentMaxId() > 0
                && mob.id() * 100.0 / state.currentMaxId() < config.highlightPercentLimit;
        return (config.highlightSessionMobs && state.isInSession(mob.id()))
                || (config.highlightAlertMobs && mob.alert())
                || (config.highlightLowIds && lowId)
                || (config.highlightHurtMobs && mob.hurt())
                || (config.highlightHostileMobs && mob.hostile())
                || (config.highlightPlayers && mob.player());
    }

    private static boolean matchesConfiguredType(TrackedMob mob, String configured) {
        String normalized = mob.type().toLowerCase(Locale.ROOT);
        for (String raw : configured.split(",")) {
            String wanted = raw.trim().toLowerCase(Locale.ROOT);
            if (wanted.isBlank()) continue;
            if (!wanted.contains(":")) wanted = "minecraft:" + wanted;
            if (mob.chargedCreeper() && wanted.endsWith(":charged_creeper")) return true;
            if (normalized.equals(wanted) || normalized.endsWith(":" + wanted.substring(wanted.indexOf(':') + 1))) {
                return true;
            }
        }
        return false;
    }

    private boolean centerMatches(TrackedMob mob) {
        boolean lowId = state.currentMaxId() > 0
                && mob.id() * 100.0 / state.currentMaxId() < config.centerPercentLimit;
        boolean category = (config.centerAlertMobs && mob.alert())
                || (config.centerSessionMobs && state.isInSession(mob.id()))
                || (config.centerLowIds && lowId)
                || (config.centerHurtMobs && mob.hurt())
                || (config.centerHostileMobs && mob.hostile())
                || (config.centerPlayers && mob.player());
        return category;
    }

    private void calculateCenter(List<TrackedMob> markers) {
        if (markers.size() < 2) {
            centerChunkX = Integer.MIN_VALUE;
            centerChunkZ = Integer.MIN_VALUE;
            centerMarkerCount = 0;
            return;
        }
        List<Integer> xs = markers.stream().map(TrackedMob::x).sorted().toList();
        List<Integer> zs = markers.stream().map(TrackedMob::z).sorted().toList();
        int minX = xs.get(0), maxX = xs.get(xs.size() - 1);
        int minZ = zs.get(0), maxZ = zs.get(zs.size() - 1);
        if (Math.max(maxX - minX, maxZ - minZ) > 128) {
            centerChunkX = Integer.MIN_VALUE;
            centerChunkZ = Integer.MIN_VALUE;
            centerMarkerCount = 0;
            return;
        }
        centerChunkX = xs.get(xs.size() / 2) >> 4;
        centerChunkZ = zs.get(zs.size() / 2) >> 4;
        centerMarkerCount = markers.size();
    }
}