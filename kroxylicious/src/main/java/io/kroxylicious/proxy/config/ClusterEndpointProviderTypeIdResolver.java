/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.config;

import java.io.IOException;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DatabindContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.jsontype.impl.TypeIdResolverBase;

import io.kroxylicious.proxy.internal.clusterendpointprovider.ClusterEndpointProviderContributorManager;

public class ClusterEndpointProviderTypeIdResolver extends TypeIdResolverBase {
    private JavaType superType;

    @Override
    public void init(JavaType baseType) {
        superType = baseType;
    }

    @Override
    public String idFromValue(Object value) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public String idFromValueAndType(Object value, Class<?> suggestedType) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public JsonTypeInfo.Id getMechanism() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public JavaType typeFromId(DatabindContext context, String id) throws IOException {
        Class<?> subType = ClusterEndpointProviderContributorManager.getInstance().getConfigType(id);
        return context.constructSpecializedType(superType, subType);
    }
}
