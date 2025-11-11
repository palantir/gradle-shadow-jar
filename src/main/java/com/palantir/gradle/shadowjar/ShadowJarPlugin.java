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

import com.github.jengelman.gradle.plugins.shadow.ShadowExtension;
import com.github.jengelman.gradle.plugins.shadow.ShadowPlugin;
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.util.GradleVersion;
import org.immutables.value.Value;

public class ShadowJarPlugin implements Plugin<Project> {
    private static final Set<Predicate<ResolvedDependency>> BANNED_LIBRARIES = ImmutableSet.of(
            groupOf("org.slf4j"),
            groupOf("commons-logging"),
            groupOf("log4j"),
            groupOf("org.apache.logging.log4j"),
            groupOf("com.palantir.safe-logging").and(artifactOf("safe-logging")),
            groupOf("com.palantir.tracing").and(artifactOf("tracing").or(artifactOf("tracing-api"))),
            groupOf("com.palantir.tritium").and(artifactOf("tritium-registry")),
            groupOf("org.springframework").and(artifactOf("spring-jcl")));

    private static Predicate<ResolvedDependency> groupOf(String group) {
        return dependency -> group.equals(dependency.getModuleGroup());
    }

    private static Predicate<ResolvedDependency> artifactOf(String name) {
        return dependency -> name.equals(dependency.getModuleName());
    }

    private static boolean isBanned(ResolvedDependency dependency) {
        return BANNED_LIBRARIES.stream().anyMatch(predicate -> predicate.test(dependency));
    }

    @Override
    public final void apply(Project project) {
        if (GradleVersion.current().compareTo(GradleVersion.version("8.11")) < 0) {
            throw new IllegalStateException(
                    "You must be using Gradle 8.11 or above to use the com.palantir.shadow-jar plugin");
        }

        if (!project.getRootProject().getPlugins().hasPlugin("com.palantir.consistent-versions")) {
            throw new IllegalStateException(
                    "You must apply com.palantir.consistent-versions to use the com.palantir.shadow-jar plugin");
        }

        project.getPluginManager().apply(JavaPlugin.class);
        project.getPluginManager().apply(ShadowPlugin.class);

        // Disable Shadow's shadowRuntimeElements variant to avoid conflicts with GCV
        // Shadow tries to modify shadowRuntimeElements in afterEvaluate, but GCV locks it earlier
        project.getExtensions().configure(ShadowExtension.class, shadow -> {
            shadow.getAddShadowVariantIntoJavaComponent().set(false);
            shadow.getAddTargetJvmVersionAttribute().set(false);
        });

        TaskProvider<ShadowJar> shadowJarProvider =
                project.getTasks().withType(ShadowJar.class).named("shadowJar");

        setupShadowJarToShadeTheCorrectDependencies(project, shadowJarProvider);

        ensureShadowJarHasDefaultClassifierThatDoesNotClashWithTheRegularJarTask(project, shadowJarProvider);

        configureShadowJarTaskWithGoodDefaults(shadowJarProvider);

        ensureShadowJarIsOnlyArtifactOnJavaConfigurations(project, shadowJarProvider);

        dependOnJarTaskInOrderToTriggerTasksAddingManifestAttributes(project, shadowJarProvider);
    }

