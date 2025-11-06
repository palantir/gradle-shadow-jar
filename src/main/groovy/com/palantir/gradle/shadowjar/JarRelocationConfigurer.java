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
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configures shadow jar relocation at configuration time.
 * Extracts logic from ShadowJarConfigurationTask to make it configuration-cache compatible.
 */
final class JarRelocationConfigurer {

    private static final Logger log = LoggerFactory.getLogger(JarRelocationConfigurer.class);
    private static final String CLASS_SUFFIX = ".class";
    private static final Pattern MULTIRELEASE_JAR_PREFIX = Pattern.compile("^META-INF/versions/\\d+/");
    private static final String SERVICE_PROVIDER_PREFIX = "META-INF/services/";

    private JarRelocationConfigurer() {}

    /**
     * Configures relocation for the shadow jar with lazy JAR scanning.
     * This is called at configuration time but JAR scanning happens at execution time.
     */
    static void configureShadowJarRelocation(
            ShadowJar shadowJar, Configuration configuration, String relocationPrefix) {

        log.info("Configuring shadow jar relocation with prefix '{}'", relocationPrefix);

        // Create a provider that resolves JARs and scans them at execution time
        Provider<Set<String>> relocatableProvider = shadowJar.getProject().provider(() -> {
            Set<File> jarFiles = shadowJar.getDependencyFilter()
                    .resolve(shadowJar.getConfigurations())
                    .getFiles();
            return scanJarsForRelocatablePaths(jarFiles);
        });

        // Add relocator - SimpleRelocator expects the prefix to end with "." for proper package relocation
        shadowJar.relocate(new JarFilesRelocator(relocatableProvider, relocationPrefix + "."));
    }

    /**
     * Scans JAR files and returns set of relocatable paths.
     */
    private static Set<String> scanJarsForRelocatablePaths(Set<File> jarFiles) {
        Set<String> pathsInJars = jarFiles.stream()
                .flatMap(jar -> {
                    try (JarFile jarFile = new JarFile(jar)) {
                        return Collections.list(jarFile.entries()).stream()
                                .filter(entry -> !entry.isDirectory())
                                .map(ZipEntry::getName)
                                .peek(path -> log.debug("Jar '{}' contains entry '{}'", jar.getName(), path))
                                .peek(path -> Preconditions.checkState(
                                        !path.startsWith("/"), "Unexpected absolute path '%s' in jar '%s'", path, jar))
                                .toList()
                                .stream();
                    } catch (IOException e) {
                        throw new UncheckedIOException("Could not open jar file: " + jar, e);
                    }
                })
                .collect(Collectors.toSet());

        // The Relocator is responsible for fixing the bytecode at callsites *and* filenames of .class files,
        // so we have to account for things _calling_ these weird multi-release classes.
        Set<String> multiReleaseStuff = pathsInJars.stream()
                .flatMap(input -> splitMultiReleasePath(input).stream().skip(1))
                .collect(Collectors.toSet());

        return Stream.concat(pathsInJars.stream(), multiReleaseStuff.stream())
                .filter(path -> !path.equals("META-INF/MANIFEST.MF")) // don't relocate this!
                .filter(path -> !path.startsWith(SERVICE_PROVIDER_PREFIX)) // service providers remain in the root
                .collect(Collectors.toSet());
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

    /**
     * Relocator that lazily resolves relocatable paths at execution time.
     * CC-compatible because it stores a Provider (serializable) which resolves at execution time.
     */
    @CacheableRelocator
    private static final class JarFilesRelocator extends SimpleRelocator {
        private final Provider<Set<String>> relocatableProvider;
        private transient Set<String> relocatable;

        private JarFilesRelocator(Provider<Set<String>> relocatableProvider, String shadedPrefix) {
            super("", shadedPrefix, ImmutableList.of(), ImmutableList.of());
            this.relocatableProvider = relocatableProvider;
        }

        private Set<String> getRelocatable() {
            if (relocatable == null) {
                relocatable = relocatableProvider.get();
                log.info("Initialized relocator with {} relocatable paths", relocatable.size());
            }
            return relocatable;
        }

        @Override
        public boolean canRelocatePath(String path) {
            Set<String> paths = getRelocatable();
            return paths.contains(path + CLASS_SUFFIX) || paths.contains(path);
        }

        @Override
        public String relocatePath(RelocatePathContext context) {
            getRelocatable(); // Ensure initialized

            List<String> maybePair = splitMultiReleasePath(context.getPath());
            if (!maybePair.isEmpty()) {
                context.setPath(maybePair.get(1));
                return maybePair.get(0) + super.relocatePath(context);
            }

            return super.relocatePath(context);
        }

        @Override
        public String relocateClass(RelocateClassContext context) {
            getRelocatable(); // Ensure initialized

            String className = context.getClassName();
            // Handle META-INF/services prefix specially to avoid double-prefixing
            if (className != null && className.startsWith(SERVICE_PROVIDER_PREFIX)) {
                String targetClassName = className.substring(SERVICE_PROVIDER_PREFIX.length());
                RelocateClassContext serviceContext = RelocateClassContext.builder()
                        .className(targetClassName)
                        .stats(context.getStats())
                        .build();
                return SERVICE_PROVIDER_PREFIX + super.relocateClass(serviceContext);
            }
            return super.relocateClass(context);
        }
    }
}
