package com.will.cellseg;

import ij.IJ;
import ij.Prefs;
import java.awt.BorderLayout;
import java.awt.CardLayout;
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
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

/** Custom batch settings dialog. */
public final class BatchSegmentationDialog extends JDialog {
    private static final String INPUT_DIR_PREF = "cellseg.batchInputDir";
    private static final String[] INPUT_MODE_LABELS = {
            "Mode 1: Two container files (pair by series index)",
            "Mode 2: Two file lists (pair by selection order)",
            "Mode 3: Same-file channels (C1=RICM, C2..=Fluor)"
    };
    private static final String[] YES_NO = {"No", "Yes"};
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
    private static final String CARD_MODE1 = "mode1";
    private static final String CARD_MODE2 = "mode2";
    private static final String CARD_MODE3 = "mode3";

    private Result result;
    private final JComboBox<String> inputModeBox;
    private final JSpinner segChannelSpinner;
    private final JSpinner firstMeasChannelSpinner;
    private final JLabel segChannelLabel;
    private final JLabel firstMeasChannelLabel;
    private final JPanel inputModeCards;
    private final JList<String> ricmContainerList;
    private final JList<String> fluorContainerList;
    private final JList<String> ricmFilesList;
    private final JList<String> fluorFilesList;
    private final JList<String> combinedFilesList;
    private File ricmContainerFile;
    private File fluorContainerFile;
    private File[] ricmFiles;
    private File[] fluorFiles;
    private File[] combinedFiles;

    private final JComboBox<String> thresholdMethodBox;
    private final JCheckBox darkObjectsBox;
    private final JComboBox<String> edgeMethodBox;
    private final JSpinner minAreaSpinner;
    private final JCheckBox excludeBorderBox;

    private final JComboBox<String> thresholdReviewBox;
    private final JComboBox<String> roiReviewBox;

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

    private final JTextField outputDirField;
    private final JComboBox<String> labelsLutBox;
    private final JCheckBox saveMaskBox;
    private final JCheckBox saveLabelsBox;
    private final JCheckBox saveOverlayBox;
    private final JCheckBox saveRoisBox;
    private final JCheckBox saveMeasurementsBox;
    private final JCheckBox saveParametersBox;

    private BatchSegmentationDialog(Frame owner, Result initial) {
        super(owner, "Batch Cell Segmentation", true);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        final JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(10, 12, 0, 12));
        header.add(new JLabel("Configure batch inputs and processing options."), BorderLayout.CENTER);
        add(header, BorderLayout.NORTH);

        inputModeBox = new JComboBox<String>(INPUT_MODE_LABELS);
        inputModeBox.setSelectedIndex(initial.inputModeIndex);
        segChannelSpinner = new JSpinner(new SpinnerNumberModel(initial.sameFileSegChannelIndex1Based, 1, Integer.MAX_VALUE, 1));
        firstMeasChannelSpinner = new JSpinner(new SpinnerNumberModel(initial.sameFileFirstMeasChannelIndex1Based, 1, Integer.MAX_VALUE, 1));
        segChannelLabel = new JLabel("Mode 3 RICM channel");
        firstMeasChannelLabel = new JLabel("Mode 3 first fluorescence channel");
        ricmContainerFile = initial.ricmContainerFile;
        fluorContainerFile = initial.fluorContainerFile;
        ricmFiles = cloneFiles(initial.ricmFiles);
        fluorFiles = cloneFiles(initial.fluorFiles);
        combinedFiles = cloneFiles(initial.combinedFiles);
        ricmContainerList = new JList<String>();
        fluorContainerList = new JList<String>();
        ricmFilesList = new JList<String>();
        fluorFilesList = new JList<String>();
        combinedFilesList = new JList<String>();
        inputModeCards = new JPanel(new CardLayout());
        inputModeCards.add(buildMode1Panel(), CARD_MODE1);
        inputModeCards.add(buildMode2Panel(), CARD_MODE2);
        inputModeCards.add(buildMode3Panel(), CARD_MODE3);
        inputModeBox.addActionListener(e -> updateInputModeCard());
        refreshFileLists();

