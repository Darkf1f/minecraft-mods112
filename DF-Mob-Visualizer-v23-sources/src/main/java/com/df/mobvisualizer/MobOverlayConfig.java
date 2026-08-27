package com.df.mobvisualizer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persistent client-side settings. Colors are ARGB hex values.
 */
public final class MobOverlayConfig {
    public int purpleIdLimit = 10_000;
    /**
     * Chunk rules are checked from left to right. The first matching rule
     * marks the chunk and supplies its color. Examples:
     * id<50=#C855E8FF;id<10000=#FF7A0000;percent<5=#FFFF2020
     */
    public boolean markOnlyRuleChunks = true;
    public String chunkColorRules =
            "id<50=#C855E8FF;id<10000=#FF7A0000;"
                    + "percent<5=#FF7A0000;percent<20=#FFFF2020;"
                    + "percent<30=#FFFF7A00;percent<50=#FFFFB000";
    /** Safe mode: no entity scan, glow, HUD or chunk rendering until enabled. */
    public boolean enabled = false;
    public double darkRedPercent = 5.0;
    public double redPercent = 20.0;
    public double darkOrangePercent = 30.0;
    public double orangePercent = 50.0;
    /**
     * Flexible first-match rules for entity-label colors. Supports id and
     * percent comparisons, for example:
     * id<5000=#C855E8FF;id<10000=#FFFF2020;percent<42=#FFFFB000
     */
    /** ID rules always win over percentage rules. Maximum: ten rules per field. */
    public String idColorRules = "id<=10001=#C855E8FF";
    /** Percentage bands are evaluated from left to right after idColorRules. */
    public String percentColorRules = "percent<30=#FFFF2020;percent<50=#FFFFB000";
    public double sessionPercentLimit = 15.0;
    public double highlightPercentLimit = 15.0;
    public double centerPercentLimit = 15.0;
    public int scanIntervalTicks = 5;

    public int purpleColor = 0xC855E8FF;
    public int darkRedColor = 0xFF7A0000;
    public int redColor = 0xFFFF2020;
    public int darkOrangeColor = 0xFFFF7A00;
    public int orangeColor = 0xFFFFB000;
    public int alertColor = 0xFFFFD34E;
    public int chargedCreeperColor = 0xFFB36BFF;
    public int hurtColor = 0xFFFF3030;

