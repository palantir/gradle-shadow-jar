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
 * Extension that adds the shadeJust() method to the dependencies block.
 * This allows users to shade only the direct dependency, with optional transitive inclusion:
 * <pre>
 * dependencies {
 *     shadeJust('com.example:library:1.0')
 *     shadeJust('com.example:library:1.0') {
 *         withTransitives 'com.internal.*'
 *     }
 * }
 * </pre>
 */
public record ShadeJustExtension(Project project, ShadeRegistry registry, String configurationName) {

    /**
     * Shades only the specified direct dependency into the shadow jar.
     * Transitive dependencies are not shaded by default.
     *
     * @param self the DependencyHandler
     * @param dependencyNotation the dependency notation (e.g., "group:name:version")
     * @return the created Dependency
     */
    public static Dependency shadeJust(DependencyHandler self, String dependencyNotation) {
        ShadeJustExtension extension = (ShadeJustExtension) self.getExtensions().getByName("shadeJust");
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
        ShadeJustExtension extension = (ShadeJustExtension) self.getExtensions().getByName("shadeJust");
        return extension.call(dependencyNotation, configureClosure);
    }

    /**
     * This makes the shadeJust() method available in the dependencies {} block.
     */
    static void register(Project project, ShadeRegistry registry) {
        ShadeJustExtension extension = new ShadeJustExtension(project, registry, "shaded");
        project.getDependencies().getExtensions().add("shadeJust", extension);
    }

    public Dependency call(String dependencyNotation) {
        return project.getDependencies().add(configurationName, dependencyNotation);
    }

    public Dependency call(String dependencyNotation, Closure<Void> configureClosure) {
        Dependency dep = project.getDependencies().add(configurationName, dependencyNotation);

        if (dep instanceof ExternalModuleDependency) {
            ShadeDependencySpec spec = new ShadeDependencySpec();

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
