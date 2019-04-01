package com.axway.apim.actions.tasks;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.ParseException;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.util.EntityUtils;

import com.axway.apim.actions.rest.POSTRequest;
import com.axway.apim.actions.rest.RestAPICall;
import com.axway.apim.actions.rest.Transaction;
import com.axway.apim.lib.AppException;
import com.axway.apim.lib.ErrorCode;
import com.axway.apim.swagger.api.APIImportDefinition;
import com.axway.apim.swagger.api.IAPIDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ImportBackendAPI extends AbstractAPIMTask implements IResponseParser {

	public ImportBackendAPI(IAPIDefinition desiredState, IAPIDefinition actualState) {
		super(desiredState, actualState);
		// TODO Auto-generated constructor stub
	}

	public void execute() throws AppException {
		LOG.info("Importing backend API (Swagger/WSDL Import)");
		try {
			if (desiredState.getWsdlURL()!=null) {
				importFromWSDL();
			} else {
				importFromSwagger();
			}
		} catch (Exception e) {
			throw new AppException("Can't import definition / Create BE-API.", ErrorCode.CANT_CREATE_BE_API, e);
		}
	}

	private void importFromWSDL() throws URISyntaxException, AppException, IOException {
		URI uri;
		HttpEntity entity = new StringEntity("");
		uri = new URIBuilder(cmd.getAPIManagerURL()).setPath(RestAPICall.API_VERSION+"/apirepo/importFromUrl/")
				.setParameter("organizationId", this.desiredState.getOrgId())
				.setParameter("type", "wsdl")
				.setParameter("url", this.desiredState.getWsdlURL())
				.setParameter("name", this.desiredState.getName()).build();
		RestAPICall importWSDL = new POSTRequest(entity, uri, this);
		importWSDL.setContentType("application/x-www-form-urlencoded");
		HttpResponse httpResponse = importWSDL.execute();
		String response = httpResponse.getEntity().getContent().toString();
		int statusCode = httpResponse.getStatusLine().getStatusCode();
		if(statusCode != 201){
			LOG.error("Received Status-Code: " +statusCode+ ", Response: " + response);
			throw new AppException("Can't import WSDL from URL / Create BE-API.", ErrorCode.CANT_CREATE_BE_API);
		}
	}

	private void importFromSwagger() throws URISyntaxException, AppException, IOException {
		URI uri;
		HttpEntity entity;
		uri = new URIBuilder(cmd.getAPIManagerURL()).setPath(RestAPICall.API_VERSION+"/apirepo/import/")
				.setParameter("field", "name").setParameter("op", "eq").setParameter("value", "API Development").build();
		
		entity = MultipartEntityBuilder.create()
				.addTextBody("name", this.desiredState.getName())
				.addTextBody("type", "swagger")
				.addBinaryBody("file", ((APIImportDefinition)this.desiredState).getSwaggerDefinition().getSwaggerContent(), ContentType.create("application/octet-stream"), "filename")
				.addTextBody("fileName", "XYZ").addTextBody("organizationId", this.desiredState.getOrgId())
				.addTextBody("integral", "false").addTextBody("uploadType", "html5").build();
		RestAPICall importSwagger = new POSTRequest(entity, uri, this);
		importSwagger.setContentType(null);
		HttpResponse httpResponse = importSwagger.execute();
		String response = httpResponse.getEntity().getContent().toString();
		int statusCode = httpResponse.getStatusLine().getStatusCode();
		if(statusCode != 201){
			LOG.error("Received Status-Code: " +statusCode+ ", Response: " + response);
			throw new AppException("Can't import Swagger-definition / Create BE-API.", ErrorCode.CANT_CREATE_BE_API);
		}
	}
	
	@Override
	public JsonNode parseResponse(HttpResponse httpResponse) throws AppException {
		ObjectMapper objectMapper = new ObjectMapper();
		String response = null;
		try {
			response = EntityUtils.toString(httpResponse.getEntity());
			JsonNode jsonNode = objectMapper.readTree(response);
			String backendAPIId = jsonNode.findPath("id").asText();
			Transaction.getInstance().put("backendAPIId", backendAPIId);
			return null;
		} catch (IOException e) {
			throw new AppException("Cannot parse JSON-Payload after create BE-API.", ErrorCode.CANT_CREATE_BE_API, e);
		}
	}

}
