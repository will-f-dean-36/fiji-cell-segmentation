package com.will.cellseg;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.Roi;
import ij.CompositeImage;
import ij.gui.WaitForUserDialog;
import ij.measure.ResultsTable;
import ij.plugin.Duplicator;
import ij.plugin.filter.Analyzer;
import ij.plugin.filter.ParticleAnalyzer;
import ij.plugin.frame.RoiManager;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ImageConverter;
import ij.process.ColorProcessor;
import ij.process.ShortProcessor;
import ij.ImageStack;
import java.awt.Window;
import ij.process.ByteProcessor;
public final class CellSegmentationPipeline {

    private CellSegmentationPipeline() {}

    private static final float[] SOBEL_X = {
            -1, 0, 1,
            -2, 0, 2,
            -1, 0, 1
    };
    private static final float[] SOBEL_Y = {
            -1, -2, -1,
             0,  0,  0,
             1,  2,  1
    };
    private static final float[] PREWITT_X = {
            -1, 0, 1,
            -1, 0, 1,
            -1, 0, 1
    };
    private static final float[] PREWITT_Y = {
            -1, -1, -1,
             0,  0,  0,
             1,  1,  1
    };
    private static final float[] SCHARR_X = {
            -3, 0, 3,
            -10, 0, 10,
            -3, 0, 3
    };
    private static final float[] SCHARR_Y = {
            -3, -10, -3,
             0,   0,  0,
             3,  10,  3
    };
    private static final float[] LAPLACIAN_3X3 = {
             0, -1,  0,
            -1,  4, -1,
             0, -1,  0
    };

    public static CellSegmentationResult run(ImagePlus imp, CellSegmentationParams p) {
        if (imp == null) {
            IJ.noImage();
            return new CellSegmentationResult(null, null, 0, null, null);
        }

        // Show work image only if threshold UI needs a window, or if user wants steps.
        final boolean showWork = p.pauseThreshold || p.showSteps;

        // Work on a duplicate so the original image stays untouched.
        ImagePlus work = duplicateForProcessing(imp, showWork);

        // 1) Edge detection (to emphasize cell boundaries before thresholding)
        applyEdgeDetector(work, p.edgeDetector);

        // Capture gradient *now*, but do not show it yet (avoid breaking threshold pause)
        ImagePlus gradientSnap = null;
        if (p.showSteps) {
            gradientSnap = work.duplicate();
            gradientSnap.setTitle("1 - Gradient");
        }

        // 2) Threshold + optional pause
        applyThreshold(work, p.thrMethod, p.darkObjects, p.pauseThreshold);

        // Convert current threshold to a binary mask.
        IJ.run(work, "Convert to Mask", "");
        // Now safe to show Gradient (Step 1)
        if (gradientSnap != null) gradientSnap.show();

        // Show Edge Mask (Step 2)
        showStepSnapshot(work, "2 - Edge Mask", p.showSteps);

        // 3) Fill holes and close small gaps while preserving edge structure.
        ImagePlus filled = fillEdgeOpenHolesHybrid(work);
        work.setProcessor(filled.getProcessor().duplicate());
        closeAlways(filled);
        // Show Edge Mask Filled
        showStepSnapshot(work, "3 - Edge Mask Filled", p.showSteps);

        // 4) Watershed
        IJ.run(work, "Watershed", "");
        // Show Watershed
        showStepSnapshot(work, "4 - Watershed", p.showSteps);

        // Output mask
        ImagePlus mask = work.duplicate();
        mask.setTitle("Cell Mask");

        // 5) Analyze particles on mask, then measure on the original image.
        AnalysisResult analysis = analyzeParticlesAndMeasureOnOriginal(
                work, imp, p.minArea, p.clearRM, p.measurements, p.showResultsTable, p.showRoiManager);
        RoiManager rm = analysis.roiManager;
        ResultsTable rt = analysis.resultsTable;

        // 6) Labels image (1-based label per ROI)
        final int w = work.getWidth();
        final int h = work.getHeight();
        ImagePlus labels = buildLabelsFromRois(rm, w, h);
        labels.setTitle("Labels");

        // Optional display LUT
        try {
            applyLabelsLut(labels, p.labelsLut);
            IJ.run(labels, "Enhance Contrast", "saturated=0");
        } catch (Throwable ignored) { }

        // 10) Optional overlay merge
        if (p.showLabelOverlay) {
            ImagePlus overlay = createLabelOverlay(imp, labels, p.labelsLut);
            if (overlay != null) {
                overlay.setTitle("LabelsOverlay");
                overlay.show();
            }
        }

        closeIfVisible(work);

        int roiCount = rm.getCount();
        return new CellSegmentationResult(mask, labels, roiCount, rm, rt);
    }

