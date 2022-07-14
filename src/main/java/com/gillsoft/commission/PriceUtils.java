package com.gillsoft.commission;

import java.io.IOException;

import org.springframework.util.SerializationUtils;

import com.gillsoft.model.Commission;
import com.gillsoft.model.Price;
import com.gillsoft.model.PricePart;
import com.gillsoft.model.Tariff;
import com.gillsoft.util.StringUtil;

public class PriceUtils {
	
	private PriceUtils() {
		
	}

	public static Price copy(Price price) {
		try {
			return StringUtil.jsonStringToObject(Price.class, StringUtil.objectToJsonString(price));
		} catch (IOException e) {
			return (Price) SerializationUtils.deserialize(SerializationUtils.serialize(price));
		}
	}
	
	public static Tariff copy(Tariff tariff) {
		try {
			return StringUtil.jsonStringToObject(Tariff.class, StringUtil.objectToJsonString(tariff));
		} catch (IOException e) {
			return (Tariff) SerializationUtils.deserialize(SerializationUtils.serialize(tariff));
		}
	}
	
	public static Commission copy(Commission commission) {
		try {
			return StringUtil.jsonStringToObject(Commission.class, StringUtil.objectToJsonString(commission));
		} catch (IOException e) {
			return (Commission) SerializationUtils.deserialize(SerializationUtils.serialize(commission));
		}
	}
	
	public static PricePart copy(PricePart pricePart) {
		try {
			return StringUtil.jsonStringToObject(PricePart.class, StringUtil.objectToJsonString(pricePart));
		} catch (IOException e) {
			return (PricePart) SerializationUtils.deserialize(SerializationUtils.serialize(pricePart));
		}
	}

}
