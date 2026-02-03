package com.will.cellseg;

import ij.IJ;
import ij.io.DirectoryChooser;
import ij.plugin.PlugIn;
import java.awt.FileDialog;
import java.io.File;
import net.imagej.legacy.IJ1Helper;
import org.scijava.Context;
import org.scijava.command.CommandService;

public class CellSegmentation_IJ1_Batch implements PlugIn {

    @Override
    public void run(String arg) {
        File[] files = chooseInputFiles();
        if (files == null || files.length == 0) return;

        File outDir = chooseOutputDir();
        if (outDir == null) return;

        final Context context = IJ1Helper.getLegacyContext();
        final CommandService cs = context.service(CommandService.class);

        cs.run(CellSegmentationCommand_Batch.class, true,
                "inputFiles", files,
                "outputDir", outDir);
    }

    private static File[] chooseInputFiles() {
        FileDialog fd = new FileDialog(IJ.getInstance(), "Select images", FileDialog.LOAD);
        fd.setMultipleMode(true);
        fd.setVisible(true);
        File[] files = fd.getFiles();
        if (files == null || files.length == 0) return null;
        return files;
    }

    private static File chooseOutputDir() {
        DirectoryChooser dc = new DirectoryChooser("Select output folder");
        String dir = dc.getDirectory();
        if (dir == null || dir.trim().isEmpty()) return null;
        return new File(dir);
    }
}
