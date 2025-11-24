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
import java.util.Set;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.Input;

@CacheableRelocator
class JarFilesRelocator extends SimpleRelocator {
    private static final Logger log = Logging.getLogger(JarFilesRelocator.class);

    private static final String CLASS_SUFFIX = ".class";

    private final Set<String> relocatable;

    JarFilesRelocator(String shadedPrefix, Set<String> relocatable) {
        super("", shadedPrefix, ImmutableList.of(), ImmutableList.of());
        this.relocatable = relocatable;
    }

    @Input
    public Set<String> getRelocatable() {
        return relocatable;
    }

    @Override
    public boolean canRelocatePath(String path) {
        return relocatable.contains(path + CLASS_SUFFIX) || relocatable.contains(path);
    }

    @Override
    public String relocatePath(RelocatePathContext context) {
        String output = super.relocatePath(context);
        log.debug("relocatePath('{}') -> {}", context.getPath(), output);
        return output;
    }

    @Override
    public String relocateClass(RelocateClassContext context) {
        String output = super.relocateClass(context);
        log.debug("relocateClass('{}') -> {}", context.getClassName(), output);
        return output;
    }
}
