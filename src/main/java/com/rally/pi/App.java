package com.rally.pi;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rallydev.rest.RallyRestApi;
import com.rallydev.rest.request.QueryRequest;
import com.rallydev.rest.request.UpdateRequest;
import com.rallydev.rest.response.QueryResponse;
import com.rallydev.rest.response.UpdateResponse;
import com.rallydev.rest.util.Fetch;
import com.rallydev.rest.util.QueryFilter;

/**
 * Hello world!
 *
 */
public class App {
	private RallyRestApi api;
	private static final Logger LOGGER = Logger.getLogger("FeatureRollUpLogger");

	private class Commander {

		@Parameter(names = "-project", description = " name of the project on which you want to run the program", required = true)
		private String project;

		@Parameter(names = "-key", description = "OAuth API key that you want to use to connect to Rally", required = true)
		private String apiKey;

		@Parameter(names = "-pilevel", description = "The level of portfolio item do you want to roll up to", required = true)
		private String piLevel;
	}

	App() throws URISyntaxException {
	}

	private void doUpdate(String apiKey, String projectName, String piLevel) throws IOException,
			URISyntaxException {
		this.api = new RallyRestApi(new URI("https://rally1.rallydev.com"), apiKey);
		LOGGER.severe("Version: " + this.api.getWsapiVersion());
		Map<String, Integer> preliminaryEstimates = new HashMap<String, Integer>();
		String projectOID = null;
		QueryRequest peRequest = new QueryRequest("preliminaryestimate");

		if (projectName != null) {
			QueryRequest request = new QueryRequest("project");
			request.setQueryFilter(new QueryFilter("Name", "=", projectName));
			request.setFetch(new Fetch("ObjectID"));
			QueryResponse response = api.query(request);
			if (response.wasSuccessful() && response.getResults().size() > 0) {
				projectOID = response.getResults().get(0).getAsJsonObject().get("ObjectID")
						.getAsString();
			} else {
				LOGGER.severe("Could not find the project " + projectName);
				for (String error : response.getErrors()) {
					LOGGER.severe("Error: " + error);
				}
				return;
			}

		}

		QueryResponse peResponse = api.query(peRequest);
		if (peResponse.wasSuccessful()) {
			for (JsonElement peResult : peResponse.getResults()) {
				JsonObject pe = peResult.getAsJsonObject();
				preliminaryEstimates.put(pe.get("Name").getAsString(), pe.get("Value").getAsInt());
			}
		}

		doRollups(preliminaryEstimates, projectOID, piLevel);

	}

	private void doRollups(Map<String, Integer> preliminaryEstimates, String projectOID,
			String piLevel) throws IOException {
		QueryRequest piParentRequest = new QueryRequest("PortfolioItem/" + piLevel);
		if (projectOID != null) {
			piParentRequest.setProject("/project/" + projectOID);
		}
		piParentRequest.setScopedDown(true);
		piParentRequest.setQueryFilter(new QueryFilter("Children.ObjectID", "!=", null));
		piParentRequest.setFetch(new Fetch("Children", "c_CalculatedFeaturePoints", "ObjectID",
				"FormattedID", "Name", "_ref"));
		LOGGER.info("Request: " + piParentRequest.toUrl());
		QueryResponse piParentResponse = api.query(piParentRequest);
		Set<JsonObject> uniqueParents = new HashSet<JsonObject>();
		if (piParentResponse.wasSuccessful()) {
			for (JsonElement piParentResult : piParentResponse.getResults()) {
				// this needs to be done because WSAPI returns duplicates
				JsonObject piParent = piParentResult.getAsJsonObject();
				uniqueParents.add(piParent);
			}

			int rollupPoints = 0;

			for (JsonObject parentPI : uniqueParents) {
				if (piLevel.equals("Epic")) {
					rollupPoints = getSizeRollupForFeatures(parentPI, preliminaryEstimates);
				} else {
					rollupPoints = getSizeRollupForEpics(parentPI, preliminaryEstimates);
				}

				updateCalculatedRollupPoints(parentPI, rollupPoints);
			}
		} else {
			LOGGER.severe("Could not run the query ");
			for (String error : piParentResponse.getErrors()) {
				LOGGER.severe("Error: " + error);
			}
			return;
		}
	}

