/*
 * (c) Copyright 2020 Palantir Technologies Inc. All rights reserved.
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

import com.palantir.gradle.versions.VersionRecommendationsExtension;
import com.palantir.gradle.versions.VersionsLockExtension;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;

public final class ShadowJarVersionLock {
    private ShadowJarVersionLock() {}

    public static void lockConfiguration(Project project, Configuration configuration) {
        VersionsLockExtension versionsLock =
                project.getExtensions().getByType(VersionsLockExtension.class);
        versionsLock.production(scope -> scope.from(configuration.getName()));
    }

    public static void excludeConfigurationFromVersionsPropsInjection(
            Project project, Configuration configuration) {
        VersionRecommendationsExtension versionRecommendations =
                project.getExtensions().getByType(VersionRecommendationsExtension.class);
        versionRecommendations.excludeConfigurations(configuration.getName());
    }
}
