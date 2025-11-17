/*
 * (c) Copyright 2025 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.gradle.shadowjar;

import groovy.lang.Closure;
import java.util.Optional;
import java.util.function.Consumer;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ExternalModuleDependency;

/**
 * Extension that adds the shadeJust() and shadeTransitively() methods to the dependencies block.
 * This allows users to write:
 * <pre>
 * dependencies {
 *     shadeJust('com.example:library:1.0') {
 *         withTransitives 'com.internal.*'
 *     }
 *     shadeTransitively('com.example:library:1.0')
 * }
 * </pre>
 */
public record ShadeExtension(
        Project project,
        ShadeRegistry registry,
        String configurationName,
        Optional<Consumer<Dependency>> postAddCallback,
        boolean allowCustomFilters) {

    /**
     * Registers the extension with the project's dependency handler.
     * This makes the method available in the dependencies {} block.
     */
    public static void registerWith(Project project, ShadeRegistry registry, String name, String configurationName) {
        ShadeExtension extension =
                new ShadeExtension(project, registry, configurationName, Optional.empty(), true);
        project.getDependencies().getExtensions().add(name, extension);
    }

    /**
     * Registers the extension with the project's dependency handler with a post-add callback.
     * This makes the method available in the dependencies {} block.
     */
    public static void registerWith(
            Project project,
            ShadeRegistry registry,
            String name,
            String configurationName,
            Consumer<Dependency> postAddCallback,
            boolean allowCustomFilters) {
        ShadeExtension extension = new ShadeExtension(
                project, registry, configurationName, Optional.of(postAddCallback), allowCustomFilters);
        // Add as a public extension to DependencyHandler using Groovy convention
        // The name makes methods on this object callable as dependencies.{name}(...)
        project.getDependencies().getExtensions().add(name, extension);
    }

    /**
     * Adds a dependency to the configuration without any filter.
     * Only the direct dependency will be shaded.
     *
     * @param dependencyNotation the dependency notation (e.g., "group:name:version")
     * @return the created Dependency
     */
    public Dependency call(Object dependencyNotation) {
        Dependency dep = project.getDependencies().add(configurationName, dependencyNotation);
        if (dep instanceof ExternalModuleDependency) {
            postAddCallback.ifPresent(callback -> callback.accept(dep));
        }
        return dep;
    }

    /**
     * Adds a dependency to the configuration with configuration via Groovy closure.
     *
     * @param dependencyNotation the dependency notation (e.g., "group:name:version")
     * @param configureClosure closure to configure the ShadeDependencySpec
     * @return the created Dependency
     */
    public Dependency call(Object dependencyNotation, Closure<Void> configureClosure) {
        Dependency dep = project.getDependencies().add(configurationName, dependencyNotation);

        if (dep instanceof ExternalModuleDependency) {
            ShadeDependencySpec spec = new ShadeDependencySpec();

            // Configure the spec using the closure
            configureClosure.setDelegate(spec);
            configureClosure.setResolveStrategy(Closure.DELEGATE_FIRST);
            configureClosure.call(spec);

            // Check if a custom filter was provided when it's not allowed
            if (!allowCustomFilters && spec.getTransitiveFilter().isPresent()) {
                throw new IllegalArgumentException(
                        "shadeTransitively() does not support custom transitive filters. All transitives are"
                                + " automatically shaded. Use shadeJust() if you need to filter transitives.");
            }

            // Register the filter if one was configured
            spec.getTransitiveFilter().ifPresent(filter -> registry.registerFilter(dep, filter));

            postAddCallback.ifPresent(callback -> callback.accept(dep));
        }

        return dep;
    }
}