	private int getSizeRollupForEpics(JsonObject parentPI, Map<String, Integer> preliminaryEstimates)
			throws IOException {
		int epicRollUp = 0;
		JsonObject epicsInfo = parentPI.getAsJsonObject("Children");
		QueryRequest epicRequest = new QueryRequest(epicsInfo);
		epicRequest.setFetch(new Fetch("FormattedID", "c_CalculatedFeaturePoints", "Name"));
		QueryResponse epicResponse = api.query(epicRequest);
		if (epicResponse.wasSuccessful()) {
			for (JsonElement result : epicResponse.getResults()) {
				JsonObject epic = result.getAsJsonObject();
				if (!epic.get("c_CalculatedFeaturePoints").isJsonNull()) {
					epicRollUp += epic.getAsJsonObject("c_CalculatedFeaturePoints").getAsInt();
				}
			}
		} else {
			LOGGER.severe("Could not run the query ");
			for (String error : epicResponse.getErrors()) {
				LOGGER.severe("Error: " + error);
			}
			return 0;
		}

		return epicRollUp;
	}

	private int getSizeRollupForFeatures(JsonObject parent,
			Map<String, Integer> preliminaryEstimates) throws IOException {
		int featureSizeRollup = 0;
		JsonObject featuresInfo;
		featuresInfo = parent.getAsJsonObject("Children");
		QueryRequest featureRequest = new QueryRequest(featuresInfo);
		featureRequest.setFetch(new Fetch("FormattedID", "PreliminaryEstimate",
				"c_CalculatedFeaturePoints", "Name"));
		QueryResponse featureResponse = api.query(featureRequest);
		if (featureResponse.wasSuccessful()) {
			int prelimEstimate = 0;
			for (JsonElement result : featureResponse.getResults()) {
				JsonObject feature = result.getAsJsonObject();
				if (!feature.get("PreliminaryEstimate").isJsonNull()) {
					prelimEstimate = preliminaryEstimates.get(feature
							.getAsJsonObject("PreliminaryEstimate").get("Name").getAsString());
					updateCalculatedRollupPoints(feature, prelimEstimate);
					featureSizeRollup += prelimEstimate;
				}
			}
		} else {
			LOGGER.severe("Could not run the query ");
			for (String error : featureResponse.getErrors()) {
				LOGGER.severe("Error: " + error);
			}
			return 0;
		}

		return featureSizeRollup;
	}

	private void updateCalculatedRollupPoints(JsonObject epic, int calculatedSize)
			throws IOException {
		int existingEstimate = epic.get("c_CalculatedFeaturePoints").isJsonNull() ? 0 : epic.get(
				"c_CalculatedFeaturePoints").getAsInt();
		if (existingEstimate != calculatedSize) {
			JsonObject featurePoints = new JsonObject();
			featurePoints.addProperty("c_CalculatedFeaturePoints", calculatedSize);
			UpdateRequest update = new UpdateRequest(epic.get("_ref").getAsString(), featurePoints);
			UpdateResponse response = api.update(update);
			if (response.wasSuccessful()) {
				LOGGER.info("Successfully updated: " + epic.get("FormattedID").getAsString()
						+ ". FeaturePoints: " + calculatedSize);
			} else {
				for (String error : response.getErrors()) {
					LOGGER.severe(error);
				}
			}
		} else {
			LOGGER.severe("No change in: " + epic.get("FormattedID").getAsString()
					+ ". Original Estimate: " + existingEstimate + ". FeaturePoints: "
					+ calculatedSize);
		}
	}

	public static void main(String[] args) throws URISyntaxException, IOException {
		App app = new App();

		Commander com = app.new Commander();
		try {
			new JCommander(com, args);
			if (!com.piLevel.equals("Epic") && !com.piLevel.equals("MultiProgramEpic")) {
				LOGGER.severe("Supported pilevel values are Epic or  MultiProgramEpic");
				return;
			}
		} catch (ParameterException ex) {
			LOGGER.severe("Usage: java -jar FeaturePointsRollup-1.0-executable.jar -key <Rally API Key> -project <Rally Project> -pilevel <Epic|MultiProgramEpic>\n\t"
					+ " To find out how to generate API Key, visit https://help.rallydev.com/rally-application-manager");
			return;
		}
		app.doUpdate(com.apiKey, com.project, com.piLevel);
	}
}