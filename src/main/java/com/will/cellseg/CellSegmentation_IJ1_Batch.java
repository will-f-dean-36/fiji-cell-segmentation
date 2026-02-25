package com.will.cellseg;

import com.will.cellseg.batch.InputMode;
import ij.IJ;
import ij.gui.GenericDialog;
import ij.io.DirectoryChooser;
import ij.plugin.PlugIn;
import java.awt.FileDialog;
import java.io.File;
import net.imagej.legacy.IJ1Helper;
import org.scijava.Context;
import org.scijava.command.CommandService;

public class CellSegmentation_IJ1_Batch implements PlugIn {

    private static final String[] MODE_LABELS = new String[] {
            "Mode 1: Two container files (pair by series index)",
            "Mode 2: Two file lists (pair by selection order)",
            "Mode 3: Same-file channels (C1=RICM, C2..=Fluor)"
    };

    @Override
    public void run(String arg) {

        IJ.log("[CellSegmentation IJ1 Batch] Starting...");
        IJ.log("[CellSegmentation IJ1 Batch] run() jar=" + getClass().getProtectionDomain().getCodeSource().getLocation());

        final BatchUiOptions uiOptions = askInputOptions();
        if (uiOptions == null) return;

        final SelectionPayload selection = chooseFiles(uiOptions.mode);
        if (selection == null) return;

        File outDir = chooseOutputDir();
        if (outDir == null) return;

        IJ.log("[CellSegmentation IJ1 Batch] Input mode: " + uiOptions.mode.name());
        IJ.log("[CellSegmentation IJ1 Batch] Output directory: " + outDir.getAbsolutePath());

        final Context context = IJ1Helper.getLegacyContext();
        final CommandService cs = context.service(CommandService.class);

        cs.run(CellSegmentationCommand_Batch.class, true,
                "inputMode", uiOptions.mode.name(),
                "ricmContainerFile", selection.ricmContainerFile,
                "fluorContainerFile", selection.fluorContainerFile,
                "ricmFiles", selection.ricmFiles,
                "fluorFiles", selection.fluorFiles,
                "combinedFiles", selection.combinedFiles,
                "sameFileSegChannelIndex1Based", Integer.valueOf(uiOptions.sameFileSegChannelIndex1Based),
                "sameFileFirstMeasChannelIndex1Based", Integer.valueOf(uiOptions.sameFileFirstMeasChannelIndex1Based),
                "allTimepoints", Boolean.TRUE,
                "outputDir", outDir);
    }

    private static BatchUiOptions askInputOptions() {
        final GenericDialog gd = new GenericDialog("Batch Input Mode");
        gd.addChoice("Input mode", MODE_LABELS, MODE_LABELS[0]);
        gd.addNumericField("Mode 3 RICM channel (1-based)", 1, 0);
        gd.addNumericField("Mode 3 first fluorescence channel (1-based)", 2, 0);
        gd.addMessage("Batch is non-interactive. Bio-Formats dialogs are disabled.");
        gd.showDialog();
        if (gd.wasCanceled()) return null;

        final int selectedMode = gd.getNextChoiceIndex();
        final int segChannel = Math.max(1, (int) Math.round(gd.getNextNumber()));
        final int firstMeas = Math.max(1, (int) Math.round(gd.getNextNumber()));

        return new BatchUiOptions(indexToMode(selectedMode), segChannel, firstMeas);
    }

    private static SelectionPayload chooseFiles(InputMode mode) {
        switch (mode) {
            case CONTAINER_SERIES_PAIR:
                File ricmContainer = chooseSingleFile("Select RICM container file");
                if (ricmContainer == null) return null;
                File fluorContainer = chooseSingleFile("Select fluorescence container file");
                if (fluorContainer == null) return null;
                return SelectionPayload.forMode1(ricmContainer, fluorContainer);
            case FILE_LIST_PAIR:
                File[] ricmFiles = chooseMultipleFiles("Select RICM files");
                if (ricmFiles == null || ricmFiles.length == 0) return null;
                File[] fluorFiles = chooseMultipleFiles("Select fluorescence files");
                if (fluorFiles == null || fluorFiles.length == 0) return null;
                return SelectionPayload.forMode2(ricmFiles, fluorFiles);
            case SAME_FILE_CHANNELS:
                File[] combinedFiles = chooseMultipleFiles("Select combined multi-channel files");
                if (combinedFiles == null || combinedFiles.length == 0) return null;
                return SelectionPayload.forMode3(combinedFiles);
            default:
                throw new IllegalArgumentException("Unsupported mode: " + mode);
        }
    }

    private static File chooseSingleFile(String title) {
        FileDialog fd = new FileDialog(IJ.getInstance(), title, FileDialog.LOAD);
        fd.setMultipleMode(false);
        fd.setVisible(true);
        File[] files = fd.getFiles();
        if (files == null || files.length == 0) return null;
        return files[0];
    }

    private static File[] chooseMultipleFiles(String title) {
        FileDialog fd = new FileDialog(IJ.getInstance(), title, FileDialog.LOAD);
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

    private static InputMode indexToMode(int idx) {
        switch (idx) {
            case 0:
                return InputMode.CONTAINER_SERIES_PAIR;
            case 1:
                return InputMode.FILE_LIST_PAIR;
            case 2:
                return InputMode.SAME_FILE_CHANNELS;
            default:
                return InputMode.CONTAINER_SERIES_PAIR;
        }
    }

    private static final class BatchUiOptions {
        private final InputMode mode;
        private final int sameFileSegChannelIndex1Based;
        private final int sameFileFirstMeasChannelIndex1Based;

        private BatchUiOptions(InputMode mode, int sameFileSegChannelIndex1Based, int sameFileFirstMeasChannelIndex1Based) {
            this.mode = mode;
            this.sameFileSegChannelIndex1Based = sameFileSegChannelIndex1Based;
            this.sameFileFirstMeasChannelIndex1Based = sameFileFirstMeasChannelIndex1Based;
        }
    }

    private static final class SelectionPayload {
        private final File ricmContainerFile;
        private final File fluorContainerFile;
        private final File[] ricmFiles;
        private final File[] fluorFiles;
        private final File[] combinedFiles;

        private SelectionPayload(File ricmContainerFile, File fluorContainerFile, File[] ricmFiles, File[] fluorFiles, File[] combinedFiles) {
            this.ricmContainerFile = ricmContainerFile;
            this.fluorContainerFile = fluorContainerFile;
            this.ricmFiles = ricmFiles;
            this.fluorFiles = fluorFiles;
            this.combinedFiles = combinedFiles;
        }

        private static SelectionPayload forMode1(File ricmContainerFile, File fluorContainerFile) {
            return new SelectionPayload(ricmContainerFile, fluorContainerFile, null, null, null);
        }

        private static SelectionPayload forMode2(File[] ricmFiles, File[] fluorFiles) {
            return new SelectionPayload(null, null, ricmFiles, fluorFiles, null);
        }

        private static SelectionPayload forMode3(File[] combinedFiles) {
            return new SelectionPayload(null, null, null, null, combinedFiles);
        }
    }
}
