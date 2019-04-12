package com.axway.apim.test.tags;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.axway.apim.lib.AppException;
import com.axway.apim.swagger.api.properties.tags.TagMap;

public class TagEqualsTest {

	private static Logger LOG = LoggerFactory.getLogger(TagEqualsTest.class);

	@Test
	public void testNullWithNoTags() throws AppException, IOException {
		TagMap<String, String[]> desiredTags = null;
		TagMap<String, String[]> actualTags = new TagMap<String, String[]>();

		// Logically null and No-Tags must equal
		LOG.info("Comparing actual tags: " + actualTags + " with desired tags: " + desiredTags);
		Assert.assertEquals(actualTags.equals(desiredTags), true);
	}
}
