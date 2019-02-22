package com.gillsoft.commission;

import java.math.BigDecimal;

import com.gillsoft.model.Commission;

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
	private BigDecimal detainСommission = BigDecimal.ZERO;

	/**
	 * сбор к возврату С НДС
	 */
	private BigDecimal returnСommission = BigDecimal.ZERO;

	/**
	 * НДС сбора к возврату
	 */
	private BigDecimal returnСommissionVat = BigDecimal.ZERO;
	
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
		return clearCommission;
	}

	public CommissionCalc setClearCommission(BigDecimal clearCommission) {
		this.clearCommission = clearCommission;
		return this;
	}

	public BigDecimal getClearCommissionVat() {
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

	public BigDecimal getDetainСommission() {
		return detainСommission;
	}

	public CommissionCalc setDetainСommission(BigDecimal detainСommission) {
		this.detainСommission = detainСommission;
		return this;
	}

	public BigDecimal getReturnСommission() {
		return returnСommission;
	}

	public CommissionCalc setReturnСommission(BigDecimal returnСommission) {
		this.returnСommission = returnСommission;
		return this;
	}

	public BigDecimal getReturnСommissionVat() {
		return returnСommissionVat;
	}

	public CommissionCalc setReturnСommissionVat(BigDecimal returnСommissionVat) {
		this.returnСommissionVat = returnСommissionVat;
		return this;
	}

}
