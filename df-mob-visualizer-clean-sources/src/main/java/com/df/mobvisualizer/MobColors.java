package com.df.mobvisualizer;

import java.util.Locale;

public final class MobColors {
    private MobColors() {}

    public static int forId(int id, int maxId, MobOverlayConfig config) {
        if (id < config.purpleIdLimit) return config.purpleColor;
        if (maxId <= 0) return config.orangeColor;
        double percent = id * 100.0 / maxId;
        if (percent < config.darkRedPercent) return config.darkRedColor;
        if (percent < config.redPercent) return config.redColor;
        if (percent < config.darkOrangePercent) return config.darkOrangeColor;
        if (percent < config.orangePercent) return config.orangeColor;
        return 0xFF9E9E9E;
    }

    public static int forEntity(String type, int id, int maxId, MobOverlayConfig config) {
        Integer custom = customColor(type, config);
        return custom != null ? custom : forId(id, maxId, config);
    }

    /** Returns a user-defined per-entity color, or null when no override exists. */
    public static Integer customColor(String type, MobOverlayConfig config) {
        if (config.customMobColors != null && !config.customMobColors.isBlank()) {
            String normalizedType = type.toLowerCase(Locale.ROOT);
            for (String entry : config.customMobColors.split(",")) {
                String[] pair = entry.trim().split("=", 2);
                if (pair.length != 2 || !normalizedType.equals(pair[0].trim().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                try {
                    String value = pair[1].trim().replace("#", "");
                    long parsed = Long.parseLong(value, 16);
                    return value.length() <= 6 ? (int) (0xFF000000L | parsed) : (int) parsed;
                } catch (NumberFormatException ignored) {
                    // Ignore one malformed custom entry and continue with defaults.
                }
            }
        }
        return null;
    }

    /**
     * Returns the color for a chunk-marking rule, or null when this entity
     * should not create/update a chunk mark. Rules are intentionally local:
     * this method only reads client-side values and never sends packets.
     */
    public static Integer chunkColor(int id, int maxId, MobOverlayConfig config) {
        if (!config.markOnlyRuleChunks) return forEntity("", id, maxId, config);
        if (config.chunkColorRules == null || config.chunkColorRules.isBlank()) return null;

        double percent = maxId <= 0 ? 100.0 : id * 100.0 / maxId;
        for (String rawRule : config.chunkColorRules.split(";")) {
            String[] pair = rawRule.trim().split("=", 2);
            if (pair.length != 2) continue;
            String condition = pair[0].trim().toLowerCase(Locale.ROOT);
            try {
                boolean percentRule = condition.startsWith("percent<");
                String prefix = percentRule ? "percent<" : "id<";
                if (!condition.startsWith(prefix)) continue;
                double limit = Double.parseDouble(condition.substring(prefix.length()).trim());
                double value = percentRule ? percent : id;
                if (value >= limit) continue;

                String hex = pair[1].trim().replace("#", "").replace("0x", "")
                        .replace("0X", "");
                long parsed = Long.parseLong(hex, 16);
                return hex.length() <= 6 ? (int) (0xFF000000L | parsed) : (int) parsed;
            } catch (NumberFormatException ignored) {
                // Ignore malformed rules and continue with the next one.
            }
        }
        return null;
    }

    public static float alpha(int color) {
        return ((color >>> 24) & 255) / 255.0f;
    }
}