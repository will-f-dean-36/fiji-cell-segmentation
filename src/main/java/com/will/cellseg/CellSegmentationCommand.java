package com.will.cellseg;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import java.io.File;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

@SuppressWarnings({"unused", "FieldMayBeFinal", "CanBeFinal", "FieldCanBeLocal"})
@Plugin(type = Command.class, name = "Cell Segmentation")
public class CellSegmentationCommand implements Command {

    @Parameter
    private ImagePlus imp;

    private int minArea = 500;
    private String thrMethod = "Default";
    private boolean darkObjects = true;
    private boolean pauseThreshold = true;
    private boolean showSteps = false;
    private boolean showLabelOverlay = false;
    private boolean showRoiOverlay = true;
    private String labelsLut = "Rainbow RGB";
    private boolean clearRM = true;
    private boolean excludeBorderTouching = false;
    private boolean autoSave = false;
    private File outputDir = CellSegmentationIO.getDefaultOutputDirectory();
    private boolean saveMask = true;
    private boolean saveLabels = true;
    private boolean saveLabelOverlayToFile = false;
    private boolean saveRois = true;
    private boolean saveMeasurements = true;
    private String edgeMethod = "Sobel (Gradient)";

    private boolean measureArea = true;
    private boolean measureMean = true;
    private boolean measureMinMax = true;
    private boolean measureStdDev = true;
    private boolean measurePerimeter = true;
    private boolean measureCentroid = true;
    private boolean measureRect = true;
    private boolean measureFeret = true;
    private boolean measureShape = true;
    private boolean measureIntDen = true;

    @Override
    public void run() {
        if (imp == null) {
            IJ.noImage();
            return;
        }

        final CellSegmentationDialog.Result options = CellSegmentationDialog.showDialog(
                imp,
                new CellSegmentationDialog.Result(
                        minArea,
                        thrMethod,
                        darkObjects,
                        pauseThreshold,
                        edgeMethod,
                        excludeBorderTouching,
                        showSteps,
                        showLabelOverlay,
                        showRoiOverlay,
                        labelsLut,
                        clearRM,
                        measureArea,
                        measureMean,
                        measureMinMax,
                        measureStdDev,
                        measurePerimeter,
                        measureCentroid,
                        measureRect,
                        measureFeret,
                        measureShape,
                        measureIntDen,
                        autoSave,
                        outputDir,
                        saveMask,
                        saveLabels,
                        saveLabelOverlayToFile,
                        saveRois,
                        saveMeasurements));
        if (options == null) {
            return;
        }

        applyOptions(options);

        final EdgeDetector edgeDetector = EdgeDetector.fromLabel(edgeMethod);

        int measurements = buildMeasurementFlags();
        if (measurements == 0) {
            measurements = ij.measure.Measurements.AREA;
            IJ.log("[CellSegmentation] No measurements selected; defaulting to Area.");
        }

        final CellSegmentationParams p = new CellSegmentationParams(
                minArea,
                thrMethod,
                darkObjects,
                pauseThreshold,
                showSteps,
                showLabelOverlay,
                showRoiOverlay,
                clearRM,
                excludeBorderTouching,
                edgeDetector,
                measurements,
                labelsLut,
                true,
                true
        );

        final boolean prevBlackBg = Prefs.blackBackground;
        try {
            Prefs.blackBackground = true;

            final CellSegmentationResult r = CellSegmentationPipeline.run(imp, p);

            if (r.mask != null) r.mask.show();
            if (r.labels != null) r.labels.show();
            if (autoSave) {
                saveOutputs(r);
            }

            IJ.log("[CellSegmentation] Done: " + r.roiCount + " ROIs");
        } finally {
            Prefs.blackBackground = prevBlackBg;
        }
    }

