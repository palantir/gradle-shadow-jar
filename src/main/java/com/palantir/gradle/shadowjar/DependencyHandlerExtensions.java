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
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;

/**
 * Groovy extension methods for DependencyHandler that provide shadeJust() and shadeTransitively().
 * This is registered as a Groovy extension module and provides IDE autocomplete.
 *
 * <p>These methods delegate to ShadeExtension instances that are registered on the DependencyHandler
 * by the ShadowJarPlugin.
 */
public final class DependencyHandlerExtensions {
    private DependencyHandlerExtensions() {}

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
}
