package com.gillsoft.commission;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.util.SerializationUtils;

import com.gillsoft.client.RestClient;
import com.gillsoft.model.CalcType;
import com.gillsoft.model.Commission;
import com.gillsoft.model.Currency;
import com.gillsoft.model.Price;
import com.gillsoft.model.ReturnCondition;
import com.gillsoft.model.Tariff;
import com.gillsoft.model.ValueType;
import com.gillsoft.ms.entity.BaseEntity;
import com.gillsoft.ms.entity.User;

public class Calculator {

	private static RestClient client = new RestClient();

	private static final String DEFAULT_ORGANIZATION = "0";

	public static Price calculateResource(Price price, User user, Currency currency) {
		Map<String, Map<String, BigDecimal>> rates = getRates(user);
		BigDecimal rate = BigDecimal.valueOf(getCoeffRate(rates, price.getCurrency(), currency));
		
		// итоговый тариф
		Tariff tariff = null;
		if (price.getTariff() != null) {
			tariff = (Tariff) SerializationUtils.deserialize(SerializationUtils.serialize(price.getTariff()));
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
		// сумма комиссий поверх
		BigDecimal commissionOut = BigDecimal.ZERO;
		BigDecimal commissionOutVat = BigDecimal.ZERO;
		
		// выделяем чистый тариф
		// считаем, что от ресурса получены все составляющие в одной валюте
		BigDecimal clearTariff = tariff.getValue();
		List<Commission> commissions = new ArrayList<>();
		
		if (price.getCommissions() != null) {
			
			// очищаем от сборов внутри тарифа
			// вычитаем фиксированные значения и считаем проценты
			BigDecimal commPercents = new BigDecimal(100);
			for (Commission commission : price.getCommissions()) {
				if (commission.getValueCalcType() == CalcType.IN) {
					if (commission.getType() == ValueType.FIXED) {
						clearTariff = clearTariff.subtract(commission.getValue());
						addCommission(commissions, commission,
								getRate(rates, rate, currency, price.getCurrency(), commission.getCurrency()));
					} else if (commission.getType() == ValueType.PERCENT) {
						commPercents = commPercents.add(commission.getValue());
					}
				}
			}
			// вычитываем процентные значения
			BigDecimal percentTariff = clearTariff;
			for (Commission commission : price.getCommissions()) {
				if (commission.getValueCalcType() == CalcType.IN
						&& commission.getType() == ValueType.PERCENT) {
					BigDecimal value = percentTariff.multiply(commission.getValue()).divide(commPercents, 2, RoundingMode.HALF_UP);
					clearTariff = clearTariff.subtract(value);
					addCommission(commissions, commission, value,
							getRate(rates, rate, currency, price.getCurrency(), commission.getCurrency()));
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
								getRate(rates, rate, currency, price.getCurrency(), commission.getCurrency()));
					}
					if (result != null 
							&& commission.getValueCalcType() == CalcType.OUT) {
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
		return result;
	}
	
	private static BigDecimal getRate(Map<String, Map<String, BigDecimal>> rates, BigDecimal rate,
			Currency saleCurrency, Currency priceCurrency, Currency commissionCurrency) {
		if (commissionCurrency == null
				|| commissionCurrency == priceCurrency) {
			return rate;
		}
		return BigDecimal.valueOf(getCoeffRate(rates, priceCurrency, saleCurrency));
	}
	
	private static Commission addCommission(List<Commission> commissions, Commission commission, BigDecimal rate) {
		return addCommission(commissions, commission, commission.getValue(), rate);
	}
	
	private static Commission addCommission(List<Commission> commissions, Commission commission, BigDecimal value, BigDecimal rate) {
		Commission resultCommission = (Commission) SerializationUtils.deserialize(SerializationUtils.serialize(commission));
		resultCommission.setType(ValueType.FIXED);
		resultCommission.setValue(value.multiply(rate).setScale(2, RoundingMode.HALF_UP));
		setCommissionVat(resultCommission);
		if (commission.getVat() != null) {
			commission.setVat(commission.getVat().multiply(rate).setScale(2, RoundingMode.HALF_UP));
		}
		commissions.add(resultCommission);
		return resultCommission;
	}
	
	private static void setCommissionVat(Commission commission) {
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
	public static Price calculateReturn(Price price, Price resourcePrice, User user, Currency currency,
			Date currentDate, Date departureDate) {
		Map<String, Map<String, BigDecimal>> rates = getRates(user);
		
		// получаем время до даты отправления
		int minutesBeforeDepart = (int) ((departureDate.getTime() - currentDate.getTime()) / 60000);
		
		// новая стоимость
		Price result = new Price();
		result.setCurrency(currency);
		
		// сперва расчитываем возврат для стоимости не учитывая данные от ресурса
		// получаем актуальные условия возврата и курс валют
		BigDecimal rate = BigDecimal.valueOf(getCoeffRate(rates, price.getCurrency(), currency));
		ReturnCondition tariffCondition = getActualReturnCondition(price.getTariff().getReturnConditions(), minutesBeforeDepart);
		
		// расчитываем возврат тарифа
		Tariff tariff = (Tariff) SerializationUtils.deserialize(SerializationUtils.serialize(price.getTariff()));
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
			
			Commission resultCommission = (Commission) SerializationUtils.deserialize(SerializationUtils.serialize(commission));
			
			// по сборам ресурса, которые внутри тарифа считаем, что они удерживаются 100%
			if (resultCommission.getId() == null
					&& resultCommission.getValueCalcType() == CalcType.IN) {
				resultCommission.setValue(BigDecimal.ZERO);
				resultCommission.setVat(BigDecimal.ZERO);
			} else {
				ReturnCondition commissionCondition = getActualReturnCondition(commission.getReturnConditions(), minutesBeforeDepart);
				if (commissionCondition == null) {
					commissionCondition = tariffCondition;
				}
				resultCommission.setValue(calcReturn(commissionCondition, resultCommission.getValue(), rate));
				resultCommission.setVat(calcReturn(commissionCondition, resultCommission.getVat(), rate));
				resultCommission.setReturnConditions(Collections.singletonList(commissionCondition));
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
			BigDecimal resourceRate = BigDecimal.valueOf(getCoeffRate(rates, resourcePrice.getCurrency(), currency));
			
			// возвраты комиссий
			if (resourcePrice.getCommissions() != null) {
				for (Commission resourceCommission : resourcePrice.getCommissions()) {
					if (resourceCommission.getValue() != null) {
						Commission resultCommission = (Commission) SerializationUtils.deserialize(SerializationUtils.serialize(resourceCommission));
						resultCommission.setValue(resultCommission.getValue().multiply(resourceRate).setScale(2, RoundingMode.HALF_UP));
						if (resultCommission.getVat() != null) {
							resultCommission.setVat(resultCommission.getVat().multiply(resourceRate).setScale(2, RoundingMode.HALF_UP));
						}
						resultCommission.setReturnConditions(getActualConditionList(resultCommission.getReturnConditions(), minutesBeforeDepart));
						
						// находим эту комиссию в просчитанных данных и обновляем
						for (Commission commission : result.getCommissions()) {
							if (Objects.equals(resultCommission.getCode(), commission.getCode())) {
								if (resultCommission.getVat() != null) {
									commission.setVat(resultCommission.getVat());
								} else {
									commission.setVat(commission.getVat().multiply(resourceCommission.getValue())
											.divide(commission.getValue(), 2, RoundingMode.HALF_UP));
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
				result.setAmount(resourcePrice.getAmount().multiply(rate).add(overOwnCommissions).setScale(2, RoundingMode.HALF_UP));
				if (resourcePrice.getVat() != null) {
					result.setVat(resourcePrice.getVat().multiply(rate).add(overOwnCommissionsVat).setScale(2, RoundingMode.HALF_UP));
					
				// если ндс нет, то берем пропорционально от данных продажи
				} else if (price.getVat() != null) {
					result.setVat(price.getVat().multiply(result.getAmount())
							.divide(price.getAmount(), 2, RoundingMode.HALF_UP));
				}
			}
			// если есть тариф, то используем его
			// берем с тарифа стоимость и условие возврата
			// нужно принять во внимание то, что может присутствовать стоимость без условия и условие без стоимости
			if (resourcePrice.getTariff() != null) {
				Tariff resourceTariff = (Tariff) SerializationUtils.deserialize(SerializationUtils.serialize(resourcePrice.getTariff()));
				
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
					resourceTariff.setVat(price.getTariff().getVat().multiply(resourceTariff.getValue())
							.divide(price.getTariff().getValue(), 2, RoundingMode.HALF_UP));
				}
				resourceTariff.setReturnConditions(getActualConditionList(resourceTariff.getReturnConditions(), minutesBeforeDepart));
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
					commission.getReturnConditions().remove(condition);
				}
			}
		}
		return result;
	}
	
	private static BigDecimal calcReturn(ReturnCondition condition, BigDecimal value, BigDecimal rate) {
		if (condition == null
				|| condition.getReturnPercent() == null
				|| value == null
				|| value.compareTo(BigDecimal.ZERO) == 0) {
			return BigDecimal.ZERO;
		} else {
			return value.multiply(condition.getReturnPercent().multiply(new BigDecimal(0.01))).multiply(rate).setScale(2, RoundingMode.HALF_UP);
		}
	}
	
	private static List<ReturnCondition> getActualConditionList(List<ReturnCondition> conditions, int minutesBeforeDepart) {
		if (conditions != null
				&& conditions.size() > 1) {
			ReturnCondition condition = getActualReturnCondition(conditions, minutesBeforeDepart);
			return condition != null ? Collections.singletonList(condition) : null;
		} else {
			return conditions;
		}
	}
	
	public static Map<String, Map<String, BigDecimal>> getRates(User user) {
		Map<String, Map<String, BigDecimal>> rates = new HashMap<>();
		BaseEntity parent = user.getParents() != null && !user.getParents().isEmpty() ? user.getParents().iterator().next() : null;
		fillRates(parent, rates);
		// добавляем обратный курс если такого нет
		rates.forEach((keyMap, valueMap) -> valueMap.forEach((key, value) -> {
			if (!rates.containsKey(key)) {
				rates.put(key, new HashMap<>());
			}
			if (!rates.get(key).containsKey(keyMap)) {
				rates.get(key).put(keyMap, BigDecimal.ONE.divide(value, 8, BigDecimal.ROUND_HALF_UP));
			}
		}));
		return rates;
	}
	
	private static void fillRates(BaseEntity parent, Map<String, Map<String, BigDecimal>> rates) {
		if (parent != null && parent.getParents() != null && !parent.getParents().isEmpty()) {
			fillRates(parent.getParents().iterator().next(), rates);
		}
		try {
			Map<String, Map<String, BigDecimal>> parentRates = client.getCachedRates(parent != null ? String.valueOf(parent.getId()) : DEFAULT_ORGANIZATION);
			if (parentRates != null && !parentRates.isEmpty()) {
				parentRates.forEach((key, value) -> {
					Map<String, BigDecimal> keyRates = rates.get(key);
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

	public static float getCoeffRate(Map<String, Map<String, BigDecimal>> rates, Currency currencyFrom, Currency currencyTo) throws LinkageError {
		if (Objects.equals(currencyFrom, currencyTo)) {
			return 1f;
		}
		Map<String, BigDecimal> curFromRates = rates.get(String.valueOf(currencyFrom));
		if (curFromRates != null && !curFromRates.isEmpty()) {
			BigDecimal curToRate = curFromRates.get(String.valueOf(currencyTo));
			if (curToRate != null) {
				return curToRate.floatValue();
			}
		}
		// если не нашли курс у организации - ищем общий (organizationId = DEFAULT_ORGANIZATION)
		if (!rates.containsKey(DEFAULT_ORGANIZATION)) {
			try {
				Map<String, Map<String, BigDecimal>> rates0 = client.getCachedRates(DEFAULT_ORGANIZATION);
				rates0.put(DEFAULT_ORGANIZATION, null);
				return getCoeffRate(rates0, currencyFrom, currencyTo);
			} catch (Exception e) { }
		}
		throw new LinkageError("No rate found for " + currencyFrom + '-' + currencyTo);
	}

	private static ReturnCondition getActualReturnCondition(List<ReturnCondition> returnConditions, int minutesBeforeDepart) {
		if (returnConditions == null) {
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