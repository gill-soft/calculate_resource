package com.gillsoft.commission;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.gillsoft.client.RestClient;
import com.gillsoft.model.CalcType;
import com.gillsoft.model.Commission;
import com.gillsoft.model.Currency;
import com.gillsoft.model.Lang;
import com.gillsoft.model.Price;
import com.gillsoft.model.ReturnCondition;
import com.gillsoft.model.Tariff;
import com.gillsoft.model.ValueType;
import com.gillsoft.ms.entity.BaseEntity;
import com.gillsoft.ms.entity.User;

public class Calculator {

	private static RestClient client = new RestClient();

	private static final String DEFAULT_ORGANIZATION = "0";
	private static final BigDecimal BIG_DECIMAL_100 = BigDecimal.valueOf(100);

	public static Price calculateResource(Price price, User user, Currency saleCurrency) {
		final double baseTariff = price.getTariff().getValue().doubleValue(); // -- Значение базового тарифа p_base_tariff
		final double[] clearTariffVtFixed = { 0 };
		final double[] clearTariffVtPercent = { 0 };
		final double[] clearTariffCur = { 0 }; // -- Значение вычисленного чистого тарифа в валюте тарифа
		final double[] clearTariffVatCur = { 0 }; // -- Значение НДС для вычисленного чистого тарифа в валюте тарифа
		final double[] tariffCur = { 0 };
		// получаем курсы валют по организации пользователя
		Map<String, Map<String, BigDecimal>> rates = getRates(user);
		
		if (price.getCommissions() == null || price.getCommissions().isEmpty()) {
			price.setAmount(price.getAmount()
					.multiply(BigDecimal.valueOf(getCoeffRate(rates, price.getCurrency(), saleCurrency))).setScale(2, RoundingMode.HALF_UP));
			price.setCurrency(saleCurrency);
		} else {
			List<CommissionCalc> clearCommissions = new ArrayList<>();
			price.getCommissions().stream().forEach(c -> {
				if (c.getCurrency() == null) {
					c.setCurrency(price.getCurrency());
				}
				if (c.getVat() == null) {
					c.setVat(BigDecimal.ZERO);
					c.setVatCalcType(CalcType.IN);
				}
				clearCommissions.add(new CommissionCalc(c));
			});
			// базовый тариф для вычислений (очистка тарифа)
			// значение очищенного тарифа в валюте тарифа
			clearCommissions.stream().filter(f -> f.getCommission().getValueCalcType().equals(CalcType.IN))
					.forEach(c -> {
						switch (c.getCommission().getType()) {
						case FIXED:
							clearTariffVtFixed[0] += c.getCommission().getValue().doubleValue()
									* getCoeffRate(rates, c.getCommission().getCurrency(), price.getCurrency());
							break;
						case PERCENT:
							clearTariffVtPercent[0] += c.getCommission().getValue().doubleValue();
							break;
						default:
							break;
						}
					});
			clearTariffCur[0] = (price.getTariff().getValue().doubleValue() - clearTariffVtFixed[0])
					/ (clearTariffVtPercent[0] / 100 + 1);
			// НДС к тарифу
			clearCommissions.stream().forEach(c -> {
				switch (c.getCommission().getType()) {
				case FIXED:
					clearTariffVatCur[0] += clearTariffVatCur[0] * (c.getCommission().getValue().doubleValue()
							* getCoeffRate(rates, c.getCommission().getCurrency(), price.getCurrency()));
					break;
				case PERCENT:
					clearTariffVatCur[0] += clearTariffVatCur[0] * (c.getCommission().getValue().doubleValue() / 100);
					break;
				default:
					break;
				}
			});
			// full_commissions
			clearCommissions.stream().forEach(c -> {
				double fullCommission = 0;
				switch (c.getCommission().getType()) {
				case FIXED:
					fullCommission = 1;
					break;
				case PERCENT:
					//-- TODO если ¿НЕ станция¿ и "поверх" или "от" - то всегда от тарифа
					if (c.getCommission().getValueCalcType().equals(CalcType.OUT) || c.getCommission().getValueCalcType().equals(CalcType.FROM)) {
						fullCommission = price.getTariff().getValue().doubleValue();
					} else {
						if (c.getCommission().getVat().compareTo(BigDecimal.ZERO) > 0) {
							fullCommission = clearTariffCur[0] + clearTariffVatCur[0];
						} else { //-- остальное от "чистого тарифа"
							fullCommission = price.getTariff().getValue().doubleValue();
						}
					}
					if (fullCommission != 0) {
						fullCommission = fullCommission * getCoeffRate(rates, price.getCurrency(), c.getCommission().getCurrency()) / 100;
					}
					break;
				default:
					break;
				}
				fullCommission = c.getCommission().getValue().doubleValue() * fullCommission;
				// НДС к сборам c округлением
				c.setCurCommissionVat(BigDecimal
						.valueOf(fullCommission * (1 - (1 / (1 + (c.getCommission().getVat().doubleValue() / 100)))))
						.setScale(2, RoundingMode.HALF_UP));
				// -- значение чистого сбора c округлением "в сторону" НДС
				// -- сбор без НДС в валюте сбора
				c.setCurCommission(BigDecimal.valueOf(fullCommission - c.getCurCommissionVat().doubleValue()));
				float coefRate = getCoeffRate(rates, c.getCommission().getCurrency(), saleCurrency);
				// -- сбор без НДС в валюте продажи
				c.setClearCommission(BigDecimal
						.valueOf(fullCommission * coefRate - (c.getCurCommissionVat().doubleValue() * coefRate))
						.setScale(2, RoundingMode.HALF_UP));
				// -- НДС в валюте продажи
				c.setClearCommissionVat(BigDecimal.valueOf(c.getCurCommissionVat().doubleValue() * coefRate));
			});
			
			if (!clearCommissions.isEmpty()) {
				clearCommissions.stream().forEach(c -> {
					int i = price.getCommissions().indexOf(c.getCommission());
					if (i != -1) {
						price.getCommissions().get(i).setCurrency(saleCurrency);
						price.getCommissions().get(i).setValue(c.getClearCommission());
						price.getCommissions().get(i).setVat(c.getClearCommissionVat());
					}
				});
				tariffCur[0] = baseTariff - tariffCur[0];
			    // -- откорректированный очищенный тариф (+- копейка)
			    float coefRate = getCoeffRate(rates, price.getCurrency(), saleCurrency);
			    price.setAmount(BigDecimal.valueOf(tariffCur[0] * coefRate + getCommissionsTotal(clearCommissions).doubleValue()));
			    price.setCurrency(saleCurrency);
			}
		}
		return price;
	}

