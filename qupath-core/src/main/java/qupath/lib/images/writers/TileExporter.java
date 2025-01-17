/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2018 - 2021 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.lib.images.writers;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.awt.common.BufferedImageTools;
import qupath.lib.common.GeneralTools;
import qupath.lib.common.ThreadTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.LabeledImageServer;
import qupath.lib.images.servers.ImageServerMetadata.ChannelType;
import qupath.lib.images.servers.TransformedServerBuilder;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectTools;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImageRegion;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.RoiTools;

/**
 * Helper class for exporting image tiles, typically for further analysis elsewhere or for training up an AI algorithm.
 * 
 * @author Pete Bankhead
 */
public class TileExporter  {

	private final static Logger logger = LoggerFactory.getLogger(TileExporter.class);

	private ImageData<BufferedImage> imageData;
	private ImageServer<BufferedImage> server;
	private ImageRegion region = null;
	
	private List<PathObject> parentObjects = null;

	private double downsample;
	private int tileWidth = 512, tileHeight = 512;
	private int overlapX = 0, overlapY = 0;

	private boolean includePartialTiles = false;
	private boolean annotatedTilesOnly = false;
	private boolean annotatedCentroidTilesOnly = false;
	
	private int minZ = 0, minT = 0;
	private int maxZ = -1, maxT = -1;

	private String ext = ".tif";
	private String extLabeled = null;
	
	private String imageSubDir = null;
	private String labelSubDir = null;
	private boolean exportJson = false;
	private String labelId = null;

	private ImageServer<BufferedImage> serverLabeled;

	/**
	 * Create a builder to export tiles.
	 * @param imageData
	 */
	public TileExporter(ImageData<BufferedImage> imageData) {
		this.imageData = imageData;
		this.server = imageData.getServer();
	}
	
	/**
	 * Specify a filter to extract parent objects to define tiles. This overrides {@link #tileSize(int, int)}, 
	 * giving tiles that match the parent ROI size instead.
	 * @param filter
	 * @return this exporter
	 */
	public TileExporter parentObjects(Predicate<PathObject> filter) {
		this.parentObjects = imageData.getHierarchy().getFlattenedObjectList(null).stream()
				.filter(filter)
				.collect(Collectors.toList());
		return this;
	}
	
	/**
	 * Specify parent objects to define tiles. This overrides {@link #tileSize(int, int)}, 
	 * giving tiles that match the parent ROI size instead.
	 * @param parentObjects
	 * @return this exporter
	 */
	public TileExporter parentObjects(Collection<? extends PathObject> parentObjects) {
		this.parentObjects = new ArrayList<>(parentObjects);
		return this;
	}
	
	/**
	 * Specify that a single tile should be generated corresponding to the full image.
	 * @return this exporter
	 */
	public TileExporter fullImageTile() {
		this.parentObjects = Collections.singletonList(imageData.getHierarchy().getRootObject());
		return this;
	}
	

	/**
	 * Define the tile size in pixel units at the export resolution.
	 * @param tileSize
	 * @return this exporter
	 */
	public TileExporter tileSize(int tileSize) {
		return tileSize(tileSize, tileSize);
	}

	/**
	 * Define the horizontal and vertical tile size in pixel units at the export resolution.
	 * @param tileWidth
	 * @param tileHeight
	 * @return this exporter
	 */
	public TileExporter tileSize(int tileWidth, int tileHeight) {
		this.tileWidth = tileWidth;
		this.tileHeight = tileHeight;
		return this;
	}
	
	/**
	 * Export only specified channels.
	 * <p>
	 * Note: currently, this always involved conversion to 32-bit.
	 * This behavior may change in a future version of QuPath to preserve image type.
	 * 
	 * @param channels channels to export (0-based indexing)
	 * @return this exporter
	 */
	public TileExporter channels(int... channels) {
		this.server = new TransformedServerBuilder(this.server)
				.extractChannels(channels)
				.build();
		return this;
	}
	
	/**
	 * Export only specified channels, identified by name.
	 * <p>
	 * Note: currently, this always involved conversion to 32-bit.
	 * This behavior may change in a future version of QuPath to preserve image type.
	 * 
	 * @param channelNames channels to export
	 * @return this exporter
	 */
	public TileExporter channels(String... channelNames) {
		this.server = new TransformedServerBuilder(this.server)
				.extractChannels(channelNames)
				.build();
		return this;
	}

