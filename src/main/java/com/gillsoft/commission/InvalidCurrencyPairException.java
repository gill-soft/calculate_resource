package com.gillsoft.commission;

public class InvalidCurrencyPairException extends Exception {

	private static final long serialVersionUID = -41069192632058045L;

	public InvalidCurrencyPairException() {
		super();
	}

	public InvalidCurrencyPairException(String message) {
		super(message);
	}

}
