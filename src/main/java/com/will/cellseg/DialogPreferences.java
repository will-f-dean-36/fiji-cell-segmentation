package com.will.cellseg;

import ij.Prefs;
import java.io.File;

/** Persists custom dialog defaults that are no longer handled by SciJava. */
final class DialogPreferences {
    private static final String SINGLE_PREFIX = "cellseg.single.";
    private static final String BATCH_PREFIX = "cellseg.batch.";

    private DialogPreferences() {}

    static CellSegmentationDialog.Result loadSingle(CellSegmentationDialog.Result fallback) {
        if (fallback == null) {
            return null;
        }
        return new CellSegmentationDialog.Result(
                getInt(SINGLE_PREFIX + "minArea", fallback.minArea),
                Prefs.get(SINGLE_PREFIX + "thrMethod", fallback.thrMethod),
                Prefs.get(SINGLE_PREFIX + "darkObjects", fallback.darkObjects),
                Prefs.get(SINGLE_PREFIX + "thresholdReview", fallback.thresholdReview),
                Prefs.get(SINGLE_PREFIX + "edgeMethod", fallback.edgeMethod),
                Prefs.get(SINGLE_PREFIX + "excludeBorderTouching", fallback.excludeBorderTouching),
                Prefs.get(SINGLE_PREFIX + "roiReview", fallback.roiReview),
                Prefs.get(SINGLE_PREFIX + "showSteps", fallback.showSteps),
                Prefs.get(SINGLE_PREFIX + "showMask", fallback.showMask),
                Prefs.get(SINGLE_PREFIX + "showLabels", fallback.showLabels),
                Prefs.get(SINGLE_PREFIX + "showRoiOverlay", fallback.showRoiOverlay),
                Prefs.get(SINGLE_PREFIX + "showLabelOverlay", fallback.showLabelOverlay),
                Prefs.get(SINGLE_PREFIX + "labelsLut", fallback.labelsLut),
                Prefs.get(SINGLE_PREFIX + "clearRM", fallback.clearRM),
                Prefs.get(SINGLE_PREFIX + "measureArea", fallback.measureArea),
                Prefs.get(SINGLE_PREFIX + "measureMean", fallback.measureMean),
                Prefs.get(SINGLE_PREFIX + "measureMinMax", fallback.measureMinMax),
                Prefs.get(SINGLE_PREFIX + "measureStdDev", fallback.measureStdDev),
                Prefs.get(SINGLE_PREFIX + "measurePerimeter", fallback.measurePerimeter),
                Prefs.get(SINGLE_PREFIX + "measureCentroid", fallback.measureCentroid),
                Prefs.get(SINGLE_PREFIX + "measureRect", fallback.measureRect),
                Prefs.get(SINGLE_PREFIX + "measureFeret", fallback.measureFeret),
                Prefs.get(SINGLE_PREFIX + "measureShape", fallback.measureShape),
                Prefs.get(SINGLE_PREFIX + "measureIntDen", fallback.measureIntDen),
                Prefs.get(SINGLE_PREFIX + "autoSave", fallback.autoSave),
                getFile(SINGLE_PREFIX + "outputDir", fallback.outputDir),
                Prefs.get(SINGLE_PREFIX + "saveMask", fallback.saveMask),
                Prefs.get(SINGLE_PREFIX + "saveLabels", fallback.saveLabels),
                Prefs.get(SINGLE_PREFIX + "saveLabelOverlayToFile", fallback.saveLabelOverlayToFile),
                Prefs.get(SINGLE_PREFIX + "saveRois", fallback.saveRois),
                Prefs.get(SINGLE_PREFIX + "saveMeasurements", fallback.saveMeasurements),
                Prefs.get(SINGLE_PREFIX + "saveParameters", fallback.saveParameters));
    }