	/**
	 * Define tile overlap (both x and y) in pixel units at the export resolution.
	 * @param overlap
	 * @return this exporter
	 */
	public TileExporter overlap(int overlap) {
		return overlap(overlap, overlap);
	}

	/**
	 * Define tile overlap (x and y separately) in pixel units at the export resolution.
	 * @param overlapX
	 * @param overlapY
	 * @return this exporter
	 */
	public TileExporter overlap(int overlapX, int overlapY) {
		this.overlapX = overlapX;
		this.overlapY = overlapY;
		return this;
	}

	/**
	 * Define resolution as a downsample value.
	 * @param downsample
	 * @return this exporter
	 * @see #requestedPixelSize(double)
	 */
	public TileExporter downsample(double downsample) {
		this.downsample = downsample;
		return this;
	}

	/**
	 * Define resolution as a pixel size in calibrated units. The actual units depend upon those stored within the server.
	 * @param pixelSize
	 * @return this exporter
	 */
	public TileExporter requestedPixelSize(double pixelSize) {
		this.downsample = pixelSize / server.getPixelCalibration().getAveragedPixelSize().doubleValue();
		return this;
	}

	/**
	 * Specify whether incomplete tiles at image boundaries should be included. Default is false.
	 * @param includePartialTiles
	 * @return this exporter
	 */
	public TileExporter includePartialTiles(boolean includePartialTiles) {
		this.includePartialTiles = includePartialTiles;
		return this;
	}
	
	/**
	 * Define the region to be processed. Default is the full image.
	 * @param region
	 * @return this exporter
	 */
	public TileExporter region(RegionRequest region) {
		this.region = region;
		this.downsample = region.getDownsample();
		return this;
	}

	/**
	 * Define the region to be processed. Default is the full image.
	 * @param region
	 * @return this exporter
	 */
	public TileExporter region(ImageRegion region) {
		this.region = region;
		return this;
	}
	
	/**
	 * Define the range of Z-slices to process. Default is all Z-slices (0 to nZSlices).<p>
	 * Note: the range is from {@code minZ} (included) to {@code maxZ} (excluded). -1 can be 
	 * used for {@code maxZ} to process all Z-slices without having to indicate the exact max number.
	 * @param minZ the lower value (included)
	 * @param maxZ the higher value (excluded)
	 * @return this exporter
	 */
	public TileExporter zRange(int minZ, int maxZ) {
		this.minZ = minZ;
		this.maxZ = maxZ;
		return this;
	}

	/**
	 * Define the range of timepoints to process. Default is all timepoints (0 to nTimepoints).<p>
	 * Note: the range is from {@code minT} (included) to {@code maxT} (excluded). -1 can be 
	 * used for {@code maxT} to process all timepoints without having to indicate the exact max number.
	 * @param minT the lower value (included)
	 * @param maxT the higher value (excluded)
	 * @return this exporter
	 */
	public TileExporter tRange(int minT, int maxT) {
		this.minT = minT;
		this.maxT = maxT;
		return this;
	}
	
	/**
	 * Specify whether tiles that do not overlap with any annotations should be included.
	 * This is a weaker criterion than {@link #annotatedCentroidTilesOnly(boolean)}.
	 * <p>
	 * Default is false.
	 * @param annotatedTilesOnly
	 * @return this exporter
	 * @see #annotatedCentroidTilesOnly(boolean)
	 */
	public TileExporter annotatedTilesOnly(boolean annotatedTilesOnly) {
		this.annotatedTilesOnly = annotatedTilesOnly;
		return this;
	}
	
	/**
	 * Specify whether tiles without any annotations over the tile centroid should be included.
	 * This is a stronger criterion than {@link #annotatedTilesOnly(boolean)}, i.e. it will exclude more tiles.
	 * <p>
	 * Default is false.
	 * @param annotatedCentroidTilesOnly
	 * @return this exporter
	 * @see #annotatedTilesOnly(boolean)
	 */
	public TileExporter annotatedCentroidTilesOnly(boolean annotatedCentroidTilesOnly) {
		this.annotatedCentroidTilesOnly = annotatedCentroidTilesOnly;
		return this;
	}

	/**
	 * Specify a file extension for the original pixels, which determines the export file format.
	 * @param ext
	 * @return this exporter
	 */
	public TileExporter imageExtension(String ext) {
		if (!ext.startsWith("."))
			ext = "." + ext;
		this.ext = ext;
		return this;
	}

