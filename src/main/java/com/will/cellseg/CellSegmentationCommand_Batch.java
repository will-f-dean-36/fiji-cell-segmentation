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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import org.scijava.ItemVisibility;
import org.scijava.Context;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.Button;

@SuppressWarnings({"unused", "FieldMayBeFinal", "CanBeFinal", "FieldCanBeLocal"})
@Plugin(type = Command.class)
public class CellSegmentationCommand_Batch implements Command {

    // These strings intentionally match the IJ1 wrapper choices exactly, since the
    // wrapper passes them through as hidden SciJava parameters.
    private static final String THRESHOLD_STOP_OFF = "Don't stop";
    private static final String THRESHOLD_STOP_ONCE = "Stop once (set and apply to all)";
    private static final String THRESHOLD_STOP_UNIQUE = "Stop once per unique RICM";

    private static final String ROI_REVIEW_OFF = "Don't stop";
    private static final String ROI_REVIEW_ONCE = "Stop once per unique RICM";

    @Parameter
    private Context context;

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
    private String thresholdStopMode = THRESHOLD_STOP_OFF;

    @Parameter(visibility = ItemVisibility.INVISIBLE)
    private String roiReviewMode = ROI_REVIEW_OFF;

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
    private final Map<String, CachedSegmentationResult> segmentationCache =
            new HashMap<String, CachedSegmentationResult>();
    private final Map<String, ThresholdConfig> thresholdConfigCache =
            new HashMap<String, ThresholdConfig>();

