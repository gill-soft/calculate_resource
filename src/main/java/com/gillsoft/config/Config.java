package com.gillsoft.config;

import java.io.IOException;
import java.util.Properties;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

public class Config {

	private static Properties properties;

	static {
		try {
			Resource resource = new ClassPathResource("application.properties");
			properties = PropertiesLoaderUtils.loadProperties(resource);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static String getUrl() {
		return properties.getProperty("url");
	}

	public static int getRequestTimeout() {
		return Integer.valueOf(properties.getProperty("request.timeout"));
	}

	public static long getCacheTimeToLive() {
		return Long.valueOf(properties.getProperty("cache.time.to.live"));
	}

}