	/**
	 * Specify a file extension for the labelled image, which determines the export file format.
	 * @param ext
	 * @return this exporter
	 */
	public TileExporter labeledImageExtension(String ext) {
		if (!ext.startsWith("."))
			ext = "." + ext;
		this.extLabeled = ext;
		return this;
	}

	/**
	 * Optional server providing image labels.
	 * These may be export as corresponding images alongside the 'original' pixels, e.g. to create 
	 * training data for an AI algorithm.
	 * @param server the labeled server
	 * @return this exporter
	 */
	public TileExporter labeledServer(ImageServer<BufferedImage> server) {
		this.serverLabeled = server;
		return this;
	}
	
	
	/**
	 * Specify a subdirectory within which image tiles should be saved.
	 * By default, tiles are written to the directory specified within {@link #writeTiles(String)}.
	 * This option makes it possible to split images and labels into separate subdirectories.
	 * @param subdir
	 * @return this exporter
	 */
	public TileExporter imageSubDir(String subdir) {
		this.imageSubDir = subdir;
		return this;
	}
	
	/**
	 * Specify a subdirectory within which labeled image tiles should be saved.
	 * By default, tile labels are written to the directory specified within {@link #writeTiles(String)}.
	 * This option makes it possible to split images and labels into separate subdirectories.
	 * <p>
	 * Only relevant if {@link #labeledServer(ImageServer)} is provided.
	 * @param subdir
	 * @return this exporter
	 */
	public TileExporter labeledImageSubDir(String subdir) {
		this.labelSubDir = subdir;
		return this;
	}
	
	/**
	 * Specify an identifier appended to the filename for labeled images.
	 * The labeled image name will be in the format {@code imageName + labeledImageId + labeledImageExtension}.
	 * <p>
	 * This can be used to avoid name clashes with export image tiles.
	 * If not specified, QuPath will generate a default ID if required.
	 * <p>
	 * Only relevant if {@link #labeledServer(ImageServer)} is provided.
	 * @param labelId
	 * @return this exporter
	 */
	public TileExporter labeledImageId(String labelId) {
		this.labelId = labelId;
		return this;
	}
	
	/**
	 * Optionally export a JSON file that includes label information and image/label pairs, where available.
	 * @param exportJson
	 * @return this exporter
	 */
	public TileExporter exportJson(boolean exportJson) {
		this.exportJson = exportJson;
		return this;
	}
	

