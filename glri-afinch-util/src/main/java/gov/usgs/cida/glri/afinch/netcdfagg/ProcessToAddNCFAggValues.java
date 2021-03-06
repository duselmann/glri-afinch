package gov.usgs.cida.glri.afinch.netcdfagg;

import gov.usgs.cida.glri.afinch.AfinchFileProcessor;
import gov.usgs.cida.glri.afinch.SimpleCLIOptions;
import gov.usgs.cida.glri.afinch.stats.Statistics1D;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.commons.cli.CommandLine;
import org.joda.time.DateTime;
import org.joda.time.Days;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ucar.ma2.Array;
import ucar.ma2.DataType;
import ucar.ma2.InvalidRangeException;
import ucar.nc2.Attribute;
import ucar.nc2.Dimension;
import ucar.nc2.NetcdfFile;
import ucar.nc2.NetcdfFileWriter;
import ucar.nc2.Variable;
import ucar.nc2.constants.CDM;
import ucar.nc2.constants.CF;

/**
 * Notes from Eric Everman 5/21/2014 (Originally writen by Tom K., but there are
 * no docs).
 *
 * It looks like this utility reads in a nc file and creates a new nc file with
 * aggregate stats. Tom's version had hard-coded file locations, so I'll make
 * those configurable.
 *
 */
public class ProcessToAddNCFAggValues {

	private static Logger log = LoggerFactory.getLogger(ProcessToAddNCFAggValues.class);
	
	public final static String OBSERVATION_STRUCT_NAME = "record"; // NetCDF-Java reqiures this to be record (last tested release was 4.2.26)

	public final static String MEAN_SUFFIX = "Mean";
	public final static String MIN_SUFFIX = "Min";
	public final static String MAX_SUFFIX = "Max";
	public final static String COUNT_SUFFIX = "Count";
	public final static String MEDIAN_SUFFIX = "Median";
	public final static String DECILE_SUFFIX = "Decile";
							
	
	private final File inputFile;			//File that will be read
	private final File outputFile;			//File to be writen
	private final String observedValueName;			//NetCdf variable name to collect stats on (eg QAccCon)
	private final String observerdValueAbbrName;	//Prefix to use for newly created stats (eg QAC to prefix QACMean)

//	public static void main(String[] args) throws Exception {
//		SimpleCLIOptions options = new SimpleCLIOptions(AfinchFileProcessor.class);
//		options.addOption(new SimpleCLIOptions.SoftRequiredOption("srcFile", "sourceFile", true, "The NetCDF file to read from"));
//		options.addOption(new SimpleCLIOptions.SoftRequiredOption("dstFile", "destinationFile", true, "The soon-to-be-created NetCDF file to write to"));
//
//		options.parse(args);
//
//		if (!options.isHelpRequest()) {
//			//Continue on
//
//			CommandLine cl = options.getCommandLine();
//			options.printEffectiveOptions(true);
//
//			ProcessToAddNCFAggValues pivoter = new ProcessToAddNCFAggValues(
//					new File(cl.getOptionValue("srcFile")),
//					new File(cl.getOptionValue("dstFile"))
//			);
//
//			pivoter.process();
//		}
//	}

	public ProcessToAddNCFAggValues(File inputFile, File outputFile,
			String observedValueName, String observerdValueAbbrName) throws Exception {
		
		this.inputFile = inputFile;
		this.outputFile = outputFile;
		this.observedValueName = observedValueName;
		this.observerdValueAbbrName = observerdValueAbbrName;
	}

	public boolean process() throws IOException, InvalidRangeException {
		long start = System.currentTimeMillis();

		NetcdfFile ncInput = null;
		
		try {
			
			ncInput = NetcdfFile.open(inputFile.getAbsolutePath());
			Variable oVariable = ncInput.findVariable(OBSERVATION_STRUCT_NAME);
			int stationIdLength = ncInput.findDimension("station_id_len").getLength();	//9
//			if (stationIdLength < 9) stationIdLength = 9;

			//
			//Ref
			log.debug("Will read from NetCDF file {}, process aggregate values and write to {}.", inputFile.getAbsolutePath(), outputFile.getAbsolutePath());
			log.debug("Station count: " + ncInput.findDimension("station").getLength());	//91168
//			if (log.isDebugEnabled()) {
				printFileInfo(ncInput);
//			}

			ReadObserationsVisitor vistor = new ReadObserationsVisitor();
			new RaggedIndexArrayStructureObservationTraverser(oVariable, observedValueName).traverse(vistor);
			Map<Integer, List<Float>> observationMap = vistor.getObservationMap();

			log.info("= = Visitor Report = =");
			log.info(
					"Station Count: " + vistor.stationCount
					+ " : TimeCountMin " + vistor.stationTimeCountMin
					+ " : TimeCountMax " + vistor.stationTimeCountMax
					+ " : RecordCount " + vistor.recordCount
			);
			log.info("Read and pivoted the input file in " + (System.currentTimeMillis() - start) + "ms");
			start = System.currentTimeMillis();

			generatePivotFile(observationMap, ncInput, vistor.stationCount, stationIdLength, vistor.stationTimeCountMax);
			log.info("Wrote output file in " + (System.currentTimeMillis() - start) + "ms");

			return true;
			
		} finally {
			if (ncInput != null) ncInput.close();
		}
	}

