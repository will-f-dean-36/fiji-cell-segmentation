package com.will.cellseg;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import org.scijava.Context;

/** Coordinates optional batch stop-points without blocking the Swing EDT. */
public final class BatchStopController {
    private boolean rememberThreshold = true;
    private ImagePlus reviewImage;

    public ThresholdSelectionResult maybeSelectThreshold(
            Context ctx,
            final ImagePlus ricmPreview,
            final ThresholdConfig currentOrDefault,
            final String title) {

        if (ricmPreview == null) {
            return ThresholdSelectionResult.continueWith(currentOrDefault, rememberThreshold);
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<ThresholdSelectionResult> selected =
                new AtomicReference<ThresholdSelectionResult>(ThresholdSelectionResult.continueWith(currentOrDefault, rememberThreshold));
        final AtomicBoolean completed = new AtomicBoolean(false);

        // UI creation and updates must happen on the Swing EDT. The batch worker thread
        // waits on the latch below instead of blocking the EDT.
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                hideReviewImage();
                if (currentOrDefault != null) {
                    currentOrDefault.applyTo(ricmPreview);
                }
                if (ricmPreview.getWindow() == null) {
                    ricmPreview.show();
                }
                if (ricmPreview.getWindow() != null) {
                    WindowManager.setCurrentWindow(ricmPreview.getWindow());
                }
                IJ.run(ricmPreview, "Threshold...", "");

                // This is intentionally a tiny controller dialog. The real threshold UI
                // is still ImageJ's standard Threshold window.
                final JDialog dialog = createDialog(title, "Adjust the threshold, then choose an action.", false);
                final JCheckBox rememberBox = new JCheckBox("Remember threshold", rememberThreshold);
                rememberBox.setBorder(new EmptyBorder(0, 16, 4, 16));
                dialog.add(rememberBox, BorderLayout.CENTER);
                final Runnable continueRun = new Runnable() {
                    @Override
                    public void run() {
                        if (completed.compareAndSet(false, true)) {
                            rememberThreshold = rememberBox.isSelected();
                            selected.set(ThresholdSelectionResult.continueWith(
                                    CellSegmentationPipeline.captureThresholdConfig(ricmPreview, currentOrDefault),
                                    rememberThreshold));
                            dialog.dispose();
                            latch.countDown();
                        }
                    }
                };

                final Runnable continueToEndRun = new Runnable() {
                    @Override
                    public void run() {
                        if (completed.compareAndSet(false, true)) {
                            rememberThreshold = rememberBox.isSelected();
                            selected.set(ThresholdSelectionResult.continueToEnd(
                                    CellSegmentationPipeline.captureThresholdConfig(ricmPreview, currentOrDefault),
                                    rememberThreshold));
                            dialog.dispose();
                            latch.countDown();
                        }
                    }
                };

                final Runnable skipRun = new Runnable() {
                    @Override
                    public void run() {
                        if (completed.compareAndSet(false, true)) {
                            rememberThreshold = rememberBox.isSelected();
                            selected.set(ThresholdSelectionResult.skip());
                            dialog.dispose();
                            latch.countDown();
                        }
                    }
                };

                final Runnable abortRun = new Runnable() {
                    @Override
                    public void run() {
                        if (completed.compareAndSet(false, true)) {
                            selected.set(ThresholdSelectionResult.abort());
                            dialog.dispose();
                            latch.countDown();
                        }
                    }
                };

                JButton continueButton = new JButton("Continue");
                continueButton.addActionListener(e -> continueRun.run());
                JButton continueToEndButton = new JButton("Continue to end");
                continueToEndButton.addActionListener(e -> continueToEndRun.run());
                JButton skipButton = new JButton("Skip");
                skipButton.addActionListener(e -> skipRun.run());
                JButton abortButton = new JButton("Abort");
                abortButton.addActionListener(e -> abortRun.run());
                dialog.add(buttonPanel(continueButton, continueToEndButton, skipButton, abortButton), BorderLayout.SOUTH);
                dialog.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        continueRun.run();
                    }
                });
                dialog.setVisible(true);
            }
        });

        // `await` runs on the batch worker thread, so Fiji's UI remains responsive.
        IJ.log("[CellSegmentation Batch] Waiting for threshold selection: " + title);
        await(latch);
        final ThresholdSelectionResult result = selected.get();
        IJ.log("[CellSegmentation Batch] Threshold selection complete: " + title
                + " action=" + result.getAction().name()
                + (result.isContinue()
                ? " mode=" + (result.getConfig() != null && result.getConfig().isManual() ? "manual" : "auto")
                + " remember=" + result.shouldRememberThreshold()
                : ""));
        return result;
    }

    public RoiReviewResult maybeReviewRois(
            Context ctx,
            final ImagePlus imp,
            final Roi[] proposedRois,
            final String title) {

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<RoiReviewResult> result = new AtomicReference<RoiReviewResult>(RoiReviewResult.continueWith(proposedRois));
        final AtomicBoolean completed = new AtomicBoolean(false);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                final RoiManager roiManager = getOrCreateReviewRoiManager();
                roiManager.reset();
                for (Roi roi : cloneRois(proposedRois)) {
                    if (roi != null) {
                        roiManager.addRoi(roi);
                    }
                }
                final ImagePlus reviewImp = updateReviewImage(imp, title);
                if (reviewImp != null) {
                    roiManager.runCommand(reviewImp, "Show All with labels");
                    if (reviewImp.getWindow() != null) {
                        WindowManager.setCurrentWindow(reviewImp.getWindow());
                    }
                }

                final JDialog dialog = createDialog(title, "Review ROIs in ROI Manager, then choose an action.", true);
                final Runnable cleanup = new Runnable() {
                    @Override
                    public void run() {
                        if (reviewImp != null) {
                            roiManager.runCommand(reviewImp, "Show None");
                            reviewImp.changes = false;
                            reviewImp.close();
                            reviewImage = null;
                        }
                        roiManager.close();
                    }
                };

                final Runnable continueRun = new Runnable() {
                    @Override
                    public void run() {
                        if (completed.compareAndSet(false, true)) {
                            result.set(RoiReviewResult.continueWith(roiManager.getRoisAsArray()));
                            cleanup.run();
                            dialog.dispose();
                            latch.countDown();
                        }
                    }
                };

                final Runnable continueToEndRun = new Runnable() {
                    @Override
                    public void run() {
                        if (completed.compareAndSet(false, true)) {
                            result.set(RoiReviewResult.continueToEnd(roiManager.getRoisAsArray()));
                            cleanup.run();
                            dialog.dispose();
                            latch.countDown();
                        }
                    }
                };

                final Runnable skipRun = new Runnable() {
                    @Override
                    public void run() {
                        if (completed.compareAndSet(false, true)) {
                            result.set(RoiReviewResult.skip());
                            cleanup.run();
                            dialog.dispose();
                            latch.countDown();
                        }
                    }
                };

                final Runnable abortRun = new Runnable() {
                    @Override
                    public void run() {
                        if (completed.compareAndSet(false, true)) {
                            result.set(RoiReviewResult.abort());
                            cleanup.run();
                            dialog.dispose();
                            latch.countDown();
                        }
                    }
                };

                JButton continueButton = new JButton("Continue");
                continueButton.addActionListener(e -> continueRun.run());
                JButton continueToEndButton = new JButton("Continue to end");
                continueToEndButton.addActionListener(e -> continueToEndRun.run());
                JButton skipButton = new JButton("Skip");
                skipButton.addActionListener(e -> skipRun.run());
                JButton abortButton = new JButton("Abort");
                abortButton.addActionListener(e -> abortRun.run());
                dialog.add(buttonPanel(continueButton, continueToEndButton, skipButton, abortButton), BorderLayout.SOUTH);
                dialog.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        continueRun.run();
                    }
                });
                dialog.setVisible(true);
            }
        });

        IJ.log("[CellSegmentation Batch] Waiting for ROI review: " + title);
        await(latch);
        final RoiReviewResult reviewed = result.get();
        IJ.log("[CellSegmentation Batch] ROI review complete: " + title + " action=" + reviewed.getAction().name());
        return reviewed;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for batch stop-point.", e);
        }
    }

    private static JDialog createDialog(String title, String message, boolean alwaysOnTop) {
        final Frame owner = IJ.getInstance();
        final JDialog dialog = new JDialog(owner, title, false);
        dialog.setLayout(new BorderLayout(8, 8));
        // HTML text is the simplest way to get multi-line centered text in a Swing label.
        final JLabel label = new JLabel("<html><div style='text-align:center;'>" + message + "</div></html>", SwingConstants.CENTER);
        label.setBorder(new EmptyBorder(12, 16, 4, 16));
        dialog.add(label, BorderLayout.NORTH);
        dialog.setAlwaysOnTop(alwaysOnTop);
        dialog.pack();
        final Dimension size = dialog.getSize();
        dialog.setSize(new Dimension(Math.max(380, size.width), Math.max(150, size.height)));
        centerOnScreen(dialog);
        return dialog;
    }

    private static void centerOnScreen(JDialog dialog) {
        final Rectangle bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        final int x = bounds.x + Math.max(0, (bounds.width - dialog.getWidth()) / 2);
        final int y = bounds.y + Math.max(0, (bounds.height - dialog.getHeight()) / 2);
        dialog.setLocation(x, y);
    }

    private static JPanel buttonPanel(JButton... buttons) {
        final JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        for (JButton button : buttons) {
            panel.add(button);
        }
        return panel;
    }

    public void dispose() {
        if (reviewImage != null) {
            reviewImage.changes = false;
            reviewImage.close();
            reviewImage = null;
        }
    }

    private void hideReviewImage() {
        if (reviewImage == null || reviewImage.getWindow() == null) {
            return;
        }
        reviewImage.getWindow().setVisible(false);
    }

    private RoiManager getOrCreateReviewRoiManager() {
        final RoiManager roiManager = new RoiManager();
        roiManager.setVisible(true);
        positionReviewRoiManager(roiManager);
        return roiManager;
    }

    private ImagePlus updateReviewImage(ImagePlus source, String title) {
        if (source == null) {
            return null;
        }
        final ImagePlus updated = source.duplicate();
        if (reviewImage == null || !isCompatibleReviewImage(reviewImage, updated)) {
            if (reviewImage != null) {
                reviewImage.changes = false;
                reviewImage.close();
            }
            reviewImage = updated;
            reviewImage.setTitle(title);
            reviewImage.show();
            return reviewImage;
        }

        reviewImage.setProcessor(title, updated.getProcessor());
        reviewImage.setCalibration(updated.getCalibration());
        reviewImage.setDimensions(updated.getNChannels(), updated.getNSlices(), updated.getNFrames());
        reviewImage.setOpenAsHyperStack(updated.isHyperStack());
        reviewImage.updateAndDraw();
        reviewImage.show();
        if (reviewImage.getWindow() != null) {
            reviewImage.getWindow().setVisible(true);
        }
        updated.changes = false;
        updated.close();
        return reviewImage;
    }

    private static boolean isCompatibleReviewImage(ImagePlus existing, ImagePlus updated) {
        return existing.getWidth() == updated.getWidth()
                && existing.getHeight() == updated.getHeight()
                && existing.getType() == updated.getType();
    }

    private static void positionReviewRoiManager(RoiManager roiManager) {
        if (roiManager == null) {
            return;
        }
        final Rectangle bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        final Dimension size = roiManager.getSize();
        final int width = Math.max(size.width, 220);
        final int height = Math.max(size.height, 320);
        final int centerX = bounds.x + bounds.width / 2;
        final int centerY = bounds.y + bounds.height / 2;
        final int gap = 260;
        final int x = Math.max(bounds.x, centerX - gap - width);
        final int y = Math.max(bounds.y, centerY - height / 2);
        roiManager.setLocation(x, y);
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

    public static final class RoiReviewResult {
        private final RoiReviewAction action;
        private final Roi[] rois;

        private RoiReviewResult(RoiReviewAction action, Roi[] rois) {
            this.action = action;
            this.rois = cloneRois(rois);
        }

        public static RoiReviewResult continueWith(Roi[] rois) {
            return new RoiReviewResult(RoiReviewAction.CONTINUE, rois);
        }

        public static RoiReviewResult continueToEnd(Roi[] rois) {
            return new RoiReviewResult(RoiReviewAction.CONTINUE_TO_END, rois);
        }

        public static RoiReviewResult skip() {
            return new RoiReviewResult(RoiReviewAction.SKIP, null);
        }

        public static RoiReviewResult abort() {
            return new RoiReviewResult(RoiReviewAction.ABORT, null);
        }

        public RoiReviewAction getAction() {
            return action;
        }

        public boolean isContinue() {
            return action == RoiReviewAction.CONTINUE || action == RoiReviewAction.CONTINUE_TO_END;
        }

        public boolean isContinueToEnd() {
            return action == RoiReviewAction.CONTINUE_TO_END;
        }

        public boolean isSkip() {
            return action == RoiReviewAction.SKIP;
        }

        public boolean isAbort() {
            return action == RoiReviewAction.ABORT;
        }

        public Roi[] getRois() {
            return cloneRois(rois);
        }
    }

    public static final class ThresholdSelectionResult {
        private final RoiReviewAction action;
        private final ThresholdConfig config;
        private final boolean rememberThreshold;

        private ThresholdSelectionResult(RoiReviewAction action, ThresholdConfig config, boolean rememberThreshold) {
            this.action = action;
            this.config = config;
            this.rememberThreshold = rememberThreshold;
        }

        public static ThresholdSelectionResult continueWith(ThresholdConfig config, boolean rememberThreshold) {
            return new ThresholdSelectionResult(RoiReviewAction.CONTINUE, config, rememberThreshold);
        }

        public static ThresholdSelectionResult continueToEnd(ThresholdConfig config, boolean rememberThreshold) {
            return new ThresholdSelectionResult(RoiReviewAction.CONTINUE_TO_END, config, rememberThreshold);
        }

        public static ThresholdSelectionResult skip() {
            return new ThresholdSelectionResult(RoiReviewAction.SKIP, null, false);
        }

        public static ThresholdSelectionResult abort() {
            return new ThresholdSelectionResult(RoiReviewAction.ABORT, null, false);
        }

        public RoiReviewAction getAction() {
            return action;
        }

        public boolean isContinue() {
            return action == RoiReviewAction.CONTINUE || action == RoiReviewAction.CONTINUE_TO_END;
        }

        public boolean isContinueToEnd() {
            return action == RoiReviewAction.CONTINUE_TO_END;
        }

        public boolean isSkip() {
            return action == RoiReviewAction.SKIP;
        }

        public boolean isAbort() {
            return action == RoiReviewAction.ABORT;
        }

        public ThresholdConfig getConfig() {
            return config;
        }

        public boolean shouldRememberThreshold() {
            return rememberThreshold;
        }
    }

    public enum RoiReviewAction {
        CONTINUE,
        CONTINUE_TO_END,
        SKIP,
        ABORT
    }
}
