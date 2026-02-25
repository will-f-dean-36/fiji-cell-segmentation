package com.will.cellseg.batch;

public final class FrameSpec {
    private final int channelIndex;
    private final int timeIndex;

    public FrameSpec(int channelIndex, int timeIndex) {
        this.channelIndex = channelIndex;
        this.timeIndex = timeIndex;
    }

    public int getChannelIndex() {
        return channelIndex;
    }

    public int getTimeIndex() {
        return timeIndex;
    }
}
