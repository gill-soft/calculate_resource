package com.gillsoft.client;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.gillsoft.cache.CacheHandler;
import com.gillsoft.cache.IOCacheException;
import com.gillsoft.cache.MemoryCacheHandler;
import com.gillsoft.config.Config;
import com.gillsoft.logging.SimpleRequestResponseLoggingInterceptor;
import com.gillsoft.util.RestTemplateUtil;

public class RestClient {

	private static final String RATES = "rates/%s";

	private CacheHandler cache;

	private RestTemplate template;
	
	public RestClient() {
		template = createNewPoolingTemplate(Config.getRequestTimeout());
		cache = new MemoryCacheHandler();
	}

	public RestTemplate createNewPoolingTemplate(int requestTimeout) {
		RestTemplate template = new RestTemplate(new BufferingClientHttpRequestFactory(
				RestTemplateUtil.createPoolingFactory(Config.getUrl(), 300, requestTimeout)));
		template.setMessageConverters(Collections.singletonList(new MappingJackson2HttpMessageConverter()));
		template.setInterceptors(Collections.singletonList(new SimpleRequestResponseLoggingInterceptor()));
		return template;
	}
	
	@SuppressWarnings("unchecked")
	public Map<String, Map<String, BigDecimal>> getCachedRates(String organizationId) throws IOCacheException {
		try {
			Object o = cache.read(getCacheParams(organizationId));
			if (o == null) {
				return getOrganizationRates(organizationId);
			}
			return (Map<String, Map<String, BigDecimal>>)o;
		} catch (Exception e) {
			return getOrganizationRates(organizationId);
		}
	}

	private Map<String, Object> getCacheParams(String organizationId) {
		Map<String, Object> params = new HashMap<>();
		params.put(MemoryCacheHandler.OBJECT_NAME, organizationId);
		params.put(MemoryCacheHandler.TIME_TO_LIVE, Config.getCacheTimeToLive());
		return params;
	}

	public Map<String, Map<String, BigDecimal>> getOrganizationRates(String organizationId) {
		Map<String, List<Map<String, Object>>> map = null;
		try {
			map = sendRequest(template, UriComponentsBuilder.fromUriString(Config.getUrl().concat(String.format(RATES, organizationId))).build().toUri());
			List<Map<String, Object>> couples = map.get("couples");
			List<Map<String, Object>> rates = map.get("rates");
			if (couples != null && rates != null && !couples.isEmpty() && !rates.isEmpty()) {
				Map<String, Map<String, BigDecimal>> ratesMap = new HashMap<>();
				couples.stream().forEach(couple -> {
					String keyFrom = String.valueOf(couple.get("currency_from"));
					Map<String, BigDecimal> coupleRates = ratesMap.get(keyFrom);
					if (coupleRates == null) {
						coupleRates = new HashMap<>();
						ratesMap.put(keyFrom, coupleRates);
					}
					rates.stream().filter(f -> f.get("couple_id").equals(couple.get("id"))).forEach(rate -> {
						String keyTo = String.valueOf(couple.get("currency_to"));
						BigDecimal coupleRate = new BigDecimal(String.valueOf(rate.get("rate")));
						ratesMap.get(keyFrom).put(keyTo, coupleRate);
					});
				});
				cache.write(ratesMap, getCacheParams(organizationId));
				return ratesMap;
			}
		} catch (Exception e) { e.printStackTrace(); }
		return null;
	}

	@SuppressWarnings("unchecked")
	private Map<String, List<Map<String, Object>>> sendRequest(RestTemplate template, URI uri) throws Exception {
		return template.getForObject(uri, Map.class);
	}

	public CacheHandler getCache() {
		return cache;
	}

}

