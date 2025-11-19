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

public record ShadeJustExtension(Project project, FilterRegistry registry, String configurationName) {

    public static Dependency shadeJust(DependencyHandler self, String dependencyNotation) {
        ShadeJustExtension extension = (ShadeJustExtension) self.getExtensions().getByName("shadeJust");
        return extension.call(dependencyNotation);
    }

    public static Dependency shadeJust(
            DependencyHandler self,
            String dependencyNotation,
            @DelegatesTo(ShadeDependencySpec.class) Closure<Void> configureClosure) {
        ShadeJustExtension extension = (ShadeJustExtension) self.getExtensions().getByName("shadeJust");
        return extension.call(dependencyNotation, configureClosure);
    }

    static void register(Project project, FilterRegistry registry) {
        project.getDependencies().getExtensions().add("shadeJust", new ShadeJustExtension(project, registry, "shaded"));
    }

    private Dependency call(String dependencyNotation) {
        return project.getDependencies().add(configurationName, dependencyNotation);
    }

    private Dependency call(String dependencyNotation, Closure<Void> configureClosure) {
        Dependency dep = project.getDependencies().add(configurationName, dependencyNotation);

        if (dep instanceof ExternalModuleDependency) {
            ShadeDependencySpec spec = new ShadeDependencySpec();
            configureClosure.setDelegate(spec);
            configureClosure.setResolveStrategy(Closure.DELEGATE_FIRST);
            configureClosure.call(spec);
            spec.getTransitiveFilter().ifPresent(filter -> registry.addFilter(dep, filter));
        }

        return dep;
    }
}
