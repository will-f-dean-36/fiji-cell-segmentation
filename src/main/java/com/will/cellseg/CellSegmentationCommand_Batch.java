package com.will.cellseg;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.io.FileSaver;
import java.io.File;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.Button;

@SuppressWarnings({"unused", "FieldMayBeFinal", "CanBeFinal", "FieldCanBeLocal"})
@Plugin(type = Command.class)
public class CellSegmentationCommand_Batch implements Command {

    @Parameter(visibility = ItemVisibility.INVISIBLE)
    private File[] inputFiles;

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
        if (inputFiles == null || inputFiles.length == 0) {
            IJ.error("No input files selected.");
            return;
        }
        if (outputDir == null) {
            IJ.error("No output directory selected.");
            return;
        }
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            IJ.error("Could not create output directory: " + outputDir.getAbsolutePath());
            return;
        }

        EdgeDetector edgeDetector = EdgeDetector.fromLabel(edgeMethod);
        int measurements = buildMeasurementFlags();
        if (measurements == 0) {
            measurements = ij.measure.Measurements.AREA;
            IJ.log("No measurements selected; defaulting to Area.");
        }

        CellSegmentationParams p = new CellSegmentationParams(
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

        for (File file : inputFiles) {
            if (file == null) continue;
            String path = file.getAbsolutePath();
            ImagePlus imp = IJ.openImage(path);
            if (imp == null) {
                IJ.log("Skipping (failed to open): " + path);
                continue;
            }

            CellSegmentationResult r = CellSegmentationPipeline.run(imp, p);
            String baseName = stripExtension(file.getName());

            if (saveMask && r.mask != null) {
                saveImage(r.mask, new File(outputDir, baseName + "_mask.tif"));
            }
            if (saveLabels && r.labels != null) {
                saveImage(r.labels, new File(outputDir, baseName + "_labels.tif"));
            }
            if (saveLabelOverlay && r.labels != null) {
                ImagePlus overlay = CellSegmentationPipeline.createLabelOverlay(imp, r.labels, labelsLut);
                if (overlay != null) {
                    saveImage(overlay, new File(outputDir, baseName + "_overlay.tif"));
                    closeImage(overlay);
                }
            }
            if (saveRois && r.roiManager != null) {
                File out = new File(outputDir, baseName + "_rois.zip");
                r.roiManager.runCommand("Save", out.getAbsolutePath());
            }
            if (saveMeasurements && r.resultsTable != null) {
                File out = new File(outputDir, baseName + "_measurements.csv");
                r.resultsTable.save(out.getAbsolutePath());
            }

            closeImage(r.mask);
            closeImage(r.labels);
            closeImage(imp);
        }

        IJ.log("Batch Cell Segmentation complete.");
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
