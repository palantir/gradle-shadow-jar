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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.gradle.api.provider.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lazy relocator that reads relocation data at execution time.
 * The provider is evaluated lazily, avoiding configuration-time JAR scanning.
 */
@CacheableRelocator
final class LazyJarFilesRelocator extends SimpleRelocator {
    private static final Logger log = LoggerFactory.getLogger(LazyJarFilesRelocator.class);
    private static final String CLASS_SUFFIX = ".class";
    private static final Pattern MULTIRELEASE_JAR_PREFIX = Pattern.compile("^META-INF/versions/\\d+/");
    private static final String SERVICE_PROVIDER_PREFIX = "META-INF/services/";

    private final Provider<RelocationData> relocationDataProvider;
    private transient Set<String> relocatable;

    LazyJarFilesRelocator(Provider<RelocationData> relocationDataProvider, String shadedPrefix) {
        super("", shadedPrefix, ImmutableList.of(), ImmutableList.of());
        this.relocationDataProvider = relocationDataProvider;
    }

    private Set<String> getRelocatable() {
        if (relocatable == null) {
            relocatable = relocationDataProvider.get().relocatable();
        }
        return relocatable;
    }

    @Override
    public boolean canRelocatePath(String path) {
        return getRelocatable().contains(path + CLASS_SUFFIX)
                || getRelocatable().contains(path);
    }

    @Override
    public String relocatePath(RelocatePathContext context) {
        List<String> maybePair = splitMultiReleasePath(context.getPath());
        if (!maybePair.isEmpty()) {
            return relocateMultiReleasePath(maybePair, context);
        }

        String output = super.relocatePath(context);
        log.debug("relocatePath('{}') -> {}", context.getPath(), output);
        return output;
    }

    private String relocateMultiReleasePath(List<String> pair, RelocatePathContext context) {
        context.setPath(pair.get(1));
        String out = pair.get(0) + super.relocatePath(context);
        log.debug("relocateMultiReleasePath('{}') -> {}", context.getPath(), out);
        return out;
    }

    @Override
    public String relocateClass(RelocateClassContext context) {
        String className = context.getClassName();
        String output;
        // Work around a poor interaction between ServiceFileTransformer and our
        // prefix configuration which otherwise results in prefixes being added
        // prior to 'META-INF', breaking service loading. The default SimpleRelocator
        // replaces the first instance of the expected prefix with the new prefix,
        // however this is problematic when the expected prefix is an empty string.
        if (className != null && className.startsWith(SERVICE_PROVIDER_PREFIX)) {
            String targetClassName = className.substring(SERVICE_PROVIDER_PREFIX.length());
            RelocateClassContext serviceContext = RelocateClassContext.builder()
                    .className(targetClassName)
                    .stats(context.getStats())
                    .build();
            output = SERVICE_PROVIDER_PREFIX + super.relocateClass(serviceContext);
        } else {
            output = super.relocateClass(context);
        }
        log.debug("relocateClass('{}') -> {}", context.getClassName(), output);
        return output;
    }

    /** Returns a pair of 'META-INF/versions/9/' and 'com/foo/whatever.class'. */
    private static List<String> splitMultiReleasePath(String input) {
        Matcher matcher = MULTIRELEASE_JAR_PREFIX.matcher(input);
        if (matcher.find()) {
            return ImmutableList.of(input.substring(0, matcher.end()), input.substring(matcher.end()));
        } else {
            return ImmutableList.of();
        }
    }
}
