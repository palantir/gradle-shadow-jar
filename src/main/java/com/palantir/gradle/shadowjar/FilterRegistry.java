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

public final class FilterRegistry {
    private final Map<String, Predicate<ResolvedDependency>> filters = new ConcurrentHashMap<>();

    void addFilter(Dependency dependency, Predicate<ResolvedDependency> filter) {
        filters.put(makeKey(dependency.getGroup(), dependency.getName()), filter);
    }

    boolean shouldShadeTransitive(ResolvedDependency directDep, ResolvedDependency transitiveDep) {
        return Optional.ofNullable(filters.get(makeKey(directDep.getModuleGroup(), directDep.getModuleName())))
                .map(filter -> filter.test(transitiveDep))
                .orElse(false);
    }

    private static String makeKey(String group, String name) {
        return group + ":" + name;
    }
}