	/**
	 * Export the image tiles to the specified directory.
	 * @param dirOutput full path to the export directory
	 * @throws IOException if an error occurs during export
	 */
	public void writeTiles(String dirOutput) throws IOException {

		if (!new File(dirOutput).isDirectory())
			throw new IOException("Output directory " + dirOutput + " does not exist!");
		
		// Make sure we have any required subdirectories
		if (imageSubDir != null)
			new File(dirOutput, imageSubDir).mkdirs();
		if (labelSubDir != null)
			new File(dirOutput, labelSubDir).mkdirs();

		if (serverLabeled != null) {
			if (extLabeled == null)
				extLabeled = serverLabeled.getMetadata().getChannelType() == ChannelType.CLASSIFICATION ? ".png" : ".tif";
		}

		var pool = Executors.newFixedThreadPool(ThreadTools.getParallelism(), ThreadTools.createThreadFactory("tile-exporter", true));

		var server = this.server;
		var labeledServer = serverLabeled;
		Collection<RegionRequest> requests;
		
		// Work out which RegionRequests to use
		if (parentObjects == null)
			requests = getTiledRegionRequests();			
		else {
			requests = new ArrayList<>();
			for (var parent : parentObjects) {
				if (parent.isRootObject()) {
					for (int t = 0; t < server.nTimepoints(); t++) {
						for (int z = 0; z < server.nZSlices(); z++) {
							requests.add(RegionRequest.createInstance(server.getPath(), downsample, 0, 0, server.getWidth(), server.getHeight(), z, t));
						}						
					}
				} else if (parent.hasROI()) {
					requests.add(RegionRequest.createInstance(server.getPath(), downsample, parent.getROI()));
				}
			}
		}
		if (requests.isEmpty()) {
			logger.warn("No regions to export!");
			return;
		}
		if (requests.size() > 1)
			logger.info("Exporting {} tiles", requests.size());
		
		String imageName = GeneralTools.stripInvalidFilenameChars(
				GeneralTools.getNameWithoutExtension(server.getMetadata().getName())
				);

		// Create something we can input as the image path for export
		String imagePathName = null;
		var uris = server.getURIs();
		if (uris.isEmpty())
			imagePathName = imageName;
		else if (uris.size() == 1)
			imagePathName = uris.iterator().next().toString();
		else
			imagePathName = "[" + uris.stream().map(u -> u.toString()).collect(Collectors.joining("|")) + "]";

//		// If we have pixel calibration information, use it in the export
//		PixelCalibration pixelSize = server.getPixelCalibration();
//		if (pixelSize.equals(PixelCalibration.getDefaultInstance()))
//			pixelSize = null;
//		else
//			pixelSize = pixelSize.createScaledInstance(downsample, downsample);
		
		
		int tileWidth = includePartialTiles || parentObjects != null ? -1 : this.tileWidth;
		int tileHeight = includePartialTiles || parentObjects != null ? -1 : this.tileHeight;
		
		// Maintain a record of what we exported
		List<TileExportEntry> exportImages = new ArrayList<>();

		for (var r : requests) {
			String baseName = String.format("%s [%s]", imageName, getRegionString(r));
			
			String exportImageName = baseName + ext;
			if (imageSubDir != null)
				exportImageName = Paths.get(imageSubDir, exportImageName).toString();
			String pathImageOutput = Paths.get(dirOutput, exportImageName).toAbsolutePath().toString();
			
			// If we want only annotated tiles, skip regions that lack them
			if (annotatedCentroidTilesOnly) {
				double cx = (r.getMinX() + r.getMaxX()) / 2.0;
				double cy = (r.getMinY() + r.getMaxY()) / 2.0;
				if (labeledServer != null && (labeledServer instanceof LabeledImageServer)) {
					if (!((LabeledImageServer)labeledServer).getObjectsForRegion(r)
							.stream()
							.anyMatch(p -> p.getROI().contains(cx, cy))) {
						logger.trace("Skipping empty labelled region based on centroid test {}", r);
						continue;
					}
				} else if (imageData != null) {
					if (PathObjectTools.getObjectsForLocation(imageData.getHierarchy(),
							cx, cy, r.getZ(), r.getT(), 0).isEmpty())
						continue;
				}
			} else if (annotatedTilesOnly) {
				if (labeledServer != null) {
					if (labeledServer.isEmptyRegion(r)) {
						logger.trace("Skipping empty labelled region {}", r);
						continue;
					}
				} else if (imageData != null) {
					if (!imageData.getHierarchy().getObjectsForRegion(PathAnnotationObject.class, r, null)
							.stream().anyMatch(p -> RoiTools.intersectsRegion(p.getROI(), r)))
						continue;
				}
			}
			
			ExportTask taskImage = new ExportTask(server, r, pathImageOutput, tileWidth, tileHeight);

			String exportLabelName = null;
			ExportTask taskLabels = null;
			if (labeledServer != null) {
				String labelName = baseName;
				if ((labelSubDir == null || labelSubDir.equals(imageSubDir)) && labelId == null && ext.equals(extLabeled)) {
					labelName = baseName + "-labelled";
				} else if (labelId != null)
					labelName = baseName + labelId;
				exportLabelName = labelName + extLabeled;
				if (labelSubDir != null)
					exportLabelName = Paths.get(labelSubDir, exportLabelName).toString();
				String pathLabelsOutput = Paths.get(dirOutput, exportLabelName).toAbsolutePath().toString();

				taskLabels = new ExportTask(labeledServer, r.updatePath(labeledServer.getPath()),
						pathLabelsOutput, tileWidth, tileHeight);
			}
			exportImages.add(new TileExportEntry(
					r.updatePath(imagePathName),
//					pixelSize,
					exportImageName,
					exportLabelName));

			if (taskImage != null)
				pool.submit(taskImage);
			if (taskLabels != null) {
				pool.submit(taskLabels);
			}
		}
		
		// Write JSON, if we need to
		if (exportJson) {
			var gson = GsonTools.getInstance(true)
					.newBuilder()
					.disableHtmlEscaping() // Required to support = in filenames
					.create();
			var data = new TileExportData(dirOutput, exportImages);
			if (labeledServer instanceof LabeledImageServer) {
				var labels = ((LabeledImageServer) labeledServer).getLabels();
				var boundaryLabels = ((LabeledImageServer) labeledServer).getBoundaryLabels();
				List<TileExportLabel> labelList = new ArrayList<>();
				Set<PathClass> existingLabels = new HashSet<>();
				for (var entry : labels.entrySet()) {
					var pathClass = entry.getKey();
					var label = new TileExportLabel(pathClass.toString(), entry.getValue(), boundaryLabels.getOrDefault(pathClass, null));
					labelList.add(label);
				}
				for (var entry : boundaryLabels.entrySet()) {
					var pathClass = entry.getKey();
					if (!existingLabels.contains(pathClass)) {
						var label = new TileExportLabel(pathClass.toString(), null, boundaryLabels.getOrDefault(pathClass, null));
						labelList.add(label);
					}
				}
				data.labels = labelList;
			}
			var pathJson = Paths.get(dirOutput, imageName + "-tiles.json");
			if (Files.exists(pathJson)) {
				logger.warn("Overwriting existing JSON file {}", pathJson);
			}
			try (var writer = Files.newBufferedWriter(pathJson, StandardCharsets.UTF_8)) {
				gson.toJson(data, writer);
			}
		}

		pool.shutdown();
		try {
			pool.awaitTermination(24, TimeUnit.HOURS);
		} catch (InterruptedException e) {
			pool.shutdownNow();
			logger.error("Tile export interrupted: {}", e);
			logger.error("", e);
		}
	}
	
	
	@SuppressWarnings("unused")
	private static class TileExportData {
		
