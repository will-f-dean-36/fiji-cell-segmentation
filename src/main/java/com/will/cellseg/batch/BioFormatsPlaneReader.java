package com.will.cellseg.batch;

import ij.ImagePlus;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import loci.formats.FormatException;
import loci.formats.IFormatReader;
import loci.formats.ImageReader;
import loci.plugins.BF;
import loci.plugins.in.ImporterOptions;

public class BioFormatsPlaneReader implements InputResolver.MetadataProvider {

    public static class BioFormatsUnavailableException extends RuntimeException {
        public BioFormatsUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class FileMetadata {
        private final int seriesCount;
        private final SeriesMetadata[] series;

        private FileMetadata(int seriesCount, SeriesMetadata[] series) {
            this.seriesCount = seriesCount;
            this.series = series;
        }
    }

    private final Map<String, FileMetadata> cache = new HashMap<String, FileMetadata>();

    public BioFormatsPlaneReader() {
        ensureAvailable();
    }

    private void ensureAvailable() {
        try {
            Class.forName("loci.formats.ImageReader");
            Class.forName("loci.plugins.BF");
        } catch (Throwable t) {
            throw new BioFormatsUnavailableException(
                    "Bio-Formats classes not found at runtime.",
                    t
            );
        }
    }

    @Override
    public int getSeriesCount(File file) throws Exception {
        return getOrLoadMetadata(file).seriesCount;
    }

    @Override
    public SeriesMetadata getSeriesMetadata(File file, int seriesIndex) throws Exception {
        final FileMetadata metadata = getOrLoadMetadata(file);
        if (seriesIndex < 0 || seriesIndex >= metadata.seriesCount) {
            throw new IllegalArgumentException("Series index out of range for " + file.getName()
                    + ": " + seriesIndex + " (seriesCount=" + metadata.seriesCount + ")");
        }
        return metadata.series[seriesIndex];
    }

    public ImagePlus openPlane(File file, int seriesIndex, int channelIndex, int timeIndex) throws Exception {
        final ImporterOptions options = new ImporterOptions();
        options.setId(file.getAbsolutePath());
        options.setQuiet(true);
        options.setVirtual(false);
        options.clearSeries();
        options.setSeriesOn(seriesIndex, true);

        options.setZBegin(seriesIndex, 0);
        options.setZEnd(seriesIndex, 0);
        options.setCBegin(seriesIndex, channelIndex);
        options.setCEnd(seriesIndex, channelIndex);
        options.setTBegin(seriesIndex, timeIndex);
        options.setTEnd(seriesIndex, timeIndex);

        final ImagePlus[] imps = BF.openImagePlus(options);
        if (imps == null || imps.length == 0 || imps[0] == null) {
            throw new FormatException("Bio-Formats returned no image for " + file.getName()
                    + " [series=" + seriesIndex + ", C=" + channelIndex + ", T=" + timeIndex + "]");
        }

        final ImagePlus imp = imps[0];
        imp.setTitle(file.getName() + "_S" + seriesIndex + "_C" + channelIndex + "_T" + timeIndex);
        return imp;
    }

    private FileMetadata getOrLoadMetadata(File file) throws Exception {
        final String path = file.getAbsolutePath();
        FileMetadata md = cache.get(path);
        if (md != null) return md;

        final IFormatReader reader = new ImageReader();
        try {
            reader.setId(path);
            final int seriesCount = reader.getSeriesCount();
            final SeriesMetadata[] series = new SeriesMetadata[seriesCount];
            for (int s = 0; s < seriesCount; s++) {
                reader.setSeries(s);
                series[s] = new SeriesMetadata(
                        reader.getSizeX(),
                        reader.getSizeY(),
                        reader.getSizeC(),
                        reader.getSizeT()
                );
            }
            md = new FileMetadata(seriesCount, series);
            cache.put(path, md);
            return md;
        } finally {
            try {
                reader.close();
            } catch (Exception ignored) {
            }
        }
    }
}
