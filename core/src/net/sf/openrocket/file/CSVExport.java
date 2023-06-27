package net.sf.openrocket.file;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.sf.openrocket.logging.Warning;
import net.sf.openrocket.logging.WarningSet;
import net.sf.openrocket.document.Simulation;
import net.sf.openrocket.rocketcomponent.AxialStage;
import net.sf.openrocket.simulation.FlightData;
import net.sf.openrocket.simulation.FlightDataBranch;
import net.sf.openrocket.simulation.FlightDataType;
import net.sf.openrocket.simulation.FlightEvent;
import net.sf.openrocket.unit.Unit;
import net.sf.openrocket.util.TextUtil;

public class CSVExport {

	/**
	 * Exports the specified flight data branch into a CSV file.
	 *
	 * @param stream				the stream to write to.
	 * @param simulation			the simulation being exported.
	 * @param branch				the branch to export.
	 * @param fields				the fields to export (in appropriate order).
	 * @param units					the units of the fields.
	 * @param fieldSeparator		the field separator string.
	 * @param decimalPlaces			the number of decimal places to use.
	 * @param isExponentialNotation	whether to use exponential notation.
	 * @param commentStarter		the comment starting character(s).
	 * @param simulationComments	whether to output general simulation comments.
	 * @param fieldComments			whether to output field comments.
	 * @param eventComments			whether to output comments for the flight events.
	 * @throws IOException 			if an I/O exception occurs.
	 */
	public static void exportCSV(OutputStream stream, Simulation simulation,
			FlightDataBranch branch, FlightDataType[] fields, Unit[] units,
			String fieldSeparator, int decimalPlaces, boolean isExponentialNotation,
			String commentStarter, boolean simulationComments, boolean fieldComments,
			boolean eventComments) throws IOException {

		if (fields.length != units.length) {
			throw new IllegalArgumentException("fields and units lengths must be equal " +
					"(" + fields.length + " vs " + units.length + ")");
		}


		PrintWriter writer = null;
		try {

			writer = new PrintWriter(stream, false, StandardCharsets.UTF_8);

			// Write the initial comments
			if (simulationComments) {
				writeSimulationComments(writer, simulation, branch, fields, commentStarter);
			}

			if (simulationComments && fieldComments) {
				writer.println(commentStarter);
			}

			if (fieldComments) {
				writer.print(commentStarter + " ");
				for (int i = 0; i < fields.length; i++) {
					writer.print(fields[i].getName() + " (" + units[i].getUnit() + ")");
					if (i < fields.length - 1) {
						writer.print(fieldSeparator);
					}
				}
				writer.println();
			}

			writeData(writer, simulation, branch, fields, units, fieldSeparator, decimalPlaces, isExponentialNotation,
					eventComments, commentStarter);


		} finally {
			if (writer != null) {
				try {
					writer.close();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}

	private static void writeData(PrintWriter writer, Simulation simulation, FlightDataBranch branch,
			FlightDataType[] fields, Unit[] units, String fieldSeparator, int decimalPlaces, boolean isExponentialNotation,
			boolean eventComments, String commentStarter) {
		final FlightData data = simulation.getSimulatedData();
		final int stageNr = data.getStageNr(branch);
		AxialStage stage = simulation.getRocket().getStage(stageNr);

		// Time variable
		List<Double> time = branch.get(FlightDataType.TYPE_TIME);

		// Extra stages don't contain all the simulation data. Instead, the data is stored in the sustainer stage
		// (since the data is the same for all stages until there is separation).
		// For CSV export however, we want to copy the data from the sustainer stage to the extra stage.
		boolean copySustainerData = stageNr > 0 && time != null && time.get(0) > 0;
		FlightDataBranch sustainerBranch = null;
		Double firstBranchTime = null;
		int lastSustainerIndex = 0;
		if (copySustainerData) {
			firstBranchTime = time.get(0);
			sustainerBranch = data.getBranch(0);
			List<Double> sustainerTime = sustainerBranch.get(FlightDataType.TYPE_TIME);

			if (sustainerTime != null) {
				for (int i = 0; i < sustainerTime.size(); i++) {
					if (sustainerTime.get(i) >= firstBranchTime) {
						lastSustainerIndex = i - 1;
						break;
					}
				}

				List<Double> timeToCopy = sustainerTime.subList(0, lastSustainerIndex + 1);
				time.addAll(0, timeToCopy);
			}
		}

		// Number of data points
		int n = time != null ? time.size() : branch.getLength();

		// Flight events in occurrence order
		List<FlightEvent> events = branch.getEvents();
		if (copySustainerData) {
			List<FlightEvent> sustainerEvents = sustainerBranch.getEvents();

			// Copy all events from the sustainer that belong to this stage
			for (FlightEvent event : sustainerEvents) {
				// Stage separation is present both in the sustainer data and extra stage data (don't really know why...)
				if (event.getType() == FlightEvent.Type.STAGE_SEPARATION) {
					continue;
				}
				if (stage == event.getSource() || stage.containsChild(event.getSource())) {
					events.add(event);
				}
			}
		}
		Collections.sort(events);
		int eventPosition = 0;

		// List of field values
		List<List<Double>> fieldValues = new ArrayList<>();
		for (FlightDataType t : fields) {
			List<Double> values = branch.get(t);
			if (copySustainerData) {
				List<Double> sustainerValues = sustainerBranch.get(t);
				List<Double> valuesToCopy = sustainerValues.subList(0, lastSustainerIndex + 1);
				values.addAll(0, valuesToCopy);
			}
			fieldValues.add(values);
		}

		// If time information is not available, print events at beginning of file
		if (eventComments && time == null) {
			for (FlightEvent e : events) {
				printEvent(writer, e, commentStarter);
			}
			eventPosition = events.size();
		}


		// Loop over all data points
		for (int pos = 0; pos < n; pos++) {

			// Check for events to store
			if (eventComments && time != null) {
				double t = time.get(pos);

				while ((eventPosition < events.size()) &&
						(events.get(eventPosition).getTime() <= t)) {
					printEvent(writer, events.get(eventPosition), commentStarter);
					eventPosition++;
				}
			}

			// Store CSV line
			for (int i = 0; i < fields.length; i++) {
				double value = fieldValues.get(i).get(pos);
				writer.print(TextUtil.doubleToString(units[i].toUnit(value), decimalPlaces, isExponentialNotation));

				if (i < fields.length - 1) {
					writer.print(fieldSeparator);
				}
			}
			writer.println();

		}

		// Store any remaining events
		if (eventComments && time != null) {
			while (eventPosition < events.size()) {
				printEvent(writer, events.get(eventPosition), commentStarter);
				eventPosition++;
			}
		}

	}


	private static void printEvent(PrintWriter writer, FlightEvent e,
			String commentStarter) {
		writer.println(commentStarter + " Event " + e.getType().name() +
				" occurred at t=" + TextUtil.doubleToString(e.getTime()) + " seconds");
	}

	private static void writeSimulationComments(PrintWriter writer,
			Simulation simulation, FlightDataBranch branch, FlightDataType[] fields,
			String commentStarter) {

		String line;

		line = simulation.getName();

		FlightData data = simulation.getSimulatedData();

		switch (simulation.getStatus()) {
		case UPTODATE:
			line += " (Up to date)";
			break;

		case LOADED:
			line += " (Data loaded from a file)";
			break;

		case OUTDATED:
			line += " (Out of date)";
			break;

		case EXTERNAL:
			line += " (Imported data)";
			break;

		case NOT_SIMULATED:
			line += " (Not simulated yet)";
			break;
		}

		writer.println(commentStarter + " " + line);


		writer.println(commentStarter + " " + branch.getLength() + " data points written for "
				+ fields.length + " variables.");


		if (data == null) {
			writer.println(commentStarter + " No simulation data available.");
			return;
		}
		WarningSet warnings = data.getWarningSet();

		if (!warnings.isEmpty()) {
			writer.println(commentStarter + " Simulation warnings:");
			for (Warning w : warnings) {
				writer.println(commentStarter + "   " + w.toString());
			}
		}
	}

}
