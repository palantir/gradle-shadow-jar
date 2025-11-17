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

import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;

/**
 * Extension that adds the shadeTransitively() method to the dependencies block.
 * This allows users to shade a dependency and ALL of its transitive dependencies:
 * <pre>
 * dependencies {
 *     shadeTransitively('com.example:library:1.0')
 * }
 * </pre>
 */
public record ShadeTransitivelyExtension(Project project, ShadeRegistry registry, String configurationName) {

    /**
     * Shades the specified dependency and ALL of its transitive dependencies into the shadow jar.
     *
     * @param self the DependencyHandler
     * @param dependencyNotation the dependency notation (e.g., "group:name:version")
     * @return the created Dependency
     */
    public static Dependency shadeTransitively(DependencyHandler self, String dependencyNotation) {
        ShadeTransitivelyExtension extension =
                (ShadeTransitivelyExtension) self.getExtensions().getByName("shadeTransitively");
        return extension.call(dependencyNotation);
    }

    /**
     * This makes the shadeTransitively() method available in the dependencies {} block.
     */
    static void register(Project project, ShadeRegistry registry) {
        ShadeTransitivelyExtension extension = new ShadeTransitivelyExtension(project, registry, "shaded");
        project.getDependencies().getExtensions().add("shadeTransitively", extension);
    }

    public Dependency call(String dependencyNotation) {
        Dependency dep = project.getDependencies().add(configurationName, dependencyNotation);
        if (dep instanceof ExternalModuleDependency) {
            // Register a filter that matches everything
            registry.registerFilter(dep, _ignored -> true);
        }
        return dep;
    }
}
