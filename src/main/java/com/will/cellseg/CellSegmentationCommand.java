package com.will.cellseg;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.gui.Roi;
import ij.measure.ResultsTable;
import ij.plugin.Duplicator;
import ij.plugin.filter.Analyzer;
import ij.plugin.frame.RoiManager;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JOptionPane;
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
    private boolean thresholdReview = true;
    private boolean roiReview = false;
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

        final boolean processAllSlices;
        try {
            processAllSlices = chooseStackMode();
        } catch (UserCanceledException ignored) {
            return;
        }

        final CellSegmentationDialog.Result options = CellSegmentationDialog.showDialog(
                imp,
                new CellSegmentationDialog.Result(
                        minArea,
                        thrMethod,
                        darkObjects,
                        thresholdReview,
                        edgeMethod,
                        excludeBorderTouching,
                        roiReview,
                        showSteps,
                        showRoiOverlay,
                        showLabelOverlay,
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
        final int measurements = buildMeasurementFlagsOrDefault();
        final boolean prevBlackBg = Prefs.blackBackground;
        final BatchStopController stopController = new BatchStopController();
        final ReviewState reviewState = new ReviewState(ThresholdConfig.auto(thrMethod, darkObjects));
        try {
            Prefs.blackBackground = true;

            if (processAllSlices) {
                processStack(measurements, stopController, reviewState);
            } else {
                processSingleSlice(imp, imp.getCurrentSlice(), measurements, stopController, reviewState);
            }
        } finally {
            stopController.dispose();
            Prefs.blackBackground = prevBlackBg;
        }
    }

    private boolean chooseStackMode() {
        if (imp == null || imp.getStackSize() <= 1) {
            return false;
        }
        final int choice = JOptionPane.showConfirmDialog(
                null,
                "This image contains " + imp.getStackSize() + " slices.\nProcess every slice?",
                "Process Stack",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION) {
            throw new UserCanceledException();
        }
        return choice == JOptionPane.YES_OPTION;
    }

    private void processSingleSlice(
            ImagePlus source,
            int sliceIndex,
            int measurements,
            BatchStopController stopController,
            ReviewState reviewState) {

        final SliceProcessingResult sliceResult = processSlice(source, sliceIndex, measurements, stopController, reviewState);
        if (sliceResult == null || sliceResult.aborted || sliceResult.skipped) {
            return;
        }

        if (sliceResult.mask != null) sliceResult.mask.show();
        if (sliceResult.labels != null) sliceResult.labels.show();
        if (sliceResult.labelOverlay != null) sliceResult.labelOverlay.show();
        if (sliceResult.resultsTable != null) sliceResult.resultsTable.show("Results");
        if (showRoiOverlay) {
            final List<Roi> rois = new ArrayList<Roi>();
            for (Roi roi : sliceResult.rois) {
                if (roi != null) {
                    final Roi positioned = (Roi) roi.clone();
                    positioned.setPosition(sliceIndex);
                    positioned.setName(buildSliceRoiName(positioned.getName(), sliceIndex));
                    rois.add(positioned);
                }
            }
            applyRoisToSourceStack(rois);
        }
        if (autoSave) {
            saveSingleOutputs(sliceResult);
        }

        IJ.log("[CellSegmentation] Done: " + sliceResult.rois.length + " ROIs");
    }

    private void processStack(
            int measurements,
            BatchStopController stopController,
            ReviewState reviewState) {

        final int stackSize = imp.getStackSize();
        final List<Roi> allRois = new ArrayList<Roi>();
        final ResultsTable combinedResults = new ResultsTable();
        final ij.ImageStack maskStack = new ij.ImageStack(imp.getWidth(), imp.getHeight());
        final ij.ImageStack labelsStack = new ij.ImageStack(imp.getWidth(), imp.getHeight());
        final ij.ImageStack overlayStack = showLabelOverlay || saveLabelOverlayToFile
                ? new ij.ImageStack(imp.getWidth(), imp.getHeight())
                : null;

        boolean aborted = false;
        for (int sliceIndex = 1; sliceIndex <= stackSize; sliceIndex++) {
            final SliceProcessingResult sliceResult = processSlice(imp, sliceIndex, measurements, stopController, reviewState);
            if (sliceResult == null) {
                continue;
            }
            if (sliceResult.aborted) {
                aborted = true;
                break;
            }

            if (sliceResult.mask != null) {
                maskStack.addSlice("Slice " + sliceIndex, sliceResult.mask.getProcessor().duplicate());
            } else {
                maskStack.addSlice("Slice " + sliceIndex, CellSegmentationPipeline.buildMaskFromRois(null, imp.getWidth(), imp.getHeight()).getProcessor());
            }

            if (sliceResult.labels != null) {
                labelsStack.addSlice("Slice " + sliceIndex, sliceResult.labels.getProcessor().duplicate());
            } else {
                labelsStack.addSlice("Slice " + sliceIndex, CellSegmentationPipeline.buildLabelsFromRois(null, imp.getWidth(), imp.getHeight(), labelsLut).getProcessor());
            }

            if (overlayStack != null) {
                ImagePlus overlay = sliceResult.labelOverlay;
                if (overlay == null) {
                    final ImagePlus blankLabels = CellSegmentationPipeline.buildLabelsFromRois(null, imp.getWidth(), imp.getHeight(), labelsLut);
                    final ImagePlus sliceSource = duplicateSlice(imp, sliceIndex);
                    try {
                        overlay = CellSegmentationPipeline.createLabelOverlay(sliceSource, blankLabels, labelsLut);
                    } finally {
                        closeImage(sliceSource);
                        closeImage(blankLabels);
                    }
                }
                if (overlay != null) {
                    overlayStack.addSlice("Slice " + sliceIndex, overlay.getProcessor().duplicate());
                    if (sliceResult.labelOverlay == null) {
                        closeImage(overlay);
                    }
                }
            }

            if (sliceResult.resultsTable != null) {
                appendResults(combinedResults, sliceResult.resultsTable, sliceIndex);
            }

            for (Roi roi : sliceResult.rois) {
                if (roi != null) {
                    final Roi positioned = (Roi) roi.clone();
                    positioned.setPosition(sliceIndex);
                    positioned.setName(buildSliceRoiName(positioned.getName(), sliceIndex));
                    allRois.add(positioned);
                }
            }

            closeImage(sliceResult.mask);
            closeImage(sliceResult.labels);
            closeImage(sliceResult.labelOverlay);
        }

        if (aborted) {
            IJ.log("[CellSegmentation] Aborted during stack processing.");
            return;
        }

        final String baseName = CellSegmentationIO.stripExtension(imp.getTitle());
        final ImagePlus maskOut = new ImagePlus(baseName + " - Cell Mask", maskStack);
        final ImagePlus labelsOut = new ImagePlus(baseName + " - Labels", labelsStack);
        maskOut.show();
        labelsOut.show();
        if (overlayStack != null) {
            final ImagePlus overlayOut = new ImagePlus(baseName + " - LabelsOverlay", overlayStack);
            overlayOut.show();
            if (autoSave && saveLabelOverlayToFile) {
                saveImage(overlayOut, new File(outputDir, baseName + "_overlay.tif"));
            }
        }

        if (combinedResults.getCounter() > 0) {
            combinedResults.show("Results");
        }
        if (showRoiOverlay) {
            applyRoisToSourceStack(allRois);
        }
        if (autoSave) {
            saveStackOutputs(baseName, maskOut, labelsOut, allRois, combinedResults, overlayStack != null ? new ImagePlus(baseName + " - LabelsOverlay", overlayStack) : null);
        }

        IJ.log("[CellSegmentation] Done: processed " + stackSize + " slices");
    }

    private SliceProcessingResult processSlice(
            ImagePlus stackSource,
            int sliceIndex,
            int measurements,
            BatchStopController stopController,
            ReviewState reviewState) {

        syncSourceSliceForDisplay(stackSource, sliceIndex);
        final ImagePlus sliceImp = duplicateSlice(stackSource, sliceIndex);
        final String title = stackSource.getTitle() + " [Slice " + sliceIndex + "]";
        sliceImp.setTitle(title);

        try {
            ThresholdConfig thresholdConfig = reviewState.sharedThresholdConfig;
            ImagePlus preparedWork = null;
            if (thresholdReview && !reviewState.thresholdReviewDisabled) {
                final ImagePlus preview = CellSegmentationPipeline.prepareThresholdPreview(sliceImp, EdgeDetector.fromLabel(edgeMethod), true);
                try {
                    preview.setTitle("Threshold Preview: " + title);
                    final BatchStopController.ThresholdSelectionResult selected = stopController.maybeSelectThreshold(
                            null,
                            preview,
                            reviewState.sharedThresholdConfig,
                            "Threshold Review: " + title);
                    if (selected.isAbort()) {
                        return SliceProcessingResult.abort();
                    }
                    if (selected.isSkip()) {
                        return SliceProcessingResult.skip();
                    }
                    thresholdConfig = selected.getConfig();
                    if (selected.shouldRememberThreshold()) {
                        reviewState.sharedThresholdConfig = thresholdConfig;
                    } else {
                        reviewState.sharedThresholdConfig = ThresholdConfig.auto(thrMethod, darkObjects);
                    }
                    if (selected.isContinueToEnd()) {
                        reviewState.thresholdReviewDisabled = true;
                    }
                    preparedWork = preview;
                } finally {
                    if (preparedWork != preview) {
                        closeImage(preview);
                    }
                }
            }

            if (preparedWork == null) {
                preparedWork = CellSegmentationPipeline.prepareThresholdPreview(sliceImp, EdgeDetector.fromLabel(edgeMethod), false);
            }

            try {
                final CellSegmentationParams params = new CellSegmentationParams(
                        minArea,
                        thrMethod,
                        darkObjects,
                        false,
                        showSteps,
                        false,
                        false,
                        false,
                        excludeBorderTouching,
                        EdgeDetector.fromLabel(edgeMethod),
                        measurements,
                        labelsLut,
                        false,
                        false
                );

                final CellSegmentationResult runResult = CellSegmentationPipeline.completeSegmentation(
                        preparedWork,
                        sliceImp,
                        params,
                        thresholdConfig);

                Roi[] finalRois = runResult.roiManager != null ? cloneRois(runResult.roiManager.getRoisAsArray()) : new Roi[0];

                if (roiReview && !reviewState.roiReviewDisabled) {
                    final BatchStopController.RoiReviewResult reviewed = stopController.maybeReviewRois(
                            null,
                            sliceImp,
                            finalRois,
                            "ROI Review: " + title);
                    if (reviewed.isAbort()) {
                        closeSliceRunResult(runResult);
                        return SliceProcessingResult.abort();
                    }
                    if (reviewed.isSkip()) {
                        closeSliceRunResult(runResult);
                        return SliceProcessingResult.skip();
                    }
                    if (reviewed.isContinueToEnd()) {
                        reviewState.roiReviewDisabled = true;
                    }
                    finalRois = reviewed.getRois();
                }

                final ImagePlus mask = CellSegmentationPipeline.buildMaskFromRois(finalRois, sliceImp.getWidth(), sliceImp.getHeight());
                mask.setTitle("Cell Mask");
                final ImagePlus labels = CellSegmentationPipeline.buildLabelsFromRois(finalRois, sliceImp.getWidth(), sliceImp.getHeight(), labelsLut);
                labels.setTitle("Labels");
                final ImagePlus labelOverlay = showLabelOverlay || saveLabelOverlayToFile
                        ? CellSegmentationPipeline.createLabelOverlay(sliceImp, labels, labelsLut)
                        : null;
                if (labelOverlay != null) {
                    labelOverlay.setTitle("LabelsOverlay");
                }
                final ResultsTable measured = measureRoisOnImage(finalRois, sliceImp, measurements);
                annotateSliceColumn(measured, sliceIndex);

                closeSliceRunResult(runResult);
                return new SliceProcessingResult(mask, labels, labelOverlay, finalRois, measured, false, false);
            } finally {
                if (preparedWork != null && preparedWork.getWindow() != null) {
                    closeImage(preparedWork);
                }
            }
        } finally {
            closeImage(sliceImp);
        }
    }

    private void saveSingleOutputs(SliceProcessingResult result) {
        if (result == null) {
            return;
        }
        if (!ensureOutputDirectory()) {
            return;
        }
        final String baseName = CellSegmentationIO.stripExtension(imp != null ? imp.getTitle() : "image");
        try {
            if (saveMask && result.mask != null) {
                saveImage(result.mask, new File(outputDir, baseName + "_mask.tif"));
            }
            if (saveLabels && result.labels != null) {
                saveImage(result.labels, new File(outputDir, baseName + "_labels.tif"));
            }
            if (saveLabelOverlayToFile && result.labelOverlay != null) {
                saveImage(result.labelOverlay, new File(outputDir, baseName + "_overlay.tif"));
            }
            if (saveRois) {
                CellSegmentationIO.saveRois(result.rois, new File(outputDir, baseName + "_rois.zip"));
            }
            if (saveMeasurements && result.resultsTable != null) {
                CellSegmentationIO.saveResultsTable(result.resultsTable, new File(outputDir, baseName + "_measurements.csv"));
            }
        } catch (Exception e) {
            IJ.handleException(e);
            IJ.error("Failed to save outputs", e.getMessage());
        }
    }

    private void saveStackOutputs(
            String baseName,
            ImagePlus maskOut,
            ImagePlus labelsOut,
            List<Roi> allRois,
            ResultsTable combinedResults,
            ImagePlus overlayOut) {
        if (!ensureOutputDirectory()) {
            closeImage(overlayOut);
            return;
        }
        try {
            if (saveMask && maskOut != null) {
                saveImage(maskOut, new File(outputDir, baseName + "_mask.tif"));
            }
            if (saveLabels && labelsOut != null) {
                saveImage(labelsOut, new File(outputDir, baseName + "_labels.tif"));
            }
            if (saveLabelOverlayToFile && overlayOut != null) {
                saveImage(overlayOut, new File(outputDir, baseName + "_overlay.tif"));
            }
            if (saveRois) {
                CellSegmentationIO.saveRois(allRois.toArray(new Roi[0]), new File(outputDir, baseName + "_rois.zip"));
            }
            if (saveMeasurements && combinedResults != null) {
                CellSegmentationIO.saveResultsTable(combinedResults, new File(outputDir, baseName + "_measurements.csv"));
            }
        } catch (Exception e) {
            IJ.handleException(e);
            IJ.error("Failed to save stack outputs", e.getMessage());
        } finally {
            closeImage(overlayOut);
        }
    }

    private void applyRoisToSourceStack(List<Roi> allRois) {
        RoiManager rm = RoiManager.getInstance();
        if (rm == null) {
            rm = new RoiManager();
        }
        if (clearRM) {
            rm.reset();
        }
        for (Roi roi : allRois) {
            rm.addRoi((Roi) roi.clone());
        }
        if (imp.getWindow() == null) {
            imp.show();
        }
        rm.runCommand(imp, "Show All with labels");
        imp.updateAndDraw();
    }

    private void syncSourceSliceForDisplay(ImagePlus source, int sliceIndex) {
        if (source == null || sliceIndex < 1 || sliceIndex > source.getStackSize()) {
            return;
        }
        source.setSlice(sliceIndex);
        source.updateAndDraw();
    }

    private boolean ensureOutputDirectory() {
        if (outputDir == null) {
            IJ.error("No output directory selected.");
            return false;
        }
        if (!CellSegmentationIO.ensureOutputDirectory(outputDir)) {
            IJ.error("Could not create output directory: " + outputDir.getAbsolutePath());
            return false;
        }
        CellSegmentationIO.rememberOutputDirectory(outputDir);
        return true;
    }

    private static ImagePlus duplicateSlice(ImagePlus source, int sliceIndex) {
        return new Duplicator().run(source, sliceIndex, sliceIndex);
    }

    private static void appendResults(ResultsTable combined, ResultsTable sliceTable, int sliceIndex) {
        if (combined == null || sliceTable == null) {
            return;
        }
        final String[] headings = sliceTable.getHeadings();
        for (int row = 0; row < sliceTable.getCounter(); row++) {
            combined.incrementCounter();
            final int targetRow = combined.getCounter() - 1;
            combined.setValue("Slice", targetRow, sliceIndex);
            if (headings == null) {
                continue;
            }
            for (String heading : headings) {
                if (heading == null || heading.trim().isEmpty() || "Label".equals(heading) || "Slice".equals(heading)) {
                    continue;
                }
                final double value = sliceTable.getValue(heading, row);
                if (!Double.isNaN(value)) {
                    combined.setValue(heading, targetRow, value);
                }
            }
        }
    }

    private static void annotateSliceColumn(ResultsTable table, int sliceIndex) {
        if (table == null) {
            return;
        }
        for (int row = 0; row < table.getCounter(); row++) {
            table.setValue("Slice", row, sliceIndex);
        }
    }

    private static ResultsTable measureRoisOnImage(Roi[] rois, ImagePlus image, int measurements) {
        final ResultsTable rt = new ResultsTable();
        if (image == null) {
            return rt;
        }
        final Analyzer analyzer = new Analyzer(image, measurements, rt);
        final Roi[] safeRois = rois != null ? rois : new Roi[0];
        for (Roi roi : safeRois) {
            if (roi == null) continue;
            image.setRoi(roi);
            analyzer.measure();
        }
        image.deleteRoi();
        return rt;
    }

    private static String buildSliceRoiName(String existing, int sliceIndex) {
        final String base = existing != null && !existing.trim().isEmpty() ? existing.trim() : "ROI";
        return "Slice_" + sliceIndex + "_" + base;
    }

    private static Roi[] cloneRois(Roi[] rois) {
        if (rois == null || rois.length == 0) {
            return new Roi[0];
        }
        final Roi[] cloned = new Roi[rois.length];
        for (int i = 0; i < rois.length; i++) {
            cloned[i] = rois[i] != null ? (Roi) rois[i].clone() : null;
        }
        return cloned;
    }

    private static void closeSliceRunResult(CellSegmentationResult result) {
        if (result == null) {
            return;
        }
        closeImage(result.mask);
        closeImage(result.labels);
    }

    private static void closeImage(ImagePlus imp) {
        if (imp == null) return;
        imp.changes = false;
        imp.close();
    }

    private void applyOptions(CellSegmentationDialog.Result options) {
        minArea = options.minArea;
        thrMethod = options.thrMethod;
        darkObjects = options.darkObjects;
        thresholdReview = options.thresholdReview;
        edgeMethod = options.edgeMethod;
        excludeBorderTouching = options.excludeBorderTouching;
        roiReview = options.roiReview;
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

    private int buildMeasurementFlagsOrDefault() {
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
        if (meas == 0) {
            meas = ij.measure.Measurements.AREA;
            IJ.log("[CellSegmentation] No measurements selected; defaulting to Area.");
        }
        return meas;
    }

    private static void saveImage(ImagePlus imp, File out) {
        CellSegmentationIO.saveImage(imp, out);
    }

    private static final class ReviewState {
        private ThresholdConfig sharedThresholdConfig;
        private boolean thresholdReviewDisabled;
        private boolean roiReviewDisabled;

        private ReviewState(ThresholdConfig sharedThresholdConfig) {
            this.sharedThresholdConfig = sharedThresholdConfig;
        }
    }

    private static final class SliceProcessingResult {
        private final ImagePlus mask;
        private final ImagePlus labels;
        private final ImagePlus labelOverlay;
        private final Roi[] rois;
        private final ResultsTable resultsTable;
        private final boolean aborted;
        private final boolean skipped;

        private SliceProcessingResult(
                ImagePlus mask,
                ImagePlus labels,
                ImagePlus labelOverlay,
                Roi[] rois,
                ResultsTable resultsTable,
                boolean aborted,
                boolean skipped) {
            this.mask = mask;
            this.labels = labels;
            this.labelOverlay = labelOverlay;
            this.rois = rois != null ? rois : new Roi[0];
            this.resultsTable = resultsTable;
            this.aborted = aborted;
            this.skipped = skipped;
        }

        private static SliceProcessingResult abort() {
            return new SliceProcessingResult(null, null, null, null, null, true, false);
        }

        private static SliceProcessingResult skip() {
            return new SliceProcessingResult(null, null, null, null, null, false, true);
        }
    }

    private static final class UserCanceledException extends RuntimeException { }

}
