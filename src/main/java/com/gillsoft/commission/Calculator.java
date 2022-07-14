package com.gillsoft.commission;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.gillsoft.client.CoupleRate;
import com.gillsoft.model.CalcType;
import com.gillsoft.model.Commission;
import com.gillsoft.model.Currency;
import com.gillsoft.model.Discount;
import com.gillsoft.model.Price;
import com.gillsoft.model.PricePart;
import com.gillsoft.model.ReturnCondition;
import com.gillsoft.model.Tariff;
import com.gillsoft.ms.entity.TariffMarkup;
import com.gillsoft.ms.entity.User;

@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
public class Calculator {
	
	@Autowired
	private RateService rateService;

	public Price copy(Price price) {
		return PriceUtils.copy(price);
	}
	
	public Tariff copy(Tariff tariff) {
		return PriceUtils.copy(tariff);
	}
	
	public Commission copy(Commission commission) {
		return PriceUtils.copy(commission);
	}
	
	public PricePart copy(PricePart pricePart) {
		return PriceUtils.copy(pricePart);
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
		
		PriceCalculator calculator = new PriceCalculator();
		calculator.setCommissions(getBeforeMarkup(price));
		calculator.setCurrency(currency);
		calculator.setPriceCurrency(price.getCurrency());
		calculator.setRate(rate);
		calculator.setRates(rates);
		calculator.setRateService(rateService);
		calculator.setTariff(tariff);
		Price clearPrice = calculator.create();
		
		// добавляем к тарифу надбавки
		tariff.setValue(applyMarkups(rates, tariff.getValue(), price.getCurrency(), tariffMarkups));
		
		calculator.setCommissions(getAfterMarkup(price));
		calculator.setTariff(tariff);
		
		// итоговая стоимость
		Price result = calculator.create();
		result.setClearPrice(clearPrice);
		
		// выделяем скидки и проставляем валюту комиссиям
		List<Discount> discounts = new ArrayList<>();
		for (Iterator<Commission> iterator = result.getCommissions().iterator(); iterator.hasNext();) {
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
		// переводим в валюту частичную оплату
		if (price.getPartialPayment() != null) {
			PricePart partialPayment = copy(price.getPartialPayment());
			BigDecimal partialRate = rateService.getRate(rates, rate, currency, price.getCurrency(), partialPayment.getCurrency());
			partialPayment.setValue(partialPayment.getValue().multiply(partialRate).setScale(2, RoundingMode.HALF_UP));
			if (partialPayment.getVat() != null) {
				partialPayment.setVat(partialPayment.getVat().multiply(partialRate).setScale(2, RoundingMode.HALF_UP));
			}
			partialPayment.setCurrency(currency);
			result.setPartialPayment(partialPayment);
		}
		return result;
	}
	
	private List<Commission> getBeforeMarkup(Price price) {
		return price.getCommissions() == null ? null :
				price.getCommissions().stream().filter(c -> isCommissionBeforeMarkup(c)).collect(Collectors.toList());
	}
	
	private List<Commission> getAfterMarkup(Price price) {
		return price.getCommissions() == null ? null :
				price.getCommissions().stream().filter(c -> !isCommissionBeforeMarkup(c)).collect(Collectors.toList());
	}
	
	private boolean isCommissionBeforeMarkup(Commission commission) {
		return commission.getAdditionals() != null
				&& (Boolean) commission.getAdditionals().get(Commission.APPLY_BEFORE_MARKUP_KEY);
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
	
	private boolean isIndividual(Price price) {
		return price != null
				&& price.isIndividual();
	}
	
	private BigDecimal applyRate(BigDecimal value, BigDecimal rate) {
		if (value == null) {
			return BigDecimal.ZERO;
		}
		if (rate == null) {
			return value;
		}
		return value.multiply(rate).setScale(2, RoundingMode.HALF_UP);
	}
	
	/**
	 * Возврат по индивидуальному условию. Какая сумма к возврату получена - ту
	 * и устанавливаем минуя все расчеты.
	 * 
	 * @param resourcePrice
	 * @param currency
	 * @param rates
	 * @return
	 */
	private Price calculateIndividualReturn(Price resourcePrice, Currency currency, Map<String, Map<String, BigDecimal>> rates) {
		
		// переводим полученные суммы в указанную валюту
		BigDecimal rate = getCoeffRate(rates, resourcePrice.getCurrency(), currency);

		// новая стоимость
		Price result = new Price();
		result.setCurrency(currency);
		result.setAmount(applyRate(resourcePrice.getAmount(), rate));
		result.setVat(applyRate(resourcePrice.getVat(), rate));
		result.setVatCalcType(CalcType.IN);
		
		// расчитываем возврат тарифа
		if (resourcePrice.getTariff() != null) {
			
		}
		Tariff tariff = copy(resourcePrice.getTariff());
		tariff.setCurrency(currency);
		tariff.setValue(applyRate(tariff.getValue(), rate));
		tariff.setVat(applyRate(tariff.getVat(), rate));
		tariff.setVatCalcType(CalcType.IN);
		if (resourcePrice.getTariff().getReturnConditions() != null
				&& !resourcePrice.getTariff().getReturnConditions().isEmpty()) {
			tariff.setReturnConditions(Collections.singletonList(resourcePrice.getTariff().getReturnConditions().get(0)));
		}
		result.setTariff(tariff);
		
		// сумма возврата комиссий поверх
		BigDecimal amount = tariff.getValue();
		BigDecimal vat = tariff.getVat();
		List<Commission> commissions = new ArrayList<>();
		if (resourcePrice.getCommissions() != null) {
			for (Commission commission : resourcePrice.getCommissions()) {
				
				Commission resultCommission = copy(commission);
				
				// по сборам ресурса, которые внутри тарифа считаем, что они удерживаются 100%
				if (resultCommission.getValueCalcType() == CalcType.OUT) {
					if (commission.getReturnConditions() != null
							&& !commission.getReturnConditions().isEmpty()) {
						resultCommission.setReturnConditions(Collections.singletonList(commission.getReturnConditions().get(0)));
					}
					resultCommission.setValue(applyRate(resultCommission.getValue(), rate));
					resultCommission.setVat(applyRate(resultCommission.getVat(), rate));
					resultCommission.setVatCalcType(CalcType.IN);
					amount = amount.add(resultCommission.getValue());
					vat = vat.add(resultCommission.getVat());
					commissions.add(resultCommission);
				}
			}
		}
		result.setCommissions(commissions);
		if (tariff.getValue().compareTo(BigDecimal.ZERO) > 0) {
			result.setAmount(amount);
			result.setVat(vat);
		} else {
			tariff.setValue(result.getAmount().subtract(amount));
			tariff.setVat(result.getVat().subtract(vat));
		}
		return result;
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
		if (isIndividual(resourcePrice)) {
			return calculateIndividualReturn(resourcePrice, currency, rates);
		}
		// получаем время до даты отправления
		int minutesBeforeDepart = (int) ((departureDate.getTime() - currentDate.getTime()) / 60000);
		
		// новая стоимость
		Price result = new Price();
		result.setCurrency(currency);
		
		// сперва расчитываем возврат для стоимости не учитывая данные от ресурса
		// получаем актуальные условия возврата и курс валют
		BigDecimal rate = getCoeffRate(rates, price.getCurrency(), currency);
		ReturnCondition tariffCondition = ReturnConditionsUtils.getActualReturnCondition(price.getTariff().getReturnConditions(), minutesBeforeDepart, false);
		
		// расчитываем возврат тарифа
		Tariff tariff = copy(price.getTariff());
		tariff.setValue(calcReturn(tariffCondition, tariff.getValue(), rate));
		tariff.setVat(calcReturn(tariffCondition, tariff.getVat(), rate));
		tariff.setVatCalcType(CalcType.IN);
		tariff.setCurrency(currency);
		tariff.setReturnConditions(Collections.singletonList(tariffCondition));
		result.setTariff(tariff);
		
		// сумма возврата комиссий поверх
		BigDecimal amount = tariff.getValue();
		BigDecimal vat = tariff.getVat();
		
		// возврат для комиссий
		List<Commission> commissions = new ArrayList<>();
		if (price.getCommissions() != null) {
			for (Commission commission : price.getCommissions()) {
				
				Commission resultCommission = copy(commission);
				
				// по сборам ресурса, которые внутри тарифа считаем, что они удерживаются 100%
				if (resultCommission.getId() == null
						&& (resultCommission.getValueCalcType() == CalcType.IN
								|| resultCommission.getValueCalcType() == CalcType.FROM)) {
					resultCommission.setValue(BigDecimal.ZERO);
					resultCommission.setVat(BigDecimal.ZERO);
				} else {
					ReturnCondition commissionCondition = ReturnConditionsUtils.getActualReturnCondition(commission.getReturnConditions(), minutesBeforeDepart,
							commission.getId() != null);
					if (commissionCondition == null) {
						commissionCondition = ReturnConditionsUtils.getActualReturnCondition(price.getTariff().getReturnConditions(), minutesBeforeDepart,
								commission.getId() != null);
					}
					resultCommission.setValue(calcReturn(commissionCondition, resultCommission.getValue(), rate));
					resultCommission.setVat(calcReturn(commissionCondition, resultCommission.getVat(), rate));
					resultCommission.setVatCalcType(CalcType.IN);
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
		}
		result.setCommissions(commissions);
		result.setAmount(amount);
		result.setVat(vat);
		result.setVatCalcType(CalcType.IN);
		
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
						resultCommission.setReturnConditions(ReturnConditionsUtils.getActualConditionList(resultCommission.getReturnConditions(), minutesBeforeDepart, false));
						
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
				resourceTariff.setReturnConditions(ReturnConditionsUtils.getActualConditionList(resourceTariff.getReturnConditions(), minutesBeforeDepart, false));
				result.setTariff(resourceTariff);
			
			// если нет тарифа от ресурса, но была стоимость
			} else if (resourcePrice.getAmount() != null) {
				result.getTariff().setValue(result.getAmount().subtract(overAllCommissions));
				result.getTariff().setVat(result.getVat().subtract(overAllCommissionsVat));
			}
		}
		// очищаем повторяющиеся условия возврата
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
	
	public BigDecimal getCoeffRate(Map<String, Map<String, BigDecimal>> rates, Currency currencyFrom, Currency currencyTo) throws LinkageError {
		return rateService.getCoeffRate(rates, currencyFrom, currencyTo);
	}
	
	public BigDecimal getCoupleDateCoeffRate(Map<String, Map<String, CoupleRate>> rates, Date date, Currency currencyFrom, Currency currencyTo) throws LinkageError {
		return rateService.getCoupleDateCoeffRate(rates, date, currencyFrom, currencyTo);
	}
	
	public Map<String, Map<String, BigDecimal>> getRates(User user) {
		return rateService.getRates(user);
	}
	
	public Map<String, Map<String, CoupleRate>> getCouples(User user) {
		return rateService.getCouples(user);
	}
	
}