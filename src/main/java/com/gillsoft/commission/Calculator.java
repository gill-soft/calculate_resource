package com.gillsoft.commission;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.util.SerializationUtils;

import com.gillsoft.client.CoupleRate;
import com.gillsoft.client.RestClient;
import com.gillsoft.model.CalcType;
import com.gillsoft.model.Commission;
import com.gillsoft.model.Currency;
import com.gillsoft.model.Discount;
import com.gillsoft.model.Price;
import com.gillsoft.model.ReturnCondition;
import com.gillsoft.model.Tariff;
import com.gillsoft.model.ValueType;
import com.gillsoft.ms.entity.BaseEntity;
import com.gillsoft.ms.entity.TariffMarkup;
import com.gillsoft.ms.entity.User;
import com.gillsoft.util.StringUtil;

@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
public class Calculator {

	@Autowired
	@Qualifier("CalculateRestClient")
	private RestClient client;

	private static final String DEFAULT_ORGANIZATION = "0";
	
	public Price copy(Price price) {
		try {
			return StringUtil.jsonStringToObject(Price.class, StringUtil.objectToJsonString(price));
		} catch (IOException e) {
			return (Price) SerializationUtils.deserialize(SerializationUtils.serialize(price));
		}
	}
	
	public Tariff copy(Tariff tariff) {
		try {
			return StringUtil.jsonStringToObject(Tariff.class, StringUtil.objectToJsonString(tariff));
		} catch (IOException e) {
			return (Tariff) SerializationUtils.deserialize(SerializationUtils.serialize(tariff));
		}
	}
	
	public Commission copy(Commission commission) {
		try {
			return StringUtil.jsonStringToObject(Commission.class, StringUtil.objectToJsonString(commission));
		} catch (IOException e) {
			return (Commission) SerializationUtils.deserialize(SerializationUtils.serialize(commission));
		}
	}
	
	public Price calculateResource(Price price, User user, Currency currency) {
		return calculateResource(price, user, currency, null);
	}