    @Override
    public void run() {

        // This command is the real batch engine: validate inputs, load planes, segment
        // the RICM source once per pair, then measure the paired fluorescence planes.
        final boolean prevBlackBg = Prefs.blackBackground;
        BatchStopController stopController = null;

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

            // `InputResolver` converts the chosen file-selection mode into a normalized
            // list of `(segmentation source, measurement source)` pairs.
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

            // Stop-point state is intentionally kept outside the pipeline so the core
            // image-processing code can stay mostly unaware of batch UI concerns.
            stopController = new BatchStopController();
            segmentationCache.clear();
            thresholdConfigCache.clear();
            ThresholdConfig sharedThresholdConfig = ThresholdConfig.auto(thrMethod, darkObjects);
            boolean thresholdStopUsed = false;
            boolean aborted = false;
            int processedPairs = 0;
            int failedPairs = 0;
            int skippedPairs = 0;

            for (int i = 0; i < pairedUnits.size(); i++) {
                final PairedUnit pair = pairedUnits.get(i);
                final SegUnit seg = pair.getSegUnit();
                final MeasUnit meas = pair.getMeasUnit();
                final String pairBase = buildPairBaseName(pair, i);
                final String segKey = buildSegUnitKey(seg);
                final String segBase = buildSegmentationBaseName(seg);
                final CachedSegmentationResult cachedSegmentation = segmentationCache.get(segKey);

                // If a previous pairing already reviewed this exact RICM source, reuse
                // that decision (continue/skip/abort plus any edited ROIs).
                if (cachedSegmentation != null) {
                    if (cachedSegmentation.isAbort()) {
                        aborted = true;
                        break;
                    }
                    if (cachedSegmentation.isSkip()) {
                        skippedPairs++;
                        IJ.log("[CellSegmentation Batch] Skip pair " + (i + 1) + " due to cached ROI skip: " + segKey);
                        continue;
                    }
                }

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

                    CachedSegmentationResult finalSegmentation = cachedSegmentation;
                    if (finalSegmentation == null) {
                        // Load exactly one segmentation plane per unique RICM: series + channel, Z=0, T=0.
                        segImp = reader.openPlane(seg.getSource(), seg.getSeriesIndex(), seg.getSegChannelIndex(), 0);
                        final boolean stopForThreshold = shouldStopForThreshold(segKey, thresholdStopUsed);
                        final ThresholdConfig pairThresholdConfig = chooseThresholdConfig(
                                stopController,
                                segImp,
                                segKey,
                                sharedThresholdConfig,
                                stopForThreshold,
                                i + 1,
                                pairedUnits.size(),
                                seg);
                        if (stopForThreshold && isThresholdStopOnce()) {
                            sharedThresholdConfig = pairThresholdConfig;
                            thresholdStopUsed = true;
                        }

                        // Segmentation always runs before ROI review; ROI review can then
                        // accept, modify, or reject the proposed ROI set.
                        result = CellSegmentationPipeline.run(segImp, p, pairThresholdConfig);

                        final Roi[] proposedRois = result.roiManager != null ? cloneRois(result.roiManager.getRoisAsArray()) : new Roi[0];
                        finalSegmentation = resolveSegmentationResult(
                                stopController,
                                segKey,
                                segImp,
                                proposedRois,
                                i + 1,
                                pairedUnits.size(),
                                seg);
                        segmentationCache.put(segKey, finalSegmentation);

                        if (finalSegmentation.isAbort()) {
                            aborted = true;
                            IJ.log("[CellSegmentation Batch] Aborted by user during ROI review: " + segKey);
                            break;
                        }
                        if (finalSegmentation.isSkip()) {
                            skippedPairs++;
                            IJ.log("[CellSegmentation Batch] Skipping pair " + (i + 1) + " after ROI review: " + segKey);
                            continue;
                        }

                        saveSegmentationOutputs(segImp, segBase, finalSegmentation.getRois());
                    }

                    final Roi[] finalRois = finalSegmentation.getRois();

                    if (saveMeasurements) {
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

                                // Measurements always use the final accepted ROI set,
                                // including any edits cached from a prior shared RICM.
                                final ResultsTable measured = measureRoisOnImage(finalRois, measImp, measurements);
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
            IJ.showStatus(aborted ? "Batch Cell Segmentation aborted." : "Batch Cell Segmentation complete.");
            IJ.log("[CellSegmentation Batch] Done. processedPairs=" + processedPairs
                    + " failedPairs=" + failedPairs
                    + " skippedPairs=" + skippedPairs
                    + " aborted=" + aborted);
        } finally {
            if (stopController != null) {
                stopController.dispose();
            }
            Prefs.blackBackground = prevBlackBg;
        }
    }

    private static List<PairedUnit> normalizeMeasurementChannels(
            List<PairedUnit> pairs,
            InputMode mode,
            BioFormatsPlaneReader reader) {

        // Mode 2 initially leaves measurement channels unspecified. After we read file
        // metadata, expand that into "all channels" so downstream code is uniform.
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
        // Batch work is easier to reason about if we fail fast before any output files
        // are written, so pair compatibility is checked up front.
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

    private static ResultsTable measureRoisOnImage(Roi[] rois, ImagePlus image, int measurements) {
        final ResultsTable rt = new ResultsTable();
        if (image == null) {
            return rt;
        }

        // Analyzer measures whichever ROI is currently set on the image, so we replay
        // the ROI array one-by-one against a fresh table.
        final Analyzer analyzer = new Analyzer(image, measurements, rt);
        final Roi[] safeRois = rois != null ? rois : new Roi[0];
        for (Roi roi : safeRois) {
            if (roi == null) {
                continue;
            }
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

    private static void saveRois(Roi[] rois, File out) {
        if (out == null) return;
        // Use a temporary hidden manager for ZIP export so we do not disturb the shared
        // on-screen ROI Manager used during interactive review.
        final RoiManager roiManager = new RoiManager(false);
        try {
            for (Roi roi : cloneRois(rois)) {
                if (roi != null) {
                    roiManager.addRoi(roi);
                }
            }
            roiManager.runCommand("Save", out.getAbsolutePath());
        } finally {
            roiManager.close();
        }
    }

    private static void closeImage(ImagePlus imp) {
        if (imp == null) return;
        if (imp.getWindow() != null && !SwingUtilities.isEventDispatchThread()) {
            final ImagePlus visibleImp = imp;
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    visibleImp.changes = false;
                    visibleImp.close();
                }
            });
            return;
        }
        imp.changes = false;
        imp.close();
    }

    private static String stripExtension(String name) {
        if (name == null) return "image";
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return name;
        return name.substring(0, dot);
    }

    private boolean shouldStopForThreshold(String segKey, boolean thresholdStopUsed) {
        // "Once per unique RICM" means one stop per segmentation source key, not per
        // measurement pairing that happens to reference that same source.
        if (THRESHOLD_STOP_UNIQUE.equals(thresholdStopMode)) {
            return !thresholdConfigCache.containsKey(segKey);
        }
        if (THRESHOLD_STOP_ONCE.equals(thresholdStopMode)) {
            return !thresholdStopUsed;
        }
        return false;
    }

    private boolean isThresholdStopOnce() {
        return THRESHOLD_STOP_ONCE.equals(thresholdStopMode);
    }

    private boolean isThresholdStopUnique() {
        return THRESHOLD_STOP_UNIQUE.equals(thresholdStopMode);
    }

    private boolean isRoiReviewEnabled() {
        return ROI_REVIEW_ONCE.equals(roiReviewMode);
    }

    private ThresholdConfig chooseThresholdConfig(
            BatchStopController stopController,
            ImagePlus segImp,
            String segKey,
            ThresholdConfig currentConfig,
            boolean shouldStop,
            int pairIndex1,
            int totalPairs,
            SegUnit seg) {

        if (isThresholdStopUnique()) {
            final ThresholdConfig cachedConfig = thresholdConfigCache.get(segKey);
            if (cachedConfig != null) {
                // Reuse the exact threshold config chosen for this SegUnit earlier.
                return cachedConfig;
            }
        }

        if (!shouldStop) {
            return currentConfig;
        }

        final ImagePlus preview = CellSegmentationPipeline.prepareThresholdPreview(segImp, EdgeDetector.fromLabel(edgeMethod), true);
        try {
            final ThresholdConfig selectedConfig = stopController.maybeSelectThreshold(
                    context,
                    preview,
                    currentConfig,
                    buildThresholdTitle(pairIndex1, totalPairs, seg));
            if (isThresholdStopUnique()) {
                thresholdConfigCache.put(segKey, selectedConfig);
            }
            return selectedConfig;
        } finally {
            closeImage(preview);
        }
    }

    private CachedSegmentationResult resolveSegmentationResult(
            BatchStopController stopController,
            String segKey,
            ImagePlus segImp,
            Roi[] proposedRois,
            int pairIndex1,
            int totalPairs,
            SegUnit seg) {

        if (!isRoiReviewEnabled()) {
            return CachedSegmentationResult.continueWith(proposedRois);
        }

        final BatchStopController.RoiReviewResult reviewed = stopController.maybeReviewRois(
                context,
                segImp,
                proposedRois,
                buildRoiReviewTitle(pairIndex1, totalPairs, seg));
        if (reviewed.isAbort()) {
            return CachedSegmentationResult.abort();
        }
        if (reviewed.isSkip()) {
            return CachedSegmentationResult.skip();
        }
        return CachedSegmentationResult.continueWith(reviewed.getRois());
    }

    private void saveSegmentationOutputs(ImagePlus segImp, String segBase, Roi[] finalRois) {
        if ((!saveMask && !saveLabels && !saveLabelOverlay && !saveRois) || segImp == null) {
            return;
        }

        ImagePlus outputMask = null;
        ImagePlus outputLabels = null;
        ImagePlus outputOverlay = null;
        try {
            if (saveMask || saveLabelOverlay) {
                outputMask = CellSegmentationPipeline.buildMaskFromRois(finalRois, segImp.getWidth(), segImp.getHeight());
            }
            if (saveLabels || saveLabelOverlay) {
                outputLabels = CellSegmentationPipeline.buildLabelsFromRois(finalRois, segImp.getWidth(), segImp.getHeight(), labelsLut);
            }
            if (saveMask && outputMask != null) {
                saveImage(outputMask, new File(outputDir, segBase + "_mask.tif"));
            }
            if (saveLabels && outputLabels != null) {
                saveImage(outputLabels, new File(outputDir, segBase + "_labels.tif"));
            }
            if (saveLabelOverlay && outputLabels != null) {
                outputOverlay = CellSegmentationPipeline.createLabelOverlay(segImp, outputLabels, labelsLut);
                if (outputOverlay != null) {
                    saveImage(outputOverlay, new File(outputDir, segBase + "_overlay.tif"));
                }
            }
            if (saveRois) {
                saveRois(finalRois, new File(outputDir, segBase + "_rois.zip"));
            }
        } finally {
            closeImage(outputOverlay);
            closeImage(outputLabels);
            closeImage(outputMask);
        }
    }

    private static String buildSegUnitKey(SegUnit seg) {
        return seg.getSource().getAbsolutePath()
                + "|s=" + seg.getSeriesIndex()
                + "|c=" + seg.getSegChannelIndex();
    }

    private static String buildSegmentationBaseName(SegUnit seg) {
        return stripExtension(seg.getSource().getName())
                + "_S" + (seg.getSeriesIndex() + 1)
                + "_C" + (seg.getSegChannelIndex() + 1);
    }

    private static String buildThresholdTitle(int pairIndex1, int totalPairs, SegUnit seg) {
        return "Threshold Selection (RICM " + pairIndex1 + "/" + totalPairs + "): "
                + seg.getSource().getName() + " [series " + (seg.getSeriesIndex() + 1) + "]";
    }

    private static String buildRoiReviewTitle(int pairIndex1, int totalPairs, SegUnit seg) {
        return "ROI Review (RICM " + pairIndex1 + "/" + totalPairs + "): "
                + seg.getSource().getName() + " [series " + (seg.getSeriesIndex() + 1) + "]";
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

    private static final class CachedSegmentationResult {
        private final BatchStopController.RoiReviewAction action;
        private final Roi[] rois;

        private CachedSegmentationResult(BatchStopController.RoiReviewAction action, Roi[] rois) {
            this.action = action;
            this.rois = cloneRois(rois);
        }

        private static CachedSegmentationResult continueWith(Roi[] rois) {
            return new CachedSegmentationResult(BatchStopController.RoiReviewAction.CONTINUE, rois);
        }

        private static CachedSegmentationResult skip() {
            return new CachedSegmentationResult(BatchStopController.RoiReviewAction.SKIP, null);
        }

        private static CachedSegmentationResult abort() {
            return new CachedSegmentationResult(BatchStopController.RoiReviewAction.ABORT, null);
        }

        private boolean isSkip() {
            return action == BatchStopController.RoiReviewAction.SKIP;
        }

        private boolean isAbort() {
            return action == BatchStopController.RoiReviewAction.ABORT;
        }

        private Roi[] getRois() {
            return cloneRois(rois);
        }
    }
}
