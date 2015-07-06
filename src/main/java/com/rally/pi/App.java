package com.rally.pi;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
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
public class App 
{
	private RallyRestApi api;
	
	
	private class Commander {

		@Parameter(names= "-project", description = " name of the project on which you want to run the program")
		private String project;
		
		@Parameter(names= "-key", description = "OAuth API key that you want to use to connect to Rally")
		private String apiKey;
			
	}
	
	App() throws URISyntaxException {
	}
	
	private void doUpdate(String apiKey, String projectName) throws IOException, URISyntaxException {
		this.api = new RallyRestApi(new URI("https://rally1.rallydev.com"), apiKey);// ,"_mFhSffp3Rv2EmTjVtzvIwWdfOcN0cEuEoHL0QDnKEI0");		
		System.out.println("Version: " + this.api.getWsapiVersion());
		Map<String,Integer> preliminaryEstimates = new HashMap<String, Integer>();
		String projectOID = null;
		QueryRequest peRequest = new QueryRequest("preliminaryestimate");
				
		if (projectName != null) {
			QueryRequest request = new QueryRequest("project");
			request.setQueryFilter(new QueryFilter("Name", "=", projectName));
			request.setFetch(new Fetch("ObjectID"));
	        QueryResponse response = api.query(request);
	        if (response.wasSuccessful() && response.getResults().size() > 0) {
	        	projectOID = response.getResults().get(0).getAsJsonObject().get("ObjectID").getAsString();
	        } else {
	        	System.out.println("Could not find the project " + projectName);
	        	for (String error: response.getErrors()) {
	        		System.out.println("Error: " + error);
	        	}
	        	return;
	        }

		}
		
        QueryResponse peResponse = api.query(peRequest);
		if (peResponse.wasSuccessful()) {
            for (JsonElement peResult: peResponse.getResults()) { 
            	JsonObject pe = peResult.getAsJsonObject();
            	preliminaryEstimates.put(pe.get("Name").getAsString(), pe.get("Value").getAsInt());
            }			
		}
		
		QueryRequest epicsRequest = new QueryRequest("PortfolioItem/Epic");
		if (projectOID  != null) {
			epicsRequest.setProject("/project/"+projectOID);
		}
		epicsRequest.setScopedDown(true);		
		epicsRequest.setQueryFilter(new QueryFilter("Children.ObjectID", "!=", null));		
		epicsRequest.setFetch(new Fetch("Children", "c_CalculatedFeaturePoints", "ObjectID", "FormattedID", "Name", "_ref"));
		System.out.println("Request: " + epicsRequest.toUrl());
        QueryResponse epicsResponse = api.query(epicsRequest);
        Set<JsonObject> uniqueEpics = new HashSet<JsonObject>();
        if (epicsResponse.wasSuccessful()){
            for (JsonElement epicResult: epicsResponse.getResults()) {        			
            	JsonObject epic = epicResult.getAsJsonObject(); // this needs to be done because wsapi returns duplicates.
            	uniqueEpics.add(epic);
    		}
            for (JsonObject epic : uniqueEpics) {
            	int featureRollup = getFeatureSizeRollup(epic, preliminaryEstimates);
            	updateCalculatedFeaturePoints(epic, featureRollup);
            }
		} else {
        	System.out.println("Could not run the query ");
        	for (String error: epicsResponse.getErrors()) {
        		System.out.println("Error: " + error);
        	}
        	return;
        }
        		
	}

	private void updateCalculatedFeaturePoints(JsonObject epic,
		int calculatedSize) throws IOException {
		int existingEstimate = epic.get("c_CalculatedFeaturePoints").isJsonNull()? 0 : epic.get("c_CalculatedFeaturePoints").getAsInt();		
		if (existingEstimate != calculatedSize) {
			JsonObject featurePoints = new JsonObject();
			featurePoints.addProperty("c_CalculatedFeaturePoints", calculatedSize);
			UpdateRequest update = new UpdateRequest(epic.get("_ref").getAsString(), featurePoints);
			UpdateResponse response = api.update(update);
			if (response.wasSuccessful()) {
				System.out.println("Successfully updated: " + epic.get("FormattedID").getAsString() + ". FeaturePoints: " + calculatedSize);
			} else {
				for (String error: response.getErrors()) {
					System.out.println(error);
				}
			}
		} else {
			System.out.println("No change in: " + epic.get("FormattedID").getAsString() + ". Original Estimate: " + existingEstimate  + ". FeaturePoints: " + calculatedSize);			
		}
	}

	private int getFeatureSizeRollup(JsonObject epic, Map<String, Integer> preliminaryEstimates) throws IOException {
		int featureSizeRollup = 0;
		JsonObject featuresInfo;		
		featuresInfo = epic.getAsJsonObject("Children");
		QueryRequest featureRequest = new QueryRequest(featuresInfo);
		featureRequest.setFetch(new Fetch("FormattedID", "PreliminaryEstimate", "c_CalculatedFeaturePoints", "Name"));
		QueryResponse featureResponse = api.query(featureRequest);
		if (featureResponse.wasSuccessful()) {		    
			int prelimEstimate = 0;
			for (JsonElement result : featureResponse.getResults()) {
		        JsonObject feature = result.getAsJsonObject();
		        if ( !feature.get("PreliminaryEstimate").isJsonNull() ) {
		        	prelimEstimate =  preliminaryEstimates.get(feature.getAsJsonObject("PreliminaryEstimate").get("Name").getAsString());
		        	updateCalculatedFeaturePoints(feature, prelimEstimate);
		        	featureSizeRollup += prelimEstimate;
		        }
		    }
		}else {
        	System.out.println("Could not run the query ");
        	for (String error: featureResponse.getErrors()) {
        		System.out.println("Error: " + error);
        	}
        	return 0;
        }
					
		return featureSizeRollup;
	}
	
	
    public static void main( String[] args ) throws URISyntaxException, IOException {    	
    	App app = new App();
    	Commander com = app.new Commander();
    	new JCommander(com, args);    	
    	app.doUpdate(com.apiKey,  com.project);
    }
}