	public Price calculateResource(Price price, User user, Currency currency, List<TariffMarkup> tariffMarkups) {
		Map<String, Map<String, BigDecimal>> rates = getRates(user);
		
		// коэфициент перевода стоимости в валюту продажи
		BigDecimal rate = getCoeffRate(rates, price.getCurrency(), currency);
		
		// итоговый тариф
		Tariff tariff = null;
		if (price.getTariff() != null) {
			tariff = copy(price.getTariff());
		} else {
			tariff = new Tariff();
		}
		if (tariff.getValue() == null) {
			tariff.setValue(price.getAmount());
			tariff.setVat(price.getVat());
		}
		if (tariff.getVat() == null) {
			tariff.setVat(BigDecimal.ZERO);
		}
		// добавляем к тарифу надбавки
		tariff.setValue(applyMarkups(rates, tariff.getValue(), price.getCurrency(), tariffMarkups));
		
		// сумма комиссий поверх
		BigDecimal commissionOut = BigDecimal.ZERO;
		BigDecimal commissionOutVat = BigDecimal.ZERO;
		
		// выделяем чистый тариф
		// считаем, что от ресурса получены все составляющие в одной валюте
		BigDecimal clearTariff = tariff.getValue();
		List<Commission> commissions = new ArrayList<>();
		
		if (price.getCommissions() != null) {
			
			// очищаем от сборов внутри тарифа
			// отрицательные комиссии в очистке не учитываем
			// вычитаем фиксированные значения и считаем проценты
			BigDecimal commPercents = new BigDecimal(100);
			for (Commission commission : price.getCommissions()) {
				if (commission.getValueCalcType() == CalcType.IN) {
					if (commission.getType() == ValueType.FIXED) {
						
						// переводим вычитаемую комиссию в валюту тарифа
						if (commission.getValue().compareTo(BigDecimal.ZERO) > 0) {
							Commission inTariffCurr = toCurr(commission, commission.getValue(),
									getRate(rates, rate, price.getCurrency(), price.getCurrency(), commission.getCurrency()));
							clearTariff = clearTariff.subtract(inTariffCurr.getValue());
						}
						addCommission(commissions, commission,
								getRate(rates, rate, currency, price.getCurrency(), commission.getCurrency()));
					} else if (commission.getType() == ValueType.PERCENT
							&& commission.getValue().compareTo(BigDecimal.ZERO) > 0) {
						commPercents = commPercents.add(commission.getValue());
					}
				}
				// все отрицательные комиссии (скидки) считаем как OUT
				if (commission.getValue().compareTo(BigDecimal.ZERO) < 0) {
					commission.setValueCalcType(CalcType.OUT);
				}
			}
			// отрицательные комиссии в очистке не учитываем
			// вычитываем процентные значения
			BigDecimal percentTariff = clearTariff;
			for (Commission commission : price.getCommissions()) {
				if (commission.getValueCalcType() == CalcType.IN
						&& commission.getType() == ValueType.PERCENT) {
					BigDecimal value = percentTariff.multiply(commission.getValue()).divide(commPercents, 2, RoundingMode.HALF_UP);
					if (commission.getValue().compareTo(BigDecimal.ZERO) > 0) {
						clearTariff = clearTariff.subtract(value);
					}
					addCommission(commissions, commission, value,
							getRate(rates, rate, currency, price.getCurrency(), price.getCurrency()));
				}
			}
			// считаем сборы OUT и FROM
			for (Commission commission : price.getCommissions()) {
				if (commission.getValueCalcType() == CalcType.OUT
						|| commission.getValueCalcType() == CalcType.FROM) {
					Commission result = null;
					if (commission.getType() == ValueType.FIXED) {
						result = addCommission(commissions, commission,
								getRate(rates, rate, currency, price.getCurrency(), commission.getCurrency()));
						
					// процент считаем от чистого тарифа
					} else if (commission.getType() == ValueType.PERCENT) {
						BigDecimal value = clearTariff.multiply(commission.getValue()).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
						result = addCommission(commissions, commission, value,
								getRate(rates, rate, currency, price.getCurrency(), price.getCurrency()));
					}
					if (result != null 
							&& commission.getValueCalcType() == CalcType.OUT
							&& commission.getValue().compareTo(BigDecimal.ZERO) > 0) {
						commissionOut = commissionOut.add(result.getValue());
						commissionOutVat = commissionOutVat.add(result.getVat());
					}
				}
			}
		}
		// переводим в валюту
		tariff.setValue(tariff.getValue().multiply(rate).setScale(2, RoundingMode.HALF_UP));
		tariff.setVat(tariff.getVat().multiply(rate).setScale(2, RoundingMode.HALF_UP));
		
		// итоговая стоимость
		Price result = new Price();
		result.setAmount(tariff.getValue().add(commissionOut));
		result.setVat(tariff.getVat().add(commissionOutVat));
		result.setCurrency(currency);
		result.setTariff(tariff);
		result.setCommissions(commissions);
		
		// выделяем скидки и проставляем валюту комиссиям
		List<Discount> discounts = new ArrayList<>();
		for (Iterator<Commission> iterator = commissions.iterator(); iterator.hasNext();) {
			Commission commission = iterator.next();
			commission.setCurrency(currency);
			if (commission.getValue().compareTo(BigDecimal.ZERO) < 0) {
				discounts.add(new Discount(commission));
				iterator.remove();
			}
		}
		if (!discounts.isEmpty()) {
			result.setDiscounts(discounts);
		}
		return result;
	}
	
