package com.will.cellseg.batch;

import java.io.File;

public final class SegUnit {
    private final File source;
    private final int seriesIndex;
    private final int segChannelIndex;

    public SegUnit(File source, int seriesIndex, int segChannelIndex) {
        this.source = source;
        this.seriesIndex = seriesIndex;
        this.segChannelIndex = segChannelIndex;
    }

    public File getSource() {
        return source;
    }

    public int getSeriesIndex() {
        return seriesIndex;
    }

    public int getSegChannelIndex() {
        return segChannelIndex;
    }
}