		private String qupath_version = GeneralTools.getVersion();
		private String base_directory;
		private List<TileExportLabel> labels;
		private List<TileExportEntry> tiles;
		
		TileExportData(String path, List<TileExportEntry> images) {
			this.base_directory = path;
			this.tiles = images;
		}
		
	}
	
	@SuppressWarnings("unused")
	private static class TileExportLabel {
		
		private String classification;
		private Integer label;
		private Integer boundaryLabel;
		
		public TileExportLabel(String classification, Integer label, Integer boundaryLabel) {
			this.classification = classification;
			this.label = label;
			this.boundaryLabel = boundaryLabel;
		}
		
	}
	
	@SuppressWarnings("unused")
	private static class TileExportEntry {
		
		private RegionRequest region;
//		private PixelCalibration pixel_size;
		private String image;
		private String labels;
		
		TileExportEntry (RegionRequest region, String image, String labels) {
			this.region = region;
//			this.pixel_size = pixelSize;
			this.image = image;
			this.labels = labels;
		}
		
	}
	


	static class ExportTask implements Runnable {

		private ImageServer<BufferedImage> server;
		private RegionRequest request;
		private String path;
		private int tileWidth, tileHeight;

		private ExportTask(ImageServer<BufferedImage> server, RegionRequest request, String path, int tileWidth, int tileHeight) {
			this.server = server;
			this.request = request;
			this.path = path;
			this.tileWidth = tileWidth;
			this.tileHeight = tileHeight;
		}

		@Override
		public void run() {
			try {
				var img = server.readBufferedImage(request);
				if ((tileWidth > 0 && tileHeight > 0) && (img.getWidth() != tileWidth || img.getHeight() != tileHeight)) {
					logger.warn("Resizing tile from {}x{} to {}x{}", img.getWidth(), img.getHeight(), tileWidth, tileHeight);
					img = BufferedImageTools.resize(img, tileWidth, tileHeight, false);
				}
				if (!Thread.currentThread().isInterrupted())
					ImageWriterTools.writeImageRegion(server, request, path);
				else
					logger.debug("Interrupted! Will not write image to {}", path);
				//				ImageWriterTools.writeImage(img, path);
			} catch (Exception e) {
				logger.error("Error writing tile: " + e.getLocalizedMessage(), e);
			}
		}

	}
	
	
	private static ThreadLocal<NumberFormat> formatter = ThreadLocal.withInitial(() -> createDefaultNumberFormat(5));

	private static NumberFormat createDefaultNumberFormat(int maxFractionDigits) {
		var formatter = NumberFormat.getNumberInstance(Locale.US);
		formatter.setMinimumFractionDigits(0);
		formatter.setMaximumFractionDigits(maxFractionDigits);
		return formatter;
	}
	
