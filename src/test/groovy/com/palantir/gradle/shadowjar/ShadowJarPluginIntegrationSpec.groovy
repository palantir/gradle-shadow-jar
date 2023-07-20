/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.gradle.shadowjar

import groovy.transform.CompileStatic
import groovy.xml.XmlUtil
import java.nio.charset.StandardCharsets
import java.util.jar.JarFile
import java.util.stream.Collectors
import nebula.test.IntegrationSpec
import nebula.test.dependencies.DependencyGraph
import nebula.test.dependencies.GradleDependencyGenerator
import nebula.test.functional.ExecutionResult
import org.apache.commons.io.IOUtils

class ShadowJarPluginIntegrationSpec extends IntegrationSpec {
    private static final String MAVEN_ROOT = 'build/repo'

    def setup() {
        settingsFile << '''
            rootProject.name = 'asd-fgh'
        '''.stripIndent()

        buildFile << """
            buildscript {
                repositories {
                    mavenCentral()
                    maven { url 'https://plugins.gradle.org/m2/' }
                }
            
                dependencies {
                    classpath 'com.palantir.gradle.consistentversions:gradle-consistent-versions:2.0.0'
                    classpath 'com.netflix.nebula:nebula-publishing-plugin:17.2.1'
                }
            }

            plugins {
                id 'org.unbroken-dome.test-sets' version '4.0.0' apply false
            }

            apply plugin: 'com.palantir.consistent-versions'
            apply plugin: 'nebula.maven-nebula-publish'
            apply plugin: 'com.palantir.shadow-jar'

            group = 'com.palantir.bar-baz_quux'
            version = '2'
            
            repositories {
                mavenCentral()
            }
            
            publishing {
                repositories {
                    maven {
                        name 'testRepo'
                        url '${projectDir}/${MAVEN_ROOT}'
                    }
                }
            }
        """.stripIndent()
    }

    def 'when using shadeTransitively the produced pom only has dependencies that arent directly included and everything else is shaded'() {
        when:
        buildFile << """
            apply plugin: 'java-library'            
            dependencies {
                api 'org.checkerframework:checker-qual:2.10.0'
                
                shadeTransitively 'com.google.guava:guava:28.2-jre'
            }
        """.stripIndent()

        then:
        runTasksAndCheckSuccess('publishNebulaPublicationToTestRepoRepository')

        def dependenciesText = dependenciesInPom()

        assert !dependenciesText.contains('<artifactId>guava</artifactId>')
        assert !dependenciesText.contains('<artifactId>error_prone_annotations</artifactId>')
        assert !dependenciesText.contains('<artifactId>listenablefuture</artifactId>')
        assert !dependenciesText.contains('<artifactId>jsr305</artifactId>')
        assert dependenciesText.contains('<artifactId>checker-qual</artifactId>')

        def jarEntryNames = jarEntryNames()

        assert jarEntryNames.containsAll([
                relocatedClass('com/google/j2objc/annotations/Property.class'),
                relocatedClass('com/google/errorprone/annotations/DoNotCall.class'),
                relocatedClass('com/google/common/io/ByteSink.class'),
                relocatedClass('javax/annotation/Nullable.class'),
        ])

        assert !jarEntryNames.contains(relocatedClass('org/checkerframework/framework/qual/PolyAll.class'))

        def jarFile = shadowJarFile()
        def classFileAsString = IOUtils.toString(jarFile.getInputStream(jarFile.getEntry(
                relocatedClass('com/google/common/util/concurrent/AbstractFuture$Waiter.class'))),
                StandardCharsets.US_ASCII)
        assert classFileAsString.contains('org/checkerframework/checker/nullness/qual/Nullable')
        assert !classFileAsString.contains(relocatedClass('org/checkerframework/checker/nullness/qual/Nullable'))
    }

