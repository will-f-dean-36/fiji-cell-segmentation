package com.will.cellseg;

import ij.IJ;
import ij.ImagePlus;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/** Custom single-image settings dialog. */
public final class CellSegmentationDialog extends JDialog {
    private static final String[] THRESHOLD_METHODS = {
            "Default",
            "Huang",
            "Intermodes",
            "IsoData",
            "Li",
            "MaxEntropy",
            "Mean",
            "MinError",
            "Minimum",
            "Moments",
            "Otsu",
            "Percentile",
            "RenyiEntropy",
            "Shanbhag",
            "Triangle",
            "Yen"
    };
    private static final String[] EDGE_METHODS = {
            "Sobel (Gradient)",
            "Prewitt",
            "Scharr",
            "Laplacian (3x3)",
            "None"
    };
    private static final String[] LABEL_LUTS = {
            "Rainbow RGB",
            "16_colors",
            "Glasbey",
            "Fire",
            "Ice",
            "Grays",
            "Spectrum"
    };

    private Result result;

    private final JComboBox<String> thresholdMethodBox;
    private final JCheckBox darkObjectsBox;
    private final JCheckBox pauseThresholdBox;
    private final JComboBox<String> edgeMethodBox;
    private final JSpinner minAreaSpinner;
    private final JCheckBox excludeBorderBox;

    private final JCheckBox showStepsBox;
    private final JCheckBox showOverlayBox;
    private final JCheckBox showRoiOverlayBox;
    private final JComboBox<String> labelsLutBox;
    private final JCheckBox clearRmBox;

    private final JCheckBox measureAreaBox;
    private final JCheckBox measureMeanBox;
    private final JCheckBox measureMinMaxBox;
    private final JCheckBox measureStdDevBox;
    private final JCheckBox measurePerimeterBox;
    private final JCheckBox measureCentroidBox;
    private final JCheckBox measureRectBox;
    private final JCheckBox measureFeretBox;
    private final JCheckBox measureShapeBox;
    private final JCheckBox measureIntDenBox;

    private final JCheckBox autoSaveBox;
    private final JTextField outputDirField;
    private final JCheckBox saveMaskBox;
    private final JCheckBox saveLabelsBox;
    private final JCheckBox saveOverlayBox;
    private final JCheckBox saveRoisBox;
    private final JCheckBox saveMeasurementsBox;

    private CellSegmentationDialog(Frame owner, ImagePlus imp, Result initial) {
        super(owner, "Cell Segmentation", true);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        final JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(10, 12, 0, 12));
        final String imageLabel = imp != null ? imp.getTitle() + " (" + imp.getWidth() + "x" + imp.getHeight() + ")" : "No image";
        header.add(new JLabel("Configure segmentation for: " + imageLabel), BorderLayout.CENTER);
        add(header, BorderLayout.NORTH);

        thresholdMethodBox = new JComboBox<String>(THRESHOLD_METHODS);
        thresholdMethodBox.setSelectedItem(initial.thrMethod);
        darkObjectsBox = new JCheckBox("Dark objects (cells darker than background)", initial.darkObjects);
        pauseThresholdBox = new JCheckBox("Pause to adjust threshold", initial.pauseThreshold);
        edgeMethodBox = new JComboBox<String>(EDGE_METHODS);
        edgeMethodBox.setSelectedItem(initial.edgeMethod);
        minAreaSpinner = new JSpinner(new SpinnerNumberModel(initial.minArea, 0, Integer.MAX_VALUE, 1));
        excludeBorderBox = new JCheckBox("Exclude cells touching image border", initial.excludeBorderTouching);

        showStepsBox = new JCheckBox("Show intermediate images", initial.showSteps);
        showOverlayBox = new JCheckBox("Show label overlay", initial.showLabelOverlay);
        showRoiOverlayBox = new JCheckBox("Show ROI overlay on source image", initial.showRoiOverlay);
        labelsLutBox = new JComboBox<String>(LABEL_LUTS);
        labelsLutBox.setSelectedItem(initial.labelsLut);
        clearRmBox = new JCheckBox("Clear ROI Manager first", initial.clearRM);

