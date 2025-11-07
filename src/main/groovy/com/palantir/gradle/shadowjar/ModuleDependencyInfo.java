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

import java.io.Serializable;
import org.gradle.api.artifacts.ResolvedDependency;
import org.immutables.value.Value;

/**
 * Serializable representation of a module dependency for configuration cache compatibility.
 * This replaces direct usage of {@link ResolvedDependency} which is not serializable.
 */
@Value.Immutable
@Value.Style(visibility = Value.Style.ImplementationVisibility.PACKAGE)
interface ModuleDependencyInfo extends Serializable {

    String group();

    String name();

    String version();

    /**
     * Creates a ModuleDependencyInfo from a ResolvedDependency.
     */
    static ModuleDependencyInfo from(ResolvedDependency resolved) {
        return ImmutableModuleDependencyInfo.builder()
                .group(resolved.getModuleGroup())
                .name(resolved.getModuleName())
                .version(resolved.getModuleVersion())
                .build();
    }

    /**
     * Returns module coordinates in "group:name" format.
     */
    default String coordinates() {
        return group() + ":" + name();
    }
}
