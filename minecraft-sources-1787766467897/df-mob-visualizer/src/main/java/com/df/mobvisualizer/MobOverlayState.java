package com.df.mobvisualizer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Holds live and persisted observations independently from the HUD.
 */
public final class MobOverlayState {
    private final MobOverlayConfig config;
    private final Map<Integer, TrackedMob> mobs = new HashMap<>();
    private final Map<Integer, TrackedMob> session = new HashMap<>();
    private final Map<Long, ChunkMark> chunks = new HashMap<>();
    private int maxId;
    private int currentMaxId;
    private boolean sessionDirty;
    private boolean chunksDirty;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir()
            .resolve("df-mob-visualizer-chunks.json");
    private static final Path SESSION_FILE = FabricLoader.getInstance().getConfigDir()
            .resolve("df-mob-visualizer-session.json");
    private static final Path MAX_ID_FILE = FabricLoader.getInstance().getConfigDir()
            .resolve("df-mob-visualizer-max-id.json");

    public MobOverlayState(MobOverlayConfig config) {
        this.config = config;
        loadChunks();
        loadSession();
        loadMaxId();
    }

    /**
     * Live entities are rebuilt on every scan. Chunk marks are deliberately
     * kept separately because they represent the persistent discovery history.
     */
    public synchronized void beginLiveScan(int currentMaxId) {
        mobs.clear();
        this.currentMaxId = Math.max(0, currentMaxId);
        if (this.currentMaxId > maxId) {
            maxId = this.currentMaxId;
            saveMaxId();
        }
    }

    public synchronized void accept(TrackedMob mob) {
        mobs.put(mob.id(), mob);
        boolean explicitType = pinnedTypes().stream().anyMatch(type ->
                mob.type().equalsIgnoreCase(type) || mob.type().toLowerCase(Locale.ROOT).endsWith(":" + type));
        boolean pin = config.sessionEnabled
                && ((config.pinLowIds && lowIdTypeAllowed(mob) && currentMaxId > 0
                    && (mob.id() * 100.0 / Math.max(1, currentMaxId)) < config.sessionPercentLimit)
                // A hit entity is always added to the session so it cannot
                // disappear from the HUD just because a category toggle is off.
                || (config.pinHurtMobs && mob.hurt())
                || (config.pinHostileMobs && mob.hostile())
                || (config.pinPlayers && mob.player())
                || (config.alertPinSession && mob.alert() && alertSessionTypeAllowed(mob))
                || explicitType);
        // Refresh an existing session entry whenever the mob is seen again,
        // even if a setting changed while the chunk was unloaded.
        if (pin || session.containsKey(mob.id())) {
            TrackedMob previous = session.put(mob.id(), mob);
            if (previous == null || !previous.equals(mob)) sessionDirty = true;
        }
        MobColors.ChunkRule rule = MobColors.chunkRule(mob.id(), currentMaxId, config);
        if (rule == null) return;
        long key = chunkKey(mob.chunkX(), mob.chunkZ());
        // The first successful discovery owns the chunk color and timestamp.
        // Seeing another entity later must not downgrade that persistent mark.
        if (chunks.containsKey(key)) return;
        chunks.put(key, new ChunkMark(
                mob.chunkX(),
                mob.chunkZ(),
                rule.color(),
                rule.type(),
                System.currentTimeMillis()));
        chunksDirty = true;
        // A discovery is a durable event, not a live-entity update. Persist
        // it immediately so a crash or world restart cannot lose the mark
        // between scan intervals.
        saveChunks();
    }

    public synchronized Collection<TrackedMob> visibleMobs() {
        ArrayList<TrackedMob> result = new ArrayList<>(mobs.values());
        result.removeIf(TrackedMob::player);
        if (config.hudSortMode == 1) {
            result.sort(Comparator.comparing(TrackedMob::type).thenComparingInt(TrackedMob::id));
        } else if (config.hudSortMode == 2) {
            result.sort(Comparator.<TrackedMob>comparingInt(mob -> mob.player() ? 0 : mob.hostile() ? 1 : 2)
                    .thenComparingInt(TrackedMob::id));
        } else {
            result.sort(Comparator.comparingInt(TrackedMob::id));
        }
        return result;
    }

    public synchronized Collection<TrackedMob> visiblePlayers() {
        ArrayList<TrackedMob> result = new ArrayList<>();
        for (TrackedMob mob : mobs.values()) {
            if (mob.player()) result.add(mob);
        }
        result.sort(Comparator.comparingInt(TrackedMob::id));
        return result;
    }

    public synchronized boolean isInSession(int id) {
        return session.containsKey(id);
    }

    public synchronized Collection<TrackedMob> visibleSession() {
        ArrayList<TrackedMob> result = new ArrayList<>(session.values());
        result.sort(Comparator.comparingInt(TrackedMob::id));
        return result;
    }

    public synchronized int currentMobCount() {
        int count = 0;
        for (TrackedMob mob : mobs.values()) {
            if (!mob.player()) count++;
        }
        return count;
    }

    public synchronized int sessionCount() {
        return session.size();
    }

    public synchronized int maxSeenId() {
        return maxId;
    }

    public synchronized int currentMaxId() {
        return currentMaxId;
    }