	/**
	 * Get a standardized string to represent a region.
	 * This is of the form
	 * <pre>
	 *  d={downsample},x={x},y={y},w={w},h={h},z={z},t={t}
	 * </pre>
	 * where downsample, z and t are optional and omitted if their default values (1.0, 0 and 0 respectively). 
	 * @param request
	 * @return
	 */
	static String getRegionString(RegionRequest request) {
		String s = "";
		if (request.getDownsample() != 1.0)
			s = "d=" + formatter.get().format(request.getDownsample()) + ",";
		s += "x="+request.getX()+",y="+request.getY()+",w="+request.getWidth()+",h="+request.getHeight();
		if (request.getZ() != 0)
			s += ",z="+request.getZ();
		if (request.getT() != 0)
			s += ",t="+request.getT();
		return s;
	}



	Collection<RegionRequest> getTiledRegionRequests() {
		List<RegionRequest> requests = new ArrayList<>();
		
		if (downsample == 0)
			throw new IllegalArgumentException("No downsample was specified!");

		ImageRegion regionLocal = region == null ? RegionRequest.createInstance(server, downsample) : region;
		
		// Z and T shouldn't be lower than 0
		int minZLocal = minZ < 0 ? 0 : minZ;
		int minTLocal = minT < 0 ? 0 : minT;
		
		// Cap Z and T variables to their maximum possible value if needed
		int maxZLocal = maxZ > server.nZSlices() || maxZ == -1 ? server.nZSlices() : maxZ;
		int maxTLocal = maxT > server.nTimepoints() || maxT == -1 ? server.nTimepoints() : maxT;

		// Create another region to account for ImageRegion and RegionRequest params simultaneously
		var regionLocal2 = RegionRequest.createInstance(server.getPath(), downsample, regionLocal);
		for (int t = minTLocal; t < maxTLocal; t++) {
			regionLocal2 = regionLocal2.updateT(t);
			for (int z = minZLocal; z < maxZLocal; z++) {
				regionLocal2 = regionLocal2.updateZ(z);
				requests.addAll(
						splitRegionRequests(regionLocal2, tileWidth, tileHeight, overlapX, overlapY, includePartialTiles)
						);
			}
		}
		return requests;
	}

	/**
	 * Split a single RegionRequest into multiple requests, specifying a tile size and overlap at the output resolution.
	 * This is useful, for example, to export fixed-size image tiles using a particular downsample value.
	 * <p>
	 * Note: one should be careful in case rounding errors within specific servers result in some off-by-one tile sizes, 
	 * so that additional cropping/padding/scaling may be necessary in some cases.
	 * 
	 * @param request
	 * @param tileWidth
	 * @param tileHeight
	 * @param xOverlap
	 * @param yOverlap
	 * @param includePartialTiles
	 * @return
	 */
	static Collection<RegionRequest> splitRegionRequests(
			RegionRequest request,
			int tileWidth, int tileHeight,
			int xOverlap, int yOverlap,
			boolean includePartialTiles) {

		var set = new LinkedHashSet<RegionRequest>();

		double downsample = request.getDownsample();
		String path = request.getPath();

		int minX = (int)(request.getMinX() / downsample);
		int minY = (int)(request.getMinY() / downsample);
		int maxX = (int)(request.getMaxX() / downsample);
		int maxY = (int)(request.getMaxY() / downsample);

		int z = request.getZ();
		int t = request.getT();

		for (int y = minY; y < maxY; y += tileHeight-yOverlap) {
			int th = tileHeight;
			if (y + th > maxY)
				th = maxY - y;

			int yi = (int)Math.round(y * downsample);
			int y2i = (int)Math.round((y + tileHeight) * downsample);

			if (y2i > request.getMaxY()) {
				if (!includePartialTiles)
					continue;
				y2i = request.getMaxY();
			} else if (y2i == yi)
				continue;

			for (int x = minX; x < maxX; x += tileWidth-xOverlap) {
				int tw = tileWidth;
				if (x + tw > maxX)
					tw = maxX - x;

				int xi = (int)Math.round(x * downsample);
				int x2i = (int)Math.round((x + tileWidth) * downsample);

				if (x2i > request.getMaxX()) {
					if (!includePartialTiles)
						continue;
					x2i = request.getMaxX();
				} else if (x2i == xi)
					continue;

				var tile = RegionRequest.createInstance(path, downsample,
						xi, yi, x2i-xi, y2i-yi, z, t
						);
				set.add(tile);
			}
		}					

		return set;
	}

}
