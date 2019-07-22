package com.gillsoft.commission;

import java.io.File;
import java.math.BigDecimal;

import com.gillsoft.model.Commission;
import com.gillsoft.model.ReturnCondition;

public class CommissionCalc {

	private Commission commission;

	/**
	 * расчитаное значение комиссии в валюте продажи без НДС
	 */
	private BigDecimal clearCommission = BigDecimal.ZERO;

	/**
	 * расчитаное значение НДС в валюте продажи
	 */
	private BigDecimal clearCommissionVat = BigDecimal.ZERO;

	/**
	 * расчитаное значение комиссии  в валюте комиссии(cur_id) без НДС
	 */
	private BigDecimal curCommission = BigDecimal.ZERO;

	/**
	 * расчитаное значение НДС в валюте комиссии(cur_id)
	 */
	private BigDecimal curCommissionVat = BigDecimal.ZERO;

	/**
	 * сколько удерживаем со сбора
	 */
	private BigDecimal detainCommission = BigDecimal.ZERO;

	/**
	 * сбор к возврату С НДС
	 */
	private BigDecimal returnCommission = BigDecimal.ZERO;

	/**
	 * НДС сбора к возврату
	 */
	private BigDecimal returnCommissionVat = BigDecimal.ZERO;

	/**
	 * Условие возврата по комиссии
	 */
	ReturnCondition returnCondition;

	/**
	 * Процент возврата по текущему условию для данной комиссии
	 */
	BigDecimal returnConditionPercent = null;
	
	public CommissionCalc(Commission commission) {
		this.commission = commission;
	}
	
	public Commission getCommission() {
		return this.commission;
	}

	public CommissionCalc setCommission(Commission commission) {
		this.commission = commission;
		return this;
	}

	public BigDecimal getClearCommission() {
		/*if (this.clearCommission != null && returnConditionPercent != null) {
			return clearCommission.multiply(returnConditionPercent);
		}*/
		return clearCommission;
	}

	public CommissionCalc setClearCommission(BigDecimal clearCommission) {
		this.clearCommission = clearCommission;
		return this;
	}

	public BigDecimal getClearCommissionVat() {
		/*if (this.clearCommissionVat != null && returnConditionPercent != null) {
			return clearCommissionVat.multiply(returnConditionPercent);
		}*/
		return clearCommissionVat;
	}

	public CommissionCalc setClearCommissionVat(BigDecimal clearCommissionVat) {
		this.clearCommissionVat = clearCommissionVat;
		return this;
	}

	public BigDecimal getCurCommission() {
		return curCommission;
	}

	public CommissionCalc setCurCommission(BigDecimal curCommission) {
		this.curCommission = curCommission;
		return this;
	}

	public BigDecimal getCurCommissionVat() {
		return curCommissionVat;
	}

	public CommissionCalc setCurCommissionVat(BigDecimal curCommissionVat) {
		this.curCommissionVat = curCommissionVat;
		return this;
	}

	public BigDecimal getDetainCommission() {
		return detainCommission;
	}

	public CommissionCalc setDetainCommission(BigDecimal detainСommission) {
		this.detainCommission = detainСommission;
		return this;
	}

	public BigDecimal getReturnCommission() {
		return returnCommission;
	}

	public CommissionCalc setReturnCommission(BigDecimal returnСommission) {
		this.returnCommission = returnСommission;
		return this;
	}

	public BigDecimal getReturnCommissionVat() {
		return returnCommissionVat;
	}

	public CommissionCalc setReturnCommissionVat(BigDecimal returnСommissionVat) {
		this.returnCommissionVat = returnСommissionVat;
		return this;
	}

	public ReturnCondition getReturnCondition() {
		return returnCondition;
	}

	public CommissionCalc setReturnCondition(ReturnCondition returnCondition) {
		this.returnCondition = returnCondition;
		if (returnCondition != null && returnCondition.getReturnPercent() != null) {
			this.returnConditionPercent = returnCondition.getReturnPercent().divide(new BigDecimal(100));
		} else {
			this.returnConditionPercent = BigDecimal.ZERO;
		}
		return this;
	}

	public static void main(String[] args) {
		Calculator.main(null);
	}

}
