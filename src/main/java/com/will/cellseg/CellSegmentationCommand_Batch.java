package com.will.cellseg;

import com.will.cellseg.batch.BioFormatsPlaneReader;
import com.will.cellseg.batch.FrameSpec;
import com.will.cellseg.batch.InputMode;
import com.will.cellseg.batch.InputResolver;
import com.will.cellseg.batch.MeasUnit;
import com.will.cellseg.batch.MeasurementPlan;
import com.will.cellseg.batch.PairedUnit;
import com.will.cellseg.batch.SegUnit;
import com.will.cellseg.batch.SeriesMetadata;
import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.gui.GenericDialog;
import ij.gui.Roi;
import ij.io.FileSaver;
import ij.measure.ResultsTable;
import ij.plugin.filter.Analyzer;
import ij.plugin.frame.RoiManager;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.Button;

@SuppressWarnings({"unused", "FieldMayBeFinal", "CanBeFinal", "FieldCanBeLocal"})
@Plugin(type = Command.class)
public class CellSegmentationCommand_Batch implements Command {

    @Parameter(visibility = ItemVisibility.INVISIBLE)
    private String inputMode;

    @Parameter(visibility = ItemVisibility.INVISIBLE, required = false)
    private File ricmContainerFile;

    @Parameter(visibility = ItemVisibility.INVISIBLE, required = false)
    private File fluorContainerFile;

    @Parameter(visibility = ItemVisibility.INVISIBLE, required = false)
    private File[] ricmFiles;

    @Parameter(visibility = ItemVisibility.INVISIBLE, required = false)
    private File[] fluorFiles;

    @Parameter(visibility = ItemVisibility.INVISIBLE, required = false)
    private File[] combinedFiles;

    @Parameter(visibility = ItemVisibility.INVISIBLE)
    private int sameFileSegChannelIndex1Based = 1;

    @Parameter(visibility = ItemVisibility.INVISIBLE)
    private int sameFileFirstMeasChannelIndex1Based = 2;

    @Parameter(visibility = ItemVisibility.INVISIBLE)
    private boolean allTimepoints = true;

    @Parameter(visibility = ItemVisibility.INVISIBLE)
    private File outputDir;

    @Parameter(label = "Min cell area (px)", min = "0")
    private int minArea = 500;

    @Parameter(
            label = "Auto-threshold method",
            choices = {
                    "Default",
                    "Huang",
                    "Intermodes",
                    "IsoData",
                    "Li",
                    "MaxEntropy",
                    "Mean",
                    "MinError",
                    "Minimum",
                    "Moments",
                    "Otsu",
                    "Percentile",
                    "RenyiEntropy",
                    "Shanbhag",
                    "Triangle",
                    "Yen"
            }
    )
    private String thrMethod = "Default";

    @Parameter(label = "Dark objects (cells darker than background)")
    private boolean darkObjects = true;

    @Parameter(
            label = "Edge method",
            choices = {
                    "Sobel (Gradient)",
                    "Prewitt",
                    "Scharr",
                    "Laplacian (3x3)",
                    "None"
            }
    )
    private String edgeMethod = "Sobel (Gradient)";

    @Parameter(
            label = "Labels LUT",
            choices = {
                    "Rainbow RGB",
                    "16_colors",
                    "Glasbey",
                    "Fire",
                    "Ice",
                    "Grays",
                    "Spectrum"
            }
    )
    private String labelsLut = "Rainbow RGB";

    @Parameter(label = "Measurements...", callback = "editMeasurements")
    private Button editMeasurements;

    @Parameter(label = "Save mask image")
    private boolean saveMask = true;

    @Parameter(label = "Save labels image")
    private boolean saveLabels = true;

    @Parameter(label = "Save label overlay")
    private boolean saveLabelOverlay = false;

    @Parameter(label = "Save ROIs (ZIP)")
    private boolean saveRois = true;

    @Parameter(label = "Save measurements (CSV)")
    private boolean saveMeasurements = true;

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

        final boolean prevBlackBg = Prefs.blackBackground;

        try {
            Prefs.blackBackground = true;

            if (outputDir == null) {
                IJ.error("No output directory selected.");
                return;
            }
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                IJ.error("Could not create output directory: " + outputDir.getAbsolutePath());
                return;
            }

