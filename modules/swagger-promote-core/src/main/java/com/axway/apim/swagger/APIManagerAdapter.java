package com.axway.apim.swagger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.axway.apim.actions.CreateNewAPI;
import com.axway.apim.actions.RecreateToUpdateAPI;
import com.axway.apim.actions.UpdateExistingAPI;
import com.axway.apim.actions.rest.APIMHttpClient;
import com.axway.apim.actions.rest.GETRequest;
import com.axway.apim.actions.rest.POSTRequest;
import com.axway.apim.actions.rest.RestAPICall;
import com.axway.apim.actions.rest.Transaction;
import com.axway.apim.lib.AppException;
import com.axway.apim.lib.CommandParameters;
import com.axway.apim.lib.ErrorCode;
import com.axway.apim.lib.ErrorState;
import com.axway.apim.swagger.api.properties.APIDefintion;
import com.axway.apim.swagger.api.properties.apiAccess.APIAccess;
import com.axway.apim.swagger.api.properties.applications.ClientApplication;
import com.axway.apim.swagger.api.properties.cacerts.CaCert;
import com.axway.apim.swagger.api.properties.organization.ApiAccess;
import com.axway.apim.swagger.api.properties.organization.Organization;
import com.axway.apim.swagger.api.properties.outboundprofiles.OutboundProfile;
import com.axway.apim.swagger.api.properties.quota.APIQuota;
import com.axway.apim.swagger.api.properties.quota.QuotaRestriction;
import com.axway.apim.swagger.api.properties.user.User;
import com.axway.apim.swagger.api.state.APIMethod;
import com.axway.apim.swagger.api.state.AbstractAPI;
import com.axway.apim.swagger.api.state.ActualAPI;
import com.axway.apim.swagger.api.state.IAPI;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The APIContract reflects the actual existing API in the API-Manager.
 * 
 *  @author cwiechmann@axway.com
 */
public class APIManagerAdapter {
	
	private static Logger LOG = LoggerFactory.getLogger(APIManagerAdapter.class);
	
	private static APIManagerAdapter instance;
	
	private static String apiManagerVersion = null;
	private static String apiManagerConfig = null;
	
	private static List<Organization> allOrgs = null;
	private static List<ClientApplication> allApps = null;
	private static List<IAPI> allAPIs = null;
	
	private static Map<String, ClientApplication> clientCredentialToAppMap = new HashMap<String, ClientApplication>();
	
	private static Map<String, List<ApiAccess>> orgsApiAccess = new HashMap<String, List<ApiAccess>>();
	
	private boolean enforceBreakingChange = false;
	
	public static APIQuota sytemQuotaConfig = null;
	public static APIQuota applicationQuotaConfig = null;
	private boolean usingOrgAdmin = false;
	private boolean hasAdminAccount = false;
	
	private ErrorState error = ErrorState.getInstance();
	
	public static String CREDENTIAL_TYPE_API_KEY 		= "apikeys";
	public static String CREDENTIAL_TYPE_EXT_CLIENTID	= "extclients";
	public static String CREDENTIAL_TYPE_OAUTH			= "oauth";
	
	public final static String SYSTEM_API_QUOTA 				= "00000000-0000-0000-0000-000000000000";
	public final static String APPLICATION_DEFAULT_QUOTA 		= "00000000-0000-0000-0000-000000000001";
	
	public final static String TYPE_FRONT_END = "proxies";
	public final static String TYPE_BACK_END = "apirepo";
	
	public static synchronized APIManagerAdapter getInstance() throws AppException {
		if (APIManagerAdapter.instance == null) {
			APIManagerAdapter.instance = new APIManagerAdapter ();
		}
		return APIManagerAdapter.instance;
	}
	
	public static synchronized void deleteInstance() throws AppException {
			APIManagerAdapter.instance = null;
			APIManagerAdapter.apiManagerConfig = null;
			APIManagerAdapter.allOrgs = null;
	}
	
	private APIManagerAdapter() throws AppException {
		super();
		Transaction transaction = Transaction.getInstance();
		transaction.beginTransaction();
		APIManagerAdapter.allApps = null; // Reset allApps with every run (relevant for testing, as executed in the same JVM)
		loginToAPIManager(false); // Login with the provided user (might be an Org-Admin)
		loginToAPIManager(true); // Second, login if needed with an admin account
		this.enforceBreakingChange = CommandParameters.getInstance().isEnforceBreakingChange();
	}

	/**
	 * This method is taking in the APIChangeState to decide about the strategy how to 
	 * synchronize the desired API-State into the API-Manager.
	 * @param changeState containing the desired and actual API
	 * @throws AppException is the desired state can't be replicated into the API-Manager.
	 */
	public void applyChanges(APIChangeState changeState) throws AppException {
		if(!this.hasAdminAccount && isAdminAccountNeeded(changeState) ) {
			error.setError("OrgAdmin user only allowed to change/register unpublished APIs.", ErrorCode.NO_ADMIN_ROLE_USER, false);
			throw new AppException("OrgAdmin user only allowed to change/register unpublished APIs.", ErrorCode.NO_ADMIN_ROLE_USER);
		}
		// No existing API found (means: No match for APIPath), creating a complete new
		if(!changeState.getActualAPI().isValid()) {
			// --> CreateNewAPI
			LOG.info("Strategy: No existing API found, creating new!");
			CreateNewAPI createAPI = new CreateNewAPI();
			createAPI.execute(changeState, false);
		// Otherwise an existing API exists
		} else {
			LOG.info("Strategy: Going to update existing API: " + changeState.getActualAPI().getName() +" (Version: "+ changeState.getActualAPI().getVersion() + ")");
			if(!changeState.hasAnyChanges()) {
				LOG.debug("BUT, no changes detected between Import- and API-Manager-API. Exiting now...");
				error.setWarning("No changes detected between Import- and API-Manager-API", ErrorCode.NO_CHANGE, false);
				throw new AppException("No changes detected between Import- and API-Manager-API", ErrorCode.NO_CHANGE);
			}
			LOG.info("Recognized the following changes. Potentially Breaking: " + changeState.getBreakingChanges() + 
					" plus Non-Breaking: " + changeState.getNonBreakingChanges());
			if (changeState.isBreaking()) { // Make sure, breaking changes aren't applied without enforcing it.
				if(!enforceBreakingChange) {
					error.setError("A potentially breaking change can't be applied without enforcing it! Try option: -f true", ErrorCode.BREAKING_CHANGE_DETECTED, false);
					throw new AppException("A potentially breaking change can't be applied without enforcing it! Try option: -f true", ErrorCode.BREAKING_CHANGE_DETECTED);
				}
			}
			
			if(changeState.isUpdateExistingAPI()) { // All changes can be applied to the existing API in current state
				LOG.info("Strategy: Update existing API, as all changes can be applied in current state.");
				UpdateExistingAPI updateAPI = new UpdateExistingAPI();
				updateAPI.execute(changeState);
				return;
			} else { // We have changes, that require a re-creation of the API
				LOG.info("Strategy: Apply breaking changes: "+changeState.getBreakingChanges()+" & and "
						+ "Non-Breaking: "+changeState.getNonBreakingChanges()+", for "+changeState.getActualAPI().getState().toUpperCase()+" API. Recreating it!");
				RecreateToUpdateAPI recreate = new RecreateToUpdateAPI();
				recreate.execute(changeState);
				return;
			}
		}
	}
	