	/**
	 * Write an output file including original data and the agg values
	 *
	 * @param observationMap
	 * @param ncInput	The original NC Input file (used to copy some header vals
	 * over)
	 * @param stationCount
	 * @param StationIdLength	The length in chars of a station ID (9)
	 * @param timeStepCount	Number of timesteps in the observation data (max
	 * number if it varies)
	 * @throws IOException
	 * @throws InvalidRangeException
	 */
	protected void generatePivotFile(Map<Integer, List<Float>> observationMap,
			NetcdfFile ncInput, int stationCount, int StationIdLength, int timeStepCount) throws IOException, InvalidRangeException {

		NetcdfFileWriter ncWriter = NetcdfFileWriter.createNew(NetcdfFileWriter.Version.netcdf3, outputFile.getAbsolutePath());
		
		try {

			Dimension nStationDim = ncWriter.addDimension(null, "station", stationCount);
			Dimension nStationIdLenDim = ncWriter.addDimension(null, "station_id_len", StationIdLength);
			Dimension nTimeDim = ncWriter.addDimension(null, "time", timeStepCount);

			Variable nStationIdVar = ncWriter.addVariable(null, "station_id", DataType.CHAR, Arrays.asList(nStationDim, nStationIdLenDim));
			nStationIdVar.addAttribute(new Attribute(CF.STANDARD_NAME, CF.STATION_ID));
			nStationIdVar.addAttribute(new Attribute(CF.CF_ROLE, CF.TIMESERIES_ID));

			Variable nTimeVar = ncWriter.addVariable(null, "time", DataType.INT, Arrays.asList(nTimeDim));
			nTimeVar.addAttribute(new Attribute(CF.STANDARD_NAME, "time"));
			nTimeVar.addAttribute(new Attribute(CDM.UNITS, "days since 1950-10-01T00:00:00.000Z"));
			nTimeVar.addAttribute(new Attribute(CF.CALENDAR, "gregorian"));

			Variable nLatVar = ncWriter.addVariable(null, "lat", DataType.FLOAT, Arrays.asList(nStationDim));
			nLatVar.addAttribute(new Attribute(CF.STANDARD_NAME, "latitude"));
			nLatVar.addAttribute(new Attribute(CDM.UNITS, CDM.LAT_UNITS));

			Variable nLonVar = ncWriter.addVariable(null, "lon", DataType.FLOAT, Arrays.asList(nStationDim));
			nLonVar.addAttribute(new Attribute(CF.STANDARD_NAME, "longitude"));
			nLonVar.addAttribute(new Attribute(CDM.UNITS, CDM.LON_UNITS));

			Variable nQAccConVar = ncWriter.addVariable(null, observedValueName, DataType.FLOAT, Arrays.asList(nStationDim, nTimeDim));
			nQAccConVar.addAttribute(new Attribute(CF.COORDINATES, "time lat lon"));
			nQAccConVar.addAttribute(new Attribute(CDM.FILL_VALUE, Float.valueOf(-1f)));

			// STATS!
			Variable nQAccConMeanVar = ncWriter.addVariable(null, observerdValueAbbrName + MEAN_SUFFIX, DataType.FLOAT, Arrays.asList(nStationDim));
			nQAccConMeanVar.addAttribute(new Attribute(CF.COORDINATES, "lat lon"));
			nQAccConMeanVar.addAttribute(new Attribute(CDM.FILL_VALUE, Float.valueOf(-1f)));

			Variable nQAccConMinVar = ncWriter.addVariable(null, observerdValueAbbrName + MIN_SUFFIX, DataType.FLOAT, Arrays.asList(nStationDim));
			nQAccConMinVar.addAttribute(new Attribute(CF.COORDINATES, "lat lon"));
			nQAccConMinVar.addAttribute(new Attribute(CDM.FILL_VALUE, Float.valueOf(-1f)));

			Variable nQAccConMaxVar = ncWriter.addVariable(null, observerdValueAbbrName + MAX_SUFFIX, DataType.FLOAT, Arrays.asList(nStationDim));
			nQAccConMaxVar.addAttribute(new Attribute(CF.COORDINATES, "lat lon"));
			nQAccConMaxVar.addAttribute(new Attribute(CDM.FILL_VALUE, Float.valueOf(-1f)));

			Variable nQAccConCountVar = ncWriter.addVariable(null, observerdValueAbbrName + COUNT_SUFFIX, DataType.FLOAT, Arrays.asList(nStationDim));
			nQAccConCountVar.addAttribute(new Attribute(CF.COORDINATES, "lat lon"));
			nQAccConCountVar.addAttribute(new Attribute(CDM.FILL_VALUE, Float.valueOf(-1f)));

			Variable nQAccConCountMedian = ncWriter.addVariable(null, observerdValueAbbrName + MEDIAN_SUFFIX, DataType.FLOAT, Arrays.asList(nStationDim));
			nQAccConCountMedian.addAttribute(new Attribute(CF.COORDINATES, "lat lon"));
			nQAccConCountMedian.addAttribute(new Attribute(CDM.FILL_VALUE, Float.valueOf(-1f)));

			Variable[] QAccConDecileUpperBound = new Variable[9];
			for (int i = 0; i < 9; i++) {
				QAccConDecileUpperBound[i] = ncWriter.addVariable(null, String.format(observerdValueAbbrName + DECILE_SUFFIX + "%d", i + 1), DataType.FLOAT, Arrays.asList(nStationDim));
				QAccConDecileUpperBound[i].addAttribute(new Attribute(CF.COORDINATES, "lat lon"));
				QAccConDecileUpperBound[i].addAttribute(new Attribute(CDM.FILL_VALUE, Float.valueOf(-1f)));
			}

			Variable nQaccConDecile = ncWriter.addVariable(null, observerdValueAbbrName + DECILE_SUFFIX, DataType.FLOAT, Arrays.asList(nStationDim, nTimeDim));
			nQaccConDecile.addAttribute(new Attribute(CF.COORDINATES, "time lat lon"));
			nQaccConDecile.addAttribute(new Attribute(CDM.FILL_VALUE, Float.valueOf(-1f)));

			ncWriter.addGroupAttribute(null, new Attribute(CDM.CONVENTIONS, "CF-1.6"));
			ncWriter.addGroupAttribute(null, new Attribute(CF.FEATURE_TYPE, "timeSeries"));

			ncWriter.setFill(true);

			ncWriter.create();

			ncWriter.write(nStationIdVar, ncInput.findVariable("station_id").read());
			ncWriter.write(nLonVar, ncInput.findVariable("lat").read());
			ncWriter.write(nLatVar, ncInput.findVariable("lon").read());

			Array nTimeArray = Array.factory(DataType.INT, new int[]{timeStepCount});
			DateTime baseDateTime = DateTime.parse("1950-10-01T00:00:00.000Z");
			DateTime currentDateTime = baseDateTime;
			for (int tIndex = 0; tIndex < timeStepCount; ++tIndex) {
				nTimeArray.setInt(tIndex, Days.daysBetween(baseDateTime, currentDateTime).getDays());
				currentDateTime = currentDateTime.plusMonths(1);
			}
			ncWriter.write(nTimeVar, nTimeArray);

			for (Map.Entry<Integer, List<Float>> entry : observationMap.entrySet()) {
				int stationIndex = entry.getKey();
				List<Float> values = entry.getValue();
				int timeMissing = timeStepCount - values.size();

				Statistics1D statistics = new Statistics1D();
				Array valueArray = Array.factory(DataType.FLOAT, new int[]{1, timeStepCount - timeMissing});
				int valueArrayIndex = 0;
				for (float value : values) {
					valueArray.setFloat(valueArrayIndex++, value);
					statistics.accumulate(value);
				}
				ncWriter.write(nQAccConVar, new int[]{stationIndex, timeMissing}, valueArray);

				ncWriter.write(nQAccConMeanVar, new int[]{stationIndex}, Array.factory(new double[]{statistics.getMean()}));
				ncWriter.write(nQAccConMinVar, new int[]{stationIndex}, Array.factory(new double[]{statistics.getMinimum()}));
				ncWriter.write(nQAccConMaxVar, new int[]{stationIndex}, Array.factory(new double[]{statistics.getMaximum()}));
				ncWriter.write(nQAccConCountVar, new int[]{stationIndex}, Array.factory(new double[]{statistics.getCount()}));

				List<Float> sorted = new ArrayList<Float>(values);
				Collections.sort(sorted);
				ncWriter.write(nQAccConCountMedian, new int[]{stationIndex}, Array.factory(new double[]{sorted.get(sorted.size() / 2)}));
				float[] decileBounds = new float[11];
				decileBounds[0] = (float) statistics.getMinimum();
				for (int i = 0; i < 9; ++i) {
					decileBounds[i + 1] = sorted.get(sorted.size() * (i + 1) / 10);
					ncWriter.write(QAccConDecileUpperBound[i], new int[]{stationIndex}, Array.factory(new double[]{decileBounds[i + 1]}));
				}
				decileBounds[10] = (float) statistics.getMaximum();

				Array decileArray = Array.factory(DataType.FLOAT, new int[]{1, timeStepCount - timeMissing});
				int decileArrayIndex = 0;
				for (float value : values) {
					float decile = Float.NaN;
					if (decileBounds[0] != decileBounds[10]) {
						for (int i = 0; i < 10 && Float.isNaN(decile); i++) {
							if (value >= decileBounds[i] && value <= decileBounds[i + 1]) {
								decile = (float) i + ((value - decileBounds[i]) / (decileBounds[i + 1] - decileBounds[i]));
								decile /= 10f; // pseudo percentile
							}
						}
					}
					if (decile != decile) {
						decile = -1;
					}
					decileArray.setFloat(decileArrayIndex++, decile);
				}
				ncWriter.write(nQaccConDecile, new int[]{stationIndex, timeMissing}, decileArray);

			}
			
		} finally {
			ncWriter.close();
		}

		
	}

