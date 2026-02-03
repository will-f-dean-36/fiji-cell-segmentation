package com.will.cellseg;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class ConnectedComponents {

    private ConnectedComponents() {}

    // Border bit flags (use with borderMask)
    public static final int TOP    = 1;
    public static final int BOTTOM = 1 << 1;
    public static final int LEFT   = 1 << 2;
    public static final int RIGHT  = 1 << 3;

    /** Measurement bundle computed during BFS (no second pass required). */
    public static final class Stats {
        public final int area;
        public final int borderMask;

        public final int minX, minY, maxX, maxY; // inclusive bounds
        public final long sumX, sumY;            // sum of pixel coordinates (for centroid)

        Stats(int area, int borderMask, int minX, int minY, int maxX, int maxY, long sumX, long sumY) {
            this.area = area;
            this.borderMask = borderMask;
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.sumX = sumX;
            this.sumY = sumY;
        }

        public int width()  { return maxX - minX + 1; }
        public int height() { return maxY - minY + 1; }

        public int borderCount() { return Integer.bitCount(borderMask); }
        public boolean touchesTop()    { return (borderMask & TOP) != 0; }
        public boolean touchesBottom() { return (borderMask & BOTTOM) != 0; }
        public boolean touchesLeft()   { return (borderMask & LEFT) != 0; }
        public boolean touchesRight()  { return (borderMask & RIGHT) != 0; }

        /** Centroid in pixel coordinates (pixel centers assumed at integer x/y). */
        public double centroidX() { return (area == 0) ? Double.NaN : ((double) sumX) / area; }
        public double centroidY() { return (area == 0) ? Double.NaN : ((double) sumY) / area; }
    }

    /**
     * Collected component (stores pixel indices + Stats).
     * Pixel indices are linear: idx = y*w + x.
     */
    public static final class Component {
        public final int w, h;
        public final int[] pixels;
        public final Stats stats;

        Component(int w, int h, int[] pixels, Stats stats) {
            this.w = w;
            this.h = h;
            this.pixels = pixels;
            this.stats = stats;
        }

        public int area() { return stats.area; }
        public int borderMask() { return stats.borderMask; }
        public int borderCount() { return stats.borderCount(); }

        public void clear(byte[] pix) {
            for (int idx : pixels) pix[idx] = 0;
        }

        public void paint(byte[] pix, byte value) {
            for (int idx : pixels) pix[idx] = value;
        }
    }

    /**
     * Streaming view of a component (doesn't require storing a List<Component>).
     * Pixels list is reused between components; consume within the handler.
     */
    public static final class ComponentView {
        private final int w, h;
        private final IntList pixels;
        private final Stats stats;
        private final byte[] raster; // the mutable raster being processed (optional convenience)

        ComponentView(int w, int h, IntList pixels, Stats stats, byte[] raster) {
            this.w = w;
            this.h = h;
            this.pixels = pixels;
            this.stats = stats;
            this.raster = raster;
        }

        public int w() { return w; }
        public int h() { return h; }

        public Stats stats() { return stats; }

        /** Number of pixels in this component. */
        public int area() { return stats.area; }

        /** Access pixels (linear indices). */
        public IntListView pixels() { return pixels.view(); }

        /** Convenience: clear component in-place (requires raster != null). */
        public void clear() {
            if (raster == null) throw new IllegalStateException("No raster provided to ComponentView");
            for (int i = 0; i < pixels.size(); i++) raster[pixels.get(i)] = 0;
        }

        /** Convenience: paint component in-place (requires raster != null). */
        public void paint(byte value) {
            if (raster == null) throw new IllegalStateException("No raster provided to ComponentView");
            for (int i = 0; i < pixels.size(); i++) raster[pixels.get(i)] = value;
        }
    }

    /** Functional interface for streaming component processing. */
    public interface ComponentHandler {
        void handle(ComponentView c);
    }

    // ---------------------------
    // Public APIs
    // ---------------------------

    /** Collect style: find all 4-connected foreground components (pix != 0). */
    public static List<Component> findForegroundComponents(ImagePlus imp) {
        return findForegroundComponents(imp, false);
    }

    /** Collect style with connectivity option. */
    public static List<Component> findForegroundComponents(ImagePlus imp, boolean eightConnected) {
        if (imp == null) return new ArrayList<>();

        ImageProcessor ip0 = imp.getProcessor();
        ByteProcessor bp = ip0.convertToByteProcessor(); // copy, safe
        byte[] pix = (byte[]) bp.getPixels();

        return findForegroundComponents(pix, imp.getWidth(), imp.getHeight(), eightConnected);
    }

    /** Collect style on raw byte raster. */
    public static List<Component> findForegroundComponents(byte[] pix, int w, int h, boolean eightConnected) {
        final ArrayList<Component> out = new ArrayList<>();
        forEachForegroundComponent(pix, w, h, eightConnected, new ComponentHandler() {
            @Override public void handle(ComponentView c) {
                // Freeze pixels into an int[] for storage
                int[] arr = c.pixels.pixelsToArray();
                out.add(new Component(w, h, arr, c.stats));
            }
        });
        return out;
    }

    /** Streaming style: 4-connected. */
    public static void forEachForegroundComponent(byte[] pix, int w, int h, ComponentHandler handler) {
        forEachForegroundComponent(pix, w, h, false, handler);
    }

    /** Streaming style: connectivity option. */
    public static void forEachForegroundComponent(byte[] pix, int w, int h, boolean eightConnected, ComponentHandler handler) {
        if (pix == null || pix.length != w * h) throw new IllegalArgumentException("pix must be length w*h");
        if (handler == null) throw new IllegalArgumentException("handler must not be null");

        // Track visited pixels to avoid revisiting components.
        final boolean[] visited = new boolean[w * h];
        final ArrayDeque<Integer> q = new ArrayDeque<>();
        final IntList comp = new IntList(1024);

        for (int y0 = 0; y0 < h; y0++) {
            final int row0 = y0 * w;
            for (int x0 = 0; x0 < w; x0++) {
                final int seed = row0 + x0;
                if (pix[seed] == 0 || visited[seed]) continue;

                // Reset per-component accumulators.
                comp.clear();
                q.clear();

                visited[seed] = true;
                q.add(seed);

                int borderMask = 0;
                int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
                long sumX = 0, sumY = 0;
                int area = 0;

                // BFS flood fill of the current component.
                while (!q.isEmpty()) {
                    final int p = q.removeFirst();
                    comp.add(p);

                    final int py = p / w;
                    final int px = p - py * w;

                    // Measurements (pixel-based, computed during BFS)
                    area++;
                    sumX += px;
                    sumY += py;
                    if (px < minX) minX = px;
                    if (py < minY) minY = py;
                    if (px > maxX) maxX = px;
                    if (py > maxY) maxY = py;

                    // Border touches (bitmask)
                    if (py == 0)     borderMask |= TOP;
                    if (py == h - 1) borderMask |= BOTTOM;
                    if (px == 0)     borderMask |= LEFT;
                    if (px == w - 1) borderMask |= RIGHT;

                    // 4-connected neighbors.
                    if (py > 0) {
                        int up = p - w;
                        if (!visited[up] && pix[up] != 0) { visited[up] = true; q.add(up); }
                    }
                    if (py < h - 1) {
                        int dn = p + w;
                        if (!visited[dn] && pix[dn] != 0) { visited[dn] = true; q.add(dn); }
                    }
                    if (px > 0) {
                        int lt = p - 1;
                        if (!visited[lt] && pix[lt] != 0) { visited[lt] = true; q.add(lt); }
                    }
                    if (px < w - 1) {
                        int rt = p + 1;
                        if (!visited[rt] && pix[rt] != 0) { visited[rt] = true; q.add(rt); }
                    }

                    // Optional 8-connected diagonals.
                    if (eightConnected) {
                        if (py > 0 && px > 0) {
                            int ul = p - w - 1;
                            if (!visited[ul] && pix[ul] != 0) { visited[ul] = true; q.add(ul); }
                        }
                        if (py > 0 && px < w - 1) {
                            int ur = p - w + 1;
                            if (!visited[ur] && pix[ur] != 0) { visited[ur] = true; q.add(ur); }
                        }
                        if (py < h - 1 && px > 0) {
                            int dl = p + w - 1;
                            if (!visited[dl] && pix[dl] != 0) { visited[dl] = true; q.add(dl); }
                        }
                        if (py < h - 1 && px < w - 1) {
                            int dr = p + w + 1;
                            if (!visited[dr] && pix[dr] != 0) { visited[dr] = true; q.add(dr); }
                        }
                    }
                }

                // Package stats + call handler immediately (streaming).
                Stats stats = new Stats(area, borderMask, minX, minY, maxX, maxY, sumX, sumY);
                ComponentView view = new ComponentView(w, h, comp, stats, pix);
                handler.handle(view);
            }
        }
    }

    // ---------------------------
    // Internal utilities
    // ---------------------------

    /** Growable int list (stores pixel indices for current component). */
    private static final class IntList {
        private int[] a;
        private int n;

        IntList(int initialCapacity) {
            a = new int[Math.max(16, initialCapacity)];
            n = 0;
        }

        void clear() { n = 0; }
        int size() { return n; }
        int get(int i) { return a[i]; }

        void add(int v) {
            if (n == a.length) {
                int[] b = new int[a.length * 2];
                System.arraycopy(a, 0, b, 0, a.length);
                a = b;
            }
            a[n++] = v;
        }

        int[] pixelsToArray() {
            int[] out = new int[n];
            System.arraycopy(a, 0, out, 0, n);
            return out;
        }

        IntListView view() { return new IntListView(this); }
    }

    /** Read-only view for pixels list (no allocations). */
    public static final class IntListView {
        private final IntList list;
        IntListView(IntList list) { this.list = list; }
        public int size() { return list.size(); }
        public int get(int i) { return list.get(i); }
    }
}
