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
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ExternalModuleDependency;

/**
 * Extension that adds the shadeJust() method to the dependencies block.
 * This allows users to write:
 * <pre>
 * dependencies {
 *     shadeJust('com.example:library:1.0') {
 *         transitiveFilter { dependency ->
 *             dependency.group.startsWith('com.internal.')
 *         }
 *     }
 * }
 * </pre>
 */
public final class ShadeJustExtension {
    private final Project project;
    private final ShadeJustRegistry registry;

    private ShadeJustExtension(Project project, ShadeJustRegistry registry) {
        this.project = project;
        this.registry = registry;
    }

    /**
     * Registers the shadeJust extension with the project's dependency handler.
     * This makes shadeJust() available as a method in the dependencies {} block.
     */
    public static void registerWith(Project project, ShadeJustRegistry registry) {
        ShadeJustExtension extension = new ShadeJustExtension(project, registry);
        // Add as a public extension to DependencyHandler using Groovy convention
        // The name "shadeJust" makes methods on this object callable as dependencies.shadeJust(...)
        project.getDependencies().getExtensions().add("shadeJust", extension);
    }

    /**
     * Adds a dependency to the shadeJust configuration without any filter.
     * Only the direct dependency will be shaded.
     *
     * @param dependencyNotation the dependency notation (e.g., "group:name:version")
     * @return the created Dependency
     */
    public Dependency call(Object dependencyNotation) {
        // Add to shadeJustInternal for shading
        return project.getDependencies().add("shadeJustInternal", dependencyNotation);
    }

    /**
     * Adds a dependency to the shadeJust configuration with configuration via Groovy closure.
     *
     * @param dependencyNotation the dependency notation (e.g., "group:name:version")
     * @param configureClosure closure to configure the ShadeJustDependencySpec
     * @return the created Dependency
     */
    public Dependency call(Object dependencyNotation, Closure<Void> configureClosure) {
        Dependency dep = project.getDependencies().add("shadeJustInternal", dependencyNotation);

        if (dep instanceof ExternalModuleDependency) {
            ShadeJustDependencySpec spec = new ShadeJustDependencySpec();

            // Configure the spec using the closure
            configureClosure.setDelegate(spec);
            configureClosure.setResolveStrategy(Closure.DELEGATE_FIRST);
            configureClosure.call(spec);

            // Register the filter if one was configured
            spec.getTransitiveFilter().ifPresent(filter -> registry.registerFilter(dep, filter));
        }

        return dep;
    }

}
