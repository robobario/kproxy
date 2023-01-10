/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.internal.filter;

import java.util.Iterator;
import java.util.ServiceLoader;

import io.kroxylicious.proxy.config.ProxyConfig;
import io.kroxylicious.proxy.filter.FilterContributor;

public class FilterContributorManager {

    private static final FilterContributorManager INSTANCE = new FilterContributorManager();

    private final ServiceLoader<FilterContributor> contributors;

    private FilterContributorManager() {
        this.contributors = ServiceLoader.load(FilterContributor.class);
    }

    public static FilterContributorManager getInstance() {
        return INSTANCE;
    }

    public Class<? extends FilterConfig> getConfigType(String shortName) {
        Iterator<FilterContributor> it = contributors.iterator();
        while (it.hasNext()) {
            FilterContributor contributor = it.next();
            Class<? extends FilterConfig> configType = contributor.getConfigType(shortName);
            if (configType != null) {
                return configType;
            }
        }

        throw new IllegalArgumentException("No filter found for name '" + shortName + "'");
    }

    public Object getFilter(String shortName, ProxyConfig proxyConfig, FilterConfig filterConfig) {
        Iterator<FilterContributor> it = contributors.iterator();
        while (it.hasNext()) {
            FilterContributor contributor = it.next();
            Object filter = contributor.getFilter(shortName, proxyConfig, filterConfig);
            if (filter != null) {
                return filter;
            }
        }

        throw new IllegalArgumentException("No filter found for name '" + shortName + "'");
    }
}
