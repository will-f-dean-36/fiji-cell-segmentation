package com.will.cellseg.batch;

public final class SeriesMetadata {
    private final int sizeX;
    private final int sizeY;
    private final int sizeC;
    private final int sizeT;

    public SeriesMetadata(int sizeX, int sizeY, int sizeC, int sizeT) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeC = sizeC;
        this.sizeT = sizeT;
    }

    public int getSizeX() {
        return sizeX;
    }

    public int getSizeY() {
        return sizeY;
    }

    public int getSizeC() {
        return sizeC;
    }

    public int getSizeT() {
        return sizeT;
    }
}
