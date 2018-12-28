package com.gillsoft.commission;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;

import com.gillsoft.client.RestClient;

import com.gillsoft.model.CalcType;
import com.gillsoft.model.Commission;
import com.gillsoft.model.Currency;
import com.gillsoft.model.Lang;
import com.gillsoft.model.Price;
import com.gillsoft.model.Tariff;
import com.gillsoft.model.ValueType;
import com.gillsoft.ms.entity.BaseEntity;
import com.gillsoft.ms.entity.User;

public class Calculator {

	@Autowired
	private static RestClient client;

	private static final MathContext ROUND = new MathContext(2, RoundingMode.HALF_UP);

	public static Price calculateResource(Price price, User user, Currency saleCurrency) {
		final double l_base_tariff = price.getTariff().getValue().doubleValue(); // -- Значение базового тарифа p_base_tariff
		final double[] l_clear_tariff_vt_fixed = new double[] {0};
		final double[] l_clear_tariff_vt_percent = new double[] {0};
		final double[] l_clear_tariff_vt_percent_add = new double[] {0};
		final double[] l_clear_tariff_cur = new double[] {0}; // -- Значение вычисленного чистого тарифа в валюте тарифа
		final double[] l_clear_tariff_vat_cur = new double[] {0}; // -- Значение НДС для вычисленного чистого тарифа в валюте тарифа
		List<CommissionCalc> clear_commissions = new ArrayList<>();
		final double[] l_tariff_cur = new double[] {0};
		
		Map<String, Map<String, BigDecimal>> rates = getRates(user);
		
		if (price.getCommissions() == null || price.getCommissions().isEmpty()) {
			price.setAmount(price.getAmount().multiply(BigDecimal.valueOf(getCoeffRate(rates, price.getCurrency(), saleCurrency))).round(ROUND));
			price.setCurrency(saleCurrency);
		} else {
			price.getCommissions().stream().forEach(c -> clear_commissions.add(new CommissionCalc(c)));
			// базовый тариф для вычислений (очистка тарифа)
			// значение очищенного тарифа в валюте тарифа
			clear_commissions.stream().filter(f -> f.getCommission().getValueCalcType().equals(CalcType.IN)).forEach(c -> {
				switch (c.getCommission().getType()) {
				case FIXED:
					l_clear_tariff_vt_fixed[0] += c.getCommission().getValue().doubleValue() * getCoeffRate(rates, c.getCommission().getCurrency(), price.getCurrency());
					break;
				case PERCENT:
					l_clear_tariff_vt_percent[0] += c.getCommission().getValue().doubleValue();
					break;
				default:
					break;
				}
			});
			// l_clear_tariff_cur := (l_base_tariff - l_clear_tariff_vt_fixed) / (l_clear_tariff_vt_percent / 100 + 1);
			l_clear_tariff_cur[0] = (price.getTariff().getValue().doubleValue() - l_clear_tariff_vt_fixed[0]) / (l_clear_tariff_vt_percent[0] /100 + 1);
			
			// НДС к тарифу
			/*for i in 1 .. l_commissions_count loop
			    if (p_commisions(i).rc_commission_type = tbl_commissions.c_ct_vat) then
			      if (p_commisions(i).commission_type = tbl_commissions.c_vt_percent) then
			        l_clear_tariff_vat_cur := l_clear_tariff_vat_cur + (l_clear_tariff_cur * (p_commisions(i).commission / 100));
			      else
			        l_clear_tariff_vat_cur := l_clear_tariff_vat_cur + (l_clear_tariff_cur * (p_commisions(i).commission * f_get_coef_rate(p_commisions(i).cur_id, p_ticket.tariff_cur_id)));
			      end if;
			    end if;
			  end loop;*/
			clear_commissions.stream().forEach(c -> {
				switch (c.getCommission().getType()) {
				case FIXED:
					l_clear_tariff_vat_cur[0] += l_clear_tariff_vat_cur[0] * (c.getCommission().getValue().doubleValue() * getCoeffRate(rates, c.getCommission().getCurrency(), price.getCurrency()));
					break;
				case PERCENT:
					l_clear_tariff_vat_cur[0] += l_clear_tariff_vat_cur[0] * (c.getCommission().getValue().doubleValue() / 100);
					break;
				default:
					break;
				}
			});
			
			// full_commissions
			clear_commissions.stream().forEach(c -> {
				double l_full_commission = 0;
				switch (c.getCommission().getType()) {
				case FIXED:
					l_full_commission = 1;
					break;
				case PERCENT:
					//-- TODO если ¿НЕ станция¿ и "поверх" или "от" - то всегда от тарифа
					if (c.getCommission().getValueCalcType().equals(CalcType.OUT) || c.getCommission().getValueCalcType().equals(CalcType.FROM)) {
						l_full_commission = price.getTariff().getValue().doubleValue();
					} else {
						if (c.getCommission().getVat().compareTo(BigDecimal.ZERO) > 0) {
							l_full_commission = l_clear_tariff_cur[0] + l_clear_tariff_vat_cur[0];
						} else { //-- остальное от "чистого тарифа"
							l_full_commission = price.getTariff().getValue().doubleValue();
						}
					}
					if (l_full_commission != 0) {
						l_full_commission = l_full_commission * getCoeffRate(rates, price.getCurrency(), c.getCommission().getCurrency()) / 100;
					}
					break;
				default:
					break;
				}
				// l_full_commission := p_commisions(i).commission * l_full_commission;
				l_full_commission = c.getCommission().getValue().doubleValue() * l_full_commission;
				// НДС к сборам c округлением
			    // p_commisions(i).cur_commission_vat := round(l_full_commission * (1 - (1 / (1 + (p_commisions(i).commission_vat / 100)))), l_round);
				c.setCurCommissionVat(BigDecimal.valueOf(l_full_commission * (1 - (1 / (1 + (c.getCommission().getVat().doubleValue() / 100))))).round(ROUND));
				// -- значение чистого сбора c округлением "в сторону" НДС
			    // -- сбор без НДС в валюте сбора
				// clear_commissions(clear_commissions.last).cur_commission := l_full_commission - p_commisions(i).cur_commission_vat;
			    c.setCurCommission(BigDecimal.valueOf(l_full_commission - c.getCurCommissionVat().doubleValue()));
			    float l_coef_rate = getCoeffRate(rates, c.getCommission().getCurrency(), saleCurrency);
			    // -- сбор без НДС в валюте продажи
			    // clear_commissions(clear_commissions.last).clear_commission := l_full_commission * l_coef_rate - round(p_commisions(i).cur_commission_vat * l_coef_rate, l_round);
			    c.setClearCommission(BigDecimal.valueOf(l_full_commission * l_coef_rate - (c.getCurCommissionVat().doubleValue() * l_coef_rate)).round(ROUND));
			    // -- НДС в валюте продажи
			    // clear_commissions(clear_commissions.last).clear_commission_vat := p_commisions(i).cur_commission_vat * l_coef_rate;
			    c.setClearCommissionVat(BigDecimal.valueOf(c.getCurCommissionVat().doubleValue() * l_coef_rate));
			});
			
			if (!clear_commissions.isEmpty()) {
				//  if (length(p_ticket.tariff_code) = 1 or substr(p_ticket.tariff_code, 2, 1) != 'Z' or clear_commissions(i).rc_commission_type != tbl_commissions.c_ct_vat) then
			    
				clear_commissions.stream().forEach(c -> {
//			      -- только для очищающих тариф сборов
//			      if (clear_commissions(i).commission_io = tbl_commissions.c_viot_inside
//			       and clear_commissions(i).rc_commission_type in (tbl_commissions.c_ct_bus_station, tbl_commissions.c_ct_insurance, tbl_commissions.c_ct_vat)) then
//			        if (clear_commissions(i).commission_type = tbl_commissions.c_vt_percent) then
//			          if (clear_commissions(i).commission_vat > 0) then
//			            l_tariff_cur := l_tariff_cur + nvl(round(clear_commissions(i).commission * (l_clear_tariff_cur + l_clear_tariff_vat_cur) / 100, l_round), 0);
//			          else
//			            l_tariff_cur := l_tariff_cur + nvl(round(clear_commissions(i).commission * l_clear_tariff_cur / 100, l_round), 0);
//			          end if;
//			        else
//			          l_tariff_cur := l_tariff_cur + nvl(round(clear_commissions(i).commission * f_get_coef_rate(clear_commissions(i).cur_id, p_ticket.tariff_cur_id), l_round), 0);
//			        end if;
//			      end if;
					/*if (c.getCommission().getValueCalcType().equals(CalcType.IN)) {
						switch (c.getCommission().getType()) {
						case PERCENT:
							if (c.getCommission().getVat().compareTo(BigDecimal.ZERO) > 0) {
								l_tariff_cur[0] += l_tariff_cur[0] + BigDecimal.valueOf(c.getCommission().getValue().doubleValue() * (l_clear_tariff_cur[0] + l_clear_tariff_vat_cur[0]) / 100).round(ROUND).doubleValue();
							} else {
								l_tariff_cur[0] += l_tariff_cur[0] + BigDecimal.valueOf(c.getCommission().getValue().doubleValue() * getCoeffRate(rates, c.getCommission().getCurrency(), price.getCurrency())).round(ROUND).doubleValue();
							}
							break;
						case FIXED:
							l_tariff_cur[0] += l_tariff_cur[0] + BigDecimal.valueOf(c.getCommission().getValue().doubleValue() * getCoeffRate(rates, c.getCommission().getCurrency(), price.getCurrency())).round(ROUND).doubleValue();
							break;
						default:
							break;
						}
					}*/
					
					int i = price.getCommissions().indexOf(c.getCommission());
					if (i != -1) {
						price.getCommissions().get(i).setCurrency(saleCurrency);
						price.getCommissions().get(i).setValue(c.getClearCommission());
						price.getCommissions().get(i).setVat(c.getClearCommissionVat());
					}
				});
			    l_tariff_cur[0] = l_base_tariff - l_tariff_cur[0];
			    // -- откорректированный очищенный тариф (+- копейка)
			    // l_coef_rate := f_get_coef_rate(p_ticket.tariff_cur_id, p_ticket.sale_cur_id);
			    float l_coef_rate = getCoeffRate(rates, price.getCurrency(), saleCurrency);
			    //price.setAmount(price.getAmount().add(getTotal(clear_commissions)));
			    price.setAmount(BigDecimal.valueOf(l_tariff_cur[0] * l_coef_rate + getTotal(clear_commissions).doubleValue()));
			    price.setCurrency(saleCurrency);
			}
		}
		return price;
	}
	
