/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.config;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DatabindContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.jsontype.impl.TypeIdResolverBase;

public class PluginConfigTypeIdResolver extends TypeIdResolverBase {

    private final Map<String, ServiceBasedPluginFactoryRegistry.ProviderAndConfigType<?>> providers;
    private JavaType superType;

    PluginConfigTypeIdResolver(Map<String, ServiceBasedPluginFactoryRegistry.ProviderAndConfigType<?>> providers) {
        this.providers = providers;
    }

    @Override
    public void init(JavaType baseType) {
        superType = baseType;
    }

    @Override
    public String idFromValue(Object value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String idFromValueAndType(Object value, Class<?> suggestedType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public JsonTypeInfo.Id getMechanism() {
        throw new UnsupportedOperationException();
    }

    @Override
    public JavaType typeFromId(DatabindContext context, String id) {
        var provider = providers.get(id);
        return context.constructSpecializedType(superType, provider.config());
    }
}
