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
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.InputUtil;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.world.Heightmap;
import net.minecraft.world.RaycastContext;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Box;
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
    private boolean clearSessionScanDown;
    private boolean clearChunksScanDown;
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
        WorldRenderEvents.LAST.register(this::renderThroughWalls);
        WorldRenderEvents.LAST.register(this::renderChunks);
    }

    private void tick(MinecraftClient client) {
        while (TOGGLE_HUD.wasPressed()) { hudOpen = !hudOpen; config.showHud = hudOpen; config.save(); }
        while (TOGGLE_CHUNKS.wasPressed()) { chunksOpen = !chunksOpen; config.showChunkOverlay = chunksOpen; config.save(); }
        while (TOGGLE_MOB_HIGHLIGHTS.wasPressed()) {
            mobHighlightsOpen = !mobHighlightsOpen; config.seeThroughMobs = mobHighlightsOpen; config.save();
        }
        // F10 changes config values while this instance is running.
        hudOpen = config.showHud;
        chunksOpen = config.showChunkOverlay;
        mobHighlightsOpen = config.seeThroughMobs;
        while (OPEN_SETTINGS.wasPressed()) {
            client.setScreen(new MobSettingsScreenV2(client.currentScreen, config, state));
        }
        while (CLEAR_SESSION.wasPressed()) state.clearSession();
        while (CLEAR_CHUNKS.wasPressed()) state.clearChunks();
        boolean clearSessionDown = scanFallbackDown(client, config.clearSessionKey,
                config.clearSessionScanCode);
        if (clearSessionDown && !clearSessionScanDown) state.clearSession();
        clearSessionScanDown = clearSessionDown;
        boolean clearChunksDown = scanFallbackDown(client, config.clearChunksKey,
                config.clearChunksScanCode);
        if (clearChunksDown && !clearChunksScanDown) state.clearChunks();
        clearChunksScanDown = clearChunksDown;
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
            if (entity == client.player) continue;
            if (!config.showPlayers && entity.isPlayer()) continue;
            int id = entity.getId();
            String typeId = entityTypeId(entity);
            int color = MobColors.forEntity(typeId, id, currentMaxId, config);
            boolean alert = isAlert(entity, id, currentMaxId);
            boolean player = entity.isPlayer();
            boolean hostile = isHostile(typeId);
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
            state.accept(new TrackedMob(id, typeId, entity.getName().getString(),
                    entity.getBlockX(), entity.getBlockY(), entity.getBlockZ(), alert, color,
                    player, hostile, hurt, chargedCreeper, entity.hasCustomName()));
            TrackedMob tracked = new TrackedMob(id, typeId, entity.getName().getString(),
                    entity.getBlockX(), entity.getBlockY(), entity.getBlockZ(), alert, color,
                    player, hostile, hurt, chargedCreeper, entity.hasCustomName());
            if (centerMatches(tracked)) centerMarkers.add(tracked);
        }
        calculateCenter(centerMarkers);
        if (client.world.getTime() % 100 == 0) {
            state.saveSessionIfDirty();
            state.saveChunks();
        }
    }

    public static void applyKeyConfig(MobOverlayConfig config) {
        TOGGLE_HUD.setBoundKey(key(config.hudKey, config.hudScanCode));
        TOGGLE_CHUNKS.setBoundKey(key(config.chunksKey, config.chunksScanCode));
        TOGGLE_MOB_HIGHLIGHTS.setBoundKey(key(config.mobHighlightsKey, config.mobHighlightsScanCode));
        OPEN_SETTINGS.setBoundKey(key(config.settingsKey, config.settingsScanCode));
        CLEAR_SESSION.setBoundKey(key(config.clearSessionKey, config.clearSessionScanCode));
        CLEAR_CHUNKS.setBoundKey(key(config.clearChunksKey, config.clearChunksScanCode));
    }

    /**
     * GLFW reports non-US layouts (including Ё) as KEY_UNKNOWN plus a physical
     * scan code. InputUtil.fromKeyCode treats that pair as a keysym, so the
     * binding never receives the press. Preserve the physical binding type.
     */
    private static InputUtil.Key key(int keyCode, int scanCode) {
        if ((keyCode == GLFW.GLFW_KEY_UNKNOWN || keyCode == 0) && scanCode > 0) {
            return InputUtil.Type.SCANCODE.createFromCode(scanCode);
        }
        if (keyCode == GLFW.GLFW_KEY_UNKNOWN || keyCode == 0) {
            return InputUtil.UNKNOWN_KEY;
        }
        return InputUtil.Type.KEYSYM.createFromCode(keyCode);
    }

    private void renderHud(net.minecraft.client.gui.DrawContext drawContext) {
        if (!config.enabled || !hudOpen || state == null) return;
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
            // ID/percentage rule colors are the visual priority. ALERT,
            // HURT and LOW_ID remain visible as tags and do not replace the
            // color chosen in the color-rule menu.
            int color = mob.color();
            drawContext.drawTextWithShadow(textRenderer, Text.literal(statusTags(mob, false)
                    + (mob.chargedCreeper() ? "[CHARGED] " : "")
                    + mob.name() + (mob.renamed() ? " [переименован]" : "") + "  ID-" + mob.id()
                    + " (" + formatPercent(mob.id(), state.currentMaxId()) + "%)"
                    + "  XYZ[" + mob.x() + ", " + mob.y() + ", " + mob.z() + "]"), x, y, color);
            y += 11;
        }
        y += 3;
        drawContext.drawTextWithShadow(textRenderer,
                Text.literal("ИГРОКИ (" + state.visiblePlayers().size() + ")"), x, y, 0xFF9EDBFF);
        y += 12;
        for (TrackedMob mob : state.visiblePlayers()) {
            drawContext.drawTextWithShadow(textRenderer,
                            Text.literal(mob.name() + " ID-" + mob.id()
                            + " XYZ[" + mob.x() + ", " + mob.y() + ", " + mob.z() + "]"),
                    x, y, mob.color());
            y += 11;
        }
        y += 3;
        drawContext.drawTextWithShadow(textRenderer, Text.literal("СЕССИЯ (" + state.sessionCount() + ")"), x, y, config.alertColor);
        y += 12;
        for (TrackedMob mob : state.visibleSession()) {
            int color = mob.color();
            String reason = statusTags(mob, true).replace("[", "").replace("]", "").trim()
                    .replace(" ", ", ");
            if (reason.isBlank()) reason = "MOB";
            drawContext.drawTextWithShadow(textRenderer, Text.literal("[" + reason + "] "
                    + mob.name() + "  ID-" + mob.id()
                    + " (" + formatPercent(mob.id(), state.currentMaxId()) + "%)"
                    + "  XYZ[" + mob.x() + ", " + mob.y() + ", " + mob.z() + "]"), x, y, color);
            y += 11;
        }
        drawContext.getMatrices().pop();
    }

    /**
     * Draws selected mobs as reliable bounding boxes through blocks. A
     * dedicated tessellator buffer is used because the shared world buffer
     * may already have been flushed when LAST is fired.
     */
    private void renderThroughWalls(WorldRenderContext context) {
        if (!config.enabled || !mobHighlightsOpen || !config.seeThroughMobs || state == null
                || MinecraftClient.getInstance().world == null
                || context.matrixStack() == null || context.consumers() == null) return;
        MinecraftClient client = MinecraftClient.getInstance();
        Vec3d cameraPos = context.camera().getPos();
        EntityRenderDispatcher dispatcher = client.getEntityRenderDispatcher();
        BufferBuilder wallLines = Tessellator.getInstance().begin(
                VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
        boolean drewBox = false;
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        try {
            RenderSystem.lineWidth(2.0f);
            for (Entity entity : client.world.getEntities()) {
                if (entity == client.player) continue;
                int id = entity.getId();
                String typeId = entityTypeId(entity);
                TrackedMob mob = new TrackedMob(id, typeId, entity.getName().getString(),
                        entity.getBlockX(), entity.getBlockY(), entity.getBlockZ(), isAlert(entity, id, state.currentMaxId()),
                        MobColors.forEntity(typeId, id, state.currentMaxId(), config),
                        entity.isPlayer(), isHostile(typeId),
                        entity instanceof LivingEntity living && living.hurtTime > 0,
                        entity instanceof CreeperEntity creeper && creeper.isCharged(), entity.hasCustomName());
                if (!shouldHighlight(mob)) continue;
                // The normal world pass already draws visible mobs. Only
                // render an extra model when a block actually occludes it;
                // otherwise cows (especially hurt cows) get a double texture.
                if (!isBehindBlock(client, entity, cameraPos)) continue;
                // Entity render layers can restore depth testing when their
                // buffered vertices are flushed. A line-layer bounding box is
                // a reliable through-wall fallback and remains visible.
                drawEntityBox(wallLines, entity, cameraPos, mob.color(), 0.95f);
                drewBox = true;
                // Also render the actual entity model. The depth-disabled
                // pass makes the model visible through solid blocks on
                // renderers that preserve the current depth state.
                context.matrixStack().push();
                try {
                    dispatcher.render(entity, entity.getX() - cameraPos.x,
                            entity.getY() - cameraPos.y, entity.getZ() - cameraPos.z,
                            entity.getYaw(), context.matrixStack(), context.consumers(),
                            15728880);
                } finally {
                    context.matrixStack().pop();
                }
            }
            // Do not call end() on an empty builder: Minecraft 1.21.4 throws
            // BufferBuilder was empty when no highlighted entity is occluded.
            if (drewBox) {
                var built = wallLines.end();
                RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);
                BufferRenderer.drawWithGlobalProgram(built);
                built.close();
            }
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.enableDepthTest();
        }
    }

    private String statusTags(TrackedMob mob, boolean sessionRow) {
        StringBuilder tags = new StringBuilder();
        if (mob.alert()) tags.append("[ALERT] ");
        if (mob.hurt()) tags.append("[HURT] ");
        // HURT* means that the hurt observation was retained in the session,
        // while HURT means the entity is currently flashing from damage.
        if (sessionRow && mob.hurt() && config.pinHurtMobs) tags.append("[HURT*] ");
        if (mob.hostile()) tags.append("[HOSTILE] ");
        if (state.currentMaxId() > 0
                && mob.id() * 100.0 / state.currentMaxId() < config.sessionPercentLimit) {
            tags.append("[LOW_ID] ");
        }
        if (mob.player()) tags.append("[PLAYER] ");
        return tags.toString();
    }

    private static String formatPercent(int id, int maxId) {
        double percent = maxId <= 0 ? 0.0 : id * 100.0 / maxId;
        return String.format(Locale.ROOT, "%.2f", percent);
    }

    private static boolean isBehindBlock(MinecraftClient client, Entity entity, Vec3d cameraPos) {
        if (client.world == null) return false;
        Vec3d target = entity.getBoundingBox().getCenter();
        var hit = client.world.raycast(new RaycastContext(cameraPos, target,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, entity));
        return hit.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK
                && hit.getPos().squaredDistanceTo(cameraPos) + 0.01
                < target.squaredDistanceTo(cameraPos);
    }

    private static boolean scanFallbackDown(MinecraftClient client, int keyCode, int scanCode) {
        // SCANCODE bindings are handled by KeyBinding.wasPressed(). Polling
        // here is only a compatibility fallback for ordinary key codes.
        return keyCode != GLFW.GLFW_KEY_UNKNOWN && keyCode != 0
                && InputUtil.isKeyPressed(client.getWindow().getHandle(), keyCode);
    }

    private boolean isAlert(Entity entity, int id, int maxId) {
        String typeId = entityTypeId(entity);
        if (!config.alertEnabled || entity.isPlayer() || typeId.endsWith(":enderman")
                || !matchesConfiguredType(typeId, config.alertEntityTypes)) return false;
        if (config.alertMode == 1) return maxId > 0 && id * 100.0 / maxId < config.alertPercent;
        return maxId > 0 && maxId - id > config.alertGap;
    }

    private static String entityTypeId(Entity entity) {
        return Registries.ENTITY_TYPE.getId(entity.getType()).toString().toLowerCase(Locale.ROOT);
    }

    private void renderChunks(WorldRenderContext context) {
        if (!config.enabled || !chunksOpen || state == null || context.consumers() == null) return;
        Camera camera = context.camera();
        Vec3d cameraPos = camera.getPos();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
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
                VertexConsumer blockFills = config.showChunkFill
                        ? context.consumers().getBuffer(RenderLayer.getDebugQuads()) : null;
                drawMarkedBlocks(client, mark, cameraPos, vertices, blockFills, r, g, b);
            }
        } catch (IllegalStateException ignored) {
            // A stale/closed shared buffer must not crash the render thread.
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.disableBlend();
        }
    }

    /**
     * Draws one precise outline for the actual top block in every column of a
     * marked chunk. The previous implementation searched a vertical range
     * and drew a full cube for every matching block; that made overlapping
     * geometry look like one flat translucent patch, especially on water.
     *
     * WORLD_SURFACE returns the first Y above the surface block, so the
     * surface block itself is always at topY - 1. Drawing only that block
     * prevents underground blocks from producing confusing lines.
     */
    private void drawMarkedBlocks(MinecraftClient client, ChunkMark mark, Vec3d cameraPos,
                                  VertexConsumer lines, VertexConsumer fills,
                                  float r, float g, float b) {
        if (client.world == null) return;
        float borderAlpha = clampOpacity(config.chunkBorderOpacity);
        float fillAlpha = clampOpacity(config.chunkOpacity * config.chunkFillStrength);
        for (int x = mark.chunkX() * 16; x < mark.chunkX() * 16 + 16; x++) {
            for (int z = mark.chunkZ() * 16; z < mark.chunkZ() * 16 + 16; z++) {
                int topY = client.world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
                int y = topY - 1;
                if (y < client.world.getBottomY()) continue;
                BlockState block = client.world.getBlockState(
                        new net.minecraft.util.math.BlockPos(x, y, z));

                double bx = x - cameraPos.x;
                double by = y - cameraPos.y;
                double bz = z - cameraPos.z;
                // Every top block participates. Filtering only grass/dirt/
                // water made marked chunks disappear on stone, glass, slabs
                // and other terrain.
                // Draw the complete 1x1x1 block wireframe. Depth is disabled
                // by the world pass, so every edge remains visible.
                drawCube(lines, bx, by, bz,
                        r, g, b, borderAlpha);
                if (fills != null) {
                    drawTopFill(fills, bx + 0.001, by + 1.002, bz + 0.001,
                            r, g, b, fillAlpha);
                }
            }
        }
    }

    private static void drawCube(VertexConsumer v, double x, double y, double z,
                                 float r, float g, float b, float a) {
        double x2 = x + 1, y2 = y + 1, z2 = z + 1;
        line(v, x, y, z, x2, y, z, r, g, b, a);
        line(v, x2, y, z, x2, y, z2, r, g, b, a);
        line(v, x2, y, z2, x, y, z2, r, g, b, a);
        line(v, x, y, z2, x, y, z, r, g, b, a);
        line(v, x, y2, z, x2, y2, z, r, g, b, a);
        line(v, x2, y2, z, x2, y2, z2, r, g, b, a);
        line(v, x2, y2, z2, x, y2, z2, r, g, b, a);
        line(v, x, y2, z2, x, y2, z, r, g, b, a);
        line(v, x, y, z, x, y2, z, r, g, b, a);
        line(v, x2, y, z, x2, y2, z, r, g, b, a);
        line(v, x2, y, z2, x2, y2, z2, r, g, b, a);
        line(v, x, y, z2, x, y2, z2, r, g, b, a);
    }

    private static void drawTopFill(VertexConsumer v, double x, double y, double z,
                                    float r, float g, float b, float a) {
        v.vertex((float) x, (float) y, (float) z).color(r, g, b, a);
        v.vertex((float) (x + 0.97), (float) y, (float) z).color(r, g, b, a);
        v.vertex((float) (x + 0.97), (float) y, (float) (z + 0.97)).color(r, g, b, a);
        v.vertex((float) x, (float) y, (float) (z + 0.97)).color(r, g, b, a);
    }

    private static void drawTopOutline(VertexConsumer v, double x, double y, double z,
                                       float r, float g, float b, float a) {
        double x2 = x + 0.998;
        double z2 = z + 0.998;
        line(v, x, y, z, x2, y, z, r, g, b, a);
        line(v, x2, y, z, x2, y, z2, r, g, b, a);
        line(v, x2, y, z2, x, y, z2, r, g, b, a);
        line(v, x, y, z2, x, y, z, r, g, b, a);
    }

    private static void drawEntityBox(VertexConsumer v, Entity entity, Vec3d cameraPos,
                                      int color, float alpha) {
        Box box = entity.getBoundingBox();
        double minX = box.minX - cameraPos.x;
        double minY = box.minY - cameraPos.y;
        double minZ = box.minZ - cameraPos.z;
        double maxX = box.maxX - cameraPos.x;
        double maxY = box.maxY - cameraPos.y;
        double maxZ = box.maxZ - cameraPos.z;
        float r = ((color >>> 16) & 255) / 255.0f;
        float g = ((color >>> 8) & 255) / 255.0f;
        float b = (color & 255) / 255.0f;

        linePlain(v, minX, minY, minZ, maxX, minY, minZ, r, g, b, alpha);
        linePlain(v, maxX, minY, minZ, maxX, minY, maxZ, r, g, b, alpha);
        linePlain(v, maxX, minY, maxZ, minX, minY, maxZ, r, g, b, alpha);
        linePlain(v, minX, minY, maxZ, minX, minY, minZ, r, g, b, alpha);
        linePlain(v, minX, maxY, minZ, maxX, maxY, minZ, r, g, b, alpha);
        linePlain(v, maxX, maxY, minZ, maxX, maxY, maxZ, r, g, b, alpha);
        linePlain(v, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, alpha);
        linePlain(v, minX, maxY, maxZ, minX, maxY, minZ, r, g, b, alpha);
        linePlain(v, minX, minY, minZ, minX, maxY, minZ, r, g, b, alpha);
        linePlain(v, maxX, minY, minZ, maxX, maxY, minZ, r, g, b, alpha);
        linePlain(v, maxX, minY, maxZ, maxX, maxY, maxZ, r, g, b, alpha);
        linePlain(v, minX, minY, maxZ, minX, maxY, maxZ, r, g, b, alpha);
    }

    private static void linePlain(VertexConsumer v, double x1, double y1, double z1,
                                  double x2, double y2, double z2,
                                  float r, float g, float b, float a) {
        v.vertex((float) x1, (float) y1, (float) z1).color(r, g, b, a);
        v.vertex((float) x2, (float) y2, (float) z2).color(r, g, b, a);
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
                || (config.highlightAlertMobs && config.alertHighlight && mob.alert())
                || (config.highlightLowIds && lowId)
                || (config.highlightHurtMobs && mob.hurt())
                || (config.highlightHostileMobs && mob.hostile())
                || (config.highlightPlayers && mob.player());
    }

    private static boolean matchesConfiguredType(TrackedMob mob, String configured) {
        if (configured == null || configured.isBlank()) return true;
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

    private static boolean matchesConfiguredType(String type, String configured) {
        if (configured == null || configured.isBlank()) return true;
        String normalized = type.toLowerCase(Locale.ROOT);
        for (String raw : configured.split(",")) {
            String wanted = raw.trim().toLowerCase(Locale.ROOT);
            if (wanted.isBlank()) continue;
            if (!wanted.contains(":")) wanted = "minecraft:" + wanted;
            if (normalized.equals(wanted) || normalized.endsWith(":" + wanted.substring(wanted.indexOf(':') + 1))) {
                return true;
            }
        }
        return false;
    }

    private boolean centerMatches(TrackedMob mob) {
        boolean lowId = state.currentMaxId() > 0
                && mob.id() * 100.0 / state.currentMaxId() < config.centerPercentLimit;
        boolean category = (config.centerAlertMobs && config.alertCenter && mob.alert())
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