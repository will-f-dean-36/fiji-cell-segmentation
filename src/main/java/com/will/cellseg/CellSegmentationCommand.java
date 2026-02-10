package com.will.cellseg;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.Prefs;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.Button;

@SuppressWarnings({"unused", "FieldMayBeFinal", "CanBeFinal", "FieldCanBeLocal"})
@Plugin(type = Command.class)
public class CellSegmentationCommand implements Command {

    @Parameter
    private ImagePlus imp;

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

    @Parameter(label = "Pause to adjust threshold")
    private boolean pauseThreshold = true;

    @Parameter(label = "Show intermediate images")
    private boolean showSteps = false;

    @Parameter(label = "Show label overlay")
    private boolean showLabelOverlay = false;

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

    @Parameter(label = "Clear ROI Manager first")
    private boolean clearRM = true;

    @Parameter(label = "Measurements...", callback = "editMeasurements")
    private Button editMeasurements;

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

        // Convert UI choice string into a concrete edge detector.
        EdgeDetector edgeDetector = EdgeDetector.fromLabel(edgeMethod);

        int measurements = buildMeasurementFlags();
        if (measurements == 0) {
            measurements = ij.measure.Measurements.AREA;
            IJ.log("[CellSegmentation] No measurements selected; defaulting to Area.");
        }

        // Bundle UI parameters for a single pipeline run.
        CellSegmentationParams p = new CellSegmentationParams(
                minArea,
                thrMethod,
                darkObjects,
                pauseThreshold,
                showSteps,
                showLabelOverlay,
                clearRM,
                edgeDetector,
                measurements,
                labelsLut,
                true,
                true
        );

        // Store global background polarity pref
        final boolean prevBlackBg = Prefs.blackBackground;
        try {
            // Ensure black defines background
            Prefs.blackBackground = true;

            // Run pipeline
            CellSegmentationResult r = CellSegmentationPipeline.run(imp, p);

            if (r.mask != null) r.mask.show();
            if (r.labels != null) r.labels.show();

            IJ.log("[CellSegmentation] Done: " + r.roiCount + " ROIs");
        } finally {
            // Restore background color pref
            Prefs.blackBackground = prevBlackBg;
        }


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
}