	public static Price calculateReturn(Price price, User user, Currency saleCurrency, Date dateCreate, Date dateDispatch) {
		final List<CommissionCalc> returnCommissions = new ArrayList<>();
		ValueType[] valueType = { null };
		BigDecimal[] value = { BigDecimal.ZERO };
		BigDecimal tariff = BigDecimal.ZERO;
		BigDecimal detainTariff = BigDecimal.ZERO;
		//BigDecimal detainForeignTariff = BigDecimal.ZERO;
		BigDecimal returnTariff = BigDecimal.ZERO;
	    BigDecimal returnTariffVat = BigDecimal.ZERO;
	    BigDecimal returnForeignTariff = BigDecimal.ZERO;
	    BigDecimal[] commissions = { BigDecimal.ZERO };
	    BigDecimal[] detain = { BigDecimal.ZERO };
	    BigDecimal[] clearTariff = { price.getTariff().getValue() };
	    BigDecimal[] clearTariffVat = { BigDecimal.ZERO };
		// получаем время до даты отправления
		int minutesBeforeDepart = (int)(dateDispatch.getTime() - new GregorianCalendar().getTime().getTime()) / 60000;
		// получаем актуальное условие возврата
		final ReturnCondition returnCondition = getActualReturnCondition(price.getTariff().getReturnConditions(), minutesBeforeDepart);
		// получаем курсы валют по организации пользователя
		//Map<String, Map<String, BigDecimal>> rates = getRates(user);
		if (price.getCommissions() != null && !price.getCommissions().isEmpty()) {
			price.getCommissions().stream().forEach(c -> {
				if (c.getCurrency() == null) {
					c.setCurrency(price.getCurrency());
				}
				if (c.getVat() == null) {
					c.setVat(BigDecimal.ZERO);
					c.setVatCalcType(CalcType.IN);
				}
				returnCommissions.add(new CommissionCalc(c).setClearCommission(c.getValue())
						.setClearCommissionVat(c.getVat() != null ? c.getVat() : BigDecimal.ZERO));
				clearTariff[0] = clearTariff[0].add(c.getValue());
				clearTariffVat[0] = clearTariffVat[0].add(c.getVat() != null ? c.getVat() : BigDecimal.ZERO);
			});
		}
		// ######## расчет величин возврата по тарифу и сборам (return_calc) ########
		// получаем данные возврата по тарифу
		valueType[0] = ValueType.PERCENT;
		// l_value := 100 - l_info(i).tariff_percent;
		value[0] = returnCondition == null ? BigDecimal.ZERO : new BigDecimal(100).subtract(returnCondition.getReturnPercent());
		// расчет по тарифу
	    /*if l_value_type = tbl_commissions.c_vt_percent then
	      -- при %-ном удержании величина удержания соотв. настройке
	      p_ticket.detain_tariff := l_value;
	      p_ticket.detain_foreign_tariff := l_value;
	    else
	      -- расчет величины удержания в абсолютной величине заданной в валюте тарифа
	      l_tariff := p_ticket.clear_tariff + p_ticket.clear_tariff_vat + p_ticket.clear_foreign_tariff;
	      -- проверка тарифа для удержаний
	      if l_tariff = 0 then
	        -- для нулевых тарифов абсолютное удержание не может быть отлично от 0
	        p_ticket.detain_tariff := 0;
	        p_ticket.detain_foreign_tariff := 0;
	      else
	        -- пересчитываем абсолютное удежрание в валюту продажи по пропорции не используя курсы валют
	        p_ticket.detain_tariff := round(l_value * (p_ticket.clear_tariff + p_ticket.clear_tariff_vat) / (p_ticket.tariff_cur + p_ticket.tariff_vat_cur), 2);
	        p_ticket.detain_foreign_tariff := round(l_value * p_ticket.clear_foreign_tariff / (p_ticket.tariff_cur + p_ticket.tariff_vat_cur), 2);
	      end if;
	    end if;*/
		if (ValueType.PERCENT.equals(valueType[0])) {
			detainTariff = value[0];
			//detainForeignTariff = value[0];
		} else {
			// расчет величины удержания в абсолютной величине заданной в валюте тарифа
			//l_tariff := p_ticket.clear_tariff + p_ticket.clear_tariff_vat + p_ticket.clear_foreign_tariff;
			tariff = price.getTariff().getValue();
			// проверка тарифа для удержаний
			if (!tariff.equals(BigDecimal.ZERO)) {
				// TODO пересчитываем абсолютное удержание в валюту продажи по пропорции не используя курсы валют
		        //detain_tariff = round(l_value * (p_ticket.clear_tariff + p_ticket.clear_tariff_vat) / (p_ticket.tariff_cur + p_ticket.tariff_vat_cur), 2);
		        //detain_foreign_tariff = round(l_value * p_ticket.clear_foreign_tariff / (p_ticket.tariff_cur + p_ticket.tariff_vat_cur), 2);
			}
		}
		
		// тариф в валюте тарифа
	    // l_tariff = p_ticket.tariff_cur + p_ticket.tariff_vat_cur;
		tariff = price.getTariff().getValue();
	    // расчет составляющих тарифа
		// return_tariff = calc_return(l_value_type, l_value, p_ticket.clear_tariff + p_ticket.clear_tariff_vat, l_tariff);
		returnTariff = calcReturn(valueType[0], value[0], clearTariff[0].add(clearTariffVat[0]), tariff);
		// return_tariff_vat = calc_return(l_value_type, l_value, p_ticket.clear_tariff_vat, l_tariff);
		returnTariffVat = calcReturn(valueType[0], value[0], clearTariffVat[0], tariff);
		// return_foreign_tariff = calc_return(l_value_type, l_value, p_ticket.clear_foreign_tariff, l_tariff);
		//returnForeignTariff = calcReturn(valueType[0], value[0], null, tariff);
	    
	    // возврат по сборам
		if (!returnCommissions.isEmpty()) {
			returnCommissions.forEach(returnCommission ->
				// настройки есть - расчитываем возврат по настройкам удержания
				returnCommission.setDetainCommission(value[0])
						.setReturnCommission(calcReturn(valueType[0], value[0],
								returnCommission.getClearCommission().add(returnCommission.getClearCommissionVat()),
								returnCommission.getCurCommission()))
						.setReturnCommissionVat(calcReturn(valueType[0], value[0],
								returnCommission.getClearCommissionVat(), returnCommission.getCurCommission()))
			);
		}
	    // ######## расчет величин возврата по тарифу и сборам (return_calc) ########

		// ######## подсчет суммы возврата (get_returned) ########
		if (!returnCommissions.isEmpty()) {
			returnCommissions.forEach(returnCommission -> {
				// получение величины возвращенных сборов
				if (returnCommission.getReturnCommission() != null
						&& returnCommission.getReturnCommission().compareTo(BigDecimal.ZERO) != 0
						&& returnCommission.getCommission().getValueCalcType().equals(CalcType.OUT)) {
					//l_commissions := l_commissions + p_ticket.commission_list(i).return_commission;
					commissions[0] = commissions[0].add(returnCommission.getReturnCommission());
				}
				// получение величины удержаний по сборам внутри тарифа
				if (returnCommission.getClearCommission() != null
						&& returnCommission.getClearCommissionVat() != null
						&& returnCommission.getReturnCommission() != null
						&& (returnCommission.getCommission().getValueCalcType().equals(CalcType.IN)
							|| returnCommission.getCommission().getValueCalcType().equals(CalcType.FROM))) {
					//l_detain := l_detain + (p_ticket.commission_list(i).clear_commission + p_ticket.commission_list(i).clear_commission_vat - p_ticket.commission_list(i).return_commission);
					detain[0] = detain[0]
							.add(returnCommission.getClearCommission().add(returnCommission.getClearCommissionVat())
									.subtract(returnCommission.getReturnCommission()));
				}
			});
		}
		// к возврату = <сумма возврата по тарифу> + <сумма возврата по сборам поверх> - <сумма удержаний внутри тарифа> и не меньше 0
		// return greatest(p_ticket.return_tariff + p_ticket.return_foreign_tariff + l_commissions - l_detain, 0);
		price.setAmount(returnTariff.add(returnForeignTariff).add(commissions[0]).subtract(detain[0]));
		if (price.getAmount().compareTo(BigDecimal.ZERO) < 0) {
			price.setAmount(BigDecimal.ZERO);
		}
		return price;
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
	 * 		Метод возвращает рассчитанную стоимость возврата com.gillsoft.model.Price returned.
			returned содержит:
			    в amount - сумму к возврату
			    в tariff - сумму к возврату от тарифа и условие, по которому выполнен возврат
			    в commissions - суммы возврата и условие, по которому выполнен возврат, по каждой комиссии
	 */
	public static Price calculateReturn(Price price, Price resourcePrice, User user, Currency currency,
			Date currentDate, Date departureDate) {
		final List<CommissionCalc> returnCommissions = new ArrayList<>();
		ValueType[] valueType = { null };
		BigDecimal[] value = { BigDecimal.ZERO };
		BigDecimal tariff = BigDecimal.ZERO;
		BigDecimal detainTariff = BigDecimal.ZERO;
		//BigDecimal detainForeignTariff = BigDecimal.ZERO;
		BigDecimal returnTariff = BigDecimal.ZERO;
	    BigDecimal returnTariffVat = BigDecimal.ZERO;
	    BigDecimal returnForeignTariff = BigDecimal.ZERO;
	    BigDecimal[] commissions = { BigDecimal.ZERO };
	    BigDecimal[] detain = { BigDecimal.ZERO };
	    BigDecimal[] clearTariff = { resourcePrice == null ? price.getTariff().getValue() : resourcePrice.getTariff().getValue() };
	    BigDecimal[] clearTariffVat = { BigDecimal.ZERO };
	    Price returned = resourcePrice == null ? new Price() : resourcePrice;
	    if (returned.getCommissions() == null) {
	    	returned.setCommissions(new ArrayList<>());
	    }
		// получаем время до даты отправления
		int minutesBeforeDepart = (int)(departureDate.getTime() - currentDate.getTime()) / 60000;
		// получаем актуальное условие возврата
		final ReturnCondition returnCondition = getActualReturnCondition(price.getTariff().getReturnConditions(), minutesBeforeDepart);
		// получаем курсы валют по организации пользователя
		Map<String, Map<String, BigDecimal>> rates = getRates(user);
		BigDecimal currencyCoefficient = BigDecimal.ONE;
		//BigDecimal resourcePriceCurrencyCoefficient = BigDecimal.ONE;
		// пересчитываем стоимость по возврату перевозчика
		if (resourcePrice != null && resourcePrice.getCurrency() != null) {
			if (!resourcePrice.getCurrency().equals(currency)) {
				currencyCoefficient = BigDecimal.valueOf(getCoeffRate(rates, resourcePrice.getCurrency(), currency));
			}
			returned.setAmount(returned.getAmount().multiply(currencyCoefficient).setScale(2, RoundingMode.HALF_UP));
			if (returned.getTariff() != null) {
				if (returned.getTariff().getValue() != null) {
					returned.getTariff().setValue(returned.getTariff().getValue().multiply(currencyCoefficient).setScale(2, RoundingMode.HALF_UP));
				}
				if (returned.getTariff().getVat() != null) {
					returned.getTariff().setVat(returned.getTariff().getVat().multiply(currencyCoefficient).setScale(2, RoundingMode.HALF_UP));
				}
			}
			clearTariff[0] = resourcePrice.getTariff().getValue();
			returned.setCurrency(currency);
		} else {
			currencyCoefficient = BigDecimal.valueOf(getCoeffRate(rates, price.getCurrency(), currency));
			price.setAmount(price.getAmount().multiply(currencyCoefficient).setScale(2, RoundingMode.HALF_UP));
			if (price.getTariff() != null) {
				if (price.getTariff().getValue() != null) {
					price.getTariff().setValue(price.getTariff().getValue().multiply(currencyCoefficient).setScale(2, RoundingMode.HALF_UP));
				}
				if (price.getTariff().getVat() != null) {
					price.getTariff().setVat(price.getTariff().getVat().multiply(currencyCoefficient).setScale(2, RoundingMode.HALF_UP));
				}
			}
			clearTariff[0] = price.getTariff().getValue();
			returned.setCurrency(currency);
		}
		//
		if (price.getCommissions() != null && !price.getCommissions().isEmpty()) {
			price.getCommissions().stream().filter(f -> f.getCode() != null).forEach(c -> {
				if (resourcePrice == null || (resourcePrice != null && resourcePrice.getCommissions() != null
						&& resourcePrice.getCommissions().stream()
								.filter(resourceCommission -> Objects.equals(resourceCommission.getCode(), c.getCode()))
								.count() == 0)) {
					if (c.getCurrency() == null) {
						c.setCurrency(price.getCurrency());
					}
					if (c.getVat() == null) {
						c.setVat(BigDecimal.ZERO);
						c.setVatCalcType(CalcType.IN);
					}
					ReturnCondition commissionReturnCondition = getCommissionReturnCondition(c, returnCondition,
							minutesBeforeDepart);
					CommissionCalc commissionCalc = new CommissionCalc(c).setClearCommission(c.getValue())
							.setClearCommissionVat(c.getVat() != null ? c.getVat() : BigDecimal.ZERO)
							.setReturnCondition(commissionReturnCondition);
					returnCommissions.add(commissionCalc);
					clearTariff[0] = clearTariff[0].add(commissionCalc.getClearCommission());
					clearTariffVat[0] = clearTariffVat[0].add(commissionCalc.getClearCommissionVat() != null
							? commissionCalc.getClearCommissionVat() : BigDecimal.ZERO);
					if (c.getCode() != null) {
						c.setReturnConditions(Arrays.asList(commissionReturnCondition));
						returned.getCommissions().add(c);
					}
				}
			});
		}
		// проверяем наличие актуальных условий возврата
		if (!returnCommissions.isEmpty()
				&& returnCommissions.stream().filter(f -> f.getReturnCondition() != null).count() != 0) {
			/*if (resourcePrice != null) {
				if (resourcePrice.getCommissions() != null) {
					returned.getCommissions().addAll(new ArrayList<>(resourcePrice.getCommissions()));
				}
				returned.setTariff(resourcePrice.getTariff());
			}*/
			// ######## расчет величин возврата по тарифу и сборам (return_calc) ########
			// получаем данные возврата по тарифу
			valueType[0] = ValueType.PERCENT;
			value[0] = returnCondition == null ? BigDecimal.ZERO : BIG_DECIMAL_100.subtract(returnCondition.getReturnPercent());
			if (ValueType.PERCENT.equals(valueType[0])) {
				detainTariff = value[0];
			} else {
				// расчет величины удержания в абсолютной величине заданной в валюте тарифа
				tariff = price.getTariff().getValue();
			}
			// тариф в валюте тарифа
			tariff = price.getTariff().getValue();
		    // расчет составляющих тарифа
			returnTariff = calcReturn(valueType[0], value[0], clearTariff[0].add(clearTariffVat[0]), tariff);
			returnTariffVat = calcReturn(valueType[0], value[0], clearTariffVat[0], tariff);
		    // возврат по сборам
			if (!returnCommissions.isEmpty()) {
				returnCommissions.forEach(returnCommission -> {
					// настройки есть - расчитываем возврат по настройкам удержания
					/*returnCommission.setDetainCommission(value[0])
							.setReturnCommission(calcReturn(valueType[0], value[0],
									returnCommission.getClearCommission().add(returnCommission.getClearCommissionVat()),
									returnCommission.getCurCommission()))
							.setReturnCommissionVat(calcReturn(valueType[0], value[0],
									returnCommission.getClearCommissionVat(), returnCommission.getCurCommission()))*/
					if (returnCommission.getReturnCondition() != null && returnCommission.getReturnCondition().getReturnPercent() != null) {
						returnCommission.setDetainCommission(BIG_DECIMAL_100.subtract(returnCommission.getReturnCondition().getReturnPercent()))
								.setReturnCommission(calcReturn(ValueType.PERCENT, returnCommission.getDetainCommission(),
										returnCommission.getClearCommission().add(returnCommission.getClearCommissionVat()),
										returnCommission.getCurCommission()))
								.setReturnCommissionVat(calcReturn(ValueType.PERCENT, returnCommission.getDetainCommission(),
										returnCommission.getClearCommissionVat(), returnCommission.getCurCommission()));
					}
				});
			}
		    // ######## расчет величин возврата по тарифу и сборам (return_calc) ########
			// ######## подсчет суммы возврата (get_returned) ########
			if (!returnCommissions.isEmpty()) {
				returnCommissions.forEach(returnCommission -> {
					// получение величины возвращенных сборов
					if (returnCommission.getReturnCommission() != null
							&& returnCommission.getReturnCommission().compareTo(BigDecimal.ZERO) != 0
							&& returnCommission.getCommission().getValueCalcType().equals(CalcType.OUT)) {
						commissions[0] = commissions[0].add(returnCommission.getReturnCommission());
					}
					// получение величины удержаний по сборам внутри тарифа
					if (returnCommission.getClearCommission() != null
							&& returnCommission.getClearCommissionVat() != null
							&& returnCommission.getReturnCommission() != null
							&& (returnCommission.getCommission().getValueCalcType().equals(CalcType.IN)
								|| returnCommission.getCommission().getValueCalcType().equals(CalcType.FROM))) {
						detain[0] = detain[0]
								.add(returnCommission.getClearCommission().add(returnCommission.getClearCommissionVat())
										.subtract(returnCommission.getReturnCommission()));
					}
				});
			}
			// к возврату = <сумма возврата по тарифу> + <сумма возврата по сборам поверх> - <сумма удержаний внутри тарифа> и не меньше 0
			if (resourcePrice != null) {
				returned.setAmount(returned.getAmount().add(commissions[0]).add(detain[0]));
			} else {
				returned.setAmount(returnTariff.add(returnForeignTariff).add(commissions[0]).add(detain[0]));
			}
			if (returned.getAmount().compareTo(BigDecimal.ZERO) < 0) {
				returned.setAmount(BigDecimal.ZERO);
			}
			if (returned.getTariff() == null) {
				returned.setTariff(new Tariff());
				returned.getTariff().setReturnConditions(Arrays.asList(returnCondition));
				returned.getTariff().setValue(returnTariff);
			}
			return returned;
		} else {
			return resourcePrice;
		}
	}

	/**
	 * Расчет суммы возврата составляющей тарифа/сбора с использованием валютной пропорции для абсолютного удержания (calc_return)
	 */
	private static BigDecimal calcReturn(ValueType tariffValueType, BigDecimal tariffValue, BigDecimal clearTariff, BigDecimal sum) {
		/*return round(case p_detain_type when tbl_commissions.c_vt_percent then
                p_value * (1 - p_detain_value / 100) when tbl_commissions.c_vt_fixed then case when
                p_sum = 0 then 0 else p_value * (p_sum - p_detain_value) / p_sum end else 0 end
               ,2);*/
		if (tariffValueType == null || tariffValue == null || clearTariff == null || BigDecimal.ZERO.equals(clearTariff)
				|| sum == null) {
			return BigDecimal.ZERO;
		}
		if (tariffValueType.equals(ValueType.PERCENT)) {
			//p_value * (1 - p_detain_value / 100)
			return clearTariff.multiply(BigDecimal.ONE.subtract(tariffValue.divide(new BigDecimal(100)))).setScale(2, RoundingMode.HALF_UP);
		} else {
			if (!sum.equals(BigDecimal.ZERO)) {
				//p_value * (p_sum - p_detain_value) / p_sum
				return clearTariff.multiply(sum.subtract(tariffValue)).divide(sum).setScale(2, RoundingMode.HALF_UP);
			}
		}
		return BigDecimal.ZERO;
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

	private static BigDecimal getCommissionsTotal(List<CommissionCalc> clear_commissions) {
		if (clear_commissions == null || clear_commissions.isEmpty()) {
			return BigDecimal.ZERO;
		}
		return BigDecimal.valueOf(
				clear_commissions.stream().filter(f -> f.getCommission().getValueCalcType().equals(CalcType.OUT))
						.mapToDouble(c -> c.getClearCommission().add(c.getClearCommissionVat()).doubleValue()).sum());
	}

	/*private static BigDecimal getReturnPercent(ReturnCondition returnCondition) {
		if (returnCondition.getDescription() != null && !returnCondition.getDescription().values().isEmpty()) {
			BigDecimal result = parseReturnCondition(returnCondition.getDescription().values().iterator().next(),
					returnCondition.getMinutesBeforeDepart());
			if (!result.equals(BigDecimal.ZERO)) {
				return result;
			}
		}
		if (returnCondition.getTitle() != null && !returnCondition.getTitle().values().isEmpty()) {
			BigDecimal result = parseReturnCondition(returnCondition.getTitle().values().iterator().next(),
					returnCondition.getMinutesBeforeDepart());
			if (!result.equals(BigDecimal.ZERO)) {
				return result;
			}
		}
		
		return BigDecimal.ZERO;
	}
	// TO DO Менше ніж 48 години, але більше ніж 10 хвилин до від'їзду – повертається 80% з вартості квитка.
	private static BigDecimal parseReturnCondition(String returnCondition, int minutesBeforeDepart) {
		Pattern pattern = Pattern.compile("(\\d+\\s{0,1}%)");
		Matcher matcher = pattern.matcher(returnCondition);
		if (matcher.find()) {
			return new BigDecimal(matcher.group().replaceAll("%| ", ""));
		}
		pattern = Pattern.compile("(\\d+)");
		matcher = pattern.matcher(returnCondition.replaceAll(" (" + minutesBeforeDepart + '|'
				+ (minutesBeforeDepart / 60) + ") ", ""));
		if (matcher.find()) {
			return new BigDecimal(matcher.group());
		}
		return BigDecimal.ZERO;
	}*/

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

	private static ReturnCondition createReturnCondition(String id, String description, int percent, int minutesBeforeDepart) {
		ReturnCondition rc = new ReturnCondition();
		rc.setId(id);
		rc.setDescription(description == null ? id : description);
		rc.setMinutesBeforeDepart(minutesBeforeDepart);
		rc.setReturnPercent(new BigDecimal(percent));
		rc.setTitle(description == null ? id : description);
		return rc;
	}

	private static ReturnCondition getCommissionReturnCondition(Commission commission,
			ReturnCondition tariffReturnCondition, int minutesBeforeDepart) {
		if (commission.getReturnConditions() == null || commission.getReturnConditions().isEmpty()) {
			return tariffReturnCondition;
		}
		return getActualReturnCondition(commission.getReturnConditions(), minutesBeforeDepart);
	}

	private static ReturnCondition getActualReturnCondition(List<ReturnCondition> returnConditions, int minutesBeforeDepart) {
		// сортируем условия по времени (минуты) до даты отправления от большего к меньшему
		returnConditions.sort((returnCondition1, returnCondition2) -> returnCondition2
				.getMinutesBeforeDepart().compareTo(returnCondition1.getMinutesBeforeDepart()));
		java.util.Optional<ReturnCondition> returnConditionOptional = returnConditions.stream()
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

	private static Commission newCommission(String code, Currency currency, ValueType type, BigDecimal value,
			CalcType valueCalcType, BigDecimal vat, CalcType vatCalcType) {
		Commission c = new Commission();
		c.setCode(code);
		c.setCurrency(currency);
		c.setDescription(Lang.EN, code + '|' + currency);
		c.setName(Lang.EN, code + '|' + currency);
		c.setType(type);
		c.setValue(value);
		c.setValueCalcType(valueCalcType);
		c.setVat(vat);
		c.setVatCalcType(vatCalcType);
		return c;
	}

	public static void main(String[] args) {
		/*List<Integer> list = Arrays.asList(3, 1, 2, 0, 5, -10);
		list.sort((i1, i2) -> i1.compareTo(i2));
		System.out.println(list);
		java.util.Optional<Integer> o = list.stream().filter(f -> f >= 3).findFirst();
		if (o.isPresent())
			System.out.println(o.get());
		if (true) return;*/
		
		//Date date = new GregorianCalendar(2019, GregorianCalendar.FEBRUARY, 22, 11, 0).getTime();
		//long l = (date.getTime() - new GregorianCalendar().getTime().getTime()) / 60000;
		//System.out.println(date);
		//System.out.println(l);
		
		/*ReturnCondition returnCondition = new ReturnCondition();
		java.util.concurrent.ConcurrentHashMap<Lang, String> map = new java.util.concurrent.ConcurrentHashMap<>();
		map.put(Lang.RU, "Понад 48 годин до від'їзду – повертається 85% з вартості квитка.");
		map.put(Lang.UA, "Понад 48 годин до від'їзду – повертається 85% з вартості квитка.");
		map.put(Lang.EN, "Ponad 48 godzin przed wyjazdem – wraca 85% kosztów biletu.");
		returnCondition.setDescription(map);
		returnCondition.setMinutesBeforeDepart(2880);
		getReturnPercent(returnCondition);
		if (true) return;*/

		//TODO
		client = new RestClient(); 

		User user = new User();
		user.setParents(new java.util.HashSet<>());
		BaseEntity parent1 = new BaseEntity();
		user.getParents().add(parent1);
		parent1.setId(1);
		parent1.setParents(new java.util.HashSet<>());
		BaseEntity parent2 = new BaseEntity();
		parent1.getParents().add(parent2);
		parent2.setId(2);
		/*Object o = getRates(user);
		if (o != null) {
			System.out.println();
		}*/

		Price price = new Price();
		price.setAmount(new BigDecimal(802));
		price.setCurrency(Currency.UAH);
		price.setVat(BigDecimal.ZERO);
		Tariff tariff = new Tariff();
		tariff.setValue(new BigDecimal(800));
		price.setTariff(tariff);
		//List<Commission> commissions = Arrays.asList(newCommission("C1F", Currency.UAH, ValueType.FIXED, new BigDecimal(50), CalcType.OUT, BigDecimal.ZERO, CalcType.OUT));
		List<Commission> commissions = new ArrayList<>(Arrays.asList(newCommission("insurance", Currency.UAH, ValueType.FIXED, new BigDecimal(8), CalcType.IN, BigDecimal.ZERO, CalcType.IN),
				newCommission("agent", Currency.UAH, ValueType.FIXED, new BigDecimal(120), CalcType.IN, BigDecimal.ZERO, CalcType.IN),
				newCommission("AGN_MATRIX_15", Currency.UAH, ValueType.FIXED, new BigDecimal(15), CalcType.IN, BigDecimal.ZERO, CalcType.IN),
				newCommission("C1", Currency.UAH, ValueType.FIXED, new BigDecimal(1), CalcType.IN, BigDecimal.ZERO, CalcType.IN),
				newCommission("C2", Currency.UAH, ValueType.FIXED, new BigDecimal(2), CalcType.OUT, BigDecimal.ZERO, CalcType.OUT)));
		//commissions.add(newCommission("C1F", Currency.UAH, ValueType.FIXED, new BigDecimal(50), CalcType.OUT, BigDecimal.ZERO, CalcType.OUT));
		//commissions.add(newCommission("C1P", Currency.UAH, ValueType.FIXED, new BigDecimal(5), CalcType.IN, BigDecimal.ZERO, CalcType.IN));
		//commissions.add(newCommission("C1P", Currency.EUR, ValueType.PERCENT, new BigDecimal(5), CalcType.IN, BigDecimal.ZERO, CalcType.IN));
		/*commissions.get(0)
				.setReturnConditions(Arrays.asList(createReturnCondition("C1F0", null, 10, 60),
						createReturnCondition("C1F50", null, 20, 720),
						createReturnCondition("C1F100", null, 100, 1440)));*/
		price.setCommissions(commissions);
		
		Price newPrice = null;
		// sale
		/*newPrice = calculateResource(price, user, Currency.UAH);
		if (newPrice != null)
			System.out.println(price.getAmount());*/
		
		// return
		price.getTariff()
				.setReturnConditions(Arrays.asList(createReturnCondition("1", null, 10, 180),
						createReturnCondition("2", null, 20, 360),
						createReturnCondition("3", null, 75, 720),
						createReturnCondition("4", null, 100, 1440)));
		/*price.getTariff().getReturnConditions().add(createReturnCondition("1", null, 10, 180));
		price.getTariff().getReturnConditions().add(createReturnCondition("2", null, 50, 360));
		price.getTariff().getReturnConditions().add(createReturnCondition("3", null, 75, 720));
		price.getTariff().getReturnConditions().add(createReturnCondition("4", null, 100, 1440));*/
		//newPrice = calculateReturn(price, user, Currency.UAH, null, new GregorianCalendar(2019, GregorianCalendar.FEBRUARY, 22, 15, 0).getTime());
		newPrice = calculateReturn(price, null /*getResourcePrice()*/, user, Currency.UAH,
				new GregorianCalendar(2019, GregorianCalendar.JULY, 4, 9, 0).getTime(),
				new GregorianCalendar(2019, GregorianCalendar.JULY, 4, 15, 0).getTime());
		if (newPrice != null)
			System.out.println(price.getAmount());

		/*RestClient client = new RestClient();
		try {
			client.getCachedRates("1");
		} catch (Exception e) {
			e.printStackTrace();
		}
		try {
			client.getCachedRates("1");
		} catch (Exception e) {
			e.printStackTrace();
		}*/

		/*Price price = new Price();
		price.setAmount(new BigDecimal(1000));
		price.setCurrency(Currency.EUR);
		price.setVat(BigDecimal.ZERO);
		Tariff tariff = new Tariff();
		tariff.setValue(new BigDecimal(1000));
		price.setTariff(tariff);
		List<Commission> commissions = new ArrayList<>();
		commissions.add(newCommission("C1F", Currency.EUR, ValueType.PERCENT, new BigDecimal(5), CalcType.OUT, BigDecimal.ZERO, CalcType.OUT));
		commissions.add(newCommission("C1P", Currency.EUR, ValueType.PERCENT, new BigDecimal(5), CalcType.IN, BigDecimal.ZERO, CalcType.IN));
		price.setCommissions(commissions);
		Price newPrice = calculateResource(price, null);
		if (newPrice != null)
			System.out.println(price.getAmount());*/

		/*Commission c = new Commission();
		c.setValue(BigDecimal.ONE);
		c.setType(ValueType.FIXED);
		c.setName(Lang.EN, "EN");
		Commission c2 = new Commission();
		c2 = (Commission)SerializationUtils.deserialize(SerializationUtils.serialize(c));
		c2.setName(Lang.EN, "EN2");
		if (c2 != null)
			System.out.println();*/
	}

	private static Price getResourcePrice() {
		Price price = new Price();
		price.setAmount(BigDecimal.valueOf(578));
		price.setCurrency(Currency.UAH);
		price.setVat(BigDecimal.ZERO);
		Tariff tariff = new Tariff();
		tariff.setValue(BigDecimal.valueOf(481.6667));
		price.setTariff(tariff);
		List<Commission> commissions = new ArrayList<>(Arrays.asList(newCommission("insurance", Currency.UAH, ValueType.FIXED, BigDecimal.valueOf(6.8), CalcType.IN, BigDecimal.ZERO, CalcType.IN),
				newCommission("agent", Currency.UAH, ValueType.FIXED, BigDecimal.valueOf(0), CalcType.IN, BigDecimal.ZERO, CalcType.IN)));
		price.setCommissions(commissions);
		return price;
	}

}