package com.will.cellseg;

import ij.IJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;

/** Concrete threshold settings that can be reused across batch runs. */
public final class ThresholdConfig {
    private final String method;
    private final boolean darkObjects;
    private final Double minThreshold;
    private final Double maxThreshold;

    private ThresholdConfig(String method, boolean darkObjects, Double minThreshold, Double maxThreshold) {
        this.method = sanitizeMethod(method);
        this.darkObjects = darkObjects;
        this.minThreshold = minThreshold;
        this.maxThreshold = maxThreshold;
    }

    public static ThresholdConfig auto(String method, boolean darkObjects) {
        // "Auto" means "rerun ImageJ's threshold method", not "capture the current
        // threshold slider positions".
        return new ThresholdConfig(method, darkObjects, null, null);
    }

    public static ThresholdConfig manual(String method, boolean darkObjects, double minThreshold, double maxThreshold) {
        return new ThresholdConfig(method, darkObjects, Double.valueOf(minThreshold), Double.valueOf(maxThreshold));
    }

    public String getMethod() {
        return method;
    }

    public boolean isDarkObjects() {
        return darkObjects;
    }

    public boolean isManual() {
        return minThreshold != null && maxThreshold != null;
    }

    public double getMinThreshold() {
        return minThreshold != null ? minThreshold.doubleValue() : ImageProcessor.NO_THRESHOLD;
    }

    public double getMaxThreshold() {
        return maxThreshold != null ? maxThreshold.doubleValue() : ImageProcessor.NO_THRESHOLD;
    }

    public void applyTo(ImagePlus image) {
        if (image == null) {
            return;
        }
        if (isManual()) {
            // Manual thresholds are replayed numerically so a batch run can reuse the
            // exact min/max chosen during an interactive stop-point.
            image.getProcessor().setThreshold(getMinThreshold(), getMaxThreshold(), ImageProcessor.RED_LUT);
            image.updateAndDraw();
            return;
        }

        // For auto mode we delegate back to ImageJ's built-in threshold implementation.
        final String darkOrLight = darkObjects ? "dark" : "light";
        IJ.setAutoThreshold(image, method + " " + darkOrLight);
    }

    private static String sanitizeMethod(String method) {
        return (method == null || method.trim().isEmpty()) ? "Default" : method.trim();
    }
}
