package com.axway.apim.swagger.api.properties.inboundprofiles;

import org.apache.commons.lang.StringUtils;

public class InboundProfile {
	
	String monitorAPI = "true";
	
	String monitorSubject = "authentication.subject.id";
	
	String securityProfile;
	
	String corsProfile;

	public String getMonitorAPI() {
		return monitorAPI;
	}

	public void setMonitorAPI(String monitorAPI) {
		this.monitorAPI = monitorAPI;
	}

	public String getMonitorSubject() {
		return monitorSubject;
	}

	public void setMonitorSubject(String monitorSubject) {
		this.monitorSubject = monitorSubject;
	}

	public String getSecurityProfile() {
		return securityProfile;
	}

	public void setSecurityProfile(String securityProfile) {
		this.securityProfile = securityProfile;
	}

	public String getCorsProfile() {
		return corsProfile;
	}

	public void setCorsProfile(String corsProfile) {
		this.corsProfile = corsProfile;
	}
	
	@Override
	public boolean equals(Object other) {
		if(other == null) return false;
		if(other instanceof InboundProfile) {
			InboundProfile otherInboundProfile = (InboundProfile)other;
			return
					StringUtils.equals(otherInboundProfile.getMonitorAPI(), this.getMonitorAPI()) &&
					StringUtils.equals(otherInboundProfile.getMonitorSubject(), this.getMonitorSubject()) &&
					StringUtils.equals(otherInboundProfile.getSecurityProfile(), this.getSecurityProfile()) &&
					StringUtils.equals(otherInboundProfile.getCorsProfile(), this.getCorsProfile());
		} else {
			return false;
		}
	}

	@Override
	public String toString() {
		return "monitorAPI: " + monitorAPI + ", monitorSubject: " + monitorSubject + ", securityProfile: " + securityProfile + ", corsProfile: " + corsProfile;
	}
}