        thresholdMethodBox = new JComboBox<String>(THRESHOLD_METHODS);
        thresholdMethodBox.setSelectedItem(initial.thrMethod);
        darkObjectsBox = new JCheckBox("Dark objects (cells darker than background)", initial.darkObjects);
        edgeMethodBox = new JComboBox<String>(EDGE_METHODS);
        edgeMethodBox.setSelectedItem(initial.edgeMethod);
        minAreaSpinner = new JSpinner(new SpinnerNumberModel(initial.minArea, 0, Integer.MAX_VALUE, 1));
        excludeBorderBox = new JCheckBox("Exclude cells touching image border", initial.excludeBorderTouching);

        thresholdReviewBox = new JComboBox<String>(YES_NO);
        thresholdReviewBox.setSelectedItem(initial.thresholdStopMode);
        roiReviewBox = new JComboBox<String>(YES_NO);
        roiReviewBox.setSelectedItem(initial.roiReviewMode);

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

        outputDirField = new JTextField(initial.outputDir != null ? initial.outputDir.getAbsolutePath() : CellSegmentationIO.getDefaultOutputDirectory().getAbsolutePath(), 28);
        labelsLutBox = new JComboBox<String>(LABEL_LUTS);
        labelsLutBox.setSelectedItem(initial.labelsLut);
        saveMaskBox = new JCheckBox("Save mask image", initial.saveMask);
        saveLabelsBox = new JCheckBox("Save labels image", initial.saveLabels);
        saveOverlayBox = new JCheckBox("Save label overlay", initial.saveLabelOverlay);
        saveRoisBox = new JCheckBox("Save ROIs (ZIP)", initial.saveRois);
        saveMeasurementsBox = new JCheckBox("Save measurements (CSV)", initial.saveMeasurements);
        saveParametersBox = new JCheckBox("Save segmentation parameters (CSV)", initial.saveParameters);

        final JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Inputs", buildInputsPanel());
        tabs.addTab("Segmentation", buildSegmentationPanel());
        tabs.addTab("Batch", buildBatchPanel());
        tabs.addTab("Measurements", buildMeasurementPanel());
        tabs.addTab("Save", buildSavePanel());
        add(tabs, BorderLayout.CENTER);

        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        final JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        final JButton run = new JButton("Run Batch");
        run.addActionListener(e -> onRun());
        buttons.add(cancel);
        buttons.add(run);

