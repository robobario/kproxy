/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.config;

import io.kroxylicious.proxy.plugin.UnknownPluginInstanceException;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A PluginFactory is able to resolve reference to a plugin implementation (ie. a name) to that implementation.
 * @param <P> The plugin type
 */
public interface PluginFactory<P> {

    /**
     * Resolves a plugin reference to a plugin implementation.
     * @param name The name of the plugin implementation
     * @return The plugin implementation
     * @throws UnknownPluginInstanceException If the plugin implementation with the given name could not be found
     */
    @NonNull
    P pluginInstance(@NonNull String name);

    /**
     * Resolves a plugin reference to the plugins config type.
     * @param name The name of the plugin implementation
     * @return The plugin's config type'
     * @throws UnknownPluginInstanceException If the plugin implementation with the given name could not be found
     */
    @NonNull
    Class<?> configType(@NonNull String name);
}
