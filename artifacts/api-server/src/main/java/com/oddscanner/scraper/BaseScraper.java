package com.oddscanner.scraper;

import com.oddscanner.domain.RawEvent;

import java.util.List;

public abstract class BaseScraper {

    public abstract String getSlug();

    public abstract String getName();

    public abstract List<RawEvent> fetchEvents() throws Exception;
}
