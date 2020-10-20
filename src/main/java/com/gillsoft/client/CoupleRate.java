package com.gillsoft.client;

import java.io.Serializable;
import java.math.BigDecimal;

public class CoupleRate implements Serializable {

	private static final long serialVersionUID = 1910569720560257230L;

	private String id;
	private BigDecimal rate;

	public CoupleRate() {
		
	}

	public CoupleRate(String id, BigDecimal rate) {
		this.id = id;
		this.rate = rate;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public BigDecimal getRate() {
		return rate;
	}

	public void setRate(BigDecimal rate) {
		this.rate = rate;
	}

}
