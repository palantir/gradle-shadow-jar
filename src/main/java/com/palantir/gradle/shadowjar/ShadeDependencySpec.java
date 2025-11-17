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

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.gradle.api.artifacts.ResolvedDependency;

/**
 * Configuration spec for shadeJust dependencies.
 * This class is only accessible when using the shadeJust configuration,
 * not shadeTransitively.
 */
public final class ShadeDependencySpec {
    private Optional<Predicate<ResolvedDependency>> transitiveFilter = Optional.empty();

    /**
     * Simplified API for including transitive dependencies that match the given glob patterns.
     * Patterns are matched against the dependency's full coordinate string (group:artifact:version).
     * Use * as a wildcard to match any characters.
     *
     * <p>Example:
     * <pre>
     * shadeJust('custom-dep:my-library:1') {
     *     withTransitives 'com.google.guava:*:*', 'com.google.common*'
     * }
     * </pre>
     *
     * @param patterns glob patterns to match against dependency coordinates (group:artifact:version)
     */
    public void withTransitives(String... patterns) {
        List<Pattern> compiledPatterns =
                Arrays.stream(patterns).map(ShadeDependencySpec::compileGlob).toList();

        this.transitiveFilter = Optional.of(dep -> {
            String coordinate = dep.getModuleGroup() + ":" + dep.getModuleName() + ":" + dep.getModuleVersion();
            return compiledPatterns.stream()
                    .anyMatch(pattern -> pattern.matcher(coordinate).matches());
        });
    }

    /**
     * Converts a glob pattern to a compiled regex Pattern.
     */
    private static Pattern compileGlob(String glob) {
        StringBuilder patternBuilder = new StringBuilder();
        boolean first = true;

        for (String token : glob.split("\\*", -1)) {
            if (first) {
                first = false;
            } else {
                patternBuilder.append(".*?");
            }
            patternBuilder.append(Pattern.quote(token));
        }

        return Pattern.compile(patternBuilder.toString());
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