    private static ImagePlus duplicateForProcessing(ImagePlus imp, boolean show) {
        ImagePlus work = new Duplicator().run(imp);
        work.setTitle("duplicate_im");

        if (show) {
            work.show();
            if (work.getWindow() != null) WindowManager.setCurrentWindow(work.getWindow());
        }
        return work;
    }

    private static void applyEdgeDetector(ImagePlus work, EdgeDetector edgeDetector) {
        if (edgeDetector == null) edgeDetector = EdgeDetector.SOBEL;

        switch (edgeDetector) {
            case NONE:
                // no-op
                break;
            case SOBEL:
                applyGradientMagnitude(work, SOBEL_X, SOBEL_Y);
                break;
            case PREWITT:
                applyGradientMagnitude(work, PREWITT_X, PREWITT_Y);
                break;
            case SCHARR:
                applyGradientMagnitude(work, SCHARR_X, SCHARR_Y);
                break;
            case LAPLACIAN_3X3:
                applyLaplacian(work, LAPLACIAN_3X3);
                break;
            default:
                applyGradientMagnitude(work, SOBEL_X, SOBEL_Y);
                break;
        }
    }

    private static void applyGradientMagnitude(ImagePlus work, float[] kx, float[] ky) {
        FloatProcessor base = work.getProcessor().convertToFloatProcessor();
        FloatProcessor gx = (FloatProcessor) base.duplicate();
        FloatProcessor gy = (FloatProcessor) base.duplicate();

        gx.convolve(kx, 3, 3);
        gy.convolve(ky, 3, 3);

        float[] px = (float[]) gx.getPixels();
        float[] py = (float[]) gy.getPixels();
        float[] out = (float[]) base.getPixels();

        for (int i = 0; i < out.length; i++) {
            out[i] = (float) Math.hypot(px[i], py[i]);
        }

        work.setProcessor(base);
    }

    private static void applyLaplacian(ImagePlus work, float[] kernel) {
        FloatProcessor base = work.getProcessor().convertToFloatProcessor();
        base.convolve(kernel, 3, 3);
        float[] out = (float[]) base.getPixels();
        for (int i = 0; i < out.length; i++) {
            out[i] = Math.abs(out[i]);
        }
        work.setProcessor(base);
    }

    private static void applyThreshold(ImagePlus work, String thrMethod, boolean darkObjects, boolean pauseThreshold) {
        final String method = (thrMethod == null || thrMethod.trim().isEmpty()) ? "Default" : thrMethod.trim();

        final String darkOrLight = darkObjects ? "dark" : "light";

        // Sets the threshold on the image based on the selected method and polarity.
        IJ.setAutoThreshold(work, method + " " + darkOrLight);

        if (pauseThreshold) {
            // Needs an image window; if work wasn't shown, show it now.
            if (work.getWindow() == null) {
                work.show();
                if (work.getWindow() != null) WindowManager.setCurrentWindow(work.getWindow());
            }

            IJ.run(work, "Threshold...", "");
            new WaitForUserDialog(
                    "Adjust threshold",
                    "Adjust the threshold sliders on the gradient image.\n" +
                            "IMPORTANT: Do NOT click 'Apply' in the Threshold window.\n" +
                            "When you're happy, click OK here to continue."
            ).show();
        }
    }

    private static AnalysisResult analyzeParticlesAndMeasureOnOriginal(
            ImagePlus binaryMask,
            ImagePlus original,
            int minArea,
            boolean clearRM,
            int measurements,
            boolean showResultsTable,
            boolean showRoiManager) {

        RoiManager rm = RoiManager.getInstance();
        if (rm == null) {
            rm = new RoiManager();
        }
        rm.setVisible(showRoiManager);
        if (clearRM) rm.reset();

        // ResultsTable for the final measurement pass (on the original image).
        ResultsTable rt = new ResultsTable();

        int paOptions = ParticleAnalyzer.ADD_TO_MANAGER;

        // NOTE: setRoiManager is static in IJ1 -> call it statically
        ParticleAnalyzer.setRoiManager(rm);

        // Use ParticleAnalyzer only to populate the ROI Manager (skip its measurements).
        ResultsTable dummyRt = new ResultsTable();
        // minArea auto-widens int -> double; no cast needed
        ParticleAnalyzer pa = new ParticleAnalyzer(paOptions, 0, dummyRt, minArea, Double.POSITIVE_INFINITY);
        pa.analyze(binaryMask);

        // Measure on original for meaningful intensity stats.
        Analyzer analyzer = new Analyzer(original, measurements, rt);

        Roi[] roisForMeasure = rm.getRoisAsArray();
        for (Roi roi : roisForMeasure) {
            original.setRoi(roi);
            analyzer.measure();
        }
        original.deleteRoi();

        if (showResultsTable) {
            rt.show("Results");
        }

        return new AnalysisResult(rm, rt);
    }

