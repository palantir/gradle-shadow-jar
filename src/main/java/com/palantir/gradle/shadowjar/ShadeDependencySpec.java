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
import java.util.Optional;
import java.util.function.Predicate;
import org.gradle.api.artifacts.ResolvedDependency;

/**
 * Configuration spec for shadeJust dependencies.
 * This class is only accessible when using the shadeJust configuration,
 * not shadeTransitively.
 */
public final class ShadeDependencySpec {
    private Optional<Predicate<ResolvedDependency>> transitiveFilter = Optional.empty();

    /**
     * Configures a filter to selectively shade transitive dependencies.
     * By default, shadeJust only shades the direct dependency. With transitiveFilter,
     * you can also shade transitives that match the given predicate.
     *
     * @param closure a closure that receives a DependencyInfo and returns true to shade it
     */
    public void transitiveFilter(Closure<Boolean> closure) {
        this.transitiveFilter = Optional.of(dep -> {
            DependencyInfo info = new DependencyInfo(dep);
            closure.setDelegate(info);
            closure.setResolveStrategy(Closure.DELEGATE_FIRST);
            return closure.call(info);
        });
    }

    /**
     * Returns the transitive filter if configured.
     *
     * @return Optional containing the filter predicate, or empty if no filter was configured
     */
    public Optional<Predicate<ResolvedDependency>> getTransitiveFilter() {
        return transitiveFilter;
    }
}
