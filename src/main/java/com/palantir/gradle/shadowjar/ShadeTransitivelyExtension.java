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

public record ShadeTransitivelyExtension(Project project, FilterRegistry registry, String configurationName) {

    public static Dependency shadeTransitively(DependencyHandler self, String dependencyNotation) {
        ShadeTransitivelyExtension extension =
                (ShadeTransitivelyExtension) self.getExtensions().getByName("shadeTransitively");
        return extension.call(dependencyNotation);
    }

    static void register(Project project, FilterRegistry registry) {
        project.getDependencies()
                .getExtensions()
                .add("shadeTransitively", new ShadeTransitivelyExtension(project, registry, "shaded"));
    }

    private Dependency call(String dependencyNotation) {
        Dependency dep = project.getDependencies().add(configurationName, dependencyNotation);
        if (dep instanceof ExternalModuleDependency) {
            registry.addFilter(dep, _ignored -> true);
        }
        return dep;
    }
}
