/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.filter;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.kroxylicious.proxy.filter.FilterFactory;
import io.kroxylicious.proxy.filter.FilterFactoryContext;
import io.kroxylicious.proxy.internal.filter.FetchResponseTransformationFilterFactory.Config;
import io.kroxylicious.proxy.plugin.PluginConfig;
import io.kroxylicious.proxy.plugin.PluginConfigType;
import io.kroxylicious.proxy.plugin.PluginReference;
import io.kroxylicious.proxy.plugin.Plugins;

@PluginConfigType(FetchResponseTransformationFilterFactory.Config.class)
public class FetchResponseTransformationFilterFactory
        implements FilterFactory<Config, Config> {

    @Override
    public Config initialize(FilterFactoryContext context, Config config) {
        return Plugins.requireConfig(this, config);
    }

    @Override
    public FetchResponseTransformationFilter createFilter(FilterFactoryContext context,
                                                          Config configuration) {
        var factory = context.pluginInstance(ByteBufferTransformationFactory.class, configuration.transformation());
        Objects.requireNonNull(factory, "Violated contract of FilterCreationContext");
        return new FetchResponseTransformationFilter(factory.createTransformation(configuration.config()));
    }

    public record Config(@JsonProperty(required = true) @PluginReference(ByteBufferTransformationFactory.class) String transformation,
                         @PluginConfig(instanceNameProperty = "transformation") Object config) {

    }

}