        final JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(0, 12, 10, 12));
        footer.add(buttons, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);

        updateInputModeCard();
        pack();
        setSize(Math.max(860, getWidth()), Math.max(620, getHeight()));
        setLocationRelativeTo(null);
    }

    static Result showDialog(Result initial) {
        final AtomicReference<Result> out = new AtomicReference<Result>();
        final Runnable task = new Runnable() {
            @Override
            public void run() {
                final Frame owner = IJ.getInstance();
                final BatchSegmentationDialog dialog = new BatchSegmentationDialog(owner, initial);
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
                throw new RuntimeException("Failed to show Batch Cell Segmentation dialog.", e.getCause());
            }
        }
        return out.get();
    }

    private JPanel buildInputsPanel() {
        final JPanel panel = createFormPanel();
        int row = 0;
        DialogFormUtils.addRow(panel, row++, new JLabel("Input mode"), inputModeBox);
        DialogFormUtils.addRow(panel, row++, segChannelLabel, segChannelSpinner);
        DialogFormUtils.addRow(panel, row++, firstMeasChannelLabel, firstMeasChannelSpinner);

        final java.awt.GridBagConstraints gbc = new java.awt.GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        gbc.fill = java.awt.GridBagConstraints.BOTH;
        panel.add(inputModeCards, gbc);
        return panel;
    }

    private JPanel buildSegmentationPanel() {
        final JPanel panel = createFormPanel();
        int row = 0;
        DialogFormUtils.addRow(panel, row++, new JLabel("Auto-threshold method"), thresholdMethodBox);
        DialogFormUtils.addCheckRow(panel, row++, darkObjectsBox);
        DialogFormUtils.addRow(panel, row++, new JLabel("Edge method"), edgeMethodBox);
        DialogFormUtils.addRow(panel, row++, new JLabel("Min cell area (px)"), minAreaSpinner);
        DialogFormUtils.addCheckRow(panel, row++, excludeBorderBox);
        DialogFormUtils.addVerticalGlue(panel, row);
        return panel;
    }

    private JPanel buildBatchPanel() {
        final JPanel panel = createFormPanel();
        int row = 0;
        DialogFormUtils.addRow(panel, row++, new JLabel("Threshold Review"), thresholdReviewBox);
        DialogFormUtils.addRow(panel, row++, new JLabel("ROI Review"), roiReviewBox);
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
        final JPanel dirPanel = new JPanel(new BorderLayout(6, 0));
        final JButton browse = new JButton("Browse...");
        browse.addActionListener(e -> chooseDirectory());
        dirPanel.add(outputDirField, BorderLayout.CENTER);
        dirPanel.add(browse, BorderLayout.EAST);
        DialogFormUtils.addRow(panel, row++, new JLabel("Output directory"), dirPanel);
        DialogFormUtils.addRow(panel, row++, new JLabel("Labels LUT"), labelsLutBox);
        DialogFormUtils.addCheckRow(panel, row++, saveMaskBox);
        DialogFormUtils.addCheckRow(panel, row++, saveLabelsBox);
        DialogFormUtils.addCheckRow(panel, row++, saveOverlayBox);
        DialogFormUtils.addCheckRow(panel, row++, saveRoisBox);
        DialogFormUtils.addCheckRow(panel, row++, saveMeasurementsBox);
        DialogFormUtils.addCheckRow(panel, row++, saveParametersBox);
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

    private void onRun() {
        final String outputPath = outputDirField.getText().trim();
        if (outputPath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Output directory is required.", "Missing Output Directory", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (!hasRequiredInputs()) {
            JOptionPane.showMessageDialog(this, "Please select the required input files for the chosen mode.", "Missing Inputs", JOptionPane.ERROR_MESSAGE);
            return;
        }
        result = new Result(
                inputModeBox.getSelectedIndex(),
                ((Number) segChannelSpinner.getValue()).intValue(),
                ((Number) firstMeasChannelSpinner.getValue()).intValue(),
                ricmContainerFile,
                fluorContainerFile,
                cloneFiles(ricmFiles),
                cloneFiles(fluorFiles),
                cloneFiles(combinedFiles),
                ((Number) minAreaSpinner.getValue()).intValue(),
                (String) thresholdMethodBox.getSelectedItem(),
                darkObjectsBox.isSelected(),
                (String) edgeMethodBox.getSelectedItem(),
                excludeBorderBox.isSelected(),
                (String) thresholdReviewBox.getSelectedItem(),
                (String) roiReviewBox.getSelectedItem(),
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
                new File(outputPath),
                (String) labelsLutBox.getSelectedItem(),
                saveMaskBox.isSelected(),
                saveLabelsBox.isSelected(),
                saveOverlayBox.isSelected(),
                saveRoisBox.isSelected(),
                saveMeasurementsBox.isSelected(),
                saveParametersBox.isSelected());
        dispose();
    }

    private boolean hasRequiredInputs() {
        switch (inputModeBox.getSelectedIndex()) {
            case 0:
                return ricmContainerFile != null && fluorContainerFile != null;
            case 1:
                return ricmFiles != null && ricmFiles.length > 0 && fluorFiles != null && fluorFiles.length > 0;
            case 2:
                return combinedFiles != null && combinedFiles.length > 0;
            default:
                return false;
        }
    }

    private JPanel buildMode1Panel() {
        final JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createTitledBorder("Container files"));
        final JPanel rows = new JPanel();
        rows.setLayout(new javax.swing.BoxLayout(rows, javax.swing.BoxLayout.Y_AXIS));
        rows.add(createFileChooserRow("RICM container", ricmContainerList, false, SelectionTarget.RICM_CONTAINER));
        rows.add(createFileChooserRow("Fluorescence container", fluorContainerList, false, SelectionTarget.FLUOR_CONTAINER));
        panel.add(rows, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildMode2Panel() {
        final JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createTitledBorder("Paired file lists"));
        final JPanel rows = new JPanel();
        rows.setLayout(new javax.swing.BoxLayout(rows, javax.swing.BoxLayout.Y_AXIS));
        rows.add(createFileChooserRow("RICM files", ricmFilesList, true, SelectionTarget.RICM_FILES));
        rows.add(createFileChooserRow("Fluorescence files", fluorFilesList, true, SelectionTarget.FLUOR_FILES));
        panel.add(rows, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildMode3Panel() {
        final JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createTitledBorder("Combined files"));
        panel.add(createFileChooserRow("Combined multi-channel files", combinedFilesList, true, SelectionTarget.COMBINED_FILES), BorderLayout.CENTER);
        return panel;
    }

    private JPanel createFileChooserRow(String title, JList<String> list, boolean multiple, SelectionTarget target) {
        final JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(BorderFactory.createTitledBorder(title));
        list.setVisibleRowCount(4);
        panel.add(new JScrollPane(list), BorderLayout.CENTER);

        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        final JButton choose = new JButton("Choose...");
        choose.addActionListener(e -> chooseFiles(target, multiple));
        final JButton clear = new JButton("Clear");
        clear.addActionListener(e -> clearFiles(target));
        buttons.add(choose);
        buttons.add(clear);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private void chooseFiles(SelectionTarget target, boolean multiple) {
        final JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(multiple);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setAcceptAllFileFilterUsed(true);
        final File startDir = chooseStartDirectory(target);
        if (startDir != null) {
            chooser.setCurrentDirectory(startDir);
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        switch (target) {
            case RICM_CONTAINER:
                ricmContainerFile = chooser.getSelectedFile();
                break;
            case FLUOR_CONTAINER:
                fluorContainerFile = chooser.getSelectedFile();
                break;
            case RICM_FILES:
                ricmFiles = cloneFiles(chooser.getSelectedFiles());
                break;
            case FLUOR_FILES:
                fluorFiles = cloneFiles(chooser.getSelectedFiles());
                break;
            case COMBINED_FILES:
                combinedFiles = cloneFiles(chooser.getSelectedFiles());
                break;
            default:
                break;
        }
        rememberInputDirectory(chooser, target);
        refreshFileLists();
    }

    private void clearFiles(SelectionTarget target) {
        switch (target) {
            case RICM_CONTAINER:
                ricmContainerFile = null;
                break;
            case FLUOR_CONTAINER:
                fluorContainerFile = null;
                break;
            case RICM_FILES:
                ricmFiles = null;
                break;
            case FLUOR_FILES:
                fluorFiles = null;
                break;
            case COMBINED_FILES:
                combinedFiles = null;
                break;
            default:
                break;
        }
        refreshFileLists();
    }

    private void refreshFileLists() {
        ricmContainerList.setListData(fileNames(ricmContainerFile != null ? new File[] {ricmContainerFile} : null));
        fluorContainerList.setListData(fileNames(fluorContainerFile != null ? new File[] {fluorContainerFile} : null));
        ricmFilesList.setListData(fileNames(ricmFiles));
        fluorFilesList.setListData(fileNames(fluorFiles));
        combinedFilesList.setListData(fileNames(combinedFiles));
    }

    private void updateInputModeCard() {
        final CardLayout cl = (CardLayout) inputModeCards.getLayout();
        final int modeIndex = inputModeBox.getSelectedIndex();
        final boolean mode3 = modeIndex == 2;
        segChannelLabel.setVisible(mode3);
        segChannelSpinner.setVisible(mode3);
        firstMeasChannelLabel.setVisible(mode3);
        firstMeasChannelSpinner.setVisible(mode3);
        switch (modeIndex) {
            case 0:
                cl.show(inputModeCards, CARD_MODE1);
                break;
            case 1:
                cl.show(inputModeCards, CARD_MODE2);
                break;
            case 2:
                cl.show(inputModeCards, CARD_MODE3);
                break;
            default:
                cl.show(inputModeCards, CARD_MODE1);
                break;
        }
    }

    private File chooseStartDirectory(SelectionTarget target) {
        final File preferred = preferredDirectoryFor(target);
        if (preferred != null && preferred.isDirectory()) {
            return preferred;
        }
        final String lastPath = Prefs.get(INPUT_DIR_PREF, (String) null);
        if (lastPath != null && !lastPath.trim().isEmpty()) {
            final File lastDir = new File(lastPath);
            if (lastDir.isDirectory()) {
                return lastDir;
            }
        }
        final File defaultDir = CellSegmentationIO.getDefaultOutputDirectory();
        return defaultDir != null && defaultDir.isDirectory() ? defaultDir : null;
    }

    private File preferredDirectoryFor(SelectionTarget target) {
        switch (target) {
            case FLUOR_CONTAINER:
                return parentDirectory(ricmContainerFile);
            case FLUOR_FILES:
                return firstParentDirectory(ricmFiles);
            default:
                return null;
        }
    }

    private void rememberInputDirectory(JFileChooser chooser, SelectionTarget target) {
        File dir = null;
        switch (target) {
            case RICM_CONTAINER:
                dir = parentDirectory(ricmContainerFile);
                break;
            case FLUOR_CONTAINER:
                dir = parentDirectory(fluorContainerFile);
                break;
            case RICM_FILES:
                dir = firstParentDirectory(ricmFiles);
                break;
            case FLUOR_FILES:
                dir = firstParentDirectory(fluorFiles);
                break;
            case COMBINED_FILES:
                dir = firstParentDirectory(combinedFiles);
                break;
            default:
                break;
        }
        if (dir == null) {
            dir = chooser.getCurrentDirectory();
        }
        if (dir != null && dir.isDirectory()) {
            Prefs.set(INPUT_DIR_PREF, dir.getAbsolutePath());
        }
    }

    private static File parentDirectory(File file) {
        if (file == null) {
            return null;
        }
        final File parent = file.getParentFile();
        return parent != null && parent.isDirectory() ? parent : null;
    }

    private static File firstParentDirectory(File[] files) {
        if (files == null || files.length == 0) {
            return null;
        }
        for (File file : files) {
            final File parent = parentDirectory(file);
            if (parent != null) {
                return parent;
            }
        }
        return null;
    }

    private static String[] fileNames(File[] files) {
        if (files == null || files.length == 0) {
            return new String[] {"No files selected"};
        }
        final String[] out = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            out[i] = files[i] != null ? files[i].getAbsolutePath() : "";
        }
        return out;
    }

    private static File[] cloneFiles(File[] files) {
        if (files == null || files.length == 0) {
            return null;
        }
        final File[] cloned = new File[files.length];
        System.arraycopy(files, 0, cloned, 0, files.length);
        return cloned;
    }

    private static JPanel createFormPanel() {
        return DialogFormUtils.createFormPanel();
    }

    static final class Result {
        final int inputModeIndex;
        final int sameFileSegChannelIndex1Based;
        final int sameFileFirstMeasChannelIndex1Based;
        final File ricmContainerFile;
        final File fluorContainerFile;
        final File[] ricmFiles;
        final File[] fluorFiles;
        final File[] combinedFiles;
        final int minArea;
        final String thrMethod;
        final boolean darkObjects;
        final String edgeMethod;
        final boolean excludeBorderTouching;
        final String thresholdStopMode;
        final String roiReviewMode;
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
        final File outputDir;
        final String labelsLut;
        final boolean saveMask;
        final boolean saveLabels;
        final boolean saveLabelOverlay;
        final boolean saveRois;
        final boolean saveMeasurements;
        final boolean saveParameters;

        Result(
                int inputModeIndex,
                int sameFileSegChannelIndex1Based,
                int sameFileFirstMeasChannelIndex1Based,
                File ricmContainerFile,
                File fluorContainerFile,
                File[] ricmFiles,
                File[] fluorFiles,
                File[] combinedFiles,
                int minArea,
                String thrMethod,
                boolean darkObjects,
                String edgeMethod,
                boolean excludeBorderTouching,
                String thresholdStopMode,
                String roiReviewMode,
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
                File outputDir,
                String labelsLut,
                boolean saveMask,
                boolean saveLabels,
                boolean saveLabelOverlay,
                boolean saveRois,
                boolean saveMeasurements,
                boolean saveParameters) {
            this.inputModeIndex = inputModeIndex;
            this.sameFileSegChannelIndex1Based = sameFileSegChannelIndex1Based;
            this.sameFileFirstMeasChannelIndex1Based = sameFileFirstMeasChannelIndex1Based;
            this.ricmContainerFile = ricmContainerFile;
            this.fluorContainerFile = fluorContainerFile;
            this.ricmFiles = cloneFiles(ricmFiles);
            this.fluorFiles = cloneFiles(fluorFiles);
            this.combinedFiles = cloneFiles(combinedFiles);
            this.minArea = minArea;
            this.thrMethod = thrMethod;
            this.darkObjects = darkObjects;
            this.edgeMethod = edgeMethod;
            this.excludeBorderTouching = excludeBorderTouching;
            this.thresholdStopMode = thresholdStopMode;
            this.roiReviewMode = roiReviewMode;
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
            this.outputDir = outputDir;
            this.labelsLut = labelsLut;
            this.saveMask = saveMask;
            this.saveLabels = saveLabels;
            this.saveLabelOverlay = saveLabelOverlay;
            this.saveRois = saveRois;
            this.saveMeasurements = saveMeasurements;
            this.saveParameters = saveParameters;
        }
    }

    private enum SelectionTarget {
        RICM_CONTAINER,
        FLUOR_CONTAINER,
        RICM_FILES,
        FLUOR_FILES,
        COMBINED_FILES
    }
}