	private BigDecimal applyMarkups(Map<String, Map<String, BigDecimal>> rates, BigDecimal tariffValue, Currency tariffCurrency, List<TariffMarkup> tariffMarkups) {
		if (tariffMarkups == null
				|| tariffMarkups.isEmpty()) {
			return tariffValue;
		}
		BigDecimal markupAmount = BigDecimal.ZERO;
		for (TariffMarkup tariffMarkup : tariffMarkups) {
			if (tariffMarkup.getValueType() == com.gillsoft.ms.entity.ValueType.PERCENT) {
				markupAmount = markupAmount.add(tariffValue.multiply(tariffMarkup.getValue()).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP));
			} else {
				BigDecimal rate = getCoeffRate(rates, Currency.valueOf(tariffMarkup.getCurrency().name()), tariffCurrency);
				markupAmount = markupAmount.add(tariffMarkup.getValue().multiply(rate).setScale(2, RoundingMode.HALF_UP));
			}
		}
		return tariffValue.add(markupAmount);
	}
	
	/*
	 * Коэфициент перевода указанной валюты в указанную валюту продажи.
	 */
	private BigDecimal getRate(Map<String, Map<String, BigDecimal>> rates, BigDecimal rate,
			Currency saleCurrency, Currency priceCurrency, Currency commissionCurrency) {
		if (commissionCurrency == null
				|| commissionCurrency == priceCurrency) {
			return rate;
		}
		return getCoeffRate(rates, commissionCurrency, saleCurrency);
	}
	
	private Commission addCommission(List<Commission> commissions, Commission commission, BigDecimal rate) {
		return addCommission(commissions, commission, commission.getValue(), rate);
	}
	
	private Commission addCommission(List<Commission> commissions, Commission commission, BigDecimal value, BigDecimal rate) {
		Commission resultCommission = toCurr(commission, value, rate);
		commissions.add(resultCommission);
		return resultCommission;
	}
	
	private Commission toCurr(Commission commission, BigDecimal value, BigDecimal rate) {
		Commission resultCommission = copy(commission);
		resultCommission.setType(ValueType.FIXED);
		resultCommission.setValue(value.multiply(rate).setScale(2, RoundingMode.HALF_UP));
		setCommissionVat(resultCommission);
		if (resultCommission.getVat() != null) {
			resultCommission.setVat(commission.getVat().multiply(rate).setScale(2, RoundingMode.HALF_UP));
		}
		return resultCommission;
	}
	
	private void setCommissionVat(Commission commission) {
		if ((commission.getId() != null // считаем, что для собственного ресурса ндс в %
				&& commission.getVat() != null)
				|| (commission.getId() == null // для сборов ресурса считаем НДС как % только где сама комиссия как %
						&& commission.getVat() != null
						&& commission.getType() == ValueType.PERCENT)) {
			commission.setVat(commission.getValue().multiply(commission.getVat()).divide(new BigDecimal(100).add(commission.getVat()), 2, RoundingMode.HALF_UP));
		}
		// в других случаях ндс без изменений
	}

	/**
	 * 
	 * @param price
	 * 		Стоимость, по которой была выполнена продажа
	 * @param resourcePrice
	 * 		Стоимость возврата рассчитанная самим ресурсом (может быть null)
	 * @param user
	 * 		Пользователь выполняющий возврат
	 * @param currency
	 * 		Валюта возврата
	 * @param currentDate
	 * 		Текущая дата в таймзоне пункта отправления
	 * @param departureDate
	 * 		Дата отправления
	 * @return
	 * 		Метод возвращает рассчитанную стоимость возврата com.gillsoft.model.Price:
			    в amount - сумму к возврату
			    в tariff - сумму к возврату от тарифа и условие, по которому выполнен возврат
			    в commissions - суммы возврата и условие, по которому выполнен возврат, по каждой комиссии
	 */
	public Price calculateReturn(Price price, Price resourcePrice, User user, Currency currency,
			Date currentDate, Date departureDate) {
		Map<String, Map<String, BigDecimal>> rates = getRates(user);
		
		// получаем время до даты отправления
		int minutesBeforeDepart = (int) ((departureDate.getTime() - currentDate.getTime()) / 60000);
		
		// новая стоимость
		Price result = new Price();
		result.setCurrency(currency);
		
		// сперва расчитываем возврат для стоимости не учитывая данные от ресурса
		// получаем актуальные условия возврата и курс валют
		BigDecimal rate = getCoeffRate(rates, price.getCurrency(), currency);
		ReturnCondition tariffCondition = getActualReturnCondition(price.getTariff().getReturnConditions(), minutesBeforeDepart, false);
		
		// расчитываем возврат тарифа
		Tariff tariff = copy(price.getTariff());
		tariff.setValue(calcReturn(tariffCondition, tariff.getValue(), rate));
		tariff.setVat(calcReturn(tariffCondition, tariff.getVat(), rate));
		tariff.setReturnConditions(Collections.singletonList(tariffCondition));
		result.setTariff(tariff);
		
		// сумма возврата комиссий поверх
		BigDecimal amount = tariff.getValue();
		BigDecimal vat = tariff.getVat();
		
		// возврат для комиссий
		List<Commission> commissions = new ArrayList<>(price.getCommissions().size());
		for (Commission commission : price.getCommissions()) {
			
			Commission resultCommission = copy(commission);
			
			// по сборам ресурса, которые внутри тарифа считаем, что они удерживаются 100%
			if (resultCommission.getId() == null
					&& (resultCommission.getValueCalcType() == CalcType.IN
							|| resultCommission.getValueCalcType() == CalcType.FROM)) {
				resultCommission.setValue(BigDecimal.ZERO);
				resultCommission.setVat(BigDecimal.ZERO);
			} else {
				ReturnCondition commissionCondition = getActualReturnCondition(commission.getReturnConditions(), minutesBeforeDepart,
						commission.getId() != null);
				if (commissionCondition == null) {
					commissionCondition = getActualReturnCondition(price.getTariff().getReturnConditions(), minutesBeforeDepart,
							commission.getId() != null);
				}
				resultCommission.setValue(calcReturn(commissionCondition, resultCommission.getValue(), rate));
				resultCommission.setVat(calcReturn(commissionCondition, resultCommission.getVat(), rate));
				if (commissionCondition != null) {
					resultCommission.setReturnConditions(Collections.singletonList(commissionCondition));
				}
				if (resultCommission.getValueCalcType() == CalcType.OUT) {
					amount = amount.add(resultCommission.getValue());
					vat = vat.add(resultCommission.getVat());
				}
			}
			commissions.add(resultCommission);
		}
		result.setCommissions(commissions);
		result.setAmount(amount);
		result.setVat(vat);
		
		// проверяем данные от ресурса
		if (resourcePrice != null) {
			BigDecimal resourceRate = getCoeffRate(rates, resourcePrice.getCurrency(), currency);
			
			// возвраты комиссий
			if (resourcePrice.getCommissions() != null) {
				for (Commission resourceCommission : resourcePrice.getCommissions()) {
					if (resourceCommission.getValue() != null) {
						Commission resultCommission = copy(resourceCommission);
						resultCommission.setValue(resultCommission.getValue().multiply(resourceRate).setScale(2, RoundingMode.HALF_UP));
						if (resultCommission.getVat() != null) {
							resultCommission.setVat(resultCommission.getVat().multiply(resourceRate).setScale(2, RoundingMode.HALF_UP));
						}
						resultCommission.setReturnConditions(getActualConditionList(resultCommission.getReturnConditions(), minutesBeforeDepart, false));
						
						// находим эту комиссию в просчитанных данных и обновляем
						for (Commission commission : result.getCommissions()) {
							if (Objects.equals(resultCommission.getCode(), commission.getCode())) {
								if (resultCommission.getVat() != null) {
									commission.setVat(resultCommission.getVat());
								} else {
									commission.setVat(calcVat(commission.getVat(), commission.getValue(), resourceCommission.getValue()));
								}
								commission.setValue(resultCommission.getValue());
								commission.setReturnConditions(resultCommission.getReturnConditions());
								break;
							}
						}
					}
				}
			}
			// сумма возврата комиссий поверх
			BigDecimal overAllCommissions = BigDecimal.ZERO;
			BigDecimal overAllCommissionsVat = BigDecimal.ZERO;
			
			// сумма возврата собственных комиссий поверх
			BigDecimal overOwnCommissions = BigDecimal.ZERO;
			BigDecimal overOwnCommissionsVat = BigDecimal.ZERO;
			for (Commission commission : result.getCommissions()) {
				if (commission.getValueCalcType() == CalcType.OUT) {
					overAllCommissions = overAllCommissions.add(commission.getValue());
					overAllCommissionsVat = overAllCommissionsVat.add(commission.getVat());
					if (commission.getId() != null) {
						overOwnCommissions = overOwnCommissions.add(commission.getValue());
						overOwnCommissionsVat = overOwnCommissionsVat.add(commission.getVat());
					}
				}
			}
			// если ресурс вернул стоимость возврата
			if (resourcePrice.getAmount() != null) {
				result.setAmount(resourcePrice.getAmount().multiply(resourceRate).add(overOwnCommissions).setScale(2, RoundingMode.HALF_UP));
				if (resourcePrice.getVat() != null) {
					result.setVat(resourcePrice.getVat().multiply(resourceRate).add(overOwnCommissionsVat).setScale(2, RoundingMode.HALF_UP));
					
				// если ндс нет, то берем пропорционально от данных продажи
				} else if (price.getVat() != null) {
					result.setVat(calcVat(price.getVat(), price.getAmount(), result.getAmount()));
				}
			}
			// если есть тариф, то используем его
			// берем с тарифа стоимость и условие возврата
			// нужно принять во внимание то, что может присутствовать стоимость без условия и условие без стоимости
			if (resourcePrice.getTariff() != null) {
				Tariff resourceTariff = copy(resourcePrice.getTariff());
				
				// если есть тариф, то используем его
				if (resourceTariff.getValue() != null) {
					resourceTariff.setValue(resourceTariff.getValue().multiply(resourceRate).setScale(2, RoundingMode.HALF_UP));
					
				// если нет величины, то берем стоимость минус сборы поверх
				} else {
					resourceTariff.setValue(result.getAmount().subtract(overAllCommissions));
				}
				if (resourceTariff.getVat() != null) {
					resourceTariff.setVat(resourceTariff.getVat().multiply(resourceRate).setScale(2, RoundingMode.HALF_UP));
					
				// если ндс нет и есть тариф, то берем пропорционально от данных продажи
				} else if (resourceTariff.getValue() != null
						&& price.getTariff().getVat() != null) {
					resourceTariff.setVat(calcVat(price.getTariff().getVat(), price.getTariff().getValue(), resourceTariff.getValue()));
				}
				resourceTariff.setReturnConditions(getActualConditionList(resourceTariff.getReturnConditions(), minutesBeforeDepart, false));
				result.setTariff(resourceTariff);
			
			// если нет тарифа от ресурса, но была стоимость
			} else if (resourcePrice.getAmount() != null) {
				result.getTariff().setValue(result.getAmount().subtract(overAllCommissions));
				result.getTariff().setVat(result.getVat().subtract(overAllCommissionsVat));
			}
		}
		// очищаем повторяющиеся условия возврат
		if (result.getTariff().getReturnConditions() != null) {
			for (ReturnCondition condition : result.getTariff().getReturnConditions()) {
				for (Commission commission : result.getCommissions()) {
					if (commission.getReturnConditions() != null
							&& commission.getReturnConditions().contains(condition)) {
						commission.setReturnConditions(null);
					}
				}
			}
		}
		// проверяем наличие скидок и отнимаем их величину от суммы возврата
		if (price.getDiscounts() != null) {
			
			// значение скидки всегда отрицательное
			price.getDiscounts().forEach(d -> result.setAmount(result.getAmount().add(d.getValue())));
			if (BigDecimal.ZERO.compareTo(result.getAmount()) > 0) {
				result.setAmount(BigDecimal.ZERO);
			}
		}
		return result;
	}
	
	private BigDecimal calcVat(BigDecimal vat1, BigDecimal value1, BigDecimal value2) {
		if (value1.compareTo(BigDecimal.ZERO) == 0) {
			return BigDecimal.ZERO;
		}
		return vat1.multiply(value2).divide(value1, 2, RoundingMode.HALF_UP);
	}
	
	private BigDecimal calcReturn(ReturnCondition condition, BigDecimal value, BigDecimal rate) {
		if (condition == null
				|| condition.getReturnPercent() == null
				|| value == null
				|| value.compareTo(BigDecimal.ZERO) == 0) {
			return BigDecimal.ZERO;
		} else {
			return value.multiply(condition.getReturnPercent().multiply(new BigDecimal(0.01))).multiply(rate).setScale(2, RoundingMode.HALF_UP);
		}
	}
	
	private List<ReturnCondition> getActualConditionList(List<ReturnCondition> conditions,
			int minutesBeforeDepart, boolean applyOnlyOwn) {
		if (conditions != null
				&& conditions.size() > 1) {
			ReturnCondition condition = getActualReturnCondition(conditions, minutesBeforeDepart, applyOnlyOwn);
			return condition != null ? Collections.singletonList(condition) : null;
		} else {
			return conditions;
		}
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
	
	private Map<String, Map<String, BigDecimal>> convertToRatesMap(Map<String, Map<String, CoupleRate>> couples) {
		return couples.entrySet().stream().collect(Collectors.toMap(Entry::getKey,
				v -> v.getValue().entrySet().stream().collect(Collectors.toMap(Entry::getKey, c -> c.getValue().getRate()))));
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

	public BigDecimal getCoeffRate(Map<String, Map<String, BigDecimal>> rates, Currency currencyFrom, Currency currencyTo) throws LinkageError {
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
		throw new LinkageError("No rate found for " + currencyFrom + '-' + currencyTo);
	}
	
	public BigDecimal getCoupleDateCoeffRate(Map<String, Map<String, CoupleRate>> rates, Date date, Currency currencyFrom, Currency currencyTo) throws LinkageError {
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
		throw new LinkageError("No rate found for " + currencyFrom + '-' + currencyTo);
	}

	private ReturnCondition getActualReturnCondition(List<ReturnCondition> returnConditions,
			int minutesBeforeDepart, boolean applyOnlyOwn) {
		if (returnConditions == null
				|| returnConditions.isEmpty()) {
			return null;
		}
		// собственные условия возврата
		List<ReturnCondition> ownConditions = returnConditions.stream()
				.filter(rc -> rc.getId() != null).collect(Collectors.toList());
		if (applyOnlyOwn) {
			return getActualReturnCondition(ownConditions, minutesBeforeDepart);
		}
		// по умолчанию собственным условиям возврата приоритет
		ReturnCondition ownCondition = getActualReturnCondition(ownConditions, minutesBeforeDepart);
		if (ownCondition != null) {
			return ownCondition;
		}
		return getActualReturnCondition(returnConditions, minutesBeforeDepart);
	}
	
	private ReturnCondition getActualReturnCondition(List<ReturnCondition> returnConditions,
			int minutesBeforeDepart) {
		if (returnConditions == null
				|| returnConditions.isEmpty()) {
			return null;
		}
		// сортируем условия по времени (минуты) до даты отправления от большего к меньшему
		returnConditions.sort((returnCondition1, returnCondition2) -> returnCondition2
				.getMinutesBeforeDepart().compareTo(returnCondition1.getMinutesBeforeDepart()));
		Optional<ReturnCondition> returnConditionOptional = returnConditions.stream()
				.filter(rc -> minutesBeforeDepart >= rc.getMinutesBeforeDepart()).findFirst();
		if (returnConditionOptional.isPresent()) {
			return returnConditionOptional.get();
		} else {
			ReturnCondition returnCondition = returnConditions.get(returnConditions.size() - 1);
			if (minutesBeforeDepart < 0 && returnCondition.getMinutesBeforeDepart() >= 0) {
				return null;
			}
			return returnCondition;
		}
	}
	
}