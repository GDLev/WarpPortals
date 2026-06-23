package dev.gdlev.warpPortals.Storage;

public enum StorageType {
    YML;

    public static StorageType fromConfig(String value) {
        return YML;
    }
}
