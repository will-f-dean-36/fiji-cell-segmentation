package com.will.cellseg;

/** Supported edge detection options and their UI labels. */
public enum EdgeDetector {
    SOBEL("Sobel (Gradient)"),
    PREWITT("Prewitt"),
    SCHARR("Scharr"),
    LAPLACIAN_3X3("Laplacian (3x3)"),
    NONE("None");

    public final String label;

    EdgeDetector(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }

    public static EdgeDetector fromLabel(String s) {
        if (s == null) return SOBEL;
        for (EdgeDetector e : values()) {
            if (e.label.equalsIgnoreCase(s.trim())) return e;
        }
        return SOBEL;
    }
}
