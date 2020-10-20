package com.gillsoft.client;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.gillsoft.cache.CacheHandler;
import com.gillsoft.cache.IOCacheException;
import com.gillsoft.cache.MemoryCacheHandler;
import com.gillsoft.config.Config;
import com.gillsoft.logging.SimpleRequestResponseLoggingInterceptor;
import com.gillsoft.util.RestTemplateUtil;

@Component("CalculateRestClient")
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
public class RestClient {
	
	private static Logger LOGGER = LogManager.getLogger(RestClient.class);

	private static final String RATES = "rates/%s";
	private static final String DATE_RATE = "rate/%s/%s";

	@Autowired
	@Qualifier("MemoryCacheHandler")
	private CacheHandler cache;

	private RestTemplate template;
	
	public RestClient() {
		template = createNewPoolingTemplate(Config.getRequestTimeout());
	}

	public RestTemplate createNewPoolingTemplate(int requestTimeout) {
		RestTemplate template = new RestTemplate(new BufferingClientHttpRequestFactory(
				RestTemplateUtil.createPoolingFactory(Config.getUrl(), 300, requestTimeout)));
		template.setMessageConverters(Collections.singletonList(new MappingJackson2HttpMessageConverter()));
		template.setInterceptors(Collections.singletonList(new SimpleRequestResponseLoggingInterceptor()));
		return template;
	}
	
	@SuppressWarnings("unchecked")
	public Map<String, Map<String, CoupleRate>> getCachedRates(String organizationId) throws IOCacheException {
		try {
			Object o = cache.read(getCacheParams(organizationId));
			if (o == null) {
				return getOrganizationRates(organizationId);
			}
			return (Map<String, Map<String, CoupleRate>>) o;
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

	@SuppressWarnings("unchecked")
	public Map<String, Map<String, CoupleRate>> getOrganizationRates(String organizationId) {
		Map<String, List<Map<String, Object>>> map = null;
		try {
			map = template.getForObject(UriComponentsBuilder.fromUriString(Config.getUrl().concat(String.format(RATES, organizationId))).build().toUri(), Map.class);
			List<Map<String, Object>> couples = map.get("couples");
			List<Map<String, Object>> rates = map.get("rates");
			Map<String, Map<String, CoupleRate>> ratesMap = new HashMap<>();
			if (couples != null && rates != null && !couples.isEmpty() && !rates.isEmpty()) {
				couples.stream().forEach(couple -> {
					String keyFrom = String.valueOf(couple.get("currency_from"));
					Map<String, CoupleRate> coupleRates = ratesMap.get(keyFrom);
					if (coupleRates == null) {
						coupleRates = new HashMap<>();
						ratesMap.put(keyFrom, coupleRates);
					}
					rates.stream().filter(f -> f.get("couple_id").equals(couple.get("id"))).forEach(rate -> {
						String keyTo = String.valueOf(couple.get("currency_to"));
						BigDecimal coupleRate = new BigDecimal(String.valueOf(rate.get("rate")));
						ratesMap.get(keyFrom).put(keyTo, new CoupleRate(String.valueOf(couple.get("id")), coupleRate));
					});
				});
			}
			cache.write(ratesMap, getCacheParams(organizationId));
			return ratesMap;
		} catch (Exception e) {
			LOGGER.error("Can not get rates for organisation " + organizationId, e);
		}
		return null;
	}
	
	public BigDecimal getCachedCoupleRate(String coupleId, Date date) {
		try {
			LocalDateTime dateTime = date.toInstant().atZone(ZoneOffset.UTC).toLocalDateTime();
			Object o = cache.read(getCacheParams(coupleId + "_" + dateTime.toString()));
			if (o == null) {
				return getCoupleRate(coupleId, date);
			}
			return (BigDecimal) o;
		} catch (Exception e) {
			return getCoupleRate(coupleId, date);
		}
	}
	
	@SuppressWarnings("unchecked")
	public BigDecimal getCoupleRate(String coupleId, Date date) {
		LocalDateTime dateTime = date.toInstant().atZone(ZoneOffset.UTC).toLocalDateTime();
		List<Map<String, Object>> rates = null;
		try {
			rates = template.getForObject(UriComponentsBuilder.fromUriString(Config.getUrl().concat(String.format(DATE_RATE, coupleId, dateTime.toString()))).build().toUri(), List.class);
			BigDecimal rate = null;
			if (rates != null
					&& !rates.isEmpty()) {
				rate = new BigDecimal(String.valueOf(rates.get(0).get("rate")));
			}
			cache.write(rate, getCacheParams(coupleId + "_" + dateTime.toString()));
			return rate;
		} catch (Exception e) {
			LOGGER.error("Can not get rates for couple " + coupleId + " for date " + dateTime.toString(), e);
		}
		return null;
	}

	public CacheHandler getCache() {
		return cache;
	}

}

