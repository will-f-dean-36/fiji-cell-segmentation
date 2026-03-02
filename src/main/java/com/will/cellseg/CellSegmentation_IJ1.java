package com.will.cellseg;

import ij.IJ;
import ij.ImagePlus;
import ij.plugin.PlugIn;

import net.imagej.legacy.IJ1Helper;
import org.scijava.Context;
import org.scijava.command.CommandService;

public class CellSegmentation_IJ1 implements PlugIn {

    @Override
    public void run(String arg) {

        // This is the classic IJ1 menu entry point. Its only job is to bridge from
        // the old PlugIn API into the SciJava command that owns the real UI/logic.
        IJ.log("[CellSegmentation IJ1] Starting...");
        // Update log with path to JAR
        IJ.log("[CellSegmentation IJ1] run() jar=" + getClass().getProtectionDomain().getCodeSource().getLocation());

        final ImagePlus imp = IJ.getImage();
        if (imp == null) {
            IJ.noImage();
            return;
        }

        // Update log with image title and dimensions
        IJ.log("[CellSegmentation IJ1] image=" + imp.getTitle() + " " + imp.getWidth() + "x" + imp.getHeight());

        // Get the running Fiji/ImageJ2 context (do NOT create a new Context).
        final Context context = IJ1Helper.getLegacyContext();
        final CommandService cs = context.service(CommandService.class);

        // `true` means "show the SciJava parameter dialog before running".
        // Named arguments here map directly onto `@Parameter` fields in the command.
        cs.run(CellSegmentationCommand.class, true, "imp", imp);
    }
}