        measureAreaBox = new JCheckBox("Area", initial.measureArea);
        measureMeanBox = new JCheckBox("Mean", initial.measureMean);
        measureMinMaxBox = new JCheckBox("Min/Max", initial.measureMinMax);
        measureStdDevBox = new JCheckBox("Std Dev", initial.measureStdDev);
        measurePerimeterBox = new JCheckBox("Perimeter", initial.measurePerimeter);
        measureCentroidBox = new JCheckBox("Centroid", initial.measureCentroid);
        measureRectBox = new JCheckBox("Bounding rectangle", initial.measureRect);
        measureFeretBox = new JCheckBox("Feret's diameter", initial.measureFeret);
        measureShapeBox = new JCheckBox("Shape descriptors", initial.measureShape);
        measureIntDenBox = new JCheckBox("Integrated density", initial.measureIntDen);

        autoSaveBox = new JCheckBox("Automatically save results", initial.autoSave);
        outputDirField = new JTextField(initial.outputDir != null ? initial.outputDir.getAbsolutePath() : CellSegmentationIO.getDefaultOutputDirectory().getAbsolutePath(), 28);
        saveMaskBox = new JCheckBox("Save mask image", initial.saveMask);
        saveLabelsBox = new JCheckBox("Save labels image", initial.saveLabels);
        saveOverlayBox = new JCheckBox("Save label overlay", initial.saveLabelOverlayToFile);
        saveRoisBox = new JCheckBox("Save ROIs (ZIP)", initial.saveRois);
        saveMeasurementsBox = new JCheckBox("Save measurements (CSV)", initial.saveMeasurements);

        final JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Segmentation", buildSegmentationPanel());
        tabs.addTab("Display", buildDisplayPanel());
        tabs.addTab("Measurements", buildMeasurementPanel());
        tabs.addTab("Save", buildSavePanel());
        add(tabs, BorderLayout.CENTER);

        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        final JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        final JButton run = new JButton("Run");
        run.addActionListener(e -> onRun());
        buttons.add(cancel);
        buttons.add(run);