    static void saveSingle(CellSegmentationDialog.Result result) {
        if (result == null) {
            return;
        }
        Prefs.set(SINGLE_PREFIX + "minArea", result.minArea);
        Prefs.set(SINGLE_PREFIX + "thrMethod", result.thrMethod);
        Prefs.set(SINGLE_PREFIX + "darkObjects", result.darkObjects);
        Prefs.set(SINGLE_PREFIX + "thresholdReview", result.thresholdReview);
        Prefs.set(SINGLE_PREFIX + "edgeMethod", result.edgeMethod);
        Prefs.set(SINGLE_PREFIX + "excludeBorderTouching", result.excludeBorderTouching);
        Prefs.set(SINGLE_PREFIX + "roiReview", result.roiReview);
        Prefs.set(SINGLE_PREFIX + "showSteps", result.showSteps);
        Prefs.set(SINGLE_PREFIX + "showMask", result.showMask);
        Prefs.set(SINGLE_PREFIX + "showLabels", result.showLabels);
        Prefs.set(SINGLE_PREFIX + "showRoiOverlay", result.showRoiOverlay);
        Prefs.set(SINGLE_PREFIX + "showLabelOverlay", result.showLabelOverlay);
        Prefs.set(SINGLE_PREFIX + "labelsLut", result.labelsLut);
        Prefs.set(SINGLE_PREFIX + "clearRM", result.clearRM);
        Prefs.set(SINGLE_PREFIX + "measureArea", result.measureArea);
        Prefs.set(SINGLE_PREFIX + "measureMean", result.measureMean);
        Prefs.set(SINGLE_PREFIX + "measureMinMax", result.measureMinMax);
        Prefs.set(SINGLE_PREFIX + "measureStdDev", result.measureStdDev);
        Prefs.set(SINGLE_PREFIX + "measurePerimeter", result.measurePerimeter);
        Prefs.set(SINGLE_PREFIX + "measureCentroid", result.measureCentroid);
        Prefs.set(SINGLE_PREFIX + "measureRect", result.measureRect);
        Prefs.set(SINGLE_PREFIX + "measureFeret", result.measureFeret);
        Prefs.set(SINGLE_PREFIX + "measureShape", result.measureShape);
        Prefs.set(SINGLE_PREFIX + "measureIntDen", result.measureIntDen);
        Prefs.set(SINGLE_PREFIX + "autoSave", result.autoSave);
        setFile(SINGLE_PREFIX + "outputDir", result.outputDir);
        Prefs.set(SINGLE_PREFIX + "saveMask", result.saveMask);
        Prefs.set(SINGLE_PREFIX + "saveLabels", result.saveLabels);
        Prefs.set(SINGLE_PREFIX + "saveLabelOverlayToFile", result.saveLabelOverlayToFile);
        Prefs.set(SINGLE_PREFIX + "saveRois", result.saveRois);
        Prefs.set(SINGLE_PREFIX + "saveMeasurements", result.saveMeasurements);
        Prefs.set(SINGLE_PREFIX + "saveParameters", result.saveParameters);
        Prefs.savePreferences();
    }

    static BatchSegmentationDialog.Result loadBatch(BatchSegmentationDialog.Result fallback) {
        if (fallback == null) {
            return null;
        }
        return new BatchSegmentationDialog.Result(
                getInt(BATCH_PREFIX + "inputModeIndex", fallback.inputModeIndex),
                getInt(BATCH_PREFIX + "sameFileSegChannelIndex1Based", fallback.sameFileSegChannelIndex1Based),
                getInt(BATCH_PREFIX + "sameFileFirstMeasChannelIndex1Based", fallback.sameFileFirstMeasChannelIndex1Based),
                fallback.ricmContainerFile,
                fallback.fluorContainerFile,
                fallback.ricmFiles,
                fallback.fluorFiles,
                fallback.combinedFiles,
                getInt(BATCH_PREFIX + "minArea", fallback.minArea),
                Prefs.get(BATCH_PREFIX + "thrMethod", fallback.thrMethod),
                Prefs.get(BATCH_PREFIX + "darkObjects", fallback.darkObjects),
                Prefs.get(BATCH_PREFIX + "edgeMethod", fallback.edgeMethod),
                Prefs.get(BATCH_PREFIX + "excludeBorderTouching", fallback.excludeBorderTouching),
                Prefs.get(BATCH_PREFIX + "thresholdStopMode", fallback.thresholdStopMode),
                Prefs.get(BATCH_PREFIX + "roiReviewMode", fallback.roiReviewMode),
                Prefs.get(BATCH_PREFIX + "measureArea", fallback.measureArea),
                Prefs.get(BATCH_PREFIX + "measureMean", fallback.measureMean),
                Prefs.get(BATCH_PREFIX + "measureMinMax", fallback.measureMinMax),
                Prefs.get(BATCH_PREFIX + "measureStdDev", fallback.measureStdDev),
                Prefs.get(BATCH_PREFIX + "measurePerimeter", fallback.measurePerimeter),
                Prefs.get(BATCH_PREFIX + "measureCentroid", fallback.measureCentroid),
                Prefs.get(BATCH_PREFIX + "measureRect", fallback.measureRect),
                Prefs.get(BATCH_PREFIX + "measureFeret", fallback.measureFeret),
                Prefs.get(BATCH_PREFIX + "measureShape", fallback.measureShape),
                Prefs.get(BATCH_PREFIX + "measureIntDen", fallback.measureIntDen),
                getFile(BATCH_PREFIX + "outputDir", fallback.outputDir),
                Prefs.get(BATCH_PREFIX + "labelsLut", fallback.labelsLut),
                Prefs.get(BATCH_PREFIX + "saveMask", fallback.saveMask),
                Prefs.get(BATCH_PREFIX + "saveLabels", fallback.saveLabels),
                Prefs.get(BATCH_PREFIX + "saveLabelOverlay", fallback.saveLabelOverlay),
                Prefs.get(BATCH_PREFIX + "saveRois", fallback.saveRois),
                Prefs.get(BATCH_PREFIX + "saveMeasurements", fallback.saveMeasurements),
                Prefs.get(BATCH_PREFIX + "saveParameters", fallback.saveParameters));
    }