	private boolean isAdminAccountNeeded(APIChangeState changeState) throws AppException {
		if(changeState.getDesiredAPI().getState().equals(IAPI.STATE_UNPUBLISHED) && 
				(!changeState.getActualAPI().isValid() || changeState.getActualAPI().getState().equals(IAPI.STATE_UNPUBLISHED))) {
			return false;
		} else {
			return true;
		}		
	}
	
	public void loginToAPIManager(boolean useAdminClient) throws AppException {
		URI uri;
		CommandParameters cmd = CommandParameters.getInstance();
		if(cmd.ignoreAdminAccount() && useAdminClient) return;
		if(hasAdminAccount && useAdminClient) return; // Already logged in with an Admin-Account.
		try {
			uri = new URIBuilder(cmd.getAPIManagerURL()).setPath(RestAPICall.API_VERSION+"/login").build();
			List<NameValuePair> params = new ArrayList<NameValuePair>();
			String username;
			String password;
			if(useAdminClient) {
				String[] usernamePassword = getAdminUsernamePassword();
				if(usernamePassword==null) return;
				username = usernamePassword[0];
				password = usernamePassword[1];
				LOG.debug("Logging in with Admin-User: '" + username + "'");
			} else {
				username = cmd.getUsername();
				password = cmd.getPassword();
				LOG.debug("Logging in with User: '" + username + "'");
			}
			// This forces to create a client which is re-used based on useAdmin
			APIMHttpClient client = APIMHttpClient.getInstance(useAdminClient);
		    params.add(new BasicNameValuePair("username", username));
		    params.add(new BasicNameValuePair("password", password));
		    POSTRequest loginRequest = new POSTRequest(new UrlEncodedFormEntity(params), uri, null, useAdminClient);
			loginRequest.setContentType(null);
			HttpResponse response = loginRequest.execute();
			int statusCode = response.getStatusLine().getStatusCode();
			if(statusCode == 403 || statusCode == 401){
				LOG.error("Login failed: " +statusCode+ ", Response: " + response);
				throw new AppException("Given user: '"+username+"' can't login.", ErrorCode.API_MANAGER_COMMUNICATION);
			} 
			User user = getCurrentUser(useAdminClient);
			if(user.getRole().equals("admin")) {
				this.hasAdminAccount = true;
				// Also register this client as an Admin-Client 
				APIMHttpClient.addInstance(true, client);
			} else if (user.getRole().equals("oadmin")) {
				this.usingOrgAdmin = true;
			} else {
				error.setError("Not supported user-role: '"+user.getRole()+"'", ErrorCode.API_MANAGER_COMMUNICATION, false);
				throw new AppException("Not supported user-role: "+user.getRole()+"", ErrorCode.API_MANAGER_COMMUNICATION);
			}
		} catch (Exception e) {
			throw new AppException("Can't login to API-Manager", ErrorCode.API_MANAGER_COMMUNICATION, e);
		}
	}
	
	private String[] getAdminUsernamePassword() throws AppException {
		if(CommandParameters.getInstance().getAdminUsername()==null) return null;
		String[] usernamePassword =  {CommandParameters.getInstance().getAdminUsername(), CommandParameters.getInstance().getAdminPassword()};
		return usernamePassword;
	}
	
	public static User getCurrentUser(boolean useAdminClient) throws AppException {
		ObjectMapper mapper = new ObjectMapper();
		URI uri;
		HttpResponse response = null;
		JsonNode jsonResponse = null;
		try {
			uri = new URIBuilder(CommandParameters.getInstance().getAPIManagerURL()).setPath(RestAPICall.API_VERSION+"/currentuser").build();
		    GETRequest currentUserRequest = new GETRequest(uri, null, useAdminClient);
		    response = currentUserRequest.execute();
		    getCsrfToken(response, useAdminClient); // Starting from 7.6.2 SP3 the CSRF token is returned on CurrentUser request
			String currentUser = IOUtils.toString(response.getEntity().getContent(), "UTF-8");
			int statusCode = response.getStatusLine().getStatusCode();
			if( statusCode != 200) {
				throw new AppException("Status-Code: "+statusCode+", Can't get current-user information on response: '" + currentUser + "'", 
						ErrorCode.API_MANAGER_COMMUNICATION);				
			}
			User user = mapper.readValue(currentUser, User.class);
			if(user == null) {
				throw new AppException("Can't get current-user information on response: '" + currentUser + "'", 
						ErrorCode.API_MANAGER_COMMUNICATION);
			}
			return user;
		    
		} catch (Exception e) {
			throw new AppException("Can't get current-user information on response: '" + jsonResponse + "'", 
					ErrorCode.API_MANAGER_COMMUNICATION, e);
		}
	}
	
	private static void getCsrfToken(HttpResponse response, boolean useAdminClient) throws AppException {
		for (Header header : response.getAllHeaders()) {
			if(header.getName().equals("CSRF-Token")) {
				APIMHttpClient.getInstance(useAdminClient).setCsrfToken(header.getValue());
				break;
			}
		}
	}
	
