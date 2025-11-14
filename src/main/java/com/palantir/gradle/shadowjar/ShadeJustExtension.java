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
import java.util.function.Consumer;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ExternalModuleDependency;

/**
 * Extension that adds the shadeJust() and shadeTransitively() methods to the dependencies block.
 * This allows users to write:
 * <pre>
 * dependencies {
 *     shadeJust('com.example:library:1.0') {
 *         transitiveFilter { dependency ->
 *             dependency.group.startsWith('com.internal.')
 *         }
 *     }
 *     shadeTransitively('com.example:library:1.0')
 * }
 * </pre>
 */
public final class ShadeJustExtension {
    private final Project project;
    private final ShadeJustRegistry registry;
    private final String configurationName;
    private final Consumer<Dependency> postAddCallback;
    private final boolean allowCustomFilters;

    private ShadeJustExtension(
            Project project,
            ShadeJustRegistry registry,
            String configurationName,
            Consumer<Dependency> postAddCallback,
            boolean allowCustomFilters) {
        this.project = project;
        this.registry = registry;
        this.configurationName = configurationName;
        this.postAddCallback = postAddCallback;
        this.allowCustomFilters = allowCustomFilters;
    }

    /**
     * Registers the extension with the project's dependency handler.
     * This makes the method available in the dependencies {} block.
     */
    public static void registerWith(Project project, ShadeJustRegistry registry, String name, String configurationName) {
        registerWith(project, registry, name, configurationName, null, true);
    }

    /**
     * Registers the extension with the project's dependency handler with a post-add callback.
     * This makes the method available in the dependencies {} block.
     */
    public static void registerWith(
            Project project,
            ShadeJustRegistry registry,
            String name,
            String configurationName,
            Consumer<Dependency> postAddCallback,
            boolean allowCustomFilters) {
        ShadeJustExtension extension =
                new ShadeJustExtension(project, registry, configurationName, postAddCallback, allowCustomFilters);
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
        if (postAddCallback != null && dep instanceof ExternalModuleDependency) {
            postAddCallback.accept(dep);
        }
        return dep;
    }

    /**
     * Adds a dependency to the configuration with configuration via Groovy closure.
     *
     * @param dependencyNotation the dependency notation (e.g., "group:name:version")
     * @param configureClosure closure to configure the ShadeJustDependencySpec
     * @return the created Dependency
     */
    public Dependency call(Object dependencyNotation, Closure<Void> configureClosure) {
        Dependency dep = project.getDependencies().add(configurationName, dependencyNotation);

        if (dep instanceof ExternalModuleDependency) {
            ShadeJustDependencySpec spec = new ShadeJustDependencySpec();

            // Configure the spec using the closure
            configureClosure.setDelegate(spec);
            configureClosure.setResolveStrategy(Closure.DELEGATE_FIRST);
            configureClosure.call(spec);

            // Check if a custom filter was provided when it's not allowed
            if (!allowCustomFilters && spec.getTransitiveFilter().isPresent()) {
                throw new IllegalArgumentException(
                        "shadeTransitively() does not support custom transitiveFilter. "
                                + "All transitives are automatically shaded. Use shadeJust() if you need to filter transitives.");
            }

            // Register the filter if one was configured
            spec.getTransitiveFilter().ifPresent(filter -> registry.registerFilter(dep, filter));

            if (postAddCallback != null) {
                postAddCallback.accept(dep);
            }
        }

        return dep;
    }

}