	public static class ReadObserationsVisitor extends AbstractObservationVisitor {

		private int stationIndexLast;
		private int stationCount;

		private int stationTimeCountMin = Integer.MAX_VALUE;
		private int stationTimeCountMax = Integer.MIN_VALUE;
		private ArrayList<Float> stationTimeSeries = null;

		private int recordCount;

		private Map<Integer, List<Float>> observationMap = new TreeMap<Integer, List<Float>>();

		ObservationVisitor delgate = new PrimingVisitor();

		@Override
		public void observation(int stationIndex, int timeIndex, float value) {
			delgate.observation(stationIndex, timeIndex, value);
		}

		@Override
		public void finish() {
			delgate.finish();
		}

		public class PrimingVisitor extends AbstractObservationVisitor {

			@Override
			public void observation(int stationIndex, int timeIndex, float value) {
				initStationData(stationIndex);
				recordCount++;
				delgate = new CountingVisitor();
			}
		}

		public class CountingVisitor extends AbstractObservationVisitor {

			@Override
			public void observation(int stationIndex, int timeIndex, float value) {
				if (stationIndexLast != stationIndex) {
					processStationData();
					initStationData(stationIndex);
				}
				stationTimeSeries.add(value);
				recordCount++;
			}

			@Override
			public void finish() {
				processStationData();
			}
		}