    static void saveBatch(BatchSegmentationDialog.Result result) {
        if (result == null) {
            return;
        }
        Prefs.set(BATCH_PREFIX + "inputModeIndex", result.inputModeIndex);
        Prefs.set(BATCH_PREFIX + "sameFileSegChannelIndex1Based", result.sameFileSegChannelIndex1Based);
        Prefs.set(BATCH_PREFIX + "sameFileFirstMeasChannelIndex1Based", result.sameFileFirstMeasChannelIndex1Based);
        Prefs.set(BATCH_PREFIX + "minArea", result.minArea);
        Prefs.set(BATCH_PREFIX + "thrMethod", result.thrMethod);
        Prefs.set(BATCH_PREFIX + "darkObjects", result.darkObjects);
        Prefs.set(BATCH_PREFIX + "edgeMethod", result.edgeMethod);
        Prefs.set(BATCH_PREFIX + "excludeBorderTouching", result.excludeBorderTouching);
        Prefs.set(BATCH_PREFIX + "thresholdStopMode", result.thresholdStopMode);
        Prefs.set(BATCH_PREFIX + "roiReviewMode", result.roiReviewMode);
        Prefs.set(BATCH_PREFIX + "measureArea", result.measureArea);
        Prefs.set(BATCH_PREFIX + "measureMean", result.measureMean);
        Prefs.set(BATCH_PREFIX + "measureMinMax", result.measureMinMax);
        Prefs.set(BATCH_PREFIX + "measureStdDev", result.measureStdDev);
        Prefs.set(BATCH_PREFIX + "measurePerimeter", result.measurePerimeter);
        Prefs.set(BATCH_PREFIX + "measureCentroid", result.measureCentroid);
        Prefs.set(BATCH_PREFIX + "measureRect", result.measureRect);
        Prefs.set(BATCH_PREFIX + "measureFeret", result.measureFeret);
        Prefs.set(BATCH_PREFIX + "measureShape", result.measureShape);
        Prefs.set(BATCH_PREFIX + "measureIntDen", result.measureIntDen);
        setFile(BATCH_PREFIX + "outputDir", result.outputDir);
        Prefs.set(BATCH_PREFIX + "labelsLut", result.labelsLut);
        Prefs.set(BATCH_PREFIX + "saveMask", result.saveMask);
        Prefs.set(BATCH_PREFIX + "saveLabels", result.saveLabels);
        Prefs.set(BATCH_PREFIX + "saveLabelOverlay", result.saveLabelOverlay);
        Prefs.set(BATCH_PREFIX + "saveRois", result.saveRois);
        Prefs.set(BATCH_PREFIX + "saveMeasurements", result.saveMeasurements);
        Prefs.set(BATCH_PREFIX + "saveParameters", result.saveParameters);
        Prefs.savePreferences();
    }

    private static int getInt(String key, int fallback) {
        return (int) Math.round(Prefs.get(key, fallback));
    }

    private static File getFile(String key, File fallback) {
        final String fallbackPath = fallback != null ? fallback.getAbsolutePath() : null;
        final String saved = Prefs.get(key, fallbackPath);
        if (saved == null || saved.trim().isEmpty()) {
            return fallback;
        }
        return new File(saved);
    }

    private static void setFile(String key, File file) {
        if (file == null) {
            return;
        }
        Prefs.set(key, file.getAbsolutePath());
    }
}
