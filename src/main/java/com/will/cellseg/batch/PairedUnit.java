package com.will.cellseg.batch;

public final class PairedUnit {
    private final SegUnit segUnit;
    private final MeasUnit measUnit;

    public PairedUnit(SegUnit segUnit, MeasUnit measUnit) {
        this.segUnit = segUnit;
        this.measUnit = measUnit;
    }

    public SegUnit getSegUnit() {
        return segUnit;
    }

    public MeasUnit getMeasUnit() {
        return measUnit;
    }
}