        final JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(0, 12, 10, 12));
        footer.add(buttons, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);

        autoSaveBox.addActionListener(e -> updateSaveEnabled());
        updateSaveEnabled();

        pack();
        setSize(Math.max(650, getWidth()), Math.max(520, getHeight()));
        setLocationRelativeTo(null);
    }

    static Result showDialog(ImagePlus imp, Result initial) {
        final AtomicReference<Result> out = new AtomicReference<Result>();
        final Runnable task = new Runnable() {
            @Override
            public void run() {
                final Frame owner = IJ.getInstance();
                final CellSegmentationDialog dialog = new CellSegmentationDialog(owner, imp, initial);
                dialog.setVisible(true);
                out.set(dialog.result);
            }
        };

        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (InvocationTargetException e) {
                throw new RuntimeException("Failed to show Cell Segmentation dialog.", e.getCause());
            }
        }
        return out.get();
    }

    private JPanel buildSegmentationPanel() {
        final JPanel panel = createFormPanel();
        int row = 0;
        DialogFormUtils.addRow(panel, row++, new JLabel("Auto-threshold method"), thresholdMethodBox);
        DialogFormUtils.addCheckRow(panel, row++, darkObjectsBox);
        DialogFormUtils.addCheckRow(panel, row++, pauseThresholdBox);
        DialogFormUtils.addRow(panel, row++, new JLabel("Edge method"), edgeMethodBox);
        DialogFormUtils.addRow(panel, row++, new JLabel("Min cell area (px)"), minAreaSpinner);
        DialogFormUtils.addCheckRow(panel, row++, excludeBorderBox);
        DialogFormUtils.addVerticalGlue(panel, row);
        return panel;
    }

    private JPanel buildDisplayPanel() {
        final JPanel panel = createFormPanel();
        int row = 0;
        DialogFormUtils.addCheckRow(panel, row++, showStepsBox);
        DialogFormUtils.addCheckRow(panel, row++, showRoiOverlayBox);
        DialogFormUtils.addCheckRow(panel, row++, showOverlayBox);
        DialogFormUtils.addRow(panel, row++, new JLabel("Labels LUT"), labelsLutBox);
        DialogFormUtils.addCheckRow(panel, row++, clearRmBox);
        DialogFormUtils.addVerticalGlue(panel, row);
        return panel;
    }

    private JPanel buildMeasurementPanel() {
        final JPanel panel = createFormPanel();
        int row = 0;
        DialogFormUtils.addCheckRow(panel, row++, measureAreaBox);
        DialogFormUtils.addCheckRow(panel, row++, measureMeanBox);
        DialogFormUtils.addCheckRow(panel, row++, measureMinMaxBox);
        DialogFormUtils.addCheckRow(panel, row++, measureStdDevBox);
        DialogFormUtils.addCheckRow(panel, row++, measurePerimeterBox);
        DialogFormUtils.addCheckRow(panel, row++, measureCentroidBox);
        DialogFormUtils.addCheckRow(panel, row++, measureRectBox);
        DialogFormUtils.addCheckRow(panel, row++, measureFeretBox);
        DialogFormUtils.addCheckRow(panel, row++, measureShapeBox);
        DialogFormUtils.addCheckRow(panel, row++, measureIntDenBox);
        DialogFormUtils.addVerticalGlue(panel, row);
        return panel;
    }

    private JPanel buildSavePanel() {
        final JPanel panel = createFormPanel();
        int row = 0;
        DialogFormUtils.addCheckRow(panel, row++, autoSaveBox);

        final JPanel dirPanel = new JPanel(new BorderLayout(6, 0));
        final JButton browse = new JButton("Browse...");
        browse.addActionListener(e -> chooseDirectory());
        dirPanel.add(outputDirField, BorderLayout.CENTER);
        dirPanel.add(browse, BorderLayout.EAST);
        DialogFormUtils.addRow(panel, row++, new JLabel("Output directory"), dirPanel);

        DialogFormUtils.addCheckRow(panel, row++, saveMaskBox);
        DialogFormUtils.addCheckRow(panel, row++, saveLabelsBox);
        DialogFormUtils.addCheckRow(panel, row++, saveOverlayBox);
        DialogFormUtils.addCheckRow(panel, row++, saveRoisBox);
        DialogFormUtils.addCheckRow(panel, row++, saveMeasurementsBox);
        DialogFormUtils.addVerticalGlue(panel, row);
        return panel;
    }

    private void chooseDirectory() {
        final JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        final String current = outputDirField.getText().trim();
        if (!current.isEmpty()) {
            chooser.setCurrentDirectory(new File(current));
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
            outputDirField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void updateSaveEnabled() {
        final boolean enabled = autoSaveBox.isSelected();
        outputDirField.setEnabled(enabled);
        saveMaskBox.setEnabled(enabled);
        saveLabelsBox.setEnabled(enabled);
        saveOverlayBox.setEnabled(enabled);
        saveRoisBox.setEnabled(enabled);
        saveMeasurementsBox.setEnabled(enabled);
    }

    private void onRun() {
        final String outputPath = outputDirField.getText().trim();
        if (autoSaveBox.isSelected() && outputPath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Output directory is required when auto-save is enabled.", "Missing Output Directory", JOptionPane.ERROR_MESSAGE);
            return;
        }

        result = new Result(
                ((Number) minAreaSpinner.getValue()).intValue(),
                (String) thresholdMethodBox.getSelectedItem(),
                darkObjectsBox.isSelected(),
                pauseThresholdBox.isSelected(),
                (String) edgeMethodBox.getSelectedItem(),
                excludeBorderBox.isSelected(),
                showStepsBox.isSelected(),
                showRoiOverlayBox.isSelected(),
                showOverlayBox.isSelected(),
                (String) labelsLutBox.getSelectedItem(),
                clearRmBox.isSelected(),
                measureAreaBox.isSelected(),
                measureMeanBox.isSelected(),
                measureMinMaxBox.isSelected(),
                measureStdDevBox.isSelected(),
                measurePerimeterBox.isSelected(),
                measureCentroidBox.isSelected(),
                measureRectBox.isSelected(),
                measureFeretBox.isSelected(),
                measureShapeBox.isSelected(),
                measureIntDenBox.isSelected(),
                autoSaveBox.isSelected(),
                outputPath.isEmpty() ? null : new File(outputPath),
                saveMaskBox.isSelected(),
                saveLabelsBox.isSelected(),
                saveOverlayBox.isSelected(),
                saveRoisBox.isSelected(),
                saveMeasurementsBox.isSelected());
        dispose();
    }

    private static JPanel createFormPanel() {
        return DialogFormUtils.createFormPanel();
    }

    static final class Result {
        final int minArea;
        final String thrMethod;
        final boolean darkObjects;
        final boolean pauseThreshold;
        final String edgeMethod;
        final boolean excludeBorderTouching;
        final boolean showSteps;
        final boolean showRoiOverlay;
        final boolean showLabelOverlay;
        final String labelsLut;
        final boolean clearRM;
        final boolean measureArea;
        final boolean measureMean;
        final boolean measureMinMax;
        final boolean measureStdDev;
        final boolean measurePerimeter;
        final boolean measureCentroid;
        final boolean measureRect;
        final boolean measureFeret;
        final boolean measureShape;
        final boolean measureIntDen;
        final boolean autoSave;
        final File outputDir;
        final boolean saveMask;
        final boolean saveLabels;
        final boolean saveLabelOverlayToFile;
        final boolean saveRois;
        final boolean saveMeasurements;

        Result(
                int minArea,
                String thrMethod,
                boolean darkObjects,
                boolean pauseThreshold,
                String edgeMethod,
                boolean excludeBorderTouching,
                boolean showSteps,
                boolean showRoiOverlay,
                boolean showLabelOverlay,
                String labelsLut,
                boolean clearRM,
                boolean measureArea,
                boolean measureMean,
                boolean measureMinMax,
                boolean measureStdDev,
                boolean measurePerimeter,
                boolean measureCentroid,
                boolean measureRect,
                boolean measureFeret,
                boolean measureShape,
                boolean measureIntDen,
                boolean autoSave,
                File outputDir,
                boolean saveMask,
                boolean saveLabels,
                boolean saveLabelOverlayToFile,
                boolean saveRois,
                boolean saveMeasurements) {
            this.minArea = minArea;
            this.thrMethod = thrMethod;
            this.darkObjects = darkObjects;
            this.pauseThreshold = pauseThreshold;
            this.edgeMethod = edgeMethod;
            this.excludeBorderTouching = excludeBorderTouching;
            this.showSteps = showSteps;
            this.showRoiOverlay = showRoiOverlay;
            this.showLabelOverlay = showLabelOverlay;
            this.labelsLut = labelsLut;
            this.clearRM = clearRM;
            this.measureArea = measureArea;
            this.measureMean = measureMean;
            this.measureMinMax = measureMinMax;
            this.measureStdDev = measureStdDev;
            this.measurePerimeter = measurePerimeter;
            this.measureCentroid = measureCentroid;
            this.measureRect = measureRect;
            this.measureFeret = measureFeret;
            this.measureShape = measureShape;
            this.measureIntDen = measureIntDen;
            this.autoSave = autoSave;
            this.outputDir = outputDir;
            this.saveMask = saveMask;
            this.saveLabels = saveLabels;
            this.saveLabelOverlayToFile = saveLabelOverlayToFile;
            this.saveRois = saveRois;
            this.saveMeasurements = saveMeasurements;
        }
    }
}
