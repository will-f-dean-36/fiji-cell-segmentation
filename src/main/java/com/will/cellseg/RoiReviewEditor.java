package com.will.cellseg;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.Wand;
import ij.io.RoiEncoder;
import ij.plugin.frame.RoiManager;
import ij.process.FloatPolygon;
import ij.process.ImageProcessor;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

/** Purpose-built ROI review controls for the current-image command. */
public final class RoiReviewEditor {
    private static final int SPLIT_STROKE_WIDTH = 1;

    private RoiReviewEditor() {
    }

    public static BatchStopController.RoiReviewResult review(
            final ImagePlus source,
            final Roi[] proposedRois,
            final String title) {

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<BatchStopController.RoiReviewResult> result =
                new AtomicReference<BatchStopController.RoiReviewResult>(
                        BatchStopController.RoiReviewResult.continueWith(proposedRois));
        final AtomicBoolean completed = new AtomicBoolean(false);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                final ReviewSession session = new ReviewSession(source, proposedRois, title);

                final Runnable continueRun = new Runnable() {
                    @Override
                    public void run() {
                        if (completed.compareAndSet(false, true)) {
                            result.set(BatchStopController.RoiReviewResult.continueWith(session.getRois()));
                            session.dispose();
                            latch.countDown();
                        }
                    }
                };

                final Runnable continueToEndRun = new Runnable() {
                    @Override
                    public void run() {
                        if (completed.compareAndSet(false, true)) {
                            result.set(BatchStopController.RoiReviewResult.continueToEnd(session.getRois()));
                            session.dispose();
                            latch.countDown();
                        }
                    }
                };

                final Runnable skipRun = new Runnable() {
                    @Override
                    public void run() {
                        if (completed.compareAndSet(false, true)) {
                            result.set(BatchStopController.RoiReviewResult.skip());
                            session.dispose();
                            latch.countDown();
                        }
                    }
                };

                final Runnable abortRun = new Runnable() {
                    @Override
                    public void run() {
                        if (completed.compareAndSet(false, true)) {
                            result.set(BatchStopController.RoiReviewResult.abort());
                            session.dispose();
                            latch.countDown();
                        }
                    }
                };

                session.installCompletionActions(continueRun, continueToEndRun, skipRun, abortRun);
                session.show();
            }
        });

        IJ.log("[CellSegmentation] Waiting for ROI review editor: " + title);
        await(latch);
        final BatchStopController.RoiReviewResult reviewed = result.get();
        IJ.log("[CellSegmentation] ROI review editor complete: " + title + " action=" + reviewed.getAction().name());
        return reviewed;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for ROI review.", e);
        }
    }

    private static final class ReviewSession {
        private final ImagePlus reviewImp;
        private final RoiManager roiManager;
        private final JDialog dialog;
        private final List<Roi[]> undoStack = new ArrayList<Roi[]>();
        private final List<Roi[]> redoStack = new ArrayList<Roi[]>();
        private final Roi[] initialRois;
        private Roi[] lastKnownRois;
        private Timer roiMonitor;
        private boolean applyingEditorEdit;

        private ReviewSession(ImagePlus source, Roi[] proposedRois, String title) {
            reviewImp = source != null ? source.duplicate() : null;
            if (reviewImp != null) {
                reviewImp.setTitle(title);
                reviewImp.show();
                if (reviewImp.getWindow() != null) {
                    WindowManager.setCurrentWindow(reviewImp.getWindow());
                }
            }

            roiManager = new RoiManager();
            roiManager.setVisible(true);
            positionRoiManager(roiManager);
            loadInitialRois(cloneRois(proposedRois));
            initialRois = cloneRois(roiManager.getRoisAsArray());
            lastKnownRois = cloneRois(roiManager.getRoisAsArray());
            startRoiMonitor();
            refreshOverlay();

            dialog = createDialog(title);
        }

        private void show() {
            dialog.setVisible(true);
        }

        private Roi[] getRois() {
            return roiManager.getRoisAsArray();
        }

        private void installCompletionActions(
                Runnable continueRun,
                Runnable continueToEndRun,
                Runnable skipRun,
                Runnable abortRun) {

            final JPanel editPanel = new JPanel(new GridLayout(3, 1, 0, 8));
            editPanel.setBorder(new EmptyBorder(8, 10, 4, 10));
            final JButton splitButton = new JButton("Split");
            splitButton.addActionListener(e -> splitSelectedWithCurrentLine());
            final JButton mergeButton = new JButton("Merge");
            mergeButton.addActionListener(e -> mergeLinked());
            final JButton deleteButton = new JButton("Delete");
            deleteButton.addActionListener(e -> deleteMarked());
            final JButton undoButton = new JButton("Undo");
            undoButton.addActionListener(e -> undo());
            final JButton redoButton = new JButton("Redo");
            redoButton.addActionListener(e -> redo());
            final JButton resetButton = new JButton("Reset");
            resetButton.addActionListener(e -> reset());
            editPanel.add(operationPanel("Split ROIs", "Draw a line across a single ROI, then click the button.", splitButton));
            editPanel.add(operationPanel("Merge ROIs", "Draw a line with endpoints inside two ROIs, then click the button.", mergeButton));
            editPanel.add(operationPanel("Delete ROIs", "Draw through ROIs or place point markers inside them, then click the button.", deleteButton));
            dialog.add(editPanel, BorderLayout.CENTER);

            final JPanel undoPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            undoPanel.setBorder(new EmptyBorder(0, 10, 0, 10));
            undoPanel.add(undoButton);
            undoPanel.add(redoButton);
            undoPanel.add(resetButton);
            dialog.add(undoPanel, BorderLayout.NORTH);

            final JButton continueButton = new JButton("Continue");
            continueButton.addActionListener(e -> continueRun.run());
            final JButton continueToEndButton = new JButton("Continue to end");
            continueToEndButton.addActionListener(e -> continueToEndRun.run());
            final JButton skipButton = new JButton("Skip");
            skipButton.addActionListener(e -> skipRun.run());
            final JButton abortButton = new JButton("Abort");
            abortButton.addActionListener(e -> abortRun.run());
            dialog.add(buttonPanel(continueButton, continueToEndButton, skipButton, abortButton), BorderLayout.SOUTH);
            dialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    continueRun.run();
                }
            });
            dialog.pack();
            dialog.setSize(new Dimension(Math.max(430, dialog.getWidth()), Math.max(260, dialog.getHeight())));
            positionDialogNearImage(dialog, reviewImp);
            positionWindowBelowDialog(roiManager, dialog);
        }

        private void deleteMarked() {
            if (reviewImp == null || reviewImp.getRoi() == null) {
                showMessage("Draw a line through ROIs or place point markers inside ROIs to delete.");
                return;
            }

            final int[] marked = findTouchedIndexes(roiManager.getRoisAsArray(), reviewImp.getRoi());
            if (marked.length == 0) {
                showMessage("No ROIs were marked for deletion.");
                return;
            }
            deleteIndexes(marked);
        }

        private void deleteIndexes(int[] indexesToDelete) {
            Arrays.sort(indexesToDelete);
            pushUndo();
            final List<Roi> kept = new ArrayList<Roi>();
            final Roi[] rois = roiManager.getRoisAsArray();
            for (int i = 0; i < rois.length; i++) {
                if (Arrays.binarySearch(indexesToDelete, i) < 0) {
                    kept.add(rois[i]);
                }
            }
            if (reviewImp != null) {
                reviewImp.killRoi();
            }
            replaceRois(kept.toArray(new Roi[kept.size()]));
        }

        private void mergeLinked() {
            if (reviewImp == null || reviewImp.getRoi() == null || !reviewImp.getRoi().isLine()) {
                showMessage("Draw a line whose endpoints are inside two different ROIs.");
                return;
            }

            final int[] linked = findEndpointLinkedIndexes(roiManager.getRoisAsArray(), reviewImp.getRoi());
            if (linked.length < 2) {
                showMessage("Draw a line with each endpoint inside a different ROI to merge them.");
                return;
            }
            mergeIndexes(linked);
        }

        private void mergeIndexes(int[] indexesToMerge) {
            Arrays.sort(indexesToMerge);
            pushUndo();
            final Roi[] rois = roiManager.getRoisAsArray();
            if (indexesToMerge.length != 2) {
                showMessage("Draw a line with endpoints inside exactly two ROIs.");
                return;
            }
            final int first = indexesToMerge[0];
            final int second = indexesToMerge[1];
            if (first < 0 || first >= rois.length || second < 0 || second >= rois.length
                    || rois[first] == null || rois[second] == null
                    || !rois[first].isArea() || !rois[second].isArea()) {
                showMessage("Selected ROIs could not be merged.");
                return;
            }

            final Roi mergedRoi = mergeIntoConnectedRoi(rois[first], rois[second], reviewImp.getWidth(), reviewImp.getHeight());
            if (mergedRoi == null) {
                showMessage("Selected ROIs could not be merged.");
                return;
            }

            final List<Roi> updated = new ArrayList<Roi>();
            for (int i = 0; i < rois.length; i++) {
                if (Arrays.binarySearch(indexesToMerge, i) < 0) {
                    updated.add(rois[i]);
                }
            }
            mergedRoi.setName("Merged");
            updated.add(mergedRoi);
            if (reviewImp != null) {
                reviewImp.killRoi();
            }
            replaceRois(updated.toArray(new Roi[updated.size()]));
            refreshOverlay();
        }

        private void splitSelectedWithCurrentLine() {
            if (reviewImp == null || reviewImp.getRoi() == null || !reviewImp.getRoi().isLine()) {
                showMessage("Draw a straight, segmented, or freehand line across the ROI to split.");
                return;
            }

            final Roi[] rois = roiManager.getRoisAsArray();
            final SplitCandidate candidate = findSplitCandidate(rois, reviewImp.getRoi(), reviewImp.getWidth(), reviewImp.getHeight());
            if (candidate == null) {
                showMessage("The line did not split any ROI. Draw it across one ROI so it separates the object into at least two parts.");
                return;
            }

            final int selectedIndex = candidate.index;
            final Roi[] parts = candidate.parts;
            pushUndo();
            final List<Roi> updated = new ArrayList<Roi>();
            for (int i = 0; i < rois.length; i++) {
                if (i != selectedIndex) {
                    updated.add(rois[i]);
                }
            }
            final String baseName = rois[selectedIndex].getName() != null ? rois[selectedIndex].getName() : "ROI";
            for (int i = 0; i < parts.length; i++) {
                parts[i].setName(baseName + " split " + (i + 1));
                updated.add(parts[i]);
            }
            reviewImp.killRoi();
            replaceRois(updated.toArray(new Roi[updated.size()]));
            refreshOverlay();
        }

        private SplitCandidate findSplitCandidate(Roi[] rois, Roi lineRoi, int width, int height) {
            SplitCandidate best = null;
            for (int i = 0; i < rois.length; i++) {
                if (rois[i] == null || !rois[i].isArea()) {
                    continue;
                }
                final int lineOverlap = countLinePointsInside(rois[i], lineRoi);
                if (lineOverlap == 0) {
                    continue;
                }
                final Roi[] parts = splitRoi(rois[i], lineRoi, width, height);
                if (parts.length < 2) {
                    continue;
                }
                if (best == null || lineOverlap > best.lineOverlap) {
                    best = new SplitCandidate(i, parts, lineOverlap);
                }
            }
            return best;
        }

        private void undo() {
            if (undoStack.isEmpty()) {
                showMessage("No ROI edit to undo.");
                return;
            }
            redoStack.add(cloneRois(roiManager.getRoisAsArray()));
            final Roi[] previous = undoStack.remove(undoStack.size() - 1);
            replaceRois(previous);
        }

        private void redo() {
            if (redoStack.isEmpty()) {
                showMessage("No ROI edit to redo.");
                return;
            }
            undoStack.add(cloneRois(roiManager.getRoisAsArray()));
            final Roi[] next = redoStack.remove(redoStack.size() - 1);
            replaceRois(next);
        }

        private void reset() {
            final Roi[] current = cloneRois(roiManager.getRoisAsArray());
            if (sameRois(current, initialRois)) {
                showMessage("ROIs are already at their initial review state.");
                return;
            }
            undoStack.add(current);
            redoStack.clear();
            replaceRois(initialRois);
        }

        private void pushUndo() {
            undoStack.add(cloneRois(roiManager.getRoisAsArray()));
            redoStack.clear();
        }

        private void loadInitialRois(Roi[] rois) {
            roiManager.reset();
            for (Roi roi : cloneRois(rois)) {
                if (roi != null) {
                    roiManager.addRoi(roi);
                }
            }
            refreshOverlay();
        }

        private void replaceRois(Roi[] rois) {
            applyingEditorEdit = true;
            try {
                roiManager.runCommand(reviewImp, "Show None");
                for (int i = roiManager.getCount() - 1; i >= 0; i--) {
                    roiManager.delete(i);
                }
                for (Roi roi : cloneRois(rois)) {
                    if (roi != null) {
                        roiManager.addRoi(roi);
                    }
                }
                lastKnownRois = cloneRois(roiManager.getRoisAsArray());
                refreshOverlay();
            } finally {
                applyingEditorEdit = false;
            }
        }

        private void refreshOverlay() {
            if (reviewImp != null) {
                roiManager.runCommand(reviewImp, "Show All with labels");
                reviewImp.updateAndDraw();
            }
        }

        private void dispose() {
            if (roiMonitor != null) {
                roiMonitor.stop();
                roiMonitor = null;
            }
            if (reviewImp != null) {
                roiManager.runCommand(reviewImp, "Show None");
                reviewImp.changes = false;
                reviewImp.close();
            }
            roiManager.close();
            dialog.dispose();
        }

        private void showMessage(String message) {
            JOptionPane.showMessageDialog(dialog, message, "ROI Review", JOptionPane.INFORMATION_MESSAGE);
        }

        private void startRoiMonitor() {
            roiMonitor = new Timer(250, e -> captureManualRoiManagerChange());
            roiMonitor.setRepeats(true);
            roiMonitor.start();
        }

        private void captureManualRoiManagerChange() {
            if (applyingEditorEdit || roiManager == null) {
                return;
            }
            final Roi[] current = cloneRois(roiManager.getRoisAsArray());
            if (!sameRois(current, lastKnownRois)) {
                undoStack.add(cloneRois(lastKnownRois));
                redoStack.clear();
                lastKnownRois = current;
            }
        }
    }

    private static int[] findEndpointLinkedIndexes(Roi[] rois, Roi lineRoi) {
        final FloatPolygon points = getRoiPoints(lineRoi);
        if (points == null || points.npoints < 2) {
            return new int[0];
        }

        final int first = findContainingRoiIndex(rois, points.xpoints[0], points.ypoints[0]);
        final int last = findContainingRoiIndex(rois, points.xpoints[points.npoints - 1], points.ypoints[points.npoints - 1]);
        if (first < 0 || last < 0 || first == last) {
            return new int[0];
        }
        return new int[] {first, last};
    }

    private static int[] findTouchedIndexes(Roi[] rois, Roi markerRoi) {
        final FloatPolygon points = getRoiPoints(markerRoi);
        if (points == null || points.npoints == 0 || rois == null || rois.length == 0) {
            return new int[0];
        }

        final boolean[] touched = new boolean[rois.length];
        for (int i = 0; i < points.npoints; i++) {
            final int index = findContainingRoiIndex(rois, points.xpoints[i], points.ypoints[i]);
            if (index >= 0) {
                touched[index] = true;
            }
        }

        int count = 0;
        for (boolean value : touched) {
            if (value) {
                count++;
            }
        }
        final int[] indexes = new int[count];
        int out = 0;
        for (int i = 0; i < touched.length; i++) {
            if (touched[i]) {
                indexes[out++] = i;
            }
        }
        return indexes;
    }

    private static int findContainingRoiIndex(Roi[] rois, float x, float y) {
        if (rois == null) {
            return -1;
        }
        final int px = Math.round(x);
        final int py = Math.round(y);
        for (int i = 0; i < rois.length; i++) {
            if (rois[i] != null && rois[i].isArea() && rois[i].contains(px, py)) {
                return i;
            }
        }
        return -1;
    }

    private static int countLinePointsInside(Roi roi, Roi lineRoi) {
        final FloatPolygon points = getRoiPoints(lineRoi);
        if (roi == null || points == null || points.npoints == 0) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < points.npoints; i++) {
            if (roi.contains(Math.round(points.xpoints[i]), Math.round(points.ypoints[i]))) {
                count++;
            }
        }
        return count;
    }

    private static FloatPolygon getRoiPoints(Roi roi) {
        if (roi == null) {
            return null;
        }
        if (roi.isLine()) {
            return roi.getInterpolatedPolygon(1.0, false);
        }
        return roi.getFloatPolygon();
    }

    private static final class SplitCandidate {
        private final int index;
        private final Roi[] parts;
        private final int lineOverlap;

        private SplitCandidate(int index, Roi[] parts, int lineOverlap) {
            this.index = index;
            this.parts = parts;
            this.lineOverlap = lineOverlap;
        }
    }

    private static Roi[] splitRoi(Roi areaRoi, Roi lineRoi, int width, int height) {
        final ImagePlus mask = IJ.createImage("ROI Split Mask", "8-bit black", width, height, 1);
        try {
            final ImageProcessor ip = mask.getProcessor();
            ip.setValue(255);
            ip.fill(areaRoi);

            final Roi cutLine = (Roi) lineRoi.clone();
            cutLine.setStrokeWidth(SPLIT_STROKE_WIDTH);
            ip.setValue(0);
            cutLine.drawPixels(ip);
            removeDiagonalOnlySplitContacts(ip, cutLine, width, height);

            return componentsToRois(ip, width, height);
        } finally {
            mask.changes = false;
            mask.close();
        }
    }

    private static void removeDiagonalOnlySplitContacts(ImageProcessor ip, Roi lineRoi, int width, int height) {
        final byte[] pix = (byte[]) ip.convertToByteProcessor().getPixels();
        final List<ConnectedComponents.Component> components =
                ConnectedComponents.findForegroundComponents(pix, width, height, false);
        if (components.size() < 2) {
            return;
        }

        final int[] labels = new int[width * height];
        final int[] areas = new int[components.size() + 1];
        for (int i = 0; i < components.size(); i++) {
            final int label = i + 1;
            final ConnectedComponents.Component component = components.get(i);
            areas[label] = component.area();
            for (int idx : component.pixels) {
                labels[idx] = label;
            }
        }

        final FloatPolygon linePoints = getRoiPoints(lineRoi);
        final boolean[] remove = new boolean[width * height];
        for (int y = 0; y < height - 1; y++) {
            for (int x = 0; x < width - 1; x++) {
                final int upperLeft = y * width + x;
                final int upperRight = upperLeft + 1;
                final int lowerLeft = upperLeft + width;
                final int lowerRight = lowerLeft + 1;

                markDiagonalContactForRemoval(labels, areas, remove, linePoints, width,
                        upperLeft, x, y,
                        lowerRight, x + 1, y + 1,
                        upperRight, lowerLeft);
                markDiagonalContactForRemoval(labels, areas, remove, linePoints, width,
                        upperRight, x + 1, y,
                        lowerLeft, x, y + 1,
                        upperLeft, lowerRight);
            }
        }

        for (int i = 0; i < remove.length; i++) {
            if (remove[i]) {
                ip.set(i, 0);
            }
        }
    }

    private static void markDiagonalContactForRemoval(
            int[] labels,
            int[] areas,
            boolean[] remove,
            FloatPolygon linePoints,
            int width,
            int first,
            int firstX,
            int firstY,
            int second,
            int secondX,
            int secondY,
            int orthogonalA,
            int orthogonalB) {

        final int firstLabel = labels[first];
        final int secondLabel = labels[second];
        if (firstLabel == 0 || secondLabel == 0 || firstLabel == secondLabel
                || labels[orthogonalA] != 0 || labels[orthogonalB] != 0) {
            return;
        }

        final double firstDistance = distanceToLinePointsSquared(firstX, firstY, linePoints);
        final double secondDistance = distanceToLinePointsSquared(secondX, secondY, linePoints);
        if (firstDistance < secondDistance) {
            remove[first] = true;
        } else if (secondDistance < firstDistance) {
            remove[second] = true;
        } else if (areas[firstLabel] <= areas[secondLabel]) {
            remove[first] = true;
        } else {
            remove[second] = true;
        }
    }

    private static double distanceToLinePointsSquared(int x, int y, FloatPolygon linePoints) {
        if (linePoints == null || linePoints.npoints == 0) {
            return 0.0;
        }
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < linePoints.npoints; i++) {
            final double dx = x - linePoints.xpoints[i];
            final double dy = y - linePoints.ypoints[i];
            final double distance = dx * dx + dy * dy;
            if (distance < best) {
                best = distance;
            }
        }
        return best;
    }

    private static Roi mergeIntoConnectedRoi(Roi first, Roi second, int width, int height) {
        final byte[] labels = rasterizeMergeLabels(first, second, width, height);
        final byte[] merged = new byte[width * height];
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] != 0) {
                merged[i] = (byte) 255;
            }
        }

        if (!isSingleComponent(merged, width, height)) {
            fillSharedWatershedGap(labels, merged, width, height);
        }
        if (!isSingleComponent(merged, width, height)) {
            addShortestConnector(labels, merged, width, height);
        }

        final List<ConnectedComponents.Component> components =
                ConnectedComponents.findForegroundComponents(merged, width, height, false);
        if (components.isEmpty()) {
            return null;
        }
        components.sort(new Comparator<ConnectedComponents.Component>() {
            @Override
            public int compare(ConnectedComponents.Component a, ConnectedComponents.Component b) {
                return Integer.compare(b.area(), a.area());
            }
        });
        return componentToRoi(components.get(0), width, height);
    }

    private static byte[] rasterizeMergeLabels(Roi first, Roi second, int width, int height) {
        final byte[] labels = new byte[width * height];
        final ImageProcessor firstIp = new ij.process.ByteProcessor(width, height, new byte[width * height], null);
        firstIp.setValue(255);
        firstIp.fill(first);
        final byte[] firstPix = (byte[]) firstIp.getPixels();
        for (int i = 0; i < firstPix.length; i++) {
            if (firstPix[i] != 0) {
                labels[i] = 1;
            }
        }

        final ImageProcessor secondIp = new ij.process.ByteProcessor(width, height, new byte[width * height], null);
        secondIp.setValue(255);
        secondIp.fill(second);
        final byte[] secondPix = (byte[]) secondIp.getPixels();
        for (int i = 0; i < secondPix.length; i++) {
            if (secondPix[i] != 0) {
                labels[i] = 2;
            }
        }
        return labels;
    }

    private static boolean isSingleComponent(byte[] merged, int width, int height) {
        return ConnectedComponents.findForegroundComponents(merged, width, height, false).size() <= 1;
    }

    private static void fillSharedWatershedGap(byte[] labels, byte[] merged, int width, int height) {
        final byte[] toFill = new byte[labels.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int idx = y * width + x;
                if (labels[idx] != 0) {
                    continue;
                }
                boolean touchesFirst = false;
                boolean touchesSecond = false;
                for (int dy = -1; dy <= 1; dy++) {
                    final int ny = y + dy;
                    if (ny < 0 || ny >= height) {
                        continue;
                    }
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) {
                            continue;
                        }
                        final int nx = x + dx;
                        if (nx < 0 || nx >= width) {
                            continue;
                        }
                        final byte label = labels[ny * width + nx];
                        touchesFirst = touchesFirst || label == 1;
                        touchesSecond = touchesSecond || label == 2;
                    }
                }
                if (touchesFirst && touchesSecond) {
                    toFill[idx] = (byte) 255;
                }
            }
        }
        for (int i = 0; i < toFill.length; i++) {
            if (toFill[i] != 0) {
                merged[i] = (byte) 255;
            }
        }
    }

    private static void addShortestConnector(byte[] labels, byte[] merged, int width, int height) {
        final List<Integer> firstBoundary = boundaryPixels(labels, width, height, (byte) 1);
        final List<Integer> secondBoundary = boundaryPixels(labels, width, height, (byte) 2);
        if (firstBoundary.isEmpty() || secondBoundary.isEmpty()) {
            return;
        }

        int bestA = firstBoundary.get(0);
        int bestB = secondBoundary.get(0);
        long bestDistance = Long.MAX_VALUE;
        for (int a : firstBoundary) {
            final int ay = a / width;
            final int ax = a - ay * width;
            for (int b : secondBoundary) {
                final int by = b / width;
                final int bx = b - by * width;
                final long dx = ax - bx;
                final long dy = ay - by;
                final long dist = dx * dx + dy * dy;
                if (dist < bestDistance) {
                    bestDistance = dist;
                    bestA = a;
                    bestB = b;
                }
            }
        }

        final int ay = bestA / width;
        final int ax = bestA - ay * width;
        final int by = bestB / width;
        final int bx = bestB - by * width;
        drawOrthogonalConnector(merged, width, ax, ay, bx, by);
    }

    private static List<Integer> boundaryPixels(byte[] labels, int width, int height, byte target) {
        final List<Integer> pixels = new ArrayList<Integer>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int idx = y * width + x;
                if (labels[idx] != target || !hasNonTargetNeighbor(labels, width, height, x, y, target)) {
                    continue;
                }
                pixels.add(idx);
            }
        }
        return pixels;
    }

    private static boolean hasNonTargetNeighbor(byte[] labels, int width, int height, int x, int y, byte target) {
        for (int dy = -1; dy <= 1; dy++) {
            final int ny = y + dy;
            if (ny < 0 || ny >= height) {
                continue;
            }
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                final int nx = x + dx;
                if (nx < 0 || nx >= width) {
                    continue;
                }
                if (labels[ny * width + nx] != target) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void drawOrthogonalConnector(byte[] merged, int width, int x1, int y1, int x2, int y2) {
        int x = x1;
        int y = y1;
        merged[y * width + x] = (byte) 255;
        while (x != x2 || y != y2) {
            if (x != x2) {
                x += x < x2 ? 1 : -1;
                merged[y * width + x] = (byte) 255;
            }
            if (y != y2) {
                y += y < y2 ? 1 : -1;
                merged[y * width + x] = (byte) 255;
            }
        }
    }

    private static Roi[] componentsToRois(ImageProcessor ip, int width, int height) {
        final byte[] pix = (byte[]) ip.convertToByteProcessor().getPixels();
        final List<ConnectedComponents.Component> components =
                ConnectedComponents.findForegroundComponents(pix, width, height, false);
        final List<Roi> areaRois = new ArrayList<Roi>();
        components.sort(new Comparator<ConnectedComponents.Component>() {
            @Override
            public int compare(ConnectedComponents.Component a, ConnectedComponents.Component b) {
                return Integer.compare(b.area(), a.area());
            }
        });

        for (ConnectedComponents.Component component : components) {
            final Roi roi = componentToRoi(component, width, height);
            if (roi != null) {
                areaRois.add(roi);
            }
        }
        return areaRois.toArray(new Roi[areaRois.size()]);
    }

    private static Roi componentToRoi(ConnectedComponents.Component component, int width, int height) {
        if (component == null || component.pixels == null || component.pixels.length == 0) {
            return null;
        }

        final byte[] componentPixels = new byte[width * height];
        for (int idx : component.pixels) {
            componentPixels[idx] = (byte) 255;
        }

        final ImageProcessor componentIp = new ij.process.ByteProcessor(width, height, componentPixels, null);
        final int seed = component.pixels[0];
        final int seedY = seed / width;
        final int seedX = seed - seedY * width;
        final Wand wand = new Wand(componentIp);
        wand.autoOutline(seedX, seedY, 255, 255, Wand.FOUR_CONNECTED);
        if (wand.npoints < 3) {
            final ConnectedComponents.Stats stats = component.stats;
            return new Roi(stats.minX, stats.minY, stats.width(), stats.height());
        }
        return new PolygonRoi(new Polygon(wand.xpoints, wand.ypoints, wand.npoints), Roi.TRACED_ROI);
    }

    private static Roi[] cloneRois(Roi[] rois) {
        if (rois == null || rois.length == 0) {
            return new Roi[0];
        }
        final Roi[] cloned = new Roi[rois.length];
        for (int i = 0; i < rois.length; i++) {
            cloned[i] = rois[i] != null ? (Roi) rois[i].clone() : null;
        }
        return cloned;
    }

    private static boolean sameRois(Roi[] first, Roi[] second) {
        if (first == second) {
            return true;
        }
        final int firstLength = first != null ? first.length : 0;
        final int secondLength = second != null ? second.length : 0;
        if (firstLength != secondLength) {
            return false;
        }
        for (int i = 0; i < firstLength; i++) {
            if (!sameRoi(first[i], second[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameRoi(Roi first, Roi second) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        final byte[] firstBytes = RoiEncoder.saveAsByteArray(first);
        final byte[] secondBytes = RoiEncoder.saveAsByteArray(second);
        return Arrays.equals(firstBytes, secondBytes);
    }

    private static int[] range(int startInclusive, int endExclusive) {
        final int length = Math.max(0, endExclusive - startInclusive);
        final int[] values = new int[length];
        for (int i = 0; i < length; i++) {
            values[i] = startInclusive + i;
        }
        return values;
    }

    private static JDialog createDialog(String title) {
        final Frame owner = IJ.getInstance();
        final JDialog dialog = new JDialog(owner, title, false);
        dialog.setLayout(new BorderLayout(8, 8));
        dialog.setAlwaysOnTop(true);
        return dialog;
    }

    private static JPanel operationPanel(String title, String detail, JButton button) {
        final JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setBorder(BorderFactory.createTitledBorder(title));
        final JLabel label = new JLabel(detail);
        panel.add(label, BorderLayout.CENTER);
        panel.add(button, BorderLayout.EAST);
        return panel;
    }

    private static JPanel buttonPanel(JButton... buttons) {
        final JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        for (JButton button : buttons) {
            panel.add(button);
        }
        return panel;
    }

    private static void positionDialogNearImage(JDialog dialog, ImagePlus image) {
        final Rectangle bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        if (image != null && image.getWindow() != null) {
            final Window imageWindow = image.getWindow();
            final int gap = 16;
            int x = imageWindow.getX() - dialog.getWidth() - gap;
            if (x < bounds.x || x + dialog.getWidth() > bounds.x + bounds.width) {
                x = imageWindow.getX() + imageWindow.getWidth() + gap;
            }
            if (x + dialog.getWidth() <= bounds.x + bounds.width) {
                final int y = Math.max(bounds.y, Math.min(imageWindow.getY(),
                        bounds.y + bounds.height - dialog.getHeight()));
                dialog.setLocation(x, y);
                return;
            }
        }

        final int x = bounds.x + Math.max(0, (bounds.width - dialog.getWidth()) / 2);
        final int y = bounds.y + Math.max(0, (bounds.height - dialog.getHeight()) / 2);
        dialog.setLocation(x, y);
    }

    private static void positionWindowBelowDialog(Window window, JDialog dialog) {
        if (window == null || dialog == null) {
            return;
        }
        final Rectangle bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        final int gap = 12;
        int x = dialog.getX();
        int y = dialog.getY() + dialog.getHeight() + gap;
        if (x + window.getWidth() > bounds.x + bounds.width) {
            x = bounds.x + bounds.width - window.getWidth();
        }
        if (y + window.getHeight() > bounds.y + bounds.height) {
            y = dialog.getY() - window.getHeight() - gap;
        }
        x = Math.max(bounds.x, x);
        y = Math.max(bounds.y, Math.min(y, bounds.y + bounds.height - window.getHeight()));
        window.setLocation(x, y);
    }

    private static void positionRoiManager(RoiManager roiManager) {
        if (roiManager == null) {
            return;
        }
        final Rectangle bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        final Dimension size = roiManager.getSize();
        final int width = Math.max(size.width, 220);
        final int height = Math.max(size.height, 320);
        final int x = Math.max(bounds.x, bounds.x + bounds.width - width - 32);
        final int y = bounds.y + Math.max(0, (bounds.height - height) / 2);
        roiManager.setLocation(x, y);
    }
}