            final InputMode mode;
            try {
                mode = InputMode.fromName(inputMode);
            } catch (Exception e) {
                IJ.error("Invalid batch mode", e.getMessage());
                return;
            }

            final BioFormatsPlaneReader reader;
            try {
                reader = new BioFormatsPlaneReader();
            } catch (BioFormatsPlaneReader.BioFormatsUnavailableException e) {
                IJ.error("Bio-Formats not available",
                        "Bio-Formats is required for batch reading (ND2/CZI/LIF/etc.).\n"
                                + "Install or update Bio-Formats in Fiji (Help > Update...).");
                return;
            }

            List<PairedUnit> pairedUnits;
            try {
                pairedUnits = InputResolver.resolve(
                        mode,
                        ricmContainerFile,
                        fluorContainerFile,
                        ricmFiles,
                        fluorFiles,
                        combinedFiles,
                        sameFileSegChannelIndex1Based,
                        sameFileFirstMeasChannelIndex1Based,
                        allTimepoints,
                        reader);
            } catch (Exception e) {
                IJ.error("Invalid batch inputs", e.getMessage());
                return;
            }

            try {
                pairedUnits = normalizeMeasurementChannels(pairedUnits, mode, reader);
            } catch (Exception e) {
                IJ.error("Batch validation failed", e.getMessage());
                return;
            }

            try {
                validatePairsOrThrow(pairedUnits, reader);
            } catch (Exception e) {
                IJ.error("Batch validation failed", e.getMessage());
                return;
            }

            IJ.log("[CellSegmentation Batch] Starting mode=" + mode.name() + " pairs=" + pairedUnits.size());
            IJ.log("[CellSegmentation Batch] Output dir: " + outputDir.getAbsolutePath());

            final EdgeDetector edgeDetector = EdgeDetector.fromLabel(edgeMethod);
            int measurements = buildMeasurementFlags();
            if (measurements == 0) {
                measurements = ij.measure.Measurements.AREA;
                IJ.log("[CellSegmentation Batch] No measurements selected; defaulting to Area.");
            }

            final CellSegmentationParams p = new CellSegmentationParams(
                    minArea,
                    thrMethod,
                    darkObjects,
                    false,
                    false,
                    false,
                    true,
                    edgeDetector,
                    measurements,
                    labelsLut,
                    false,
                    false
            );

            int processedPairs = 0;
            int failedPairs = 0;

