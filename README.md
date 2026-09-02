# Fiji Cell Segmentation

Fiji/ImageJ plugin for segmenting cells from RICM images, reviewing thresholds and ROIs interactively, measuring accepted ROIs, and saving masks, labels, overlays, ROIs, measurements, and run parameters.

The plugin is aimed at workflows where:
- RICM images are used for segmentation
- accepted ROIs are measured either on the same RICM image or on paired fluorescence images
- the user may want to stop during thresholding or ROI review and adjust results interactively

## Status

Early release (`0.x`), under active development.

## Installation From a JAR

### Requirements

- Fiji / ImageJ

### Install steps

1. Obtain the plugin JAR.
2. Copy the JAR into Fiji’s `plugins/` directory.
3. Restart Fiji.
4. The plugin should appear under:

```text
Plugins > Cell Segmentation
```

Menu entries:

```text
Run on current image/stack...
Run on multiple images...
```

If the plugin does not appear:
- confirm the JAR is in the Fiji `plugins/` folder
- restart Fiji completely
- check `Help > Refresh Menus`

## Building From Source

### Requirements

- JDK 8 or newer
- Maven

### Build

From the repository root:

```bash
mvn clean package
```

The built JAR will be written to:

```text
target/Cell_Segmentation-<version>.jar
```

Install that JAR into Fiji as described above.

## What the Plugin Does

The segmentation pipeline is:

1. Duplicate the segmentation image
2. Apply the selected edge detector
3. Normalize the gradient image to `[0, 1]`
4. Threshold the gradient image
5. Convert to mask
6. Fill holes / close gaps
7. Optionally run watershed to separate touching cells
8. Generate ROIs from 4-connected mask components
9. Optionally exclude border-touching objects
10. Optionally review ROIs interactively
11. Measure accepted ROIs on the chosen measurement image

Outputs can include:
- mask image
- labels image
- label overlay image
- ROI ZIP
- measurements CSV
- segmentation parameters CSV

## Data Requirements

### Single-image mode

Menu:

```text
Plugins > Cell Segmentation > Run on current image/stack...
```

Input requirements:
- the currently active Fiji image is used
- it can be:
  - a single 2D RICM image
  - a simple stack where each slice is treated as an independent RICM image

For stack mode:
- the plugin will ask whether to process all slices
- each slice is treated as a separate segmentation target
- masks, labels, label overlays, measurements, and parameter rows are aggregated across slices

Single-image mode does not require fluorescence images.
Measurements are performed on the active image itself.

### Batch mode

Menu:

```text
Plugins > Cell Segmentation > Run on multiple images...
```

Batch mode supports five input modes.

#### Mode 1: RICM file list

Use this when:
- you have a list of standalone RICM files
- each file is a single segmentation target
- no fluorescence file is involved

Measurements:
- accepted ROIs are measured on the same RICM image

#### Mode 2: RICM container

Use this when:
- you have one multi-series container file
- each series is an independent RICM image
- no fluorescence file is involved

Measurements:
- accepted ROIs are measured on the same RICM series

#### Mode 3: Two container files

Use this when:
- one container file holds RICM series
- another container file holds fluorescence series
- series are paired by series index

Example:
- RICM series 1 pairs with fluorescence series 1
- RICM series 2 pairs with fluorescence series 2

#### Mode 4: Two file lists

Use this when:
- one file list contains RICM images
- one file list contains fluorescence images
- files are paired by selection order

Example:
- first selected RICM file pairs with first selected fluorescence file
- second selected RICM file pairs with second selected fluorescence file

#### Mode 5: Same-file channels

Use this when:
- one file contains both RICM and fluorescence channels
- one channel is used for segmentation
- later channels are used for measurement

You must specify:
- the RICM segmentation channel
- the first fluorescence channel

## Single-image Dialog

The single-image dialog has two tabs:

- `General`
- `Advanced`

### General tab

- `Auto-threshold method`
  - Fiji/ImageJ auto-threshold method applied to the normalized edge image unless a manual threshold is selected during review.
- `Object polarity`
  - `Dark`: cells are darker than background
  - `Light`: cells are lighter than background
- `Edge method`
  - edge detector used before thresholding
  - options: Sobel, Prewitt, Scharr, Laplacian, or None
- `Min cell area (px)`
  - minimum accepted object area in pixels
- `Exclude border-touching cells`
  - `Yes`: remove any ROI touching the image border
  - `No`: keep border-touching ROIs
- `Attempt to separate touching cells (watershed)`
  - `Yes`: run ImageJ watershed before ROI generation
  - `No`: skip watershed

### Advanced tab

The advanced tab groups review, display, measurement, and save options into sections.

#### Review

- `Threshold Review`
  - `Yes`: stop after edge detection and before thresholding
  - `No`: run without threshold stop points
- `ROI Review`
  - `Yes`: show detected ROIs for manual review before final acceptance
  - `No`: accept detected ROIs directly

If threshold review is enabled, the stop-point dialog supports:
- `Continue`
- `Continue to end`
- `Skip`
- `Abort`
- `Remember threshold`

If ROI review is enabled, the stop-point dialog supports:
- `Split ROIs`
  - draw a line across a single ROI, then click `Split`
- `Merge ROIs`
  - draw a line with endpoints inside two ROIs, then click `Merge`
- `Delete ROIs`
  - draw through ROIs or place point markers inside them, then click `Delete`
- `Undo`
- `Redo`
- `Reset`
- `Continue`
- `Continue to end`
- `Skip`
- `Abort`

For stacks, those review steps are applied slice-by-slice.

#### Display

- `Show intermediate images`
  - show intermediate processing steps from the segmentation pipeline
- `Show mask image`
  - show the final binary mask image
- `Show labels image`
  - show the final label image
- `Show ROI overlay on source image`
  - apply accepted ROIs to the source image via ROI Manager
- `Show label overlay`
  - show a label overlay image
- `Labels LUT`
  - LUT used when displaying labels
- `Clear ROI Manager first`
  - clear the ROI Manager before adding final accepted ROIs

#### Measurements

Available measurement toggles:
- Area
- Mean
- Min/Max
- Std Dev
- Perimeter
- Centroid
- Bounding rectangle
- Feret's diameter
- Shape descriptors
- Integrated density

These are standard ImageJ measurements performed on the accepted ROIs.

#### Save

- `Automatically save results`
  - enables saving to disk after the run
- `Output directory`
  - destination folder for saved outputs
- `Save mask image`
- `Save labels image`
- `Save label overlay`
- `Save ROIs (ZIP)`
- `Save measurements (CSV)`
- `Save segmentation parameters (CSV)`

When auto-save is off, the individual save toggles are disabled.

## Batch Dialog

The batch dialog has three tabs:

- `Inputs`
- `General`
- `Advanced`

### Inputs tab

Controls vary by input mode.

Common behavior:
- selected files are shown in lists
- file selection uses `JFileChooser`
- the last-used input directory is remembered

Mode-specific inputs:

- Mode 1
  - `RICM files`
- Mode 2
  - `RICM container`
- Mode 3
  - `RICM container`
  - `Fluorescence container`
- Mode 4
  - `RICM files`
  - `Fluorescence files`
- Mode 5
  - `Combined multi-channel files`
  - `Mode 5 RICM channel`
  - `Mode 5 first fluorescence channel`

### General tab

Same meaning as single-image mode:
- `Auto-threshold method`
- `Object polarity`
- `Edge method`
- `Min cell area (px)`
- `Exclude border-touching cells`
- `Attempt to separate touching cells (watershed)`

### Advanced tab

The advanced tab groups review, measurement, and save options into sections.

#### Review

- `Threshold Review`
  - `Yes`: stop once per unique RICM segmentation source
  - `No`: no threshold stop points
- `ROI Review`
  - `Yes`: review ROIs once per unique RICM segmentation source
  - `No`: no ROI review

Behavior notes:
- `Continue to end` for threshold review only disables future threshold review stops
- `Continue to end` for ROI review only disables future ROI review stops
- these two review streams are independent
- batch ROI review uses the same split, merge, delete, undo, redo, and reset tools as single-image mode

#### Measurements

Same measurement toggles as single-image mode.

For batch mode:
- in Modes 1-2, measurements are performed on the RICM segmentation image/series itself
- in Modes 3-5, measurements are performed on the paired fluorescence image(s) or fluorescence channels

#### Save

- `Output directory`
- `Labels LUT`
- `Save mask image`
- `Save labels image`
- `Save label overlay`
- `Save ROIs (ZIP)`
- `Save measurements (CSV)`
- `Save segmentation parameters (CSV)`

## Saved Outputs

### Measurements CSV

Contains the selected ImageJ measurements for accepted ROIs.

In single-image stack mode:
- a `Slice` column is included

In batch mode:
- one or more measurement CSV files may be written depending on the measurement channels and timepoints

### Segmentation parameters CSV

Batch mode writes:

```text
segmentation_parameters.csv
```

Single-image mode writes:

```text
<base>_segmentation_parameters.csv
```

The parameter CSV records the segmentation settings actually used for each processed image or slice, including:
- source file / source path
- series or slice index
- segmentation channel
- threshold mode (`auto` or `manual`)
- threshold method
- object polarity
- manual threshold min/max when applicable
- edge method
- minimum area
- border-touch exclusion
- ROI review action
- accepted ROI count

## Notes

- The plugin is currently distributed as a JAR, not an update site.
- Settings chosen in the custom dialogs are remembered between runs.
- Batch file selections themselves are not persisted as defaults.
- This is still a `0.x` project, so UI details and defaults may continue to evolve.