    private void setupShadowJarToShadeTheCorrectDependencies(
            Project project, TaskProvider<ShadowJar> shadowJarProvider) {
        NamedDomainObjectProvider<Configuration> shadeTransitively = project.getConfigurations()
                .register("shadeTransitively", conf -> {
                    conf.setCanBeConsumed(false);
                    conf.setVisible(false);
                });

        NamedDomainObjectProvider<Configuration> unshaded = project.getConfigurations()
                .register("unshaded", conf -> {
                    conf.setCanBeConsumed(false);
                    conf.setVisible(false);
                });

        NamedDomainObjectProvider<Configuration> rejectedFromShading = project.getConfigurations()
                .register("rejectedFromShading", conf -> {
                    conf.setCanBeConsumed(false);
                    conf.setVisible(false);
                    conf.setCanBeResolved(false);
                });

        project.getConfigurations()
                .named(JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME)
                .configure(runtimeElements -> {
                    runtimeElements.extendsFrom(rejectedFromShading.get());
                });

        ShadowJarVersionLock.lockConfiguration(project, shadeTransitively);
        ShadowJarVersionLock.lockConfiguration(project, unshaded);

        // This is needed to "break the loop" when GCV does --write-locks. At project.afterEvaluate, VersionsLockPlugin
        // will calculate its lock state, which involves resolving unifiedClasspath. unifiedClasspath extends from
        // pretty much every other configuration, including rejectedFromShading, meaning when unifiedClasspath is
        // resolved it causes the dependencies of rejectedFromShading to be evaluated. In an addAllLater below we do
        // a resolution of shadedTransitively. Starting Gradle 8, afterEvaluate is considered "configuring" project
        // state rather than "executed" project state. VersionPropsPlugin prevents us from resolving configurations
        // at configuration time (for perf reasons). One way to avoid this is to exclude rejectedFromShading from
        // the version props plugin which is what we do below. Any constraints that were going to be injected into
        // in a "final" configuration like runtimeClasspath or runtimeElements should still get these constraints
        // from another source, so this *should* be ok (there is a test for this).
        ShadowJarVersionLock.excludeConfigurationFromVersionsPropsInjection(project, rejectedFromShading);

        unshaded.configure(unshadedConf -> {
            unshadedConf.getIncoming().beforeResolve(_incoming -> {
                // only process if the unshaded configuration is still unresolved. The GCV plugin creates an
                // unshadedCopy configuration from the original and this beforeResolve Action is copied as well. That
                // leads to errors when it tries to modify the original configuration below for a second time.
                if (!unshadedConf.getState().equals(Configuration.State.UNRESOLVED)) {
                    return;
                }
                Stream.of(
                                JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME,
                                JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME)
                        .map(project.getConfigurations()::getByName)
                        .forEach(classpathConf -> {
                            unshadedConf.extendsFrom(classpathConf.getExtendsFrom().stream()
                                    .filter(extendsFromConf -> extendsFromConf != shadeTransitively.get())
                                    .toArray(Configuration[]::new));
                        });
            });
        });

        project.getExtensions().getByType(SourceSetContainer.class).configureEach(sourceSet -> Stream.of(
                        sourceSet.getCompileClasspathConfigurationName(),
                        sourceSet.getRuntimeClasspathConfigurationName())
                .map(project.getConfigurations()::named)
                .forEach(confProvider -> confProvider.configure(conf -> conf.extendsFrom(shadeTransitively.get()))));

        Provider<ShadowingCalculation> shadowingCalculation =
                shadeTransitively.zip(unshaded, (shadeTransitivelyConf, unshadedConf) -> {
                    Set<ResolvedDependency> shadedModules = shadeTransitivelyConf
                            .getResolvedConfiguration()
                            .getLenientConfiguration()
                            .getAllModuleDependencies();

                    Set<ResolvedDependency> unshadedModules = unshadedConf
                            .getResolvedConfiguration()
                            .getLenientConfiguration()
                            .getAllModuleDependencies();

                    Set<ResolvedDependency> onlyShadedModules = Sets.difference(shadedModules, unshadedModules);

                    Set<ResolvedDependency> directlyRejectedModules = onlyShadedModules.stream()
                            .filter(ShadowJarPlugin::isBanned)
                            .collect(Collectors.toSet());

                    Set<ResolvedDependency> highestLevelRejectedModules =
                            Sets.difference(directlyRejectedModules, allChildren(directlyRejectedModules));

                    Set<ResolvedDependency> highestLevelRejectedModulesThatArentDirectlyListed = Sets.filter(
                            highestLevelRejectedModules,
                            dependency -> moduleDoesNotExistDirectlyInConfiguration(shadeTransitivelyConf, dependency));

                    Set<ResolvedDependency> transitivelyRejectedModules =
                            selfAndAllChildren(highestLevelRejectedModulesThatArentDirectlyListed);

                    Set<ResolvedDependency> acceptedModules =
                            Sets.difference(onlyShadedModules, transitivelyRejectedModules);

                    return ImmutableShadowingCalculation.builder()
                            .acceptedShadedModules(acceptedModules)
                            .rejectedShadedModules(highestLevelRejectedModulesThatArentDirectlyListed)
                            .build();
                });

        rejectedFromShading.configure(conf -> conf.getDependencies()
                .addAllLater(project.getObjects()
                        .setProperty(Dependency.class)
                        .value(shadowingCalculation.map(calc -> calc.rejectedShadedModules().stream()
                                .map(this::depToString)
                                .map(project.getDependencies()::create)
                                .collect(Collectors.toSet())))));

        shadowJarProvider.configure(shadowJar -> {
            shadowJar.getConfigurations().set(shadeTransitively.map(Collections::singletonList));

            String prefix = String.join(".", "shadow", project.getGroup().toString(), project.getName())
                    .replace('-', '_')
                    .toLowerCase(Locale.US);

            Provider<FileCollection> jars =
                    shadowingCalculation.map(calc -> project.files(calc.acceptedShadedModules().stream()
                            .flatMap(dep -> dep.getModuleArtifacts().stream())
                            .map(ResolvedArtifact::getFile)
                            .toArray()));

            shadowJar.getIncludedDependencies().setFrom(jars);

            shadowJar.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE);

            Provider<Set<String>> pathsInJars = jars.map(RelocationHelper::scanJarsForPaths);

            Provider<Set<String>> relocatablePaths = pathsInJars.map(RelocationHelper::computeRelocatablePaths);

            shadowJar.getRelocators().add(new JarFilesRelocator(relocatablePaths, prefix + "."));

            shadowJar.getTransformers().add(new ConditionalMultiReleaseTransformer(pathsInJars));
        });
    }

    private static boolean moduleDoesNotExistDirectlyInConfiguration(
            Configuration shadeTransitively, ResolvedDependency dependency) {

        return shadeTransitively.getDependencies().withType(ExternalModuleDependency.class).stream()
                .noneMatch(directDep -> directDep
                        .getModule()
                        .equals(dependency.getModule().getId().getModule()));
    }

    private static Set<ResolvedDependency> allChildren(Set<ResolvedDependency> deps) {
        return deps.stream()
                .flatMap(resolvedDependency -> selfAndAllChildren(resolvedDependency.getChildren()).stream())
                .collect(Collectors.toSet());
    }

    private static Set<ResolvedDependency> selfAndAllChildren(Set<ResolvedDependency> deps) {
        return Sets.union(deps, allChildren(deps));
    }

    @Value.Immutable
    interface ShadowingCalculation {
        Set<ResolvedDependency> acceptedShadedModules();

        Set<ResolvedDependency> rejectedShadedModules();
    }

    private String depToString(ResolvedDependency resolvedDependency) {
        return String.format("%s:%s", resolvedDependency.getModuleGroup(), resolvedDependency.getModuleName());
    }

    private static void ensureShadowJarHasDefaultClassifierThatDoesNotClashWithTheRegularJarTask(
            Project project, TaskProvider<ShadowJar> shadowJarProvider) {

        project.getTasks().withType(Jar.class).named("jar").configure(jar -> jar.getArchiveClassifier()
                .set("thin"));

        shadowJarProvider.configure(
                shadowJar -> shadowJar.getArchiveClassifier().set((String) null));
    }

    private static void configureShadowJarTaskWithGoodDefaults(TaskProvider<ShadowJar> shadowJarProvider) {
        shadowJarProvider.configure(shadowJar -> {
            // Enable archive with more than 2^16 files
            shadowJar.setZip64(true);

            // Multiple jars might have an entry in META-INF/services for the same interface, so we merge them.
            // https://imperceptiblethoughts.com/shadow/configuration/merging/#merging-service-descriptor-files
            shadowJar.mergeServiceFiles();
        });
    }

    private static void ensureShadowJarIsOnlyArtifactOnJavaConfigurations(
            Project project, TaskProvider<ShadowJar> shadowJarProvider) {

        Stream.of(JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME, JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME)
                .map(project.getConfigurations()::named)
                .forEach(provider -> provider.configure(conf -> {
                    conf.getOutgoing().getArtifacts().clear();
                    conf.getOutgoing().artifact(shadowJarProvider);
                }));
    }

    private static void dependOnJarTaskInOrderToTriggerTasksAddingManifestAttributes(
            Project project, TaskProvider<ShadowJar> shadowJarProvider) {
        shadowJarProvider.configure(shadowJar ->
                shadowJar.dependsOn(project.getTasks().withType(Jar.class).named("jar")));
    }
}