	/**
	 * Checks if the API-Manager has at least given version. If the given requested version is the same or lower 
	 * than the actual API-Manager version, true is returned otherwise false.  
	 * This helps to use features, that are introduced with a certain version or even service-pack.
	 * @param version has the API-Manager this version of higher?
	 * @return false if API-Manager doesn't have this version otherwise true
	 * @throws AppException if the API-Manager version can't be detected
	 */
	public static boolean hasAPIManagerVersion(String version) throws AppException {
		try {
			List<Integer> managerVersion	= getMajorVersions(getApiManagerVersion());
			List<Integer> requestedVersion	= getMajorVersions(version);
			int managerSP	= getServicePackVersion(getApiManagerVersion());
			int requestedSP = getServicePackVersion(version);
			for(int i=0;i<requestedVersion.size(); i++) {
				int managerVer = managerVersion.get(i);
				if(managerVer>requestedVersion.get(i)) return true;
				if(managerVer<requestedVersion.get(i)) return false;
			}
			if(managerSP<requestedSP) return false;
		} catch(Exception e) {
			LOG.warn("Can't parse API-Manager version: '"+apiManagerVersion+"'. Requested version was: '"+version+"'. Returning false!");
			return false;
		}
		return true;
	}
	
	private static int getServicePackVersion(String version) {
		int spNumber = 0;
		if(version.contains(" SP")) {
			try {
				String spVersion = version.substring(version.indexOf(" SP")+3);
				spNumber = Integer.parseInt(spVersion);
			} catch (Exception e){
				LOG.trace("Can't parse service pack version in version: '"+version+"'");
			}
		}
		return spNumber;
	}
	
	private static List<Integer> getMajorVersions(String version) {
		List<Integer> majorNumbers = new ArrayList<Integer>();
		String versionWithoutSP = version;
		if(version.contains(" SP")) {
			versionWithoutSP = version.substring(0, version.indexOf(" SP"));
		}
		try {
			String[] versions = versionWithoutSP.split("\\.");
			for(int i = 0; i<versions.length; i++) {
				majorNumbers.add(Integer.parseInt(versions[i]));
			}
		} catch (Exception e){
			LOG.trace("Can't parse major version numbers in: '"+version+"'");
		}
		return majorNumbers;
	}
	
	/**
	 * Creates the API-Manager API-Representation. Basically the "Actual" state of the API.
	 *  
	 * @param jsonConfiguration the JSON-Configuration which is returned from the API-Manager REST-API (Proxy-Endpoint)
	 * @param desiredAPI for some tasks the desiredAPI is needed (e.g. Custom-Properties)
	 * @return an APIManagerAPI instance, which is flagged either as valid, if the API was found or invalid, if not found!
	 * @throws AppException when the API-Manager API-State can't be created
	 */
	public IAPI getAPIManagerAPI(JsonNode jsonConfiguration, IAPI desiredAPI) throws AppException {
		if(jsonConfiguration == null) {
			IAPI apiManagerAPI = new ActualAPI();
			apiManagerAPI.setValid(false);
			return apiManagerAPI;
		}
		
		ObjectMapper mapper = new ObjectMapper();
		IAPI apiManagerApi;
		try {
			apiManagerApi = mapper.readValue(jsonConfiguration.toString(), ActualAPI.class);
			apiManagerApi.setAPIDefinition(new APIDefintion(getOriginalAPIDefinitionFromAPIM(apiManagerApi.getApiId())));
			if(apiManagerApi.getImage()!=null) {
				apiManagerApi.getImage().setImageContent(getAPIImageFromAPIM(apiManagerApi.getId()));
			}
			apiManagerApi.setValid(true);
			// As the API-Manager REST doesn't provide information about Custom-Properties, we have to setup 
			// the Custom-Properties based on the Import API.
			if(desiredAPI!=null && desiredAPI.getCustomProperties() != null) {
				Map<String, String> customProperties = new LinkedHashMap<String, String>();
				Iterator<String> it = desiredAPI.getCustomProperties().keySet().iterator();
				while(it.hasNext()) {
					String customPropKey = it.next();
					JsonNode value = jsonConfiguration.get(customPropKey);
					String customPropValue = (value == null) ? null : value.asText();
					customProperties.put(customPropKey, customPropValue);
				}
				((AbstractAPI)apiManagerApi).setCustomProperties(customProperties);
			}
			addQuotaConfiguration(apiManagerApi, desiredAPI);
			addClientOrganizations(apiManagerApi, desiredAPI);
			addClientApplications(apiManagerApi, desiredAPI);
			addExistingClientAppQuotas(apiManagerApi.getApplications());
			return apiManagerApi;
		} catch (Exception e) {
			throw new AppException("Can't initialize API-Manager API-State.", ErrorCode.API_MANAGER_COMMUNICATION, e);
		}
	}
	
	private void addClientOrganizations(IAPI apiManagerApi, IAPI desiredAPI) throws AppException {
		if(!hasAdminAccount) return;
		if(desiredAPI.getState().equals(IAPI.STATE_UNPUBLISHED)) {
			LOG.info("Ignoring Client-Organizations, as desired API-State is Unpublished!");
			return;
		}
		if(desiredAPI.getClientOrganizations()==null && desiredAPI.getApplications()==null 
				&& CommandParameters.getInstance().getClientOrgsMode().equals(CommandParameters.MODE_REPLACE)) return;
		List<String> grantedOrgs = new ArrayList<String>();
		List<Organization> allOrgs = getAllOrgs();
		for(Organization org : allOrgs) {
			List<APIAccess> orgAPIAccess = getAPIAccess(org.getId(), "organizations");
			for(APIAccess access : orgAPIAccess) {
				if(access.getApiId().equals(apiManagerApi.getId())) {
					grantedOrgs.add(org.getName());
				}
			}
		}
		apiManagerApi.setClientOrganizations(grantedOrgs);
	}
	
	private void addClientApplications(IAPI apiManagerApi, IAPI desiredAPI) throws AppException {
		if(!hasAdminAccount) return;
		List<ClientApplication> existingClientApps = new ArrayList<ClientApplication>();
		List<ClientApplication> allApps = getAllApps();
		if(APIManagerAdapter.hasAPIManagerVersion("7.7")) {
			existingClientApps = getSubscribedApps(apiManagerApi.getId());
		} else {
			for(ClientApplication app : allApps) {
				List<APIAccess> APIAccess = getAPIAccess(app.getId(), "applications");
				for(APIAccess access : APIAccess) {
					if(access.getApiId().equals(apiManagerApi.getId())) {
						existingClientApps.add(app);
					}
				}
			}
		}
		apiManagerApi.setApplications(existingClientApps);
	}
	private void addExistingClientAppQuotas(List<ClientApplication> existingClientApps) throws AppException {
		if(existingClientApps==null || existingClientApps.size()==0) return; // No apps subscribed to this APIs
		for(ClientApplication app : existingClientApps) {
			APIQuota appQuota = getQuotaFromAPIManager(app.getId());
			app.setAppQuota(appQuota);
		}
	}
	
