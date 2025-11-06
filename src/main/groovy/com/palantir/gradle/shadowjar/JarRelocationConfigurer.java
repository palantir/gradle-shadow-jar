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
     * This is called at configuration time but JAR scanning happens lazily at execution time.
     */
    static void configureShadowJarRelocation(
            ShadowJar shadowJar,
            Configuration configuration,
            Provider<Set<String>> acceptedCoordinatesProvider,
            String relocationPrefix) {

        log.info("Configuring shadow jar relocation with prefix '{}'", relocationPrefix);

        // Add a lazy relocator that will scan JARs at execution time
        // SimpleRelocator expects the prefix to end with "." for proper package relocation
        shadowJar.relocate(new LazyJarFilesRelocator(configuration, acceptedCoordinatesProvider, relocationPrefix + "."));
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
     * Lazy relocator that scans JARs at execution time (first use).
     * This is CC-compatible because configuration resolution happens at execution time.
     *
     * Note: We cannot store a reference to ShadowJar task as it's not serializable.
     * Instead, we get the configuration's resolved files directly.
     */
    @CacheableRelocator
    private static final class LazyJarFilesRelocator extends SimpleRelocator {
        private final Configuration configuration;
        private final Provider<Set<String>> acceptedCoordinatesProvider;
        private transient Set<String> relocatable;
        private transient boolean initialized = false;

        private LazyJarFilesRelocator(
                Configuration configuration,
                Provider<Set<String>> acceptedCoordinatesProvider,
                String shadedPrefix) {
            super("", shadedPrefix, ImmutableList.of(), ImmutableList.of());
            this.configuration = configuration;
            this.acceptedCoordinatesProvider = acceptedCoordinatesProvider;
        }

        private synchronized void ensureInitialized() {
            if (initialized) {
                return;
            }

            log.info("Lazy-initializing JAR relocator (scanning JARs at execution time)");

            // Get the accepted coordinates
            Set<String> acceptedCoords = acceptedCoordinatesProvider.get();

            // Resolve JARs at execution time and filter to accepted ones
            Set<File> jarFiles = configuration.getResolvedConfiguration()
                    .getLenientConfiguration()
                    .getAllModuleDependencies()
                    .stream()
                    .filter(dep -> {
                        String coord = dep.getModuleGroup() + ":" + dep.getModuleName();
                        return acceptedCoords.contains(coord);
                    })
                    .flatMap(dep -> dep.getAllModuleArtifacts().stream())
                    .map(artifact -> artifact.getFile())
                    .collect(Collectors.toSet());

            // Scan JARs
            relocatable = scanJarsForRelocatablePaths(jarFiles);

            // Note: Multi-release manifest handling is removed here as we can't access shadowJar
            // It should be set up in the plugin configuration if needed

            initialized = true;
            log.info("JAR relocator initialized with {} relocatable paths from {} JARs",
                    relocatable.size(), jarFiles.size());
        }

        @Override
        public boolean canRelocatePath(String path) {
            ensureInitialized();
            return relocatable.contains(path + CLASS_SUFFIX) || relocatable.contains(path);
        }

        @Override
        public String relocatePath(RelocatePathContext context) {
            ensureInitialized();

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
            ensureInitialized();

            String className = context.getClassName();

            // Handle service provider class names specially
            // For service providers, the className is like "META-INF/services/fully.qualified.Interface"
            // We need to check the interface name, not the full path
            String classToCheck = className;
            boolean isServiceProvider = className != null && className.startsWith(SERVICE_PROVIDER_PREFIX);
            if (isServiceProvider) {
                classToCheck = className.substring(SERVICE_PROVIDER_PREFIX.length());
            }

            // Check if we should relocate this class at all
            // Convert class name to a path that we can check against relocatable set
            // Class names might use dots or slashes, so normalize to slashes
            String classPath = classToCheck != null ? classToCheck.replace('.', '/') : "";

            // Only relocate if this class is in our relocatable set (i.e., it's from an accepted JAR)
            if (!relocatable.contains(classPath + CLASS_SUFFIX) && !relocatable.contains(classPath)) {
                // Don't relocate - return the original class name
                log.debug("relocateClass('{}') -> {} (not relocatable)", className, className);
                return className;
            }

            String output;
            // Work around a poor interaction between ServiceFileTransformer and our
            // prefix configuration which otherwise results in prefixes being added
            // prior to 'META-INF', breaking service loading. The default SimpleRelocator
            // replaces the first instance of the expected prefix with the new prefix,
            // however this is problematic when the expected prefix is an empty string.
            if (isServiceProvider) {
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
    }
}
