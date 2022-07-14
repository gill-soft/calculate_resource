package com.gillsoft.commission;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.gillsoft.model.ReturnCondition;

public class ReturnConditionsUtils {
	
	private ReturnConditionsUtils() {
		
	}

	public static List<ReturnCondition> getActualConditionList(List<ReturnCondition> conditions,
			int minutesBeforeDepart, boolean applyOnlyOwn) {
		if (conditions != null
				&& conditions.size() > 1) {
			ReturnCondition condition = getActualReturnCondition(conditions, minutesBeforeDepart, applyOnlyOwn);
			return condition != null ? Collections.singletonList(condition) : null;
		} else {
			return conditions;
		}
	}
	
	public static ReturnCondition getActualReturnCondition(List<ReturnCondition> returnConditions,
			int minutesBeforeDepart, boolean applyOnlyOwn) {
		if (returnConditions == null
				|| returnConditions.isEmpty()) {
			return null;
		}
		// собственные условия возврата
		List<ReturnCondition> ownConditions = returnConditions.stream()
				.filter(rc -> rc.getId() != null).collect(Collectors.toList());
		if (applyOnlyOwn) {
			return getActualReturnCondition(ownConditions, minutesBeforeDepart);
		}
		// по умолчанию собственным условиям возврата приоритет
		ReturnCondition ownCondition = getActualReturnCondition(ownConditions, minutesBeforeDepart);
		if (ownCondition != null) {
			return ownCondition;
		}
		return getActualReturnCondition(returnConditions, minutesBeforeDepart);
	}
	
	public static ReturnCondition getActualReturnCondition(List<ReturnCondition> returnConditions,
			int minutesBeforeDepart) {
		if (returnConditions == null
				|| returnConditions.isEmpty()) {
			return null;
		}
		// сортируем условия по времени (минуты) до даты отправления от большего к меньшему
		returnConditions.sort((returnCondition1, returnCondition2) -> {
			if (returnCondition2.getMinutesBeforeDepart() == null) {
				return 1;
			}
			if (returnCondition1.getMinutesBeforeDepart() == null) {
				return -1;
			}
			return returnCondition2.getMinutesBeforeDepart().compareTo(returnCondition1.getMinutesBeforeDepart());
		});
		Optional<ReturnCondition> returnConditionOptional = returnConditions.stream()
				.filter(rc -> rc.getMinutesBeforeDepart() == null || minutesBeforeDepart >= rc.getMinutesBeforeDepart()).findFirst();
		if (returnConditionOptional.isPresent()) {
			return returnConditionOptional.get();
		} else {
			return null;
		}
	}

}