    public synchronized Collection<ChunkMark> visibleChunks() {
        return new ArrayList<>(chunks.values());
    }

    public synchronized int maxId() {
        return maxId;
    }

    public synchronized void clearLiveMobs() {
        mobs.clear();
    }

    public synchronized void clearSession() {
        session.clear();
        sessionDirty = false;
        saveSession();
    }

    public synchronized void saveSessionIfDirty() {
        if (!sessionDirty || !config.persistSession) return;
        sessionDirty = false;
        saveSession();
    }

    public synchronized void clearChunks() {
        chunks.clear();
        chunksDirty = true;
        saveChunks();
    }

    public synchronized void clearMaxId() {
        maxId = 0;
        try {
            Files.deleteIfExists(MAX_ID_FILE);
        } catch (Exception ignored) {
        }
    }

    private Set<String> pinnedTypes() {
        if (config.pinnedEntityTypes == null || config.pinnedEntityTypes.isBlank()) return Set.of();
        return Arrays.stream(config.pinnedEntityTypes.split(","))
                .map(value -> value.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "minecraft:"))
                .filter(value -> !value.isBlank())
                .collect(Collectors.toSet());
    }

    private boolean lowIdTypeAllowed(TrackedMob mob) {
        if (config.lowIdEntityTypes == null || config.lowIdEntityTypes.isBlank()) return true;
        return Arrays.stream(config.lowIdEntityTypes.split(","))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .map(value -> value.contains(":") ? value : "minecraft:" + value)
                .anyMatch(value -> mob.type().equalsIgnoreCase(value));
    }

    private boolean alertSessionTypeAllowed(TrackedMob mob) {
        if (config.alertSessionEntityTypes == null || config.alertSessionEntityTypes.isBlank()) {
            return true;
        }
        String mobType = mob.type().toLowerCase(Locale.ROOT);
        return Arrays.stream(config.alertSessionEntityTypes.split(","))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .map(value -> value.contains(":") ? value : "minecraft:" + value)
                .anyMatch(value -> mobType.equals(value)
                        || mobType.endsWith(":" + value.substring(value.indexOf(':') + 1)));
    }

    private synchronized void saveSession() {
        try (Writer writer = Files.newBufferedWriter(SESSION_FILE)) {
            GSON.toJson(new ArrayList<>(session.values()), writer);
        } catch (Exception ignored) {
        }
    }

    private synchronized void loadSession() {
        if (!config.persistSession || !Files.exists(SESSION_FILE)) return;
        try (Reader reader = Files.newBufferedReader(SESSION_FILE)) {
            List<TrackedMob> saved = GSON.fromJson(reader,
                    new TypeToken<List<TrackedMob>>() {}.getType());
            if (saved != null) {
                for (TrackedMob mob : saved) session.put(mob.id(), mob);
            }
        } catch (Exception ignored) {
        }
    }

    private synchronized void loadMaxId() {
        if (!Files.exists(MAX_ID_FILE)) return;
        try (Reader reader = Files.newBufferedReader(MAX_ID_FILE)) {
            Integer saved = GSON.fromJson(reader, Integer.class);
            if (saved != null) maxId = Math.max(0, saved);
        } catch (Exception ignored) {
        }
    }

    private synchronized void saveMaxId() {
        try (Writer writer = Files.newBufferedWriter(MAX_ID_FILE)) {
            GSON.toJson(maxId, writer);
        } catch (Exception ignored) {
        }
    }

    public static long chunkKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    public synchronized void saveChunks() {
        if (!chunksDirty && Files.exists(FILE)) return;
        try (Writer writer = Files.newBufferedWriter(FILE)) {
            GSON.toJson(new ArrayList<>(chunks.values()), writer);
            chunksDirty = false;
        } catch (Exception ignored) {
        }
    }

    private synchronized void loadChunks() {
        if (!Files.exists(FILE)) return;
        try (Reader reader = Files.newBufferedReader(FILE)) {
            JsonArray saved = GSON.fromJson(reader, JsonArray.class);
            if (saved != null) {
                for (JsonElement element : saved) {
                    if (!element.isJsonObject()) continue;
                    JsonObject object = element.getAsJsonObject();
                    if (!object.has("chunkX") || !object.has("chunkZ")
                            || !object.has("color")) continue;
                    int chunkX = object.get("chunkX").getAsInt();
                    int chunkZ = object.get("chunkZ").getAsInt();
                    int color = object.get("color").getAsInt();

                    // v23 used alert/ring/lastSeen. Read that format once so
                    // an existing history is not lost during the migration.
                    boolean legacyRing = object.has("ring") && object.get("ring").getAsBoolean();
                    if (legacyRing) continue;
                    String type = object.has("type")
                            ? object.get("type").getAsString() : "LEGACY_RULE";
                    long discoveredAt = object.has("discoveredAt")
                            ? object.get("discoveredAt").getAsLong()
                            : object.has("lastSeen") ? object.get("lastSeen").getAsLong() : 0L;
                    chunks.put(chunkKey(chunkX, chunkZ),
                            new ChunkMark(chunkX, chunkZ, color, type, discoveredAt));
                }
                // Rewrite migrated entries to the current compact schema.
                chunksDirty = true;
            }
        } catch (Exception ignored) {
        }
    }
}