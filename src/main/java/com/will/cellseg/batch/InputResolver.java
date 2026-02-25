package com.will.cellseg.batch;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class InputResolver {

    public interface MetadataProvider {
        int getSeriesCount(File file) throws Exception;
        SeriesMetadata getSeriesMetadata(File file, int seriesIndex) throws Exception;
    }

    private InputResolver() {}

    public static List<PairedUnit> resolve(
            InputMode mode,
            File ricmContainerFile,
            File fluorContainerFile,
            File[] ricmFiles,
            File[] fluorFiles,
            File[] combinedFiles,
            int sameFileSegChannelIndex1Based,
            int sameFileFirstMeasChannelIndex1Based,
            boolean allTimepoints,
            MetadataProvider metadataProvider) throws Exception {

        switch (mode) {
            case CONTAINER_SERIES_PAIR:
                return resolveMode1(ricmContainerFile, fluorContainerFile, allTimepoints, metadataProvider);
            case FILE_LIST_PAIR:
                return resolveMode2(ricmFiles, fluorFiles, allTimepoints);
            case SAME_FILE_CHANNELS:
                return resolveMode3(combinedFiles,
                        sameFileSegChannelIndex1Based,
                        sameFileFirstMeasChannelIndex1Based,
                        allTimepoints,
                        metadataProvider);
            default:
                throw new IllegalArgumentException("Unsupported input mode: " + mode);
        }
    }

    private static List<PairedUnit> resolveMode1(
            File ricmContainerFile,
            File fluorContainerFile,
            boolean allTimepoints,
            MetadataProvider metadataProvider) throws Exception {

        requireFile(ricmContainerFile, "RICM container");
        requireFile(fluorContainerFile, "Fluorescence container");

        final int ricmSeries = metadataProvider.getSeriesCount(ricmContainerFile);
        final int fluorSeries = metadataProvider.getSeriesCount(fluorContainerFile);

        if (ricmSeries != fluorSeries) {
            throw new IllegalArgumentException("Mode 1 series count mismatch. RICM=" + ricmSeries
                    + " (" + ricmContainerFile.getName() + ") vs Fluorescence=" + fluorSeries
                    + " (" + fluorContainerFile.getName() + ")");
        }

        final List<PairedUnit> out = new ArrayList<PairedUnit>();
        for (int s = 0; s < ricmSeries; s++) {
            final SegUnit seg = new SegUnit(ricmContainerFile, s, 0);
            final SeriesMetadata measMeta = metadataProvider.getSeriesMetadata(fluorContainerFile, s);
            final int[] measChannels = channelsFromRangeExcluding(Math.max(1, measMeta.getSizeC()), 0, -1);
            final MeasUnit meas = new MeasUnit(fluorContainerFile, s, measChannels, allTimepoints);
            out.add(new PairedUnit(seg, meas));
        }
        return out;
    }

    private static List<PairedUnit> resolveMode2(
            File[] ricmFiles,
            File[] fluorFiles,
            boolean allTimepoints) throws Exception {

        final File[] ricm = cleanFiles(ricmFiles);
        final File[] fluor = cleanFiles(fluorFiles);

        if (ricm.length == 0) {
            throw new IllegalArgumentException("Mode 2 requires at least one RICM file.");
        }
        if (fluor.length == 0) {
            throw new IllegalArgumentException("Mode 2 requires at least one fluorescence file.");
        }
        if (ricm.length != fluor.length) {
            throw new IllegalArgumentException("Mode 2 file count mismatch. RICM=" + ricm.length
                    + " vs Fluorescence=" + fluor.length);
        }

        final List<PairedUnit> out = new ArrayList<PairedUnit>();
        for (int i = 0; i < ricm.length; i++) {
            requireFile(ricm[i], "RICM file #" + (i + 1));
            requireFile(fluor[i], "Fluorescence file #" + (i + 1));
            final SegUnit seg = new SegUnit(ricm[i], 0, 0);
            final MeasUnit meas = new MeasUnit(fluor[i], 0, null, allTimepoints);
            out.add(new PairedUnit(seg, meas));
        }
        return out;
    }

    private static List<PairedUnit> resolveMode3(
            File[] combinedFiles,
            int sameFileSegChannelIndex1Based,
            int sameFileFirstMeasChannelIndex1Based,
            boolean allTimepoints,
            MetadataProvider metadataProvider) throws Exception {

        final File[] files = cleanFiles(combinedFiles);
        if (files.length == 0) {
            throw new IllegalArgumentException("Mode 3 requires at least one input file.");
        }

        final int segChannel = sameFileSegChannelIndex1Based - 1;
        final int firstMeasChannel = sameFileFirstMeasChannelIndex1Based - 1;
        if (segChannel < 0) {
            throw new IllegalArgumentException("Segmentation channel must be >= 1.");
        }
        if (firstMeasChannel < 0) {
            throw new IllegalArgumentException("First fluorescence channel must be >= 1.");
        }

        final List<PairedUnit> out = new ArrayList<PairedUnit>();
        for (int i = 0; i < files.length; i++) {
            final File file = files[i];
            requireFile(file, "Combined file #" + (i + 1));

            final SeriesMetadata meta = metadataProvider.getSeriesMetadata(file, 0);
            final int sizeC = Math.max(1, meta.getSizeC());
            if (segChannel >= sizeC) {
                throw new IllegalArgumentException("Mode 3 segmentation channel out of range for "
                        + file.getName() + ": selected C" + (segChannel + 1)
                        + " but sizeC=" + sizeC);
            }
            if (firstMeasChannel >= sizeC) {
                throw new IllegalArgumentException("Mode 3 fluorescence channel range out of bounds for "
                        + file.getName() + ": selected start C" + (firstMeasChannel + 1)
                        + " but sizeC=" + sizeC);
            }

            final int[] measChannels = channelsFromRangeExcluding(sizeC, firstMeasChannel, segChannel);
            if (measChannels.length == 0) {
                throw new IllegalArgumentException("Mode 3 has no fluorescence channels after mapping for "
                        + file.getName() + ".");
            }

            final SegUnit seg = new SegUnit(file, 0, segChannel);
            final MeasUnit meas = new MeasUnit(file, 0, measChannels, allTimepoints);
            out.add(new PairedUnit(seg, meas));
        }

        return out;
    }

    private static void requireFile(File file, String name) {
        if (file == null) {
            throw new IllegalArgumentException(name + " is required.");
        }
        if (!file.isFile()) {
            throw new IllegalArgumentException(name + " is not a readable file: " + file);
        }
    }

    private static File[] cleanFiles(File[] files) {
        if (files == null || files.length == 0) {
            return new File[0];
        }
        final List<File> out = new ArrayList<File>();
        for (File f : files) {
            if (f != null) out.add(f);
        }
        return out.toArray(new File[0]);
    }

    private static int[] channelsFromRangeExcluding(int sizeC, int startInclusive, int excluded) {
        final List<Integer> channels = new ArrayList<Integer>();
        final int start = Math.max(0, startInclusive);
        for (int c = start; c < sizeC; c++) {
            if (c == excluded) continue;
            channels.add(Integer.valueOf(c));
        }

        final int[] arr = new int[channels.size()];
        for (int i = 0; i < channels.size(); i++) {
            arr[i] = channels.get(i).intValue();
        }
        return arr;
    }
}
