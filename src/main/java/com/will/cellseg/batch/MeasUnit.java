package com.will.cellseg.batch;

import java.io.File;

public final class MeasUnit {
    private final File source;
    private final int seriesIndex;
    private final int[] measChannelIndices;
    private final boolean allTimepoints;

    public MeasUnit(File source, int seriesIndex, int[] measChannelIndices, boolean allTimepoints) {
        this.source = source;
        this.seriesIndex = seriesIndex;
        this.measChannelIndices = measChannelIndices;
        this.allTimepoints = allTimepoints;
    }

    public File getSource() {
        return source;
    }

    public int getSeriesIndex() {
        return seriesIndex;
    }

    public int[] getMeasChannelIndices() {
        return measChannelIndices;
    }

    public boolean isAllTimepoints() {
        return allTimepoints;
    }
}