	public String getMethodNameForId(String apiId, String methodId) throws AppException {
		ObjectMapper mapper = new ObjectMapper();
		String response = null;
		URI uri;
		try {
			uri = new URIBuilder(CommandParameters.getInstance().getAPIManagerURL()).setPath(RestAPICall.API_VERSION + "/proxies/"+apiId+"/operations/"+methodId).build();
			RestAPICall getRequest = new GETRequest(uri, null);
			HttpResponse httpResponse = getRequest.execute();
			response = EntityUtils.toString(httpResponse.getEntity());
			EntityUtils.consume(httpResponse.getEntity());
			LOG.trace("Response: " + response);
			JsonNode operationDetails = mapper.readTree(response);
			if(operationDetails.size()==0) {
				LOG.warn("No operation with ID: "+methodId+" found for API with id: " + apiId);
				return null;
			}
			return operationDetails.get("name").asText();
		} catch (Exception e) {
			LOG.error("Can't load name for operation with id: "+methodId+" for API: "+apiId+". Can't parse response: " + response);
			throw new AppException("Can't load name for operation with id: "+methodId+" for API: "+apiId, ErrorCode.API_MANAGER_COMMUNICATION, e);
		}
	}
	
	public List<APIMethod> getAllMethodsForAPI(String apiId) throws AppException {
		ObjectMapper mapper = new ObjectMapper();
		String response = null;
		URI uri;
		List<APIMethod> apiMethods = new ArrayList<APIMethod>();
		try {
			uri = new URIBuilder(CommandParameters.getInstance().getAPIManagerURL()).setPath(RestAPICall.API_VERSION + "/proxies/"+apiId+"/operations").build();
			RestAPICall getRequest = new GETRequest(uri, null);
			HttpResponse httpResponse = getRequest.execute();
			/*response = EntityUtils.toString(httpResponse.getEntity());
			EntityUtils.consume(httpResponse.getEntity());
			LOG.trace("Response: " + response);
			JsonNode operations = mapper.readTree(response);
			HttpResponse httpResponse = getRequest.execute();*/
			response = EntityUtils.toString(httpResponse.getEntity());
			apiMethods = mapper.readValue(response, new TypeReference<List<APIMethod>>(){});
			return apiMethods;
		} catch (Exception e) {
			LOG.error("Error cant load API-Methods for API: '"+apiId+"' from API-Manager. Can't parse response: " + response);
			throw new AppException("Error cant load API-Methods for API: '"+apiId+"' from API-Manager", ErrorCode.API_MANAGER_COMMUNICATION, e);
		}
	}
	
	public String getMethodIdPerName(String apiId, String methodName) throws AppException {
		List<APIMethod> apiMethods = getAllMethodsForAPI(apiId);
		if(apiMethods.size()==0) {
			LOG.warn("No operations found for API with id: " + apiId);
			return null;
		}
		for(APIMethod method : apiMethods) {
			String operationName = method.getName();
			if(operationName.equals(methodName)) {
				return method.getId();
			}
		}
		LOG.warn("No operation found with name: '"+methodName+"' for API: '"+apiId+"'");
		return null;
	}
	
	public String getOrgId(String orgName) throws AppException {
		return getOrgId(orgName, false);
	}
	/**
	 * The actual Org-ID based on the OrgName. Lazy implementation.
	 * @param orgName the name of the organizations
	 * @param devOrgsOnly limit the query to organization having the development flag enabled
	 * @return the id of the organization
	 * @throws AppException if allOrgs can't be read from the API-Manager
	 */
	public String getOrgId(String orgName, boolean devOrgsOnly) throws AppException {
		if(!this.hasAdminAccount) return null;
		if(allOrgs == null) getAllOrgs();
		for(Organization org : allOrgs) {
			if(devOrgsOnly && org.getDevelopment().equals("false")) continue; // Ignore non-dev orgs
			if(orgName.equals(org.getName())) return org.getId();
		}
		LOG.error("Requested OrgId for unknown orgName: " + orgName);
		return null;
	}
	
	/**
	 * The actual Org-ID based on the OrgName. Lazy implementation.
	 * @param orgId the id of the organizations you want the name for
	 * @return the id of the organization
	 * @throws AppException if allOrgs can't be read from the API-Manager
	 */
	public String getOrgName(String orgId) throws AppException {
		if(allOrgs == null) getAllOrgs();
		for(Organization org : allOrgs) {
			if(orgId.equals(org.getId())) return org.getName();
		}
		LOG.error("Requested OrgName for unknown orgId: " + orgId);
		return null;
	}
	
	/**
	 * The actual App-ID based on the AppName. Lazy implementation.
	 * @param appName the name of the application
	 * @return the application object
	 * @throws AppException if allApps can't be read from API-Manager 
	 */
	public ClientApplication getApplication(String appName) throws AppException {
		if(allApps==null) getAllApps();
		for(ClientApplication app : allApps) {
			LOG.debug("Configured app with name: '"+appName+"' found. ID: '"+app.getId()+"'");
			if(appName.equals(app.getName())) return app;
		}
		LOG.error("Requested AppId for unknown appName: " + appName);
		return null;
	}
	
	/**
	 * The actual App-ID based on the AppName. Lazy implementation.
	 * @param appId unique ID for the application
	 * @return the id of the organization
	 */
	public static ClientApplication getAppForId(String appId) {
		for(ClientApplication app : allApps) {
			if(appId.equals(app.getId())) return app;
		}
		LOG.error("Requested Application for unknown appId: "+appId+" not found.");
		return null;
	}
	
