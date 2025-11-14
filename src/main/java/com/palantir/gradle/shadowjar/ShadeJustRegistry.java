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

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ResolvedDependency;

/**
 * Registry that tracks which shadeJust dependencies have transitiveFilters configured.
 * This allows the shadowing logic to determine which transitive dependencies should be shaded.
 */
public final class ShadeJustRegistry {
    private final Map<String, Predicate<ResolvedDependency>> filters = new ConcurrentHashMap<>();

    /**
     * Registers a transitive filter for a given dependency.
     *
     * @param dependency the direct dependency that was added to shadeJust
     * @param filter the predicate that determines which transitives to shade
     */
    public void registerFilter(Dependency dependency, Predicate<ResolvedDependency> filter) {
        String key = makeKey(dependency.getGroup(), dependency.getName());
        filters.put(key, filter);
    }

    /**
     * Checks if a transitive dependency should be shaded based on its parent's filter.
     *
     * @param directDependency the direct shadeJust dependency
     * @param transitiveDependency the transitive dependency to check
     * @return true if the transitive should be shaded according to the filter
     */
    public boolean shouldShadeTransitive(
            ResolvedDependency directDependency, ResolvedDependency transitiveDependency) {
        return getFilter(directDependency)
                .map(filter -> filter.test(transitiveDependency))
                .orElse(false);
    }

    /**
     * Gets the filter configured for a specific dependency.
     *
     * @param dependency the dependency to look up
     * @return Optional containing the filter if one was configured, empty otherwise
     */
    public Optional<Predicate<ResolvedDependency>> getFilter(ResolvedDependency dependency) {
        String key = makeKey(dependency.getModuleGroup(), dependency.getModuleName());
        return Optional.ofNullable(filters.get(key));
    }

    /**
     * Checks if a dependency has a filter registered.
     *
     * @param dependency the dependency to check
     * @return true if a filter is registered for this dependency
     */
    public boolean hasFilter(ResolvedDependency dependency) {
        return getFilter(dependency).isPresent();
    }

    private static String makeKey(String group, String name) {
        return group + ":" + name;
    }
}