		private void processStationData() {
			stationTimeSeries.trimToSize();
			observationMap.put(stationIndexLast, stationTimeSeries);
			int stationTimeCount = stationTimeSeries.size();
			if (stationTimeCount < stationTimeCountMin) {
				stationTimeCountMin = stationTimeCount;
			}
			if (stationTimeCount > stationTimeCountMax) {
				stationTimeCountMax = stationTimeCount;
			}
		}

		private void initStationData(int stationIndex) {
			stationIndexLast = stationIndex;
			stationCount++;
			stationTimeSeries = new ArrayList<Float>();
		}

		Map<Integer, List<Float>> getObservationMap() {
			return observationMap;
		}
	}

	public static void printFileInfo(NetcdfFile ncFile) {
		ncFile.findDimension("station").getLength();	//91168
		for (Variable v : ncFile.getVariables()) {
			System.out.println("+++++++++++++++++++++++++++++++++++++++++++++++++++");
			System.out.println("var name: " + v.getShortName());
			System.out.println("     element size: " + v.getElementSize());
			System.out.println("     Attributes: ");
			printAttributes(v.getAttributes());

		}

		for (Dimension d : ncFile.getDimensions()) {
			System.out.println("+++++++++++++++++++++++++++++++++++++++++++++++++++");
			System.out.println("dim name: " + d.getShortName());
			System.out.println("     len: " + d.getLength());
			System.out.println("     group: " + d.getGroup().getShortName());
			System.out.println("     group attribs: -------------------------------");
			printAttributes(d.getGroup().getAttributes());
		}
	}

	public static void printAttributes(List<Attribute> attribs) {
		for (Attribute a : attribs) {
			System.out.println("         name: " + a.getShortName() + " length: " + a.getLength());
			if (a.isString()) {
				System.out.println("         str val: " + a.getStringValue());
			} else if (a.isArray()) {
				System.out.println("         Its an array!");
			} else {
				System.out.println("         str val: " + a.getNumericValue());
			}

		}
	}
}