	/**
	 * The actual App-ID based on the AppName. Lazy implementation.
	 * @param credential The credentials (API-Key, Client-ID) which is registered for an application
	 * @param type of the credential. See APIManagerAdapter for potential credential types 
	 * @return the id of the organization
	 * @throws AppException if JSON response from API-Manager can't be parsed
	 */
	public ClientApplication getAppIdForCredential(String credential, String type) throws AppException {
		if(clientCredentialToAppMap.containsKey(type+"_"+credential)) {
			ClientApplication app = clientCredentialToAppMap.get(type+"_"+credential);
			LOG.info("Found existing application (in cache): '"+app.getName()+"' based on credential (Type: '"+type+"'): '"+credential+"'");
			return app;
		}
		getAllApps(); // Make sure, we loaded all apps before!
		LOG.debug("Searching credential (Type: "+type+"): '"+credential+"' in: " + allApps.size() + " apps.");
		Collection<ClientApplication> appIds = clientCredentialToAppMap.values();
		for(ClientApplication app : allApps) {
			if(appIds.contains(app.getId())) continue; // Not sure, if this really makes sense. Need to check!
			ObjectMapper mapper = new ObjectMapper();
			String response = null;
			URI uri;
			try {
				uri = new URIBuilder(CommandParameters.getInstance().getAPIManagerURL()).setPath(RestAPICall.API_VERSION + "/applications/"+app.getId()+"/"+type+"").build();
				LOG.debug("Loading credentials of type: '" + type + "' for application: '" + app.getName() + "' from API-Manager.");
				RestAPICall getRequest = new GETRequest(uri, null, true);
				HttpResponse httpResponse = getRequest.execute();
				response = EntityUtils.toString(httpResponse.getEntity());
				LOG.trace("Response: " + response);
				JsonNode clientIds = mapper.readTree(response);
				if(clientIds.size()==0) {
					LOG.debug("No credentials (Type: '"+type+"') found for application: '"+app.getName()+"'");
					continue;
				}
				for(JsonNode clientId : clientIds) {
					String key;
					if(type.equals(CREDENTIAL_TYPE_API_KEY)) {
						key = clientId.get("id").asText();
					} else if(type.equals(CREDENTIAL_TYPE_EXT_CLIENTID) || type.equals(CREDENTIAL_TYPE_OAUTH)) {
						if(clientId.get("clientId")==null) {
							key = "NOT_FOUND";
						} else {
							key = clientId.get("clientId").asText();
						}
					} else {
						throw new AppException("Unknown credential type: " + type, ErrorCode.UNXPECTED_ERROR);
					}
					LOG.debug("Found credential (Type: '"+type+"'): '"+key+"' for application: '"+app.getName()+"'");
					clientCredentialToAppMap.put(type+"_"+key, app);
					if(key.equals(credential)) {
						LOG.info("Found existing application: '"+app.getName()+"' based on credential (Type: '"+type+"'): '"+credential+"'");
						return app;
					}
				}
			} catch (Exception e) {
				LOG.error("Can't load applications credentials. Can't parse response: " + response);
				throw new AppException("Can't load applications credentials.", ErrorCode.API_MANAGER_COMMUNICATION, e);
			}
		}
		LOG.error("No application found for credential ("+type+"): " + credential);
		return null;
	}
	
	
	
	private static void addQuotaConfiguration(IAPI api, IAPI desiredAPI) throws AppException {
		// No need to load quota, if not given in the desired API
		if(desiredAPI!=null && (desiredAPI.getApplicationQuota() == null && desiredAPI.getSystemQuota() == null)) return;
		ActualAPI managerAPI = (ActualAPI)api;
		try {
			applicationQuotaConfig = getQuotaFromAPIManager(APPLICATION_DEFAULT_QUOTA); // Get the Application-Default-Quota
			sytemQuotaConfig = getQuotaFromAPIManager(SYSTEM_API_QUOTA); // Get the System-Default-Quota
			managerAPI.setApplicationQuota(getAPIQuota(applicationQuotaConfig, managerAPI.getId()));
			managerAPI.setSystemQuota(getAPIQuota(sytemQuotaConfig, managerAPI.getId()));
		} catch (AppException e) {
			LOG.error("Application-Default quota response: '"+applicationQuotaConfig+"'");
			LOG.error("System-Default quota response: '"+sytemQuotaConfig+"'");
			throw e;
		}
	}
	
	private static APIQuota getQuotaFromAPIManager(String identifier) throws AppException {
		ObjectMapper mapper = new ObjectMapper();
		URI uri;
		
			try {
				if(identifier.equals(APPLICATION_DEFAULT_QUOTA) || identifier.equals(SYSTEM_API_QUOTA)) {
					uri = new URIBuilder(CommandParameters.getInstance().getAPIManagerURL()).setPath(RestAPICall.API_VERSION + "/quotas/"+identifier).build();
				} else {
					uri = new URIBuilder(CommandParameters.getInstance().getAPIManagerURL()).setPath(RestAPICall.API_VERSION + "/applications/"+identifier+"/quota/").build();
				}
				RestAPICall getRequest = new GETRequest(uri, null, true);
				HttpResponse response = getRequest.execute();
				int statusCode = response.getStatusLine().getStatusCode();
				if( statusCode == 403){
					throw new AppException("Can't get API-Manager Quota-Configuration, User should have API administrator role", ErrorCode.API_MANAGER_COMMUNICATION);
				}
				if( statusCode != 200){
					throw new AppException("Can't get API-Manager Quota-Configuration.", ErrorCode.API_MANAGER_COMMUNICATION);
				}
				String config = IOUtils.toString(response.getEntity().getContent(), "UTF-8");
				APIQuota quotaConfig = mapper.readValue(config, APIQuota.class);
				return quotaConfig;
			} catch (URISyntaxException | UnsupportedOperationException | IOException e) {
				throw new AppException("Can't get API-Manager Quota-Configuration.", ErrorCode.API_MANAGER_COMMUNICATION, e);
			} 
		
	}
	
	private static APIQuota getAPIQuota(APIQuota quotaConfig, String apiId) throws AppException {
		APIQuota apiQuota;
		try {
			for(QuotaRestriction restriction : quotaConfig.getRestrictions()) {
				if(restriction.getApi().equals(apiId)) {
					apiQuota = new APIQuota();
					apiQuota.setDescription(quotaConfig.getDescription());
					apiQuota.setName(quotaConfig.getName());
					apiQuota.setRestrictions(new ArrayList<QuotaRestriction>());
					apiQuota.getRestrictions().add(restriction);
					return apiQuota;
				}
			}
		} catch (Exception e) {
			throw new AppException("Can't parse quota from API-Manager", ErrorCode.API_MANAGER_COMMUNICATION, e);
		}
		return null;
	}
	