    private void applyOptions(CellSegmentationDialog.Result options) {
        minArea = options.minArea;
        thrMethod = options.thrMethod;
        darkObjects = options.darkObjects;
        pauseThreshold = options.pauseThreshold;
        edgeMethod = options.edgeMethod;
        excludeBorderTouching = options.excludeBorderTouching;
        showSteps = options.showSteps;
        showLabelOverlay = options.showLabelOverlay;
        showRoiOverlay = options.showRoiOverlay;
        labelsLut = options.labelsLut;
        clearRM = options.clearRM;
        measureArea = options.measureArea;
        measureMean = options.measureMean;
        measureMinMax = options.measureMinMax;
        measureStdDev = options.measureStdDev;
        measurePerimeter = options.measurePerimeter;
        measureCentroid = options.measureCentroid;
        measureRect = options.measureRect;
        measureFeret = options.measureFeret;
        measureShape = options.measureShape;
        measureIntDen = options.measureIntDen;
        autoSave = options.autoSave;
        outputDir = options.outputDir;
        saveMask = options.saveMask;
        saveLabels = options.saveLabels;
        saveLabelOverlayToFile = options.saveLabelOverlayToFile;
        saveRois = options.saveRois;
        saveMeasurements = options.saveMeasurements;
    }

    private void saveOutputs(CellSegmentationResult result) {
        if (result == null) {
            return;
        }
        if (outputDir == null) {
            IJ.error("No output directory selected.");
            return;
        }
        if (!CellSegmentationIO.ensureOutputDirectory(outputDir)) {
            IJ.error("Could not create output directory: " + outputDir.getAbsolutePath());
            return;
        }

        CellSegmentationIO.rememberOutputDirectory(outputDir);

        final String baseName = CellSegmentationIO.stripExtension(imp != null ? imp.getTitle() : "image");
        try {
            if (saveMask && result.mask != null) {
                CellSegmentationIO.saveImage(result.mask, new File(outputDir, baseName + "_mask.tif"));
            }
            if (saveLabels && result.labels != null) {
                CellSegmentationIO.saveImage(result.labels, new File(outputDir, baseName + "_labels.tif"));
            }
            if (saveLabelOverlayToFile && result.labels != null && imp != null) {
                final ImagePlus overlay = CellSegmentationPipeline.createLabelOverlay(imp, result.labels, labelsLut);
                try {
                    if (overlay != null) {
                        CellSegmentationIO.saveImage(overlay, new File(outputDir, baseName + "_overlay.tif"));
                    }
                } finally {
                    closeImage(overlay);
                }
            }
            if (saveRois && result.roiManager != null) {
                CellSegmentationIO.saveRois(result.roiManager.getRoisAsArray(), new File(outputDir, baseName + "_rois.zip"));
            }
            if (saveMeasurements && result.resultsTable != null) {
                CellSegmentationIO.saveResultsTable(result.resultsTable, new File(outputDir, baseName + "_measurements.csv"));
            }
        } catch (Exception e) {
            IJ.handleException(e);
            IJ.error("Failed to save outputs", e.getMessage());
        }
    }

    private static void closeImage(ImagePlus imp) {
        if (imp == null) return;
        imp.changes = false;
        imp.close();
    }

    private int buildMeasurementFlags() {
        int meas = 0;
        if (measureArea) meas |= ij.measure.Measurements.AREA;
        if (measureMean) meas |= ij.measure.Measurements.MEAN;
        if (measureMinMax) meas |= ij.measure.Measurements.MIN_MAX;
        if (measureStdDev) meas |= ij.measure.Measurements.STD_DEV;
        if (measurePerimeter) meas |= ij.measure.Measurements.PERIMETER;
        if (measureCentroid) meas |= ij.measure.Measurements.CENTROID;
        if (measureRect) meas |= ij.measure.Measurements.RECT;
        if (measureFeret) meas |= ij.measure.Measurements.FERET;
        if (measureShape) meas |= ij.measure.Measurements.SHAPE_DESCRIPTORS;
        if (measureIntDen) meas |= ij.measure.Measurements.INTEGRATED_DENSITY;
        return meas;
    }
}
