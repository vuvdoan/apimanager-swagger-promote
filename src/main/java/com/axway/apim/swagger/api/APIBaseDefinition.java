package com.axway.apim.swagger.api;

import com.axway.apim.swagger.api.properties.APIImage;

/**
 * Not sure if this class is really needed.
 * 
 * @author cwiechmann@axway.com
 */
public class APIBaseDefinition extends AbstractAPIDefinition implements IAPIDefinition {

	@Override
	public String getState() {
		return this.state;
	}

	public void setStatus(String state) {
		this.state = state;
	}

	@Override
	public String getVersion() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getName() {
		// TODO Auto-generated method stub
		return null;
	}
	
	@Override
	public String getSummary() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getApiId() {
		// TODO Auto-generated method stub
		return null;
	}
	
	@Override
	public APIImage getImage() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void setWsdlURL(String wsdlURL) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public String getWsdlURL() {
		// TODO Auto-generated method stub
		return null;
	}
}
