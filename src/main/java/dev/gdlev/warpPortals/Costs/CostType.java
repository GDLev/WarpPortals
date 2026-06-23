package dev.gdlev.warpPortals.Costs;

import java.util.Locale;

public enum CostType {
    NONE,
    XP,
    LEVEL,
    VAULT,
    ITEM;

    public static CostType fromConfig(String value) {
        if (value == null) {
            return NONE;
        }

        try {
            return CostType.valueOf(value.trim().replace("-", "_").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return NONE;
        }
    }
}
