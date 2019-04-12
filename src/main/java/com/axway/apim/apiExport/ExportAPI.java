package com.axway.apim.apiExport;

import java.util.List;
import java.util.Map;

import com.axway.apim.apiImport.APIManagerAdapter;
import com.axway.apim.lib.AppException;
import com.axway.apim.swagger.api.properties.APIDefintion;
import com.axway.apim.swagger.api.properties.APIImage;
import com.axway.apim.swagger.api.properties.applications.ClientApplication;
import com.axway.apim.swagger.api.properties.authenticationProfiles.AuthType;
import com.axway.apim.swagger.api.properties.authenticationProfiles.AuthenticationProfile;
import com.axway.apim.swagger.api.properties.cacerts.CaCert;
import com.axway.apim.swagger.api.properties.corsprofiles.CorsProfile;
import com.axway.apim.swagger.api.properties.inboundprofiles.InboundProfile;
import com.axway.apim.swagger.api.properties.outboundprofiles.OutboundProfile;
import com.axway.apim.swagger.api.properties.profiles.ServiceProfile;
import com.axway.apim.swagger.api.properties.quota.APIQuota;
import com.axway.apim.swagger.api.properties.securityprofiles.SecurityProfile;
import com.axway.apim.swagger.api.state.AbstractAPI;
import com.axway.apim.swagger.api.state.ActualAPI;
import com.axway.apim.swagger.api.state.IAPI;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

@JsonPropertyOrder({ "name", "path", "state", "version", "organization", "image", "backendBasepath" })
@JsonSerialize(include = JsonSerialize.Inclusion.NON_NULL)
public class ExportAPI extends AbstractAPI implements IAPI {
	
	IAPI actualAPIProxy = null;
	
	@Override
	public String getPath() throws AppException {
		return this.actualAPIProxy.getPath();
	}

	public ExportAPI(IAPI actualAPIProxy) {
		super();
		this.actualAPIProxy = actualAPIProxy;
	}
	
	@Override
	@JsonIgnore
	public boolean isValid() {
		return this.actualAPIProxy.isValid();
	}

	@Override
	@JsonIgnore
	public String getOrganizationId() {
		try {
			return this.actualAPIProxy.getOrganizationId();
		} catch (AppException e) {
			throw new RuntimeException("Can't read orgId");
		}
	}

	@Override
	@JsonIgnore
	public APIDefintion getAPIDefinition() {
		return this.actualAPIProxy.getAPIDefinition();
	}

	@Override
	public Map<String, OutboundProfile> getOutboundProfiles() {
		OutboundProfile profile = this.actualAPIProxy.getOutboundProfiles().get("_default");
		if(		profile.getRouteType().equals("proxy") && 
				profile.getRequestPolicy()==null && 
				profile.getResponsePolicy()==null &&
				profile.getRoutePolicy()==null &&
				profile.getFaultHandlerPolicy()==null && 
				profile.getAuthenticationProfile().equals("_default")
				) 
			return null;
		return this.actualAPIProxy.getOutboundProfiles();
	}

	@Override
	public List<SecurityProfile> getSecurityProfiles() {
		if(this.actualAPIProxy.getSecurityProfiles().get(0).getDevices().get(0).getType().equals("passThrough")) 
			return null;
		return this.actualAPIProxy.getSecurityProfiles();
	}

	@Override
	public List<AuthenticationProfile> getAuthenticationProfiles() {
		if(this.actualAPIProxy.getAuthenticationProfiles().get(0).getType().equals(AuthType.none)) return null;
		return this.actualAPIProxy.getAuthenticationProfiles();
	}

	@Override
	public Map<String, InboundProfile> getInboundProfiles() {
		InboundProfile profile = this.actualAPIProxy.getInboundProfiles().get("_default");
		if(profile.getCorsProfile().equals("_default") && 
				profile.getSecurityProfile().equals("_default")) 
			return null;
		return this.actualAPIProxy.getInboundProfiles();
	}