            for (int i = 0; i < pairedUnits.size(); i++) {
                final PairedUnit pair = pairedUnits.get(i);
                final SegUnit seg = pair.getSegUnit();
                final MeasUnit meas = pair.getMeasUnit();
                final String pairBase = buildPairBaseName(pair, i);

                ImagePlus segImp = null;
                ImagePlus overlay = null;
                CellSegmentationResult result = null;

                try {
                    IJ.showStatus("Cell Segmentation batch pair " + (i + 1) + "/" + pairedUnits.size());
                    IJ.showProgress(i, pairedUnits.size());

                    IJ.log("[CellSegmentation Batch] Pair " + (i + 1) + "/" + pairedUnits.size()
                            + " mode=" + mode.name()
                            + " seg=" + seg.getSource().getName() + " S" + (seg.getSeriesIndex() + 1)
                            + " C" + (seg.getSegChannelIndex() + 1)
                            + " meas=" + meas.getSource().getName() + " S" + (meas.getSeriesIndex() + 1));

                    segImp = reader.openPlane(seg.getSource(), seg.getSeriesIndex(), seg.getSegChannelIndex(), 0);
                    result = CellSegmentationPipeline.run(segImp, p);

                    if (saveMask && result.mask != null) {
                        saveImage(result.mask, new File(outputDir, pairBase + "_mask.tif"));
                    }
                    if (saveLabels && result.labels != null) {
                        saveImage(result.labels, new File(outputDir, pairBase + "_labels.tif"));
                    }
                    if (saveLabelOverlay && result.labels != null) {
                        overlay = CellSegmentationPipeline.createLabelOverlay(segImp, result.labels, labelsLut);
                        if (overlay != null) {
                            saveImage(overlay, new File(outputDir, pairBase + "_overlay.tif"));
                        }
                    }
                    if (saveRois && result.roiManager != null) {
                        File out = new File(outputDir, pairBase + "_rois.zip");
                        result.roiManager.runCommand("Save", out.getAbsolutePath());
                    }

                    if (saveMeasurements && result.roiManager != null) {
                        final SeriesMetadata measMeta = reader.getSeriesMetadata(meas.getSource(), meas.getSeriesIndex());
                        final List<FrameSpec> frames = MeasurementPlan.planFrames(meas, measMeta);
                        final boolean singleFrame = frames.size() == 1;

                        for (FrameSpec frame : frames) {
                            ImagePlus measImp = null;
                            try {
                                IJ.log("[CellSegmentation Batch] Measure pair=" + (i + 1)
                                        + " file=" + meas.getSource().getName()
                                        + " S" + (meas.getSeriesIndex() + 1)
                                        + " C" + (frame.getChannelIndex() + 1)
                                        + " T" + (frame.getTimeIndex() + 1));

                                measImp = reader.openPlane(
                                        meas.getSource(),
                                        meas.getSeriesIndex(),
                                        frame.getChannelIndex(),
                                        frame.getTimeIndex());

                                final ResultsTable measured = measureRoisOnImage(result.roiManager, measImp, measurements);
                                final String frameSuffix = singleFrame
                                        ? "_measurements.csv"
                                        : "_C" + (frame.getChannelIndex() + 1)
                                        + "_T" + (frame.getTimeIndex() + 1) + "_measurements.csv";
                                measured.save(new File(outputDir, pairBase + frameSuffix).getAbsolutePath());
                            } catch (Exception frameEx) {
                                throw new RuntimeException("Measurement failed for file=" + meas.getSource().getName()
                                        + " series=" + (meas.getSeriesIndex() + 1)
                                        + " channel=" + (frame.getChannelIndex() + 1)
                                        + " time=" + (frame.getTimeIndex() + 1), frameEx);
                            } finally {
                                closeImage(measImp);
                            }
                        }
                    }

                    processedPairs++;

                } catch (Exception pairEx) {
                    failedPairs++;
                    IJ.log("[CellSegmentation Batch] ERROR pair " + (i + 1) + ": " + pairEx.getMessage());
                    IJ.handleException(pairEx);
                } finally {
                    closeImage(overlay);
                    closeImage(result != null ? result.mask : null);
                    closeImage(result != null ? result.labels : null);
                    closeImage(segImp);
                }
            }