	private static Map<String, Map<String, BigDecimal>> getRates(User user) {
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
		if (parent.getParents() != null && !parent.getParents().isEmpty()) {
			fillRates(parent.getParents().iterator().next(), rates);
		}
		try {
			Map<String, Map<String, BigDecimal>> parentRates = client.getCachedRates(String.valueOf(parent.getId()));
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

	private static BigDecimal getTotal(List<CommissionCalc> clear_commissions) {
		if (clear_commissions == null || clear_commissions.isEmpty()) {
			return BigDecimal.ZERO;
		}
		double l_commissions_total[] = new double[] {0};
		clear_commissions.stream().filter(f -> f.getCommission().getValueCalcType().equals(CalcType.OUT)).forEach(
				c -> l_commissions_total[0] += c.getClearCommission().add(c.getClearCommissionVat()).doubleValue());
		return BigDecimal.valueOf(l_commissions_total[0]);
	}

	public static void main(String[] args) {
		client = new RestClient(); //TODO
		
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
		price.setAmount(new BigDecimal(1000));
		price.setCurrency(Currency.UAH);
		price.setVat(BigDecimal.ZERO);
		Tariff tariff = new Tariff();
		tariff.setValue(new BigDecimal(1000));
		price.setTariff(tariff);
		List<Commission> commissions = new ArrayList<>();
		commissions.add(newCommission("C1F", Currency.UAH, ValueType.FIXED, new BigDecimal(5), CalcType.OUT, BigDecimal.ZERO, CalcType.OUT));
		commissions.add(newCommission("C1P", Currency.UAH, ValueType.FIXED, new BigDecimal(5), CalcType.IN, BigDecimal.ZERO, CalcType.IN));
		//commissions.add(newCommission("C1P", Currency.EUR, ValueType.PERCENT, new BigDecimal(5), CalcType.IN, BigDecimal.ZERO, CalcType.IN));
		price.setCommissions(commissions);
		Price newPrice = calculateResource(price, user, Currency.UAH);
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

	private static float getCoeffRate(Map<String, Map<String, BigDecimal>> rates, Currency currencyFrom, Currency currencyTo) throws LinkageError {
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
		// если не нашли курс у организации - ищем общий (organizationId = 0)
		if (!rates.containsKey("0")) {
			try {
				Map<String, Map<String, BigDecimal>> rates0 = client.getCachedRates("0");
				rates0.put("0", null);
				return getCoeffRate(rates0, currencyFrom, currencyTo);
			} catch (Exception e) { }
		}
		throw new LinkageError("No rate found for " + currencyFrom + '-' + currencyTo);
	}
	
}