package com.gillsoft.commission;

import java.math.BigDecimal;

import com.gillsoft.model.Commission;

public class CommissionCalc {

	Commission commission;

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
	
	public CommissionCalc(Commission commission) {
		this.commission = commission;
	}
	
	public Commission getCommission() {
		return this.commission;
	}

	public void setCommission(Commission commission) {
		this.commission = commission;
	}

	public BigDecimal getClearCommission() {
		return clearCommission;
	}

	public void setClearCommission(BigDecimal clearCommission) {
		this.clearCommission = clearCommission;
	}

	public BigDecimal getClearCommissionVat() {
		return clearCommissionVat;
	}

	public void setClearCommissionVat(BigDecimal clearCommissionVat) {
		this.clearCommissionVat = clearCommissionVat;
	}

	public BigDecimal getCurCommission() {
		return curCommission;
	}

	public void setCurCommission(BigDecimal curCommission) {
		this.curCommission = curCommission;
	}

	public BigDecimal getCurCommissionVat() {
		return curCommissionVat;
	}

	public void setCurCommissionVat(BigDecimal curCommissionVat) {
		this.curCommissionVat = curCommissionVat;
	}

}
