package com.will.cellseg;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.gui.Roi;
import ij.io.FileSaver;
import ij.measure.ResultsTable;
import ij.plugin.frame.RoiManager;
import java.io.File;

/** Shared helpers for output paths and file export. */
public final class CellSegmentationIO {
    private static final String OUTPUT_DIR_PREF = "cellseg.outputDir";

    private CellSegmentationIO() {}

    public static File getDefaultOutputDirectory() {
        final String defaultPath = IJ.getDir("home");
        final String savedPath = Prefs.get(OUTPUT_DIR_PREF, defaultPath);
        if (savedPath == null || savedPath.trim().isEmpty()) {
            return new File(defaultPath != null ? defaultPath : ".");
        }
        return new File(savedPath);
    }

    public static void rememberOutputDirectory(File dir) {
        if (dir == null) {
            return;
        }
        Prefs.set(OUTPUT_DIR_PREF, dir.getAbsolutePath());
    }

    public static boolean ensureOutputDirectory(File dir) {
        if (dir == null) {
            return false;
        }
        if (dir.exists()) {
            return dir.isDirectory();
        }
        return dir.mkdirs();
    }

    public static String stripExtension(String name) {
        if (name == null) return "image";
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return name;
        return name.substring(0, dot);
    }

    public static void saveImage(ImagePlus imp, File out) {
        if (imp == null || out == null) return;
        FileSaver saver = new FileSaver(imp);
        saver.saveAsTiff(out.getAbsolutePath());
    }

    public static void saveRois(Roi[] rois, File out) {
        if (out == null) return;
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

    public static void saveResultsTable(ResultsTable table, File out) throws Exception {
        if (table == null || out == null) {
            return;
        }
        table.save(out.getAbsolutePath());
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
}
