package com.will.cellseg.batch;

import java.util.ArrayList;
import java.util.List;

public final class MeasurementPlan {

    private MeasurementPlan() {}

    public static List<FrameSpec> planFrames(MeasUnit measUnit, SeriesMetadata metadata) {
        final int[] channels = measUnit.getMeasChannelIndices();
        if (channels == null || channels.length == 0) {
            throw new IllegalArgumentException("No measurement channels selected.");
        }

        final int maxChannels = Math.max(1, metadata.getSizeC());
        final int timepoints = Math.max(1, metadata.getSizeT());
        final int tStart = 0;
        final int tEndExclusive = measUnit.isAllTimepoints() ? timepoints : 1;

        final List<FrameSpec> frames = new ArrayList<FrameSpec>();
        for (int channel : channels) {
            if (channel < 0 || channel >= maxChannels) {
                throw new IllegalArgumentException("Measurement channel index out of range: " + (channel + 1));
            }
            for (int t = tStart; t < tEndExclusive; t++) {
                frames.add(new FrameSpec(channel, t));
            }
        }
        return frames;
    }
}