	/**
	 * Based on the given apiPath this method returns the JSON-Configuration for the API 
	 * as it's stored in the API-Manager. The result is basically used to create the APIManagerAPI in 
	 * method getAPIManagerAPI
	 * @param apiPath path of the API, which can be considered as the key.
	 * @return the JSON-Configuration as it's returned from the API-Manager REST-API /proxies endpoint.
	 * @throws AppException if the API can't be found or created
	 */
	public JsonNode getExistingAPI(String apiPath, List<NameValuePair> filter, String type) throws AppException {
		CommandParameters cmd = CommandParameters.getInstance();
		ObjectMapper mapper = new ObjectMapper();
		URI uri;
		try {
			List<NameValuePair> usedFilters = new ArrayList<>();
			if(hasAPIManagerVersion("7.7") && apiPath != null) { // With 7.7 we can query the API directly on the path 
				usedFilters.add(new BasicNameValuePair("field", "path"));
				usedFilters.add(new BasicNameValuePair("op", "eq"));
				usedFilters.add(new BasicNameValuePair("value", apiPath));
			} 
			if(filter != null) { usedFilters.addAll(filter); } 
			uri = new URIBuilder(cmd.getAPIManagerURL()).setPath(RestAPICall.API_VERSION + "/"+type)
				.addParameters(usedFilters)
				.build();
			RestAPICall getRequest = new GETRequest(uri, null);
			InputStream response = getRequest.execute().getEntity().getContent();
			
			JsonNode jsonResponse;
			String path;
			JsonNode foundApi = null;
			try {
				jsonResponse = mapper.readTree(response);
				// We can directly access what we are looking for, as for 7.7 we filtered directly for the apiPath or 
				// we have used some filters!
				if(jsonResponse.size()!=0 && (filter!=null || hasAPIManagerVersion("7.7"))) {
					foundApi =  jsonResponse.get(0);
				} else {
					for(JsonNode api : jsonResponse) {
						path = api.get("path").asText();
						if(path.equals(apiPath)) {
							foundApi = api;
						}
					}
				}
				if(foundApi!=null) {
					if(type.equals(TYPE_FRONT_END)) {
						path = foundApi.get("path").asText();
						LOG.info("Found existing API on path: '"+path+"' ("+foundApi.get("state").asText()+") (ID: '" + foundApi.get("id").asText()+"')");
					} else if(type.equals(TYPE_BACK_END)) {
						String name = foundApi.get("name").asText();
						LOG.info("Found existing Backend-API with name: '"+name+"' (ID: '" + foundApi.get("id").asText()+"')");						
					}
					return foundApi;
				}
				if(apiPath!=null && filter!=null) {
					LOG.info("No existing API found exposed on: '" + apiPath + "' and filter: "+filter+"");
				} else if (apiPath==null ) {
					LOG.info("No existing API found with filters: "+filter+"");
				} else {
					LOG.info("No existing API found exposed on: '" + apiPath + "'");
				}
				
				return null;
			} catch (IOException e) {
				throw new AppException("Can't initialize API-Manager API-Representation.", ErrorCode.API_MANAGER_COMMUNICATION, e);
			}
		} catch (Exception e) {
			throw new AppException("Can't initialize API-Manager API-Representation.", ErrorCode.API_MANAGER_COMMUNICATION, e);
		}
	}
	
	private static byte[] getOriginalAPIDefinitionFromAPIM(String backendApiID) throws AppException {
		URI uri;
		try {
			uri = new URIBuilder(CommandParameters.getInstance().getAPIManagerURL()).setPath(RestAPICall.API_VERSION + "/apirepo/"+backendApiID+"/download")
					.setParameter("original", "true").build();
			RestAPICall getRequest = new GETRequest(uri, null);
			HttpResponse response=getRequest.execute();
			String res = EntityUtils.toString(response.getEntity(),StandardCharsets.UTF_8);
			return res.getBytes(StandardCharsets.UTF_8);
		} catch (Exception e) {
			throw new AppException("Can't read Swagger-File.", ErrorCode.CANT_READ_API_DEFINITION_FILE, e);
		}
	}
	
	private static byte[] getAPIImageFromAPIM(String backendApiID) throws AppException {
		URI uri;
		try {
			uri = new URIBuilder(CommandParameters.getInstance().getAPIManagerURL()).setPath(RestAPICall.API_VERSION + "/proxies/"+backendApiID+"/image").build();
			RestAPICall getRequest = new GETRequest(uri, null);
			HttpEntity response = getRequest.execute().getEntity();
			if(response == null) return null; // no Image found in API-Manager
			InputStream is = response.getContent();
			return IOUtils.toByteArray(is);
		} catch (Exception e) {
			throw new AppException("Can't read Image from API-Manager.", ErrorCode.API_MANAGER_COMMUNICATION, e);
		}
	}
	
	public static String getApiManagerVersion() throws AppException {
		if(APIManagerAdapter.apiManagerVersion!=null) {
			return apiManagerVersion;
		}
		APIManagerAdapter.apiManagerVersion = getApiManagerConfig("productVersion");
		LOG.info("API-Manager version is: " + apiManagerVersion);
		return APIManagerAdapter.apiManagerVersion;
	}
	
	/**
	 * Lazy helper method to get the actual API-Manager version. This is used to toggle on/off some 
	 * of the features (such as API-Custom-Properties)
	 * @return the API-Manager version as returned from the API-Manager REST-API /config endpoint
	 * @param configField name of the configField from API-Manager
	 * @throws AppException is something goes wrong.
	 */
	public static String getApiManagerConfig(String configField) throws AppException {
		ObjectMapper mapper = new ObjectMapper();
		URI uri;
		try {
			if(apiManagerConfig==null) {
				uri = new URIBuilder(CommandParameters.getInstance().getAPIManagerURL()).setPath(RestAPICall.API_VERSION + "/config").build();
				RestAPICall getRequest = new GETRequest(uri, null, true);
				HttpResponse httpResponse = getRequest.execute();
				apiManagerConfig = EntityUtils.toString(httpResponse.getEntity());
			}
			JsonNode jsonResponse;
			jsonResponse = mapper.readTree(apiManagerConfig);
			JsonNode retrievedConfigField = jsonResponse.get(configField);
			if(retrievedConfigField==null) {
				LOG.debug("Config field: '"+configField+"' is unsuporrted!");
				return "UnknownConfigField"+configField;
			}
			return retrievedConfigField.asText();
		} catch (Exception e) {
			LOG.error("Error AppInfo from API-Manager. Can't parse response: " + apiManagerConfig);
			throw new AppException("Can't get "+configField+" from API-Manager", ErrorCode.API_MANAGER_COMMUNICATION, e);
		}
	}
	