    private static ImagePlus buildLabelsFromRois(RoiManager rm, int w, int h) {
        ImagePlus labels = IJ.createImage("Labels", "16-bit black", w, h, 1);
        ImageProcessor ip = labels.getProcessor();

        Roi[] rois = (rm == null) ? new Roi[0] : rm.getRoisAsArray();
        for (int i = 0; i < rois.length; i++) {
            ip.setValue(i + 1);
            ip.fill(rois[i]);
        }
        return labels;
    }

    public static ImagePlus createLabelOverlay(ImagePlus imp, ImagePlus labelsOut, String labelsLut) {
        if (imp == null || labelsOut == null) return null;
        ImagePlus base = imp.duplicate();
        ImagePlus labels = labelsOut.duplicate();

        // Ensure labels are colored before RGB conversion.
        applyLabelsLut(labels, labelsLut);

        ImagePlus blended = alphaBlendRgb(base, labels, labelsOut, 0.5f, true);
        closeAlways(base);
        closeAlways(labels);
        return blended;
    }

    private static void applyLabelsLut(ImagePlus labels, String labelsLut) {
        String lut = (labelsLut == null || labelsLut.trim().isEmpty()) ? "Rainbow RGB" : labelsLut.trim();
        IJ.run(labels, lut, "");
    }

