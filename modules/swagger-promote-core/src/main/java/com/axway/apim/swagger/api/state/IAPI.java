package com.axway.apim.swagger.api.state;

import java.util.List;
import java.util.Map;

import com.axway.apim.lib.AppException;
import com.axway.apim.swagger.api.properties.APIDefintion;
import com.axway.apim.swagger.api.properties.APIImage;
import com.axway.apim.swagger.api.properties.applications.ClientApplication;
import com.axway.apim.swagger.api.properties.authenticationProfiles.AuthenticationProfile;
import com.axway.apim.swagger.api.properties.cacerts.CaCert;
import com.axway.apim.swagger.api.properties.corsprofiles.CorsProfile;
import com.axway.apim.swagger.api.properties.inboundprofiles.InboundProfile;
import com.axway.apim.swagger.api.properties.outboundprofiles.OutboundProfile;
import com.axway.apim.swagger.api.properties.profiles.ServiceProfile;
import com.axway.apim.swagger.api.properties.quota.APIQuota;
import com.axway.apim.swagger.api.properties.securityprofiles.SecurityProfile;

public interface IAPI {
	
	public final static String STATE_PUBLISHED = "published";
	public final static String STATE_UNPUBLISHED = "unpublished";
	public final static String STATE_DEPRECATED = "deprecated";
	public final static String STATE_DELETED = "deleted";
	
	public final int SWAGGGER_API = 1;
	public final int WSDL_API = 2;

	
	public String getVersion();
	
	public String getPath() throws AppException;
	
	public String getState() throws AppException;
	
	public void setState(String state) throws AppException;
	
	public Map<String, OutboundProfile> getOutboundProfiles();
	public Map<String, InboundProfile> getInboundProfiles();
	public List<SecurityProfile> getSecurityProfiles();
	public List<AuthenticationProfile> getAuthenticationProfiles();
	public List<CorsProfile> getCorsProfiles();
	
	public void setInboundProfiles(Map<String, InboundProfile> inboundProfiles);
	
	/**
	 * @param securityProfiles control the way an application must Authn (e.g. API-Key, etc.)
	 */
	public void setSecurityProfiles(List<SecurityProfile> securityProfiles);
	/**
	 * @param authenticationProfiles is used for AuthN against the downstream service-provider
	 */
	public void setAuthenticationProfiles(List<AuthenticationProfile> authenticationProfiles);
	
	public Map<String, String> getCustomProperties();
	
	
	public boolean isValid();
	
	public void setValid(boolean valid);
	
	public String getOrgId() throws AppException;
	
	public void setOrgId(String orgId);
	
	public String getOrganization();
	
	public String getName();
	
	public String getSummary();
	
	public String getVhost();
	
	public String getId() throws AppException;
	
	public String getApiId() throws AppException;
	
	public APIImage getImage();
	
	public Map<String, String[]> getTags();
	
	public APIDefintion getAPIDefinition();
	
	public void setAPIDefinition(APIDefintion apiDefinition);
	
	public String getDescriptionType();
	
	public String getDescriptionManual();
	
	public String getDescriptionMarkdown();
	
	public String getDescriptionUrl();
	
	public List<CaCert> getCaCerts();
	
	public APIQuota getSystemQuota();
	
	public APIQuota getApplicationQuota();
	
	public List<String> getClientOrganizations();
	
	public void setClientOrganizations(List<String> clientOrganizations);
	
	public List<ClientApplication> getApplications();

	public void setApplications(List<ClientApplication> clientApplications);
	
	public Map<String, ServiceProfile> getServiceProfiles();

	String getApiDefinitionImport();
	
	public String getApiRoutingKey();
}