    def 'should not shade known logging implementations'() {
        when:
        def mavenRepo = generateMavenRepo(
                'dep-that-depends-on:slf4j-log4j12:1 -> org.slf4j:slf4j-log4j12:1.7.30',
        )

        buildFile << """
            repositories {
                maven { url "file:///${mavenRepo.getAbsolutePath()}" }
            }
            
            dependencies {
                // depends on org.slf4j:slf4j-api and log4j:log4j
                shadeTransitively 'dep-that-depends-on:slf4j-log4j12:1'
            }
        """.stripIndent()

        then:
        runTasksAndCheckSuccess('publishNebulaPublicationToTestRepoRepository')

        String dependenciesText = dependenciesInPom()

        // Only needs slf4j-log4j12, as the other two are depended on by it
        assert dependenciesText.contains('<artifactId>slf4j-log4j12</artifactId>')
        assert !dependenciesText.contains('<artifactId>slf4j-api</artifactId>')
        assert !dependenciesText.contains('<artifactId>log4j</artifactId>')

        def jarEntryNames = jarEntryNames()

        assert !jarEntryNames.contains(relocatedClass('org/slf4j/LoggerFactory.class'))
        assert !jarEntryNames.contains(relocatedClass('org/apache/log4j/MDC.class'))
        assert !jarEntryNames.contains(relocatedClass('org/slf4j/impl/Log4jLoggerFactory.class'))
    }

    def 'should not shade tritium, tracing or safe-logging'() {
        when:
        def mavenRepo = generateMavenRepo(
                'telemetry-dep:telemetry:1 -> ' +
                        'com.palantir.tracing:tracing:6.17.0 ' +
                        '| com.palantir.safe-logging:safe-logging:3.2.0 ' +
                        '| com.palantir.tritium:tritium-registry:0.63.0'
        )

        buildFile << """
            repositories {
                maven { url "file:///${mavenRepo.getAbsolutePath()}" }
            }

            dependencies {
                shadeTransitively 'telemetry-dep:telemetry:1'
            }
        """.stripIndent()

        then:
        runTasksAndCheckSuccess('publishNebulaPublicationToTestRepoRepository')

        String dependenciesText = dependenciesInPom()

        assert dependenciesText.contains('<artifactId>tritium-registry</artifactId>')
        assert dependenciesText.contains('<artifactId>tracing</artifactId>')
        assert !dependenciesText.contains('<groupId>safe-logging</groupId>') // tracing contains safe-logging

        def jarEntryNames = jarEntryNames()

        assert !jarEntryNames.contains(relocatedClass('com/palantir/tracing/Tracer.class'))
        assert !jarEntryNames.contains(relocatedClass(
                'com/palantir/tritium/metrics/registry/DefaultTaggedMetricRegistry.class'))
        assert !jarEntryNames.contains(relocatedClass('com/palantir/logsafe/SafeArg.class'))
    }

    def 'should support multi-release jars'() {
        // https://www.baeldung.com/java-multi-release-jar

        when:
        buildFile << """
            repositories {
                mavenCentral()
            }
            
            dependencies {
                shadeTransitively 'one.util:streamex:0.7.3'
            }
            
            task extractForAssertions(type: Copy) {
                dependsOn publishNebulaPublicationToTestRepoRepository
                from zipTree("${MAVEN_ROOT}/com/palantir/bar-baz_quux/asd-fgh/2/asd-fgh-2.jar")
                into "\$buildDir/extractForAssertions"
            }
        """.stripIndent()

        then:
        writeHelloWorld()
        runTasksAndCheckSuccess('extractForAssertions')

        def jarEntryNames = shadowJarFile().stream()
                .map({ it.name })
                .collect(Collectors.toCollection({ new LinkedHashSet() }))

        assert jarEntryNames.contains(
                'META-INF/versions/9/shadow/com/palantir/bar_baz_quux/asd_fgh/one/util/streamex/VerSpec.class')
        assert jarEntryNames.contains(
                'META-INF/versions/9/shadow/com/palantir/bar_baz_quux/asd_fgh/one/util/streamex/Java9Specific.class')
        assert !jarEntryNames.contains(
                'META-INF/versions/9/one/util/streamex/VerSpec.class')
        assert !jarEntryNames.contains(
                'META-INF/versions/9/one/util/streamex/Java9Specific.class')

        assert shadowJarFile().isMultiRelease() ?:
                "The jar manifest must include 'Multi-Release: true', but was '" +
                        file("build/extractForAssertions/META-INF/MANIFEST.MF").text + "'"

        // What is of interest here is that this does not throw an exception
        shadowJarFile().getManifest()
    }

