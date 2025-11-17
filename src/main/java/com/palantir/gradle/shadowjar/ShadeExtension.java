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
import groovy.lang.DelegatesTo;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;

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
        Project project, ShadeRegistry registry, String configurationName, boolean isShadeTransitively) {

    /**
     * Shades only the specified direct dependency into the shadow jar.
     * Transitive dependencies are not shaded by default.
     *
     * @param self the DependencyHandler
     * @param dependencyNotation the dependency notation (e.g., "group:name:version")
     * @return the created Dependency
     */
    public static Dependency shadeJust(DependencyHandler self, String dependencyNotation) {
        ShadeExtension extension = (ShadeExtension) self.getExtensions().getByName("shadeJust");
        return extension.call(dependencyNotation);
    }

    /**
     * Shades only the specified direct dependency into the shadow jar.
     * Use the closure to configure which transitive dependencies to include.
     *
     * <pre>
     * shadeJust('com.example:library:1.0') {
     *     withTransitives 'com.google.guava:*', 'com.google.code.*'
     * }
     * </pre>
     *
     * @param self the DependencyHandler
     * @param dependencyNotation the dependency notation (e.g., "group:name:version")
     * @param configureClosure closure to configure ShadeDependencySpec
     * @return the created Dependency
     */
    public static Dependency shadeJust(
            DependencyHandler self,
            String dependencyNotation,
            @DelegatesTo(ShadeDependencySpec.class) Closure<Void> configureClosure) {
        ShadeExtension extension = (ShadeExtension) self.getExtensions().getByName("shadeJust");
        return extension.call(dependencyNotation, configureClosure);
    }

    /**
     * Shades the specified dependency and ALL of its transitive dependencies into the shadow jar.
     *
     * @param self the DependencyHandler
     * @param dependencyNotation the dependency notation (e.g., "group:name:version")
     * @return the created Dependency
     */
    public static Dependency shadeTransitively(DependencyHandler self, String dependencyNotation) {
        ShadeExtension extension = (ShadeExtension) self.getExtensions().getByName("shadeTransitively");
        return extension.call(dependencyNotation);
    }

    /**
     * Registers the shadeJust extension with the project's dependency handler.
     * This makes the shadeJust() method available in the dependencies {} block.
     */
    static void registerJust(Project project, ShadeRegistry registry) {
        ShadeExtension extension = new ShadeExtension(project, registry, "shaded", false);
        project.getDependencies().getExtensions().add("shadeJust", extension);
    }

    /**
     * Registers the shadeTransitively extension with the project's dependency handler.
     * This makes the shadeTransitively() method available in the dependencies {} block.
     * All transitive dependencies are automatically shaded by registering an "always true" filter.
     */
    static void registerTransitively(Project project, ShadeRegistry registry) {
        ShadeExtension extension = new ShadeExtension(project, registry, "shaded", true);
        project.getDependencies().getExtensions().add("shadeTransitively", extension);
    }

    /**
     * Adds a dependency to the configuration.
     * For shadeJust: only the direct dependency will be shaded.
     * For shadeTransitively: all transitives will be shaded by registering an "always true" filter.
     *
     * @param dependencyNotation the dependency notation (e.g., "group:name:version")
     * @return the created Dependency
     */
    public Dependency call(String dependencyNotation) {
        Dependency dep = project.getDependencies().add(configurationName, dependencyNotation);
        if (isShadeTransitively && dep instanceof ExternalModuleDependency) {
            // For shadeTransitively, register a filter that matches everything
            registry.registerFilter(dep, _ignored -> true);
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
    public Dependency call(String dependencyNotation, Closure<Void> configureClosure) {
        Dependency dep = project.getDependencies().add(configurationName, dependencyNotation);

        if (dep instanceof ExternalModuleDependency) {
            ShadeDependencySpec spec = new ShadeDependencySpec();

            // Configure the spec using the closure
            configureClosure.setDelegate(spec);
            configureClosure.setResolveStrategy(Closure.DELEGATE_FIRST);
            configureClosure.call(spec);

            // Check if a custom filter was provided when it's not allowed
            if (isShadeTransitively) {
                if (spec.getTransitiveFilter().isPresent()) {
                    throw new IllegalArgumentException(
                            "shadeTransitively() does not support custom transitive filters. All transitives are"
                                    + " automatically shaded. Use shadeJust() if you need to filter transitives.");
                }
                // For shadeTransitively, register a filter that matches everything
                registry.registerFilter(dep, _ignored -> true);
            } else {
                // For shadeJust, register the filter if one was configured
                spec.getTransitiveFilter().ifPresent(filter -> registry.registerFilter(dep, filter));
            }
        }

        return dep;
    }
}