    private static ImagePlus alphaBlendRgb(ImagePlus base, ImagePlus overlay, ImagePlus zeroMaskSource, float alpha, boolean transparentZeroOverlay) {
        if (alpha < 0f || alpha > 1f) {
            throw new IllegalArgumentException("alpha must be in [0,1]");
        }
        ImagePlus baseRgb = base.duplicate();
        ImagePlus overlayRgb = overlay.duplicate();

        new ImageConverter(baseRgb).convertToRGB();
        new ImageConverter(overlayRgb).convertToRGB();

        ColorProcessor bp = (ColorProcessor) baseRgb.getProcessor();
        ColorProcessor op = (ColorProcessor) overlayRgb.getProcessor();

        int[] bpx = (int[]) bp.getPixels();
        int[] opx = (int[]) op.getPixels();

        int w = bp.getWidth();
        int h = bp.getHeight();
        int[] out = new int[w * h];

        ImageProcessor zp = (zeroMaskSource != null) ? zeroMaskSource.getProcessor() : null;
        byte[] zb = null;
        short[] zs = null;
        float[] zf = null;
        if (zp != null && zp.getWidth() == w && zp.getHeight() == h) {
            if (zp instanceof ByteProcessor) zb = (byte[]) zp.getPixels();
            else if (zp instanceof ShortProcessor) zs = (short[]) zp.getPixels();
            else if (zp instanceof FloatProcessor) zf = (float[]) zp.getPixels();
        }

        float inv = 1.0f - alpha;
        for (int i = 0; i < out.length; i++) {
            if (transparentZeroOverlay) {
                if (zb != null && (zb[i] & 0xFF) == 0) {
                    out[i] = bpx[i];
                    continue;
                }
                if (zs != null && (zs[i] & 0xFFFF) == 0) {
                    out[i] = bpx[i];
                    continue;
                }
                if (zf != null && Math.round(zf[i]) == 0) {
                    out[i] = bpx[i];
                    continue;
                }
                if (zb == null && zs == null && zf == null && (opx[i] & 0x00FFFFFF) == 0) {
                    out[i] = bpx[i];
                    continue;
                }
            }

            int bc = bpx[i];
            int br = (bc >> 16) & 0xFF;
            int bg = (bc >> 8) & 0xFF;
            int bb = bc & 0xFF;

            int oc = opx[i];
            int or = (oc >> 16) & 0xFF;
            int og = (oc >> 8) & 0xFF;
            int ob = oc & 0xFF;

            int r = Math.round(br * inv + or * alpha);
            int g = Math.round(bg * inv + og * alpha);
            int b = Math.round(bb * inv + ob * alpha);

            out[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
        }

        return new ImagePlus("LabelsOverlay", new ColorProcessor(w, h, out));
    }

    private static void showStepSnapshot(ImagePlus img, String title, boolean show) {
        if (!show || img == null) return;
        ImagePlus snap = img.duplicate();
        snap.setTitle(title);
        snap.show();
    }

    private static void closeIfVisible(ImagePlus imp) {
        if (imp != null && imp.getWindow() != null) {
            imp.changes = false;
            imp.close();
        }
    }

    private static void closeAlways(ImagePlus imp) {
        if (imp == null) return;
        imp.changes = false;
        imp.close(); // safe whether visible or not
    }

    private static final class AnalysisResult {
        final RoiManager roiManager;
        final ResultsTable resultsTable;

        private AnalysisResult(RoiManager roiManager, ResultsTable resultsTable) {
            this.roiManager = roiManager;
            this.resultsTable = resultsTable;
        }
    }

    public static ImagePlus fillEdgeOpenHolesHybrid(ImagePlus edgeMask) {
        if (edgeMask == null) return null;

        final int w = edgeMask.getWidth();
        final int h = edgeMask.getHeight();

        // I1: closed edges with 1px padding to reduce border effects.
        ImagePlus I1 = edgeMask.duplicate();
        IJ.run(I1, "8-bit", "");

        // pad by 1px
        IJ.run(I1, "Canvas Size...", "width=" + (w+2) + " height=" + (h+2) + " position=Center");
        // close
        IJ.run(I1, "Close-", "");
        // crop back
        IJ.run(I1, "Canvas Size...", "width=" + w + " height=" + h + " position=Center");


        // I2: global fill holes of I1.
        ImagePlus I2 = I1.duplicate();
        IJ.run(I2, "Fill Holes", "");
        closeAlways(I1);

        // I3: complement of I2.
        ImagePlus I3 = I2.duplicate();
        IJ.run(I3, "Invert", "");

        // I4: fill background holes of I3.
        ImagePlus I4 = I3.duplicate();
        IJ.run(I4, "Fill Holes", "");
        closeAlways(I3);

        // I5: remove any 4-connected components that touch 3+ image edges.
        ImagePlus I5 = removeBorderComponents(I4, 3);
        closeAlways(I4);

        // Final mask = I2 OR I5.
        ImagePlus out = orBinary(I2, I5);
        out.setTitle("FilledMask");
        closeAlways(I2);
        closeAlways(I5);
        return out;
    }

    private static ImagePlus removeBorderComponents(ImagePlus binary, int minBorderCount) {
        final int w = binary.getWidth();
        final int h = binary.getHeight();

        if (minBorderCount < 1 || minBorderCount > 4) {
            throw new IllegalArgumentException("minBorderCount must be in [1,4]");
        }

        // Work on a byte copy so we can safely clear components in-place.
        ByteProcessor ip = binary.getProcessor().convertToByteProcessor(); // independent copy
        final byte[] pix = (byte[]) ip.getPixels();

        ConnectedComponents.forEachForegroundComponent(pix, w, h, false, new ConnectedComponents.ComponentHandler() {
            @Override public void handle(ConnectedComponents.ComponentView c) {
                if (c.stats().borderCount() >= minBorderCount) {
                    c.clear(); // clears in-place on pix
                }
            }
        });

        return new ImagePlus("I5", ip);
    }

    /** OR two binary images (nonzero => 255). Assumes same w/h. */
    private static ImagePlus orBinary(ImagePlus a, ImagePlus b) {
        final int w = a.getWidth();
        final int h = a.getHeight();

        ByteProcessor ipa = (ByteProcessor) a.getProcessor().convertToByteProcessor();
        ByteProcessor ipb = (ByteProcessor) b.getProcessor().convertToByteProcessor();

        byte[] pa = (byte[]) ipa.getPixels();
        byte[] pb = (byte[]) ipb.getPixels();

        ByteProcessor out = new ByteProcessor(w, h);
        byte[] po = (byte[]) out.getPixels();

        for (int i = 0; i < w * h; i++) {
            int va = pa[i] & 0xFF;
            int vb = pb[i] & 0xFF;
            po[i] = (byte) ((va != 0 || vb != 0) ? 255 : 0);
        }

        return new ImagePlus("I2_OR_I5", out);
    }
}