            IJ.showProgress(1.0);
            IJ.showStatus("Batch Cell Segmentation complete.");
            IJ.log("[CellSegmentation Batch] Done. processedPairs=" + processedPairs + " failedPairs=" + failedPairs);
        } finally {
            Prefs.blackBackground = prevBlackBg;
        }
    }

    private static List<PairedUnit> normalizeMeasurementChannels(
            List<PairedUnit> pairs,
            InputMode mode,
            BioFormatsPlaneReader reader) {

        if (mode != InputMode.FILE_LIST_PAIR) {
            return pairs;
        }

        final List<PairedUnit> out = new ArrayList<PairedUnit>();
        for (PairedUnit pair : pairs) {
            try {
                final MeasUnit meas = pair.getMeasUnit();
                final SeriesMetadata md = reader.getSeriesMetadata(meas.getSource(), meas.getSeriesIndex());
                final int sizeC = Math.max(1, md.getSizeC());
                final int[] channels = new int[sizeC];
                for (int c = 0; c < sizeC; c++) channels[c] = c;

                out.add(new PairedUnit(
                        pair.getSegUnit(),
                        new MeasUnit(meas.getSource(), meas.getSeriesIndex(), channels, meas.isAllTimepoints())
                ));
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to read fluorescence channel metadata for "
                        + pair.getMeasUnit().getSource().getName() + ": " + e.getMessage(), e);
            }
        }

        return out;
    }

    private static void validatePairsOrThrow(List<PairedUnit> pairs, BioFormatsPlaneReader reader) throws Exception {
        for (int i = 0; i < pairs.size(); i++) {
            final PairedUnit pair = pairs.get(i);
            final SegUnit seg = pair.getSegUnit();
            final MeasUnit meas = pair.getMeasUnit();

            final SeriesMetadata segMeta = reader.getSeriesMetadata(seg.getSource(), seg.getSeriesIndex());
            final SeriesMetadata measMeta = reader.getSeriesMetadata(meas.getSource(), meas.getSeriesIndex());

            if (seg.getSegChannelIndex() < 0 || seg.getSegChannelIndex() >= Math.max(1, segMeta.getSizeC())) {
                throw new IllegalArgumentException("Segmentation channel out of range at pair " + (i + 1)
                        + " for file " + seg.getSource().getName()
                        + " series " + (seg.getSeriesIndex() + 1)
                        + ": C" + (seg.getSegChannelIndex() + 1)
                        + " but sizeC=" + segMeta.getSizeC());
            }

            final List<FrameSpec> frames = MeasurementPlan.planFrames(meas, measMeta);
            if (frames.isEmpty()) {
                throw new IllegalArgumentException("No measurement frames at pair " + (i + 1));
            }

            if (segMeta.getSizeX() != measMeta.getSizeX() || segMeta.getSizeY() != measMeta.getSizeY()) {
                throw new IllegalArgumentException("XY size mismatch at pair " + (i + 1)
                        + ". seg=" + seg.getSource().getName() + "(S" + (seg.getSeriesIndex() + 1)
                        + ", " + segMeta.getSizeX() + "x" + segMeta.getSizeY() + ")"
                        + " vs meas=" + meas.getSource().getName() + "(S" + (meas.getSeriesIndex() + 1)
                        + ", " + measMeta.getSizeX() + "x" + measMeta.getSizeY() + ")");
            }
        }
    }

    private static ResultsTable measureRoisOnImage(RoiManager roiManager, ImagePlus image, int measurements) {
        final ResultsTable rt = new ResultsTable();
        if (roiManager == null || image == null) {
            return rt;
        }

        final Analyzer analyzer = new Analyzer(image, measurements, rt);
        final Roi[] rois = roiManager.getRoisAsArray();
        for (Roi roi : rois) {
            image.setRoi(roi);
            analyzer.measure();
        }
        image.deleteRoi();
        return rt;
    }

    private String buildPairBaseName(PairedUnit pair, int pairIndex0) {
        final SegUnit seg = pair.getSegUnit();
        final MeasUnit meas = pair.getMeasUnit();

        final String segBase = stripExtension(seg.getSource().getName());
        final String measBase = stripExtension(meas.getSource().getName());
        final String segSeries = "S" + (seg.getSeriesIndex() + 1);
        final String measSeries = "S" + (meas.getSeriesIndex() + 1);

        if (seg.getSource().equals(meas.getSource()) && seg.getSeriesIndex() == meas.getSeriesIndex()) {
            return segBase + "_" + segSeries;
        }
        return "pair" + (pairIndex0 + 1) + "_" + segBase + "_" + segSeries + "__" + measBase + "_" + measSeries;
    }

    private void editMeasurements() {
        GenericDialog gd = new GenericDialog("Measurements");
        gd.addMessage("Select particle measurements to record:");
        gd.addCheckbox("Area", measureArea);
        gd.addCheckbox("Mean", measureMean);
        gd.addCheckbox("Min/Max", measureMinMax);
        gd.addCheckbox("Std Dev", measureStdDev);
        gd.addCheckbox("Perimeter", measurePerimeter);
        gd.addCheckbox("Centroid", measureCentroid);
        gd.addCheckbox("Bounding rectangle", measureRect);
        gd.addCheckbox("Feret's diameter", measureFeret);
        gd.addCheckbox("Shape descriptors", measureShape);
        gd.addCheckbox("Integrated density", measureIntDen);
        gd.showDialog();
        if (gd.wasCanceled()) return;

        measureArea = gd.getNextBoolean();
        measureMean = gd.getNextBoolean();
        measureMinMax = gd.getNextBoolean();
        measureStdDev = gd.getNextBoolean();
        measurePerimeter = gd.getNextBoolean();
        measureCentroid = gd.getNextBoolean();
        measureRect = gd.getNextBoolean();
        measureFeret = gd.getNextBoolean();
        measureShape = gd.getNextBoolean();
        measureIntDen = gd.getNextBoolean();
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

    private static void saveImage(ImagePlus imp, File out) {
        if (imp == null || out == null) return;
        FileSaver saver = new FileSaver(imp);
        saver.saveAsTiff(out.getAbsolutePath());
    }

    private static void closeImage(ImagePlus imp) {
        if (imp == null) return;
        imp.changes = false;
        imp.close();
    }

    private static String stripExtension(String name) {
        if (name == null) return "image";
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return name;
        return name.substring(0, dot);
    }
}
