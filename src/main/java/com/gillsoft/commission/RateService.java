package com.gillsoft.commission;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.gillsoft.client.CoupleRate;
import com.gillsoft.client.RestClient;
import com.gillsoft.model.Currency;
import com.gillsoft.ms.entity.BaseEntity;
import com.gillsoft.ms.entity.User;

@Service
public class RateService {
	
	@Autowired
	@Qualifier("CalculateRestClient")
	private RestClient client;

	private static final String DEFAULT_ORGANIZATION = "0";
	
	public BigDecimal getCoeffRate(Map<String, Map<String, BigDecimal>> rates, Currency currencyFrom, Currency currencyTo) throws InvalidCurrencyPairException {
		if (Objects.equals(currencyFrom, currencyTo)) {
			return BigDecimal.ONE;
		}
		Map<String, BigDecimal> curFromRates = rates.get(String.valueOf(currencyFrom));
		if (curFromRates != null && !curFromRates.isEmpty()) {
			BigDecimal curToRate = curFromRates.get(String.valueOf(currencyTo));
			if (curToRate != null) {
				return curToRate;
			}
		}
		// если не нашли курс у организации - ищем общий (organizationId = DEFAULT_ORGANIZATION)
		if (!rates.containsKey(DEFAULT_ORGANIZATION)) {
			try {
				Map<String, Map<String, BigDecimal>> rates0 = convertToRatesMap(client.getCachedRates(DEFAULT_ORGANIZATION));
				rates0.put(DEFAULT_ORGANIZATION, null);
				return getCoeffRate(rates0, currencyFrom, currencyTo);
			} catch (Exception e) { }
		}
		throw new InvalidCurrencyPairException("No rate found for " + currencyFrom + '-' + currencyTo);
	}
	
	public BigDecimal getCoupleDateCoeffRate(Map<String, Map<String, CoupleRate>> rates, Date date, Currency currencyFrom, Currency currencyTo) throws InvalidCurrencyPairException {
		if (Objects.equals(currencyFrom, currencyTo)) {
			return BigDecimal.ONE;
		}
		Map<String, CoupleRate> curFromRates = rates.get(String.valueOf(currencyFrom));
		if (curFromRates != null && !curFromRates.isEmpty()) {
			CoupleRate curToRate = curFromRates.get(String.valueOf(currencyTo));
			if (curToRate != null) {
				return client.getCachedCoupleRate(curToRate.getId(), date);
			}
		}
		// если не нашли курс у организации - ищем общий (organizationId = DEFAULT_ORGANIZATION)
		if (!rates.containsKey(DEFAULT_ORGANIZATION)) {
			try {
				Map<String, Map<String, CoupleRate>> rates0 = client.getCachedRates(DEFAULT_ORGANIZATION);
				rates0.put(DEFAULT_ORGANIZATION, null);
				return getCoupleDateCoeffRate(rates0, date, currencyFrom, currencyTo);
			} catch (Exception e) { }
		}
		throw new InvalidCurrencyPairException("No rate found for " + currencyFrom + '-' + currencyTo);
	}
	
	public Map<String, Map<String, BigDecimal>> getRates(User user) {
		Map<String, Map<String, CoupleRate>> couples = getCouples(user);
		return convertToRatesMap(couples);
	}
	
	public Map<String, Map<String, CoupleRate>> getCouples(User user) {
		Map<String, Map<String, CoupleRate>> couples = new HashMap<>();
		BaseEntity parent = user.getParents() != null && !user.getParents().isEmpty() ? user.getParents().iterator().next() : null;
		fillRates(parent, couples);
		return couples;
	}
	
	private void fillRates(BaseEntity parent, Map<String, Map<String, CoupleRate>> rates) {
		if (parent != null && parent.getParents() != null && !parent.getParents().isEmpty()) {
			fillRates(parent.getParents().iterator().next(), rates);
		}
		try {
			Map<String, Map<String, CoupleRate>> parentRates = client.getCachedRates(parent != null ? String.valueOf(parent.getId()) : DEFAULT_ORGANIZATION);
			if (parentRates != null && !parentRates.isEmpty()) {
				parentRates.forEach((key, value) -> {
					Map<String, CoupleRate> keyRates = rates.get(key);
					if (keyRates == null) {
						rates.put(key, value);
					} else {
						keyRates.putAll(value);
					}
				});
			}
		} catch (Exception e) {
			
		}
	}
	
	private Map<String, Map<String, BigDecimal>> convertToRatesMap(Map<String, Map<String, CoupleRate>> couples) {
		return couples.entrySet().stream().collect(Collectors.toMap(Entry::getKey,
				v -> v.getValue().entrySet().stream().collect(Collectors.toMap(Entry::getKey, c -> c.getValue().getRate()))));
	}
	
	/*
	 * Коэфициент перевода указанной валюты в указанную валюту продажи.
	 */
	public BigDecimal getRate(Map<String, Map<String, BigDecimal>> rates, BigDecimal rate, Currency saleCurrency,
			Currency priceCurrency, Currency commissionCurrency) throws InvalidCurrencyPairException {
		if (commissionCurrency == null
				|| commissionCurrency == priceCurrency) {
			return rate;
		}
		return getCoeffRate(rates, commissionCurrency, saleCurrency);
	}

}