	public static List<APIAccess> getAPIAccess(String id, String type) throws AppException {
		List<APIAccess> allApiAccess = new ArrayList<APIAccess>();
		ObjectMapper mapper = new ObjectMapper();
		String response = null;
		URI uri;
		try {
			uri = new URIBuilder(CommandParameters.getInstance().getAPIManagerURL()).setPath(RestAPICall.API_VERSION + "/"+type+"/"+id+"/apis").build();
			RestAPICall getRequest = new GETRequest(uri, null, true);
			HttpResponse httpResponse = getRequest.execute();
			response = EntityUtils.toString(httpResponse.getEntity());
			allApiAccess = mapper.readValue(response, new TypeReference<List<APIAccess>>(){});
			return allApiAccess;
		} catch (Exception e) {
			LOG.error("Error cant load API-Access for "+type+" from API-Manager. Can't parse response: " + response);
			throw new AppException("API-Access for "+type+" from API-Manager", ErrorCode.API_MANAGER_COMMUNICATION, e);
		}
	}
	
	private static List<ClientApplication> getSubscribedApps(String apiId) throws AppException {
		ObjectMapper mapper = new ObjectMapper();
		String response = null;
		URI uri;
		if(!APIManagerAdapter.hasAPIManagerVersion("7.7")) {
			throw new AppException("API-Manager: " + apiManagerVersion + " doesn't support /proxies/<apiId>/applications", ErrorCode.UNXPECTED_ERROR);
		}
		try {
			uri = new URIBuilder(CommandParameters.getInstance().getAPIManagerURL()).setPath(RestAPICall.API_VERSION + "/proxies/"+apiId+"/applications").build();
			RestAPICall getRequest = new GETRequest(uri, null, true);
			HttpResponse httpResponse = getRequest.execute();
			response = EntityUtils.toString(httpResponse.getEntity());
			List<ClientApplication> subscribedApps = mapper.readValue(response, new TypeReference<List<ClientApplication>>(){});
			return subscribedApps;
		} catch (Exception e) {
			LOG.error("Error cant load subscribes applications from API-Manager. Can't parse response: " + response);
			throw new AppException("Error cant load subscribes applications from API-Manager.", ErrorCode.API_MANAGER_COMMUNICATION, e);
		}
	}
	
	public List<Organization> getAllOrgs() throws AppException {
		if(!hasAdminAccount) {
			LOG.error("Cant load all organizations without an Admin-Account.");
			return null;
		}
		if(APIManagerAdapter.allOrgs!=null) {
			return APIManagerAdapter.allOrgs;
		}
		allOrgs = new ArrayList<Organization>();
		ObjectMapper mapper = new ObjectMapper();
		String response = null;
		URI uri;
		try {
			uri = new URIBuilder(CommandParameters.getInstance().getAPIManagerURL()).setPath(RestAPICall.API_VERSION + "/organizations").build();
			RestAPICall getRequest = new GETRequest(uri, null, true);
			HttpResponse httpResponse = getRequest.execute();
			response = EntityUtils.toString(httpResponse.getEntity());
			allOrgs = mapper.readValue(response, new TypeReference<List<Organization>>(){});
			return allOrgs;
		} catch (Exception e) {
			LOG.error("Error cant read all orgs from API-Manager. Can't parse response: " + response);
			throw new AppException("Can't read all orgs from API-Manager", ErrorCode.API_MANAGER_COMMUNICATION, e);
		}
	}
	
	public List<IAPI> getAllAPIs() throws AppException {
		if(!hasAdminAccount) {
			LOG.error("Cant load all APIs without an Admin-Account.");
			return null;
		}
		if(APIManagerAdapter.allAPIs!=null) {
			return APIManagerAdapter.allAPIs;
		}
		allAPIs = new ArrayList<IAPI>();
		ObjectMapper mapper = new ObjectMapper();
		String response = null;
		URI uri;
		try {
			uri = new URIBuilder(CommandParameters.getInstance().getAPIManagerURL()).setPath(RestAPICall.API_VERSION + "/proxies").build();
			RestAPICall getRequest = new GETRequest(uri, null, true);
			HttpResponse httpResponse = getRequest.execute();
			response = EntityUtils.toString(httpResponse.getEntity());
			allAPIs = mapper.readValue(response, new TypeReference<List<ActualAPI>>(){});
			return allAPIs;
		} catch (Exception e) {
			LOG.error("Error cant read all APIs from API-Manager. Can't parse response: " + response);
			throw new AppException("Can't read all APIs from API-Manager", ErrorCode.API_MANAGER_COMMUNICATION, e);
		}
	}
	
	public List<ClientApplication> getAllApps() throws AppException {
		if(!hasAdminAccount) {
			LOG.error("Cant load all applications without an Admin-Account.");
			return null;
		}
		if(APIManagerAdapter.allApps!=null) {
			LOG.trace("Not reloading existing apps from API-Manager. Number of apps: " + APIManagerAdapter.allApps.size());
			return APIManagerAdapter.allApps;
		}
		LOG.debug("Loading existing apps from API-Manager.");
		allApps = new ArrayList<ClientApplication>();
		ObjectMapper mapper = new ObjectMapper();
		String response = null;
		URI uri;
		try {
			uri = new URIBuilder(CommandParameters.getInstance().getAPIManagerURL()).setPath(RestAPICall.API_VERSION + "/applications").build();
			RestAPICall getRequest = new GETRequest(uri, null, true);
			HttpResponse httpResponse = getRequest.execute();
			response = EntityUtils.toString(httpResponse.getEntity());
			allApps = mapper.readValue(response, new TypeReference<List<ClientApplication>>(){});
			LOG.debug("Loaded: " + allApps.size() + " apps from API-Manager.");
			return allApps;
		} catch (Exception e) {
			LOG.error("Error cant read all applications from API-Manager. Can't parse response: " + response);
			throw new AppException("Can't read all applications from API-Manager", ErrorCode.API_MANAGER_COMMUNICATION, e);
		}
	}
	
	
	
