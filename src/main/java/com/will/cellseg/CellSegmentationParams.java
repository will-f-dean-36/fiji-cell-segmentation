package com.will.cellseg;

/** Immutable parameter bundle for a single segmentation run. */
public final class CellSegmentationParams {
    public final int minArea;
    public final String thrMethod;
    public final boolean darkObjects;
    public final boolean pauseThreshold;
    public final boolean showSteps;
    public final boolean showLabelOverlay;
    public final boolean clearRM;
    public final EdgeDetector edgeDetector;
    public final int measurements;
    public final String labelsLut;
    public final boolean showResultsTable;
    public final boolean showRoiManager;

    public CellSegmentationParams(
            int minArea,
            String thrMethod,
            boolean darkObjects,
            boolean pauseThreshold,
            boolean showSteps,
            boolean showLabelOverlay,
            boolean clearRM,
            EdgeDetector edgeDetector,
            int measurements,
            String labelsLut,
            boolean showResultsTable,
            boolean showRoiManager) {

        this.minArea = minArea;
        this.thrMethod = thrMethod;
        this.darkObjects = darkObjects;
        this.pauseThreshold = pauseThreshold;
        this.showSteps = showSteps;
        this.showLabelOverlay = showLabelOverlay;
        this.clearRM = clearRM;
        this.edgeDetector = edgeDetector;
        this.measurements = measurements;
        this.labelsLut = labelsLut;
        this.showResultsTable = showResultsTable;
        this.showRoiManager = showRoiManager;
    }
}
