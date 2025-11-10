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

import com.github.jengelman.gradle.plugins.shadow.relocation.CacheableRelocator;
import com.github.jengelman.gradle.plugins.shadow.relocation.RelocateClassContext;
import com.github.jengelman.gradle.plugins.shadow.relocation.RelocatePathContext;
import com.github.jengelman.gradle.plugins.shadow.relocation.SimpleRelocator;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Set;

@CacheableRelocator
public final class JarFilesRelocator extends SimpleRelocator {
    private static final String CLASS_SUFFIX = ".class";
    private static final String SERVICE_PROVIDER_PREFIX = "META-INF/services/";

    private final Set<String> relocatable;

    public JarFilesRelocator(Set<String> relocatable, String shadedPrefix) {
        super("", shadedPrefix, ImmutableList.of(), ImmutableList.of());
        this.relocatable = relocatable;
    }

    @Override
    public boolean canRelocatePath(String path) {
        return relocatable.contains(path + CLASS_SUFFIX) || relocatable.contains(path);
    }

    @Override
    public String relocatePath(RelocatePathContext context) {
        List<String> maybePair = RelocationHelper.splitMultiReleasePath(context.getPath());
        if (maybePair.isEmpty()) {
            return super.relocatePath(context);
        }
        // Multi-release path: relocate the class portion, keep the META-INF/versions/N/ prefix
        RelocatePathContext pathWithoutPrefix = new RelocatePathContext(maybePair.get(1));
        return maybePair.get(0) + super.relocatePath(pathWithoutPrefix);
    }

    @Override
    public String relocateClass(RelocateClassContext context) {
        String className = context.getClassName();
        // Work around a poor interaction between ServiceFileTransformer and our
        // prefix configuration which otherwise results in prefixes being added
        // prior to 'META-INF', breaking service loading. The default SimpleRelocator
        // replaces the first instance of the expected prefix with the new prefix,
        // however this is problematic when the expected prefix is an empty string.
        if (className.startsWith(SERVICE_PROVIDER_PREFIX)) {
            String targetClassName = className.substring(SERVICE_PROVIDER_PREFIX.length());
            RelocateClassContext serviceContext = new RelocateClassContext(targetClassName);
            return SERVICE_PROVIDER_PREFIX + super.relocateClass(serviceContext);
        }
        return super.relocateClass(context);
    }
}
