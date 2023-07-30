package com.gillsoft.commission;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.gillsoft.model.CalcType;
import com.gillsoft.model.Commission;
import com.gillsoft.model.Currency;
import com.gillsoft.model.Price;
import com.gillsoft.model.Tariff;
import com.gillsoft.model.ValueType;

public class PriceCalculator {
	
	// входящие параметры
	private Tariff tariff;
	private List<Commission> commissions;
	private Currency currency;
	private Currency priceCurrency;
	private Map<String, Map<String, BigDecimal>> rates;
	private BigDecimal rate;
	private RateService rateService;
	
	public void setTariff(Tariff tariff) {
		this.tariff = PriceUtils.copy(tariff);
	}

	public void setCommissions(List<Commission> commissions) {
		this.commissions = commissions;
	}

	public void setCurrency(Currency currency) {
		this.currency = currency;
	}

	public void setPriceCurrency(Currency priceCurrency) {
		this.priceCurrency = priceCurrency;
	}

	public void setRates(Map<String, Map<String, BigDecimal>> rates) {
		this.rates = rates;
	}

	public void setRate(BigDecimal rate) {
		this.rate = rate;
	}

	public void setRateService(RateService rateService) {
		this.rateService = rateService;
	}

	public Price create() throws InvalidCurrencyPairException {
		
		// сумма комиссий поверх
		BigDecimal commissionOut = BigDecimal.ZERO;
		BigDecimal commissionOutVat = BigDecimal.ZERO;
		
		// выделяем чистый тариф
		// считаем, что от ресурса получены все составляющие в одной валюте
		BigDecimal clearTariffValue = tariff.getValue();
		List<Commission> commissions = new ArrayList<>();
		
		if (this.commissions != null) {
			
			// очищаем от сборов внутри тарифа
			// отрицательные комиссии в очистке не учитываем
			// вычитаем фиксированные значения и считаем проценты
			BigDecimal commPercents = new BigDecimal(100);
			for (Commission commission : this.commissions) {
				if (commission.getValueCalcType() == CalcType.IN) {
					if (commission.getType() == ValueType.FIXED) {
						
						// переводим вычитаемую комиссию в валюту тарифа
						if (commission.getValue().compareTo(BigDecimal.ZERO) > 0) {
							Commission inTariffCurr = toCurr(commission, commission.getValue(),
									rateService.getRate(rates, rate, priceCurrency, priceCurrency, commission.getCurrency()));
							clearTariffValue = clearTariffValue.subtract(inTariffCurr.getValue());
						}
						addCommission(commissions, commission,
								rateService.getRate(rates, rate, currency, priceCurrency, commission.getCurrency()));
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
			BigDecimal percentTariff = clearTariffValue;
			for (Commission commission : this.commissions) {
				if (commission.getValueCalcType() == CalcType.IN
						&& commission.getType() == ValueType.PERCENT) {
					BigDecimal value = percentTariff.multiply(commission.getValue()).divide(commPercents, 2, RoundingMode.HALF_UP);
					if (commission.getValue().compareTo(BigDecimal.ZERO) > 0) {
						clearTariffValue = clearTariffValue.subtract(value);
					}
					addCommission(commissions, commission, value,
							rateService.getRate(rates, rate, currency, priceCurrency, priceCurrency));
				}
			}
			// считаем сборы OUT и FROM
			for (Commission commission : this.commissions) {
				if (commission.getValueCalcType() == CalcType.OUT
						|| commission.getValueCalcType() == CalcType.FROM) {
					Commission result = null;
					if (commission.getType() == ValueType.FIXED) {
						result = addCommission(commissions, commission,
								rateService.getRate(rates, rate, currency, priceCurrency, commission.getCurrency()));
						
					// процент считаем от чистого тарифа
					} else if (commission.getType() == ValueType.PERCENT) {
						BigDecimal value = clearTariffValue.multiply(commission.getValue()).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
						result = addCommission(commissions, commission, value,
								rateService.getRate(rates, rate, currency, priceCurrency, priceCurrency));
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
		tariff.setValue(Calculator.applyRate(tariff.getValue(), rate));
		tariff.setVat(Calculator.applyRate(tariff.getVat(),rate));
		tariff.setVatCalcType(CalcType.IN);
		tariff.setCurrency(currency);
		
		// итоговая стоимость
		Price result = new Price();
		result.setAmount(tariff.getValue().add(commissionOut));
		result.setVat(tariff.getVat().add(commissionOutVat));
		result.setVatCalcType(CalcType.IN);
		result.setCurrency(currency);
		result.setTariff(tariff);
		result.setCommissions(commissions);
		
		return result;
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
		Commission resultCommission = PriceUtils.copy(commission);
		resultCommission.setType(ValueType.FIXED);
		resultCommission.setValue(Calculator.applyRate(value, rate));
		setCommissionVat(resultCommission);
		if (resultCommission.getVat() != null) {
			resultCommission.setVat(Calculator.applyRate(commission.getVat(), rate));
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
			commission.setVatCalcType(CalcType.IN);
		}
		// в других случаях ндс без изменений
	}
	
}
