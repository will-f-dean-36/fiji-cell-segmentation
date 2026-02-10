# Fiji Cell Segmentation

Fiji/ImageJ plugin for segmenting cells in an RICM image using edge detection, thresholding, and watershed.

## Status
Early release (0.x). In active development.

## Installation (recommended)

Pre-built JARs are available on the [GitHub Releases](https://github.com/will-f-dean-36/fiji-cell-segmentation/releases) page.

### Requirements (runtime)
- Fiji (ImageJ)

### Install

1. Download the latest release JAR
2. Copy the JAR into your Fiji `plugins/` directory
3. Restart Fiji

## Building from source

### Requirements (build only)
- JDK 8+
- Maven

### Build
From the repository root:

```bash
mvn clean package
```

This will generate a JAR in:

```
target/cell-segmentation-<version>.jar
```

You can then install this JAR manually as described above.

## Usage

### Run on current image
```
Plugins → Cell Segmentation → Run on current image...
```

Processes the currently active image and outputs segmentation results and measurements.

### Batch mode
```
Plugins → Cell Segmentation → Run on multiple images...
```

Runs the segmentation pipeline on a selected set of input images and writes outputs to disk.

## Compatibility
- Compiled for Java 8 (runs on Java 8+)
- Tested with Fiji/ImageJ (no update site yet)

## Notes
- This plugin is under active development; defaults and behavior may change between releases.
- If the plugin does not appear after installation, verify the JAR is in the Fiji `plugins/` directory and restart Fiji.

