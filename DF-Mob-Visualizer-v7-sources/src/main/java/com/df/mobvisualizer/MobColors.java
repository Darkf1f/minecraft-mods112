package com.df.mobvisualizer;

import java.util.Locale;

public final class MobColors {
    private MobColors() {}

    public static int forId(int id, int maxId, MobOverlayConfig config) {
        // Explicit ID exceptions are always stronger than percentage bands.
        Integer ruleColor = ruleColor(id, maxId, config.idColorRules, false);
        if (ruleColor != null) return ruleColor;
        ruleColor = ruleColor(id, maxId, config.percentColorRules, true);
        if (ruleColor != null) return ruleColor;
        if (id < config.purpleIdLimit) return config.purpleColor;
        if (maxId <= 0) return config.orangeColor;
        double percent = id * 100.0 / maxId;
        if (percent < config.darkRedPercent) return config.darkRedColor;
        if (percent < config.redPercent) return config.redColor;
        if (percent < config.darkOrangePercent) return config.darkOrangeColor;
        if (percent < config.orangePercent) return config.orangeColor;
        return 0xFF9E9E9E;
    }

    private static Integer ruleColor(int id, int maxId, String rules, boolean percentageOnly) {
        if (rules == null || rules.isBlank()) return null;
        double percent = maxId <= 0 ? 100.0 : id * 100.0 / maxId;
        int validRules = 0;
        for (String raw : rules.split(";")) {
            if (validRules >= 10) break;
            String[] pair = raw.trim().split("=", 2);
            if (pair.length != 2) continue;
            String condition = pair[0].trim().toLowerCase(Locale.ROOT).replace(" ", "");
            try {
                boolean percentRule = condition.startsWith("percent");
                if (percentageOnly != percentRule) continue;
                if (!percentRule && !condition.startsWith("id")) continue;
                double value = percentRule ? percent : id;
                String expression = percentRule ? condition.substring("percent".length())
                        : condition.substring("id".length());
                boolean matches;
                if (expression.startsWith("<=")) matches = value <= Double.parseDouble(expression.substring(2));
                else if (expression.startsWith(">=")) matches = value >= Double.parseDouble(expression.substring(2));
                else if (expression.startsWith("<")) matches = value < Double.parseDouble(expression.substring(1));
                else if (expression.startsWith(">")) matches = value > Double.parseDouble(expression.substring(1));
                else if (expression.startsWith("=")) matches = value == Double.parseDouble(expression.substring(1));
                else continue;
                if (!matches) continue;
                String hex = pair[1].trim().replace("#", "").replace("0x", "").replace("0X", "");
                validRules++;
                return parseColor(hex);
            } catch (NumberFormatException ignored) {
                // Skip only the malformed rule.
            }
            validRules++;
        }
        return null;
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
                    String value = pair[1].trim().replace("#", "").replace("0x", "").replace("0X", "");
                    return parseColor(value);
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
                boolean percentRule = condition.startsWith("percent");
                String prefix = percentRule ? "percent" : "id";
                if (!condition.startsWith(prefix)) continue;
                String expression = condition.substring(prefix.length()).trim();
                String operator;
                if (expression.startsWith("<=") || expression.startsWith(">=")) operator = expression.substring(0, 2);
                else if (expression.startsWith("<") || expression.startsWith(">") || expression.startsWith("="))
                    operator = expression.substring(0, 1);
                else continue;
                double limit = Double.parseDouble(expression.substring(operator.length()).trim());
                double value = percentRule ? percent : id;
                boolean matches = switch (operator) {
                    case "<" -> value < limit;
                    case "<=" -> value <= limit;
                    case ">" -> value > limit;
                    case ">=" -> value >= limit;
                    default -> value == limit;
                };
                if (!matches) continue;

                String hex = pair[1].trim().replace("#", "").replace("0x", "")
                        .replace("0X", "");
                return parseColor(hex);
            } catch (NumberFormatException ignored) {
                // Ignore malformed rules and continue with the next one.
            }
        }
        return null;
    }

    public static float alpha(int color) {
        return ((color >>> 24) & 255) / 255.0f;
    }

    /** Parses only RGB/ARGB values so malformed menu rules cannot poison colors. */
    private static int parseColor(String value) {
        if (value.length() != 6 && value.length() != 8) throw new NumberFormatException("color length");
        long parsed = Long.parseLong(value, 16);
        return value.length() == 6 ? (int) (0xFF000000L | parsed) : (int) parsed;
    }
}