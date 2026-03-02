package com.will.cellseg;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.Overlay;
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
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import org.scijava.Context;

/** Coordinates optional batch stop-points without blocking the Swing EDT. */
public final class BatchStopController {
    // ROI Manager is treated as a shared global IJ1 tool window, so we either reuse the
    // existing one or create one once and keep reusing it for the whole batch run.
    private RoiManager reviewRoiManager;
    private boolean reviewRoiManagerOwned;

    public ThresholdConfig maybeSelectThreshold(
            Context ctx,
            final ImagePlus ricmPreview,
            final ThresholdConfig currentOrDefault,
            final String title) {

        if (ricmPreview == null) {
            return currentOrDefault;
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<ThresholdConfig> selected = new AtomicReference<ThresholdConfig>(currentOrDefault);
        final AtomicBoolean completed = new AtomicBoolean(false);

        // UI creation and updates must happen on the Swing EDT. The batch worker thread
        // waits on the latch below instead of blocking the EDT.
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
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
                final JDialog dialog = createDialog(title, "Adjust the threshold, then click Apply.", false);
                final Runnable finish = new Runnable() {
                    @Override
                    public void run() {
                        if (completed.compareAndSet(false, true)) {
                            selected.set(CellSegmentationPipeline.captureThresholdConfig(ricmPreview, currentOrDefault));
                            dialog.dispose();
                            latch.countDown();
                        }
                    }
                };

                JButton applyButton = new JButton("Apply");
                applyButton.addActionListener(e -> finish.run());
                dialog.add(buttonPanel(applyButton), BorderLayout.SOUTH);
                dialog.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        finish.run();
                    }
                });
                dialog.setVisible(true);
            }
        });

        // `await` runs on the batch worker thread, so Fiji's UI remains responsive.
        IJ.log("[CellSegmentation Batch] Waiting for threshold selection: " + title);
        await(latch);
        final ThresholdConfig result = selected.get();
        IJ.log("[CellSegmentation Batch] Threshold selection complete: " + title
                + " mode=" + (result != null && result.isManual() ? "manual" : "auto"));
        return result != null ? result : currentOrDefault;
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
                final ImagePlus reviewImp = imp != null ? imp.duplicate() : null;
                if (reviewImp != null) {
                    reviewImp.setTitle(title);
                    reviewImp.setOverlay(buildOverlay(proposedRois));
                    reviewImp.show();
                    if (reviewImp.getWindow() != null) {
                        WindowManager.setCurrentWindow(reviewImp.getWindow());
                    }
                }

                // ROI edits happen through the standard IJ1 ROI Manager so users can
                // rely on familiar Fiji tools instead of custom editing controls.
                final RoiManager roiManager = getVisibleRoiManager();
                roiManager.reset();
                for (Roi roi : cloneRois(proposedRois)) {
                    if (roi != null) {
                        roiManager.addRoi(roi);
                    }
                }

                final JDialog dialog = createDialog(title, "Review ROIs in ROI Manager, then choose an action.", true);
                final Runnable cleanup = new Runnable() {
                    @Override
                    public void run() {
                        if (reviewImp != null) {
                            reviewImp.changes = false;
                            reviewImp.close();
                        }
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
                JButton skipButton = new JButton("Skip");
                skipButton.addActionListener(e -> skipRun.run());
                JButton abortButton = new JButton("Abort");
                abortButton.addActionListener(e -> abortRun.run());
                dialog.add(buttonPanel(continueButton, skipButton, abortButton), BorderLayout.SOUTH);
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
        dialog.add(label, BorderLayout.CENTER);
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

    private RoiManager getVisibleRoiManager() {
        if (reviewRoiManager == null) {
            // `getInstance2` returns the existing manager if one is already open and
            // otherwise avoids creating a second floating window.
            reviewRoiManager = RoiManager.getInstance2();
            if (reviewRoiManager == null) {
                reviewRoiManager = new RoiManager();
                reviewRoiManagerOwned = true;
            }
        }
        reviewRoiManager.setVisible(true);
        return reviewRoiManager;
    }

    public void dispose() {
        // If we created the manager, close it. If the user already had one, leave the
        // window open but clear the batch ROIs we inserted.
        if (reviewRoiManager == null) {
            return;
        }
        if (reviewRoiManagerOwned) {
            reviewRoiManager.reset();
            reviewRoiManager.close();
        } else {
            reviewRoiManager.reset();
        }
        reviewRoiManager = null;
        reviewRoiManagerOwned = false;
    }

    private static Overlay buildOverlay(Roi[] rois) {
        final Overlay overlay = new Overlay();
        for (Roi roi : cloneRois(rois)) {
            if (roi != null) {
                overlay.add(roi);
            }
        }
        return overlay;
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
            return action == RoiReviewAction.CONTINUE;
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

    public enum RoiReviewAction {
        CONTINUE,
        SKIP,
        ABORT
    }
}
