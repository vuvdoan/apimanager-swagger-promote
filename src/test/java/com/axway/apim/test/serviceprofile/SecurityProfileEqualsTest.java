package com.axway.apim.test.serviceprofile;

import java.io.IOException;
import java.security.Security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.axway.apim.lib.AppException;
import com.axway.apim.swagger.api.properties.securityprofiles.SecurityDevice;
import com.axway.apim.swagger.api.properties.securityprofiles.SecurityProfile;
import com.fasterxml.jackson.databind.ObjectMapper;

public class SecurityProfileEqualsTest {

	private static Logger LOG = LoggerFactory.getLogger(SecurityProfileEqualsTest.class);

	@Test
	public void testPassThroughEquals() throws AppException, IOException {
		SecurityProfile desiredSecurityProfile = new SecurityProfile();
		desiredSecurityProfile.setName("_default");
		desiredSecurityProfile.setIsDefault("true");
		SecurityDevice passthroughDevice = new SecurityDevice();
		passthroughDevice.setName("Pass Through");
		passthroughDevice.setType("passThrough");
		passthroughDevice.setOrder("0");
		passthroughDevice.getProperties().put("subjectIdFieldName", "Pass Through");
		passthroughDevice.getProperties().put("removeCredentialsOnSuccess", "true");
		desiredSecurityProfile.getDevices().add(passthroughDevice);
		
		ObjectMapper mapper = new ObjectMapper();
		// This is the Passthrough-Security-Profile as loaded from the API-Manager for a new API 
		String jsonSecurityProfile = "{ \"name\": \"_default\", \"isDefault\": true, \"devices\": [ { \"name\": \"Pass Through\", \"type\": \"passThrough\", \"order\": 0, \"properties\": { \"subjectIdFieldName\": \"Pass Through\", \"removeCredentialsOnSuccess\": \"true\"}}]}";
		SecurityProfile actualSecurityProfile = mapper.readValue(jsonSecurityProfile, SecurityProfile.class);
		
		// Both security profile should equals
		LOG.info("Comparing actual SecurityProfile Actual: " + actualSecurityProfile + " with desired: " + desiredSecurityProfile);
		Assert.assertEquals(actualSecurityProfile.equals(desiredSecurityProfile), true);
		
	}
}