	public static List<ApiAccess> getOrgsApiAccess(String orgId, boolean forceReload) throws AppException {
		if(!forceReload && orgsApiAccess.containsKey(orgId)) {
			return orgsApiAccess.get(orgId);
		}
		ObjectMapper mapper = new ObjectMapper();
		String response = null;
		URI uri;
		List<ApiAccess> apiAccess;
		try {
			uri = new URIBuilder(CommandParameters.getInstance().getAPIManagerURL()).setPath(RestAPICall.API_VERSION + "/organizations/"+orgId+"/apis").build();
			RestAPICall getRequest = new GETRequest(uri, null, true);
			HttpResponse httpResponse = getRequest.execute();
			response = EntityUtils.toString(httpResponse.getEntity());
			apiAccess = mapper.readValue(response, new TypeReference<List<ApiAccess>>(){});
			orgsApiAccess.put(orgId, apiAccess);
			return apiAccess;
		} catch (Exception e) {
			LOG.error("Error cant read API-Access for org: "+orgId+" from API-Manager. Can't parse response: " + response);
			throw new AppException("Error cant read API-Access for org: "+orgId+" from API-Manager", ErrorCode.API_MANAGER_COMMUNICATION, e);
		}
	}
	
	public static JsonNode getCustomPropertiesConfig() throws AppException {
		
		String appConfig = null;
		URI uri;
		try {
			uri = new URIBuilder(CommandParameters.getInstance().getAPIManagerURL()).setPath("/vordel/apiportal/app/app.config").build();
			RestAPICall getRequest = new GETRequest(uri, null);
			HttpEntity response = getRequest.execute().getEntity();
			appConfig = IOUtils.toString(response.getContent(), "UTF-8");
			return parseAppConfig(appConfig);
		} catch (Exception e) {
			throw new AppException("Can't read app.config from API-Manager: '" + appConfig + "'", ErrorCode.API_MANAGER_COMMUNICATION, e);
		}
	}
	
	/**
	 * Helper method to validate that configured Custom-Properties are really configured 
	 * in the API-Manager configuration.<br>
	 * Will become obsolete sine the API-Manager REST-API provides an endpoint for that.
	 * @param appConfig from the API-Manager (which isn't JSON)
	 * @return JSON-Configuration with the custom-properties section
	 * @throws AppException if the app.config can't be parsed
	 */
	public static JsonNode parseAppConfig(String appConfig) throws AppException {
		ObjectMapper mapper = new ObjectMapper();
		try {
			appConfig = appConfig.substring(appConfig.indexOf("customPropertiesConfig:")+23, appConfig.indexOf("wizardModels"));
			//appConfig = appConfig.substring(0, appConfig.length()-1); // Remove the tail comma
			mapper.configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);
			mapper.configure(JsonParser.Feature.ALLOW_COMMENTS, true);
			mapper.configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);
			return mapper.readTree(appConfig);
		} catch (Exception e) {
			throw new AppException("Can't parse API-Manager app.config.", ErrorCode.API_MANAGER_COMMUNICATION, e);
		}
	}
	
	/**
	 * Helper method to fulfill the given certificates by the API-Developer into the required 
	 * format as it's needed by the API-Manager. 
	 * @param certFile InputStream to the Certificate
	 * @param cert the certificate itself
	 * @return JsonNode as it's required by the API-Manager.
	 * @throws AppException if JSON-Node-Config can't be created
	 */
	public static JsonNode getCertInfo(InputStream certFile, CaCert cert) throws AppException {
		URI uri;
		ObjectMapper mapper = new ObjectMapper();
		try {
			uri = new URIBuilder(CommandParameters.getInstance().getAPIManagerURL()).setPath(RestAPICall.API_VERSION + "/certinfo/").build();
			
			HttpEntity entity = MultipartEntityBuilder.create()
					.addBinaryBody("file", IOUtils.toByteArray(certFile), ContentType.create("application/x-x509-ca-cert"), cert.getCertFile())
					.addTextBody("inbound", cert.getInbound())
					.addTextBody("outbound", cert.getOutbound())
					.build();
			POSTRequest postRequest = new POSTRequest(entity, uri, null);
			postRequest.setContentType(null);
			HttpEntity response = postRequest.execute().getEntity();
			JsonNode jsonResponse = mapper.readTree(response.getContent());
			return jsonResponse;
		} catch (Exception e) {
			throw new AppException("Can't read certificate information from API-Manager.", ErrorCode.API_MANAGER_COMMUNICATION, e);
		}
	}
	
	/**
	 * Helper method to translate a Base64 encoded format 
	 * as it's needed by the API-Manager.
	 * @param certFile input stream to the certificate file
	 * @param filename the name of the certificate file used as a reference in the generated Json object
	 * @throws AppException when the certificate information can't be created
	 * @return a Json-Object structure as needed by the API-Manager
	 */
	public static JsonNode getFileData(InputStream certFile, String filename) throws AppException {
		URI uri;
		ObjectMapper mapper = new ObjectMapper();
		try {
			uri = new URIBuilder(CommandParameters.getInstance().getAPIManagerURL()).setPath(RestAPICall.API_VERSION + "/filedata/").build();
			
			HttpEntity entity = MultipartEntityBuilder.create()
					.addBinaryBody("file", IOUtils.toByteArray(certFile), ContentType.create("application/x-pkcs12"), filename)
					.build();
			POSTRequest postRequest = new POSTRequest(entity, uri, null);
			postRequest.setContentType(null);
			HttpEntity response = postRequest.execute().getEntity();
			JsonNode jsonResponse = mapper.readTree(response.getContent());
			return jsonResponse;
		} catch (Exception e) {
			throw new AppException("Can't read certificate information from API-Manager.", ErrorCode.API_MANAGER_COMMUNICATION, e);
		}
	}
	
	public <profile> void translateMethodIds(Map<String, profile> profiles, IAPI actualAPI) throws AppException {
		Map<String, profile> updatedEntries = new LinkedHashMap<String, profile>();
		if(profiles!=null) {
			Iterator<String> keys = profiles.keySet().iterator();
			while(keys.hasNext()) {
				String key = keys.next();
				if(key.equals("_default")) continue;
				List<APIMethod> methods = getAllMethodsForAPI(actualAPI.getId());
				for(APIMethod method : methods) {
					if(method.getName().equals(key)) {
						profile value = profiles.get(key);
						if(value instanceof OutboundProfile) {
							((OutboundProfile)value).setApiMethodId(method.getApiMethodId());
							((OutboundProfile)value).setApiId(method.getApiId());
						}
						updatedEntries.put(method.getId(), profiles.get(key));
						keys.remove();
						break;
					}
				}
			}
			profiles.putAll(updatedEntries);
		}
	}

	public boolean hasAdminAccount() {
		return hasAdminAccount;
	}
	
	public boolean isUsingOrgAdmin() {
		return usingOrgAdmin;
	}
}
