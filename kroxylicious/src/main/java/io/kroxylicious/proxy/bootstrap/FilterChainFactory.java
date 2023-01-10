/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.bootstrap;

import io.kroxylicious.proxy.config.Configuration;
import io.kroxylicious.proxy.filter.KrpcFilter;
import io.kroxylicious.proxy.internal.filter.FilterContributorManager;

/**
 * Abstracts the creation of a chain of filter instances, hiding the configuration
 * required for instantiation at the point at which instances are created.
 * New instances are created during initialization of a downstream channel.
 */
public class FilterChainFactory {

    private final Configuration config;

    public FilterChainFactory(Configuration config) {
        this.config = config;
    }

    /**
     * Create a new chain of filter instances
     * @return the new chain.
     */
    public KrpcFilter[] createFilters() {
        FilterContributorManager filterContributorManager = FilterContributorManager.getInstance();

        return config.filters()
                .stream()
                .map(f -> filterContributorManager.getFilter(f.type(), config.proxy(), f.config()))
                .map(KrpcFilter::of)
                .toArray(KrpcFilter[]::new);
    }

}