    def 'should support service-loader providers'() {
        when:
        buildFile << """
            repositories {
                mavenCentral()
            }
            
            dependencies {
                // The service is not relocated, only the provider, which still must
                // provide ws.rs-api 'jakarta.ws.rs.ext.RuntimeDelegate'
                implementation 'jakarta.ws.rs:jakarta.ws.rs-api:3.1.0'
                shadeTransitively 'org.glassfish.jersey.core:jersey-common:3.1.1'
            }
            
            task extractForAssertions(type: Copy) {
                dependsOn publishNebulaPublicationToTestRepoRepository
                from zipTree("${MAVEN_ROOT}/com/palantir/bar-baz_quux/asd-fgh/2/asd-fgh-2.jar")
                into "\$buildDir/extractForAssertions"
            }
        """.stripIndent()

        then:
        writeHelloWorld()
        runTasksAndCheckSuccess('extractForAssertions')

        def jarEntryNames = shadowJarFile().stream()
                .map({ it.name })
                .collect(Collectors.toCollection({ new LinkedHashSet() }))

        def service = 'META-INF/services/jakarta.ws.rs.ext.RuntimeDelegate'
        assert jarEntryNames.contains(service)
        assert new File("${buildFile.parentFile.absolutePath}/build/extractForAssertions/${service}").text
                == 'shadow.com.palantir.bar_baz_quux.asd_fgh.org.glassfish.jersey.internal.RuntimeDelegateImpl'
        assert jarEntryNames.contains(
                'shadow/com/palantir/bar_baz_quux/asd_fgh/org/glassfish/jersey/internal/RuntimeDelegateImpl.class')
        assert !jarEntryNames.contains(
                'shadow/com/palantir/bar_baz_quux/asd_fgh/jakarta/ws/rs/ext/RuntimeDelegate.class')
    }

    def 'should support service-loader providers for relocated services'() {
        when:
        buildFile << """
            repositories {
                mavenCentral()
            }
            
            dependencies {
                // The service and provider are both relocated
                shadeTransitively 'jakarta.ws.rs:jakarta.ws.rs-api:3.1.0'
                shadeTransitively 'org.glassfish.jersey.core:jersey-common:3.1.1'
            }
            
            task extractForAssertions(type: Copy) {
                dependsOn publishNebulaPublicationToTestRepoRepository
                from zipTree("${MAVEN_ROOT}/com/palantir/bar-baz_quux/asd-fgh/2/asd-fgh-2.jar")
                into "\$buildDir/extractForAssertions"
            }
        """.stripIndent()

        then:
        writeHelloWorld()
        runTasksAndCheckSuccess('extractForAssertions')

        def jarEntryNames = shadowJarFile().stream()
                .map({ it.name })
                .collect(Collectors.toCollection({ new LinkedHashSet() }))

        def service = 'META-INF/services/shadow.com.palantir.bar_baz_quux.asd_fgh.jakarta.ws.rs.ext.RuntimeDelegate'
        assert !jarEntryNames.contains('shadow.com.palantir.bar_baz_quux.asd_fgh.META-INF/services/jakarta.ws.rs.ext.RuntimeDelegate')
        assert jarEntryNames.contains(
                'META-INF/services/shadow.com.palantir.bar_baz_quux.asd_fgh.jakarta.ws.rs.ext.RuntimeDelegate')
        assert new File("${buildFile.parentFile.absolutePath}/build/extractForAssertions/${service}").text
                == 'shadow.com.palantir.bar_baz_quux.asd_fgh.org.glassfish.jersey.internal.RuntimeDelegateImpl'
        assert jarEntryNames.contains(
                'shadow/com/palantir/bar_baz_quux/asd_fgh/org/glassfish/jersey/internal/RuntimeDelegateImpl.class')
    }

