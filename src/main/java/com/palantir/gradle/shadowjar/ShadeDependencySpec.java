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
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.gradle.api.artifacts.ResolvedDependency;

public final class ShadeDependencySpec {
    private Predicate<ResolvedDependency> filter;

    public void withTransitives(String... patterns) {
        Pattern[] compiled =
                Arrays.stream(patterns).map(ShadeDependencySpec::globToPattern).toArray(Pattern[]::new);
        this.filter = dep -> {
            String coord = dep.getModuleGroup() + ":" + dep.getModuleName() + ":" + dep.getModuleVersion();
            return Arrays.stream(compiled).anyMatch(p -> p.matcher(coord).matches());
        };
    }

    Optional<Predicate<ResolvedDependency>> getTransitiveFilter() {
        return Optional.ofNullable(filter);
    }

    private static Pattern globToPattern(String glob) {
        StringBuilder regex = new StringBuilder();
        for (String part : glob.split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*?");
            }
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex.toString());
    }
}