    public boolean showHud = false;
    public boolean showChunkOverlay = false;
    public boolean seeThroughMobs = false;
    public boolean highlightSessionMobs = true;
    public boolean highlightAlertMobs = true;
    public boolean highlightLowIds = true;
    /** A damaged entity must be visible in the HUD immediately. */
    public boolean highlightHurtMobs = true;
    public boolean highlightHostileMobs = true;
    public boolean highlightPlayers = false;
    public boolean centerAlertMobs = true;
    public boolean centerSessionMobs = false;
    public boolean centerLowIds = false;
    public boolean centerHurtMobs = false;
    public boolean centerHostileMobs = true;
    public boolean centerPlayers = false;
    /**
     * The old chunk-wide quad is disabled by default. Per-block top outlines
     * are clearer and do not hide the terrain or water underneath.
     */
    /** Draw the saved chunk history as a visible surface overlay by default. */
    public boolean showChunkFill = true;
    public boolean sessionEnabled = true;
    public boolean pinLowIds = true;
    /** If non-empty, only these types can enter the session through LOW_ID. */
    public String lowIdEntityTypes = "";
    public boolean pinHurtMobs = true;
    public boolean pinHostileMobs = false;
    public boolean pinPlayers = true;
    public boolean showPlayers = true;
    public boolean hostileOnly = false;
    public boolean includeOtherEntities = false;
    public int hudSortMode = 0;
    public int hudKey = 297;
    public int chunksKey = 298;
    public int mobHighlightsKey = 296;
    public int settingsKey = 299;
    /** F5/F6 are deliberately safe defaults: clearing does not require opening the menu. */
    public int clearSessionKey = 294;
    public int clearChunksKey = 295;
    public int hudScanCode = 0;
    public int chunksScanCode = 0;
    public int mobHighlightsScanCode = 0;
    public int settingsScanCode = 0;
    public int clearSessionScanCode = 0;
    public int clearChunksScanCode = 0;
    public float hudScale = 1.0f;
    public float hudTextScale = 1.0f;
    public int hudWidth = 420;
    public int hudX = 8;
    public int hudY = 8;
    /** Base opacity of the colored surface overlay. */
    public float chunkOpacity = 0.55f;
    /** Opacity of the surface/chunk border. */
    public float chunkBorderOpacity = 0.95f;
    /**
     * Extra multiplier for surface visibility. This is deliberately separate
     * from chunkOpacity so the user can test whether the issue is simply
     * insufficient alpha without changing the saved rule colors.
     */
    public float chunkFillStrength = 1.0f;
    public double renderDistanceChunks = 12.0;
    /** Comma-separated entity ids/types to pin in addition to the category rules. */
    public String pinnedEntityTypes = "";
    /** When non-empty, only these entity ids receive the through-wall glow. */
    public String highlightEntityTypes = "";
    /** Comma-separated entity id=color pairs, e.g. minecraft:zombie=#FFFF0000. */
    public String customMobColors = "";
    public boolean persistSession = true;
    /** ALERT is disabled by default until explicitly enabled in the menu. */
    public boolean alertEnabled = true;
    /** 0 = fixed MAX_ID gap, 1 = percentage of MAX_ID. */
    public int alertMode = 0;
    public int alertGap = 100_000;
    public double alertPercent = 80.0;
    /** Empty means every entity type is eligible; otherwise this is an allow-list. */
    public String alertEntityTypes = "";
    /** Empty means every ALERT entity type may enter the session. */
    public String alertSessionEntityTypes = "";
    public boolean alertShowHud = true;
    public boolean alertPinSession = true;
    public boolean alertHighlight = true;
    public boolean alertCenter = true;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir()
            .resolve("df-mob-visualizer.json");

    public static MobOverlayConfig load() {
        if (!Files.exists(FILE)) return new MobOverlayConfig();
        try (Reader reader = Files.newBufferedReader(FILE)) {
            MobOverlayConfig config = GSON.fromJson(reader, MobOverlayConfig.class);
            return config == null ? new MobOverlayConfig() : config;
        } catch (Exception e) {
            System.err.println("[DF Mob Visualizer] Failed to load config: " + e.getMessage());
            return new MobOverlayConfig();
        }
    }

    public void save() {
        try (Writer writer = Files.newBufferedWriter(FILE)) {
            GSON.toJson(this, writer);
        } catch (IOException ignored) {
            // A failed config write must not stop the client render loop.
        }
    }

    public void normalize() {
        purpleIdLimit = Math.max(0, purpleIdLimit);
        darkRedPercent = clampPercent(darkRedPercent);
        redPercent = clampPercent(redPercent);
        darkOrangePercent = clampPercent(darkOrangePercent);
        orangePercent = clampPercent(orangePercent);
        sessionPercentLimit = clampPercent(sessionPercentLimit);
        highlightPercentLimit = clampPercent(highlightPercentLimit);
        centerPercentLimit = clampPercent(centerPercentLimit);
        scanIntervalTicks = Math.max(1, Math.min(100, scanIntervalTicks));
        hudScale = Math.max(0.5f, Math.min(2.0f, hudScale));
        hudTextScale = Math.max(0.5f, Math.min(2.0f, hudTextScale));
        hudWidth = Math.max(220, Math.min(1200, hudWidth));
        chunkOpacity = Math.max(0.0f, Math.min(1.0f, chunkOpacity));
        chunkBorderOpacity = Math.max(0.0f, Math.min(1.0f, chunkBorderOpacity));
        chunkFillStrength = Math.max(0.1f, Math.min(3.0f, chunkFillStrength));
        renderDistanceChunks = Math.max(1.0, renderDistanceChunks);
    }

    private static double clampPercent(double v) {
        return Math.max(0.0, Math.min(100.0, v));
    }
}