    def 'should shade known logging implementations iff it is placed in shadeTransitively directly'() {
        when:
        def mavenRepo = generateMavenRepo(
                'dep-that-depends-on:slf4j-log4j12:1 -> org.slf4j:slf4j-log4j12:1.7.30',
        )

        buildFile << """
            repositories {
                maven { url "file:///${mavenRepo.getAbsolutePath()}" }
            }
            
            dependencies {
                // depends on org.slf4j:slf4j-api and log4j:log4j
                shadeTransitively 'dep-that-depends-on:slf4j-log4j12:1'
                
                // yes, we really want to do this
                shadeTransitively 'org.slf4j:slf4j-log4j12:1.7.30'
            }
        """.stripIndent()

        then:
        runTasksAndCheckSuccess('publishNebulaPublicationToTestRepoRepository')

        String dependenciesText = dependenciesInPom()

        // We explicitly asked to shade the logging library, so no dependencies
        assert !dependenciesText.contains('<artifactId>slf4j-log4j12</artifactId>')
        assert !dependenciesText.contains('<artifactId>slf4j-api</artifactId>')
        assert !dependenciesText.contains('<artifactId>log4j</artifactId>')

        def jarEntryNames = jarEntryNames()

        assert jarEntryNames.contains(relocatedClass('org/slf4j/LoggerFactory.class'))
        assert jarEntryNames.contains(relocatedClass('org/apache/log4j/MDC.class'))
        assert jarEntryNames.contains(relocatedClass('org/slf4j/impl/Log4jLoggerFactory.class'))
    }

    def 'should not shade runtimeOnly dependencies'() {
        when:
        def mavenRepo = generateMavenRepo(
                'depends-on:api-guardian:1 -> org.apiguardian:apiguardian-api:1.1.0',
        )

        buildFile << """
            repositories {
                maven { url "file:///${mavenRepo.getAbsolutePath()}" }
            }
                        
            dependencies {
                runtimeOnly 'org.apiguardian:apiguardian-api:1.1.0'
                
                shadeTransitively 'depends-on:api-guardian:1'
            }
        """.stripIndent()

        then:
        runTasksAndCheckSuccess('publishNebulaPublicationToTestRepoRepository')

        def dependenciesText = dependenciesInPom()

        assert dependenciesText.contains('<artifactId>apiguardian-api</artifactId>')
        assert !dependenciesText.contains('<artifactId>api-guardian</artifactId>')

        def jarEntryNames = jarEntryNames()

        assert !jarEntryNames.contains(relocatedClass('org/apiguardian/api/API.class'))
    }

    def 'root level module-info-java should not break stuff'() {
        when:
        buildFile << """                        
            dependencies {
                // This contains a root level module-info.class
                shadeTransitively 'jakarta.ws.rs:jakarta.ws.rs-api:2.1.6'
            }
        """.stripIndent()

        then:
        runTasksAndCheckSuccess('publishNebulaPublicationToTestRepoRepository')

        def jarEntryNames = jarEntryNames()

        assert jarEntryNames.contains(relocatedClass('javax/ws/rs/core/Response.class'))
    }

