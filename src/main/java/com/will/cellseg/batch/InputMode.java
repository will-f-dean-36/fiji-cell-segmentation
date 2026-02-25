package com.will.cellseg.batch;

public enum InputMode {
    CONTAINER_SERIES_PAIR,
    FILE_LIST_PAIR,
    SAME_FILE_CHANNELS;

    public static InputMode fromName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Input mode is required.");
        }
        for (InputMode mode : values()) {
            if (mode.name().equalsIgnoreCase(name.trim())) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unsupported input mode: " + name);
    }
}