	@Override
	public List<CorsProfile> getCorsProfiles() {
		if(this.actualAPIProxy.getCorsProfiles()==null) return null;
		CorsProfile cors = this.actualAPIProxy.getCorsProfiles().get(0);
		if(cors.getName().equals("_default")) return null;
		return this.actualAPIProxy.getCorsProfiles();
	}

	@Override
	public String getVhost() {
		return this.actualAPIProxy.getVhost();
	}

	@Override
	public Map<String, String[]> getTags() {
		if(this.actualAPIProxy.getTags().size()==0) return null;
		return this.actualAPIProxy.getTags();
	}

	@Override
	public String getState() throws AppException {
		return this.actualAPIProxy.getState();
	}

	@Override
	public String getVersion() {
		return this.actualAPIProxy.getVersion();
	}

	@Override
	public String getSummary() {
		return this.actualAPIProxy.getSummary();
	}

	@Override
	public APIImage getImage() {
		if(this.actualAPIProxy.getImage()==null) return null;
		return this.actualAPIProxy.getImage();
	}

	@Override
	public String getName() {
		return this.actualAPIProxy.getName();
	}

	@Override
	public String getOrganization() {
		String orgId = null;
		try {
			orgId = getOrganizationId();
			return APIManagerAdapter.getInstance().getOrgName(orgId);
		} catch (Exception e) {
			throw new RuntimeException("Can't read orgName for orgId: '"+orgId+"'");
		}
	}

	@Override
	@JsonIgnore
	public String getDeprecated() {
		return ((ActualAPI)this.actualAPIProxy).getDeprecated();
	}

	@Override
	public Map<String, String> getCustomProperties() {
		return this.actualAPIProxy.getCustomProperties();
	}

	@Override
	@JsonIgnore
	public int getAPIType() {
		return ((ActualAPI)this.actualAPIProxy).getAPIType();
	}

	@Override
	public String getDescriptionType() {
		if(this.actualAPIProxy.getDescriptionType().equals("original")) return null;
		return this.actualAPIProxy.getDescriptionType();
	}

	@Override
	public String getDescriptionManual() {
		return this.actualAPIProxy.getDescriptionManual();
	}

	@Override
	public String getDescriptionMarkdown() {
		return this.actualAPIProxy.getDescriptionMarkdown();
	}

	@Override
	public String getDescriptionUrl() {
		return this.actualAPIProxy.getDescriptionUrl();
	}


	@Override
	@JsonIgnore
	public List<CaCert> getCaCerts() {
		boolean hasCert = false;
		if(this.actualAPIProxy.getCaCerts()==null) return null;
		if(this.actualAPIProxy.getCaCerts().size()==0) return null;
		for(CaCert cert : this.actualAPIProxy.getCaCerts()) {
			if(cert.getAlias()!=null || !cert.getAlias().equals("")) {
				hasCert = true;
				break;
			}
		}
		if(!hasCert) return null;
		return this.actualAPIProxy.getCaCerts();
	}

	@Override
	public APIQuota getApplicationQuota() {
		return this.actualAPIProxy.getApplicationQuota();
	}

	@Override
	public APIQuota getSystemQuota() {
		return this.actualAPIProxy.getSystemQuota();
	}

	@Override
	@JsonIgnore
	public Map<String, ServiceProfile> getServiceProfiles() {
		return this.actualAPIProxy.getServiceProfiles();
	}

	@Override
	public List<String> getClientOrganizations() {
		if(this.actualAPIProxy.getClientOrganizations().size()==0) return null;
		if(this.actualAPIProxy.getClientOrganizations().size()==1 && 
				this.actualAPIProxy.getClientOrganizations().get(0).equals(getOrganization())) 
			return null;
		return this.actualAPIProxy.getClientOrganizations();
	}

	@Override
	public List<ClientApplication> getApplications() {
		if(this.actualAPIProxy.getApplications().size()==0) return null;
		return this.actualAPIProxy.getApplications();
	}

	@Override
	@JsonIgnore
	public String getApiDefinitionImport() {
		return null;
	}
	
	public String getBackendBasepath() {
		return this.getServiceProfiles().get("_default").getBasePath();
	}
}
