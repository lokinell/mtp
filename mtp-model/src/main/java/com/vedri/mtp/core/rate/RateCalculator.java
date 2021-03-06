package com.vedri.mtp.core.rate;

import org.joda.time.LocalDate;

/**
 * Gives rate for input currencyFrom. currencyTo for specific date.
 */
public interface RateCalculator {

	default Rate sellRate(String currencyFrom, String currencyTo, LocalDate date) throws NoRateException {
		final Rate.Key key = new Rate.Key(currencyFrom, currencyTo, date);
		return sellRate(key);
	}

	Rate sellRate(final Rate.Key key) throws NoRateException;

}