    def 'shadeTransitively should be available to test source sets'() {
        when:
        buildFile << """
            apply plugin: 'org.unbroken-dome.test-sets'
            
            testSets {
                integrationTest
            }
                        
            dependencies {
                shadeTransitively 'com.google.guava:guava:28.2-jre'
                
                testImplementation 'junit:junit:4.12'
                integrationTestImplementation 'junit:junit:4.12'
            }
        """.stripIndent()

        file('src/main/java/pkg/Foo.java') << '''
            package pkg;
            import com.google.common.collect.ImmutableList;
            class MainSourceSetClass {
                static void useGuava() { ImmutableList.of(); }
            }
        '''.stripIndent()

        file('src/test/java/pkg/FooTest.java') << '''
            package pkg;
            import org.junit.Test;
            import com.google.common.collect.ImmutableList;
            public class FooTest {
                @Test
                public void use_guava_directly() {
                    ImmutableList.of();
                }
                
                @Test
                public void use_guava_though_main_source_set() {
                    MainSourceSetClass.useGuava();
                }
            }
        '''.stripIndent()

        file('src/integrationTest/java/pkg/FooIntegrationTest.java') << '''
            package pkg;
            import org.junit.Test;
            import com.google.common.collect.ImmutableList;
            public class FooIntegrationTest {
                @Test
                public void use_guava_directly() {
                    ImmutableList.of();
                }
                
                @Test
                public void use_guava_though_main_source_set() {
                    MainSourceSetClass.useGuava();
                }
            }
        '''.stripIndent()

        then:
        runTasksAndCheckSuccess('test', 'integrationTest')
    }

    def 'the jar task should produce a jar with a classifier of thin to not clash with shadowJar'() {
        when:
        buildFile << """            
            dependencies {
                implementation 'org.apiguardian:apiguardian-api:1.1.0'
            }
        """

        then:
        runTasksAndCheckSuccess('jar')

        assert new File(projectDir, 'build/libs/asd-fgh-2-thin.jar').exists()
    }

    def 'shadowJar should contain manifest entries added to thin jar in tasks'() {
        buildFile << '''
            dependencies {
                shadeTransitively 'org.apiguardian:apiguardian-api:1.1.0'
            }
            
            // This replicates what the 'com.palantir.sls-recommended-dependencies' plugin does
            task addManifestItem {
                doFirst {
                    jar.manifest.attributes('Foo': 'Bar')
                }
            }
            
            jar.dependsOn addManifestItem
        '''

        when:
        runTasksAndCheckSuccess('publishNebulaPublicationToTestRepoRepository')

        then:
        shadowJarFile().manifest.mainAttributes.getValue('Foo') == 'Bar'
    }

    @CompileStatic
    private Set<String> jarEntryNames() {
        JarFile shadowJar = shadowJarFile()
        return shadowJar.stream().map({ it.name }).collect(Collectors.toSet())
    }

    @CompileStatic
    private JarFile shadowJarFile() {
        return new JarFile(file("${MAVEN_ROOT}/com/palantir/bar-baz_quux/asd-fgh/2/asd-fgh-2.jar"))
    }

    @CompileStatic
    private File generateMavenRepo(String... graph) {
        DependencyGraph dependencyGraph = new DependencyGraph(graph)
        GradleDependencyGenerator generator = new GradleDependencyGenerator(
                dependencyGraph, new File(projectDir, "build/testrepogen").toString())
        return generator.generateTestMavenRepo()
    }

    private String dependenciesInPom() {
        def pomFile = new File(projectDir, "${MAVEN_ROOT}/com/palantir/bar-baz_quux/asd-fgh/2/asd-fgh-2.pom")
        def pomXml = new groovy.xml.XmlParser().parse(pomFile)
        // GCV publishes the shaded constraints in dependencyManagement - this should be fine
        // Explicitly cast to "Node" before serializing to avoid errors due to Groovy choosing the wrong method override
        def dependenciesText = pomXml.dependencies.collect { node -> XmlUtil.serialize((Node) node) }.join('\n')
        dependenciesText
    }

    @CompileStatic
    private String relocatedClass(String clazz) {
        return 'shadow/com/palantir/bar_baz_quux/asd_fgh/' + clazz
    }

    @CompileStatic
    private ExecutionResult runTasksAndCheckSuccess(String... args) {
        ExecutionResult executionResult = runTasks((['--warning-mode=none', '--write-locks'] as String[]) + args)
        println executionResult.getStandardOutput()
        println executionResult.getStandardError()
        executionResult.rethrowFailure()

        return executionResult
    }

}
