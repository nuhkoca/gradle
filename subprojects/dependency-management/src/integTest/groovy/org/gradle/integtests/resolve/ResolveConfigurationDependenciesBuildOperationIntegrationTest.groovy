/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.integtests.resolve

import org.gradle.api.internal.artifacts.configurations.ResolveConfigurationDependenciesBuildOperationType
import org.gradle.integtests.fixtures.AbstractHttpDependencyResolutionTest
import org.gradle.integtests.fixtures.BuildOperationNotificationsFixture
import org.gradle.integtests.fixtures.BuildOperationsFixture
import org.gradle.integtests.fixtures.ToBeFixedForConfigurationCache
import org.gradle.test.fixtures.maven.MavenFileRepository
import org.gradle.test.fixtures.server.http.AuthScheme
import org.gradle.test.fixtures.server.http.MavenHttpModule
import org.gradle.test.fixtures.server.http.MavenHttpRepository
import spock.lang.Unroll

class ResolveConfigurationDependenciesBuildOperationIntegrationTest extends AbstractHttpDependencyResolutionTest {

    def operations = new BuildOperationsFixture(executer, temporaryFolder)

    @SuppressWarnings("GroovyUnusedDeclaration")
    def operationNotificationsFixture = new BuildOperationNotificationsFixture(executer, temporaryFolder)

    @ToBeFixedForConfigurationCache
    def "resolved configurations are exposed via build operation"() {
        setup:
        buildFile << """
            allprojects {
                apply plugin: "java"
                repositories {
                    maven { url '${mavenHttpRepo.uri}' }
                }
            }
            dependencies {
                implementation 'org.foo:hiphop:1.0'
                implementation 'org.foo:unknown:1.0' //does not exist
                implementation project(":child")
                implementation 'org.foo:rock:1.0' //contains unresolved transitive dependency
            }

            task resolve(type: Copy) {
                from configurations.compileClasspath
                into "build/resolved"
            }
        """
        settingsFile << "include 'child'"
        def m1 = mavenHttpRepo.module('org.foo', 'hiphop').publish()
        def m2 = mavenHttpRepo.module('org.foo', 'unknown')
        def m3 = mavenHttpRepo.module('org.foo', 'broken')
        def m4 = mavenHttpRepo.module('org.foo', 'rock').dependsOn(m3).publish()

        m1.allowAll()
        m2.allowAll()
        m3.pom.expectGetBroken()
        m4.allowAll()

        when:
        fails "resolve"

        then:
        def op = operations.first(ResolveConfigurationDependenciesBuildOperationType)
        op.details.configurationName == "compileClasspath"
        op.details.projectPath == ":"
        op.details.buildPath == ":"
        op.details.scriptConfiguration == false
        op.details.configurationDescription ==~ /Compile classpath for source set 'main'.*/
        op.details.configurationVisible == false
        op.details.configurationTransitive == true

        op.result.resolvedDependenciesCount == 4
    }

    @ToBeFixedForConfigurationCache(because = "Task.getProject() during execution")
    def "resolved detached configurations are exposed"() {
        setup:
        buildFile << """
        repositories {
            maven { url '${mavenHttpRepo.uri}' }
        }

        task resolve {
            doLast {
                project.configurations.detachedConfiguration(dependencies.create('org.foo:dep:1.0')).files
            }
        }
        """
        def m1 = mavenHttpRepo.module('org.foo', 'dep').publish()


        m1.allowAll()

        when:
        run "resolve"

        then:
        def op = operations.first(ResolveConfigurationDependenciesBuildOperationType)
        op.details.configurationName == "detachedConfiguration1"
        op.details.projectPath == ":"
        op.details.scriptConfiguration == false
        op.details.buildPath == ":"
        op.details.configurationDescription == null
        op.details.configurationVisible == true
        op.details.configurationTransitive == true

        op.result.resolvedDependenciesCount == 1
    }

    def "resolved configurations in composite builds are exposed via build operation"() {
        setup:
        def m1 = mavenHttpRepo.module('org.foo', 'app-dep').publish()
        def m2 = mavenHttpRepo.module('org.foo', 'root-dep').publish()

        setupComposite()
        buildFile << """
            allprojects {
                apply plugin: "java"
                repositories {
                    maven { url '${mavenHttpRepo.uri}' }
                }
            }
            dependencies {
                implementation 'org.foo:root-dep:1.0'
                implementation 'org.foo:my-composite-app:1.0'
            }

            task resolve(type: Copy) {
                from configurations.compileClasspath
                into "build/resolved"
            }
        """


        m1.allowAll()
        m2.allowAll()

        when:
        run "resolve"

        then: "configuration of composite are exposed"
        def resolveOperations = operations.all(ResolveConfigurationDependenciesBuildOperationType)
        resolveOperations.size() == 2
        resolveOperations[0].details.configurationName == "compileClasspath"
        resolveOperations[0].details.projectPath == ":"
        resolveOperations[0].details.buildPath == ":"
        resolveOperations[0].details.scriptConfiguration == false
        resolveOperations[0].details.configurationDescription ==~ /Compile classpath for source set 'main'.*/
        resolveOperations[0].details.configurationVisible == false
        resolveOperations[0].details.configurationTransitive == true
        resolveOperations[0].result.resolvedDependenciesCount == 2

        and: "classpath configuration is exposed"
        resolveOperations[1].details.configurationName == "compileClasspath"
        resolveOperations[1].details.projectPath == ":"
        resolveOperations[1].details.buildPath == ":my-composite-app"
        resolveOperations[1].details.scriptConfiguration == false
        resolveOperations[1].details.configurationDescription == "Compile classpath for source set 'main'."
        resolveOperations[1].details.configurationVisible == false
        resolveOperations[1].details.configurationTransitive == true
        resolveOperations[1].result.resolvedDependenciesCount == 1
    }

    @ToBeFixedForConfigurationCache(because = ":buildEnvironment")
    def "resolved configurations of composite builds as build dependencies are exposed"() {
        setup:
        def m1 = mavenHttpRepo.module('org.foo', 'root-dep').publish()
        setupComposite()
        buildFile << """
            buildscript {
                repositories {
                    maven { url '${mavenHttpRepo.uri}' }
                }
                dependencies {
                    classpath 'org.foo:root-dep:1.0'
                    classpath 'org.foo:my-composite-app:1.0'
                }
            }

            apply plugin: "java"
        """


        m1.allowAll()

        when:
        run "buildEnvironment"

        then:
        def resolveOperations = operations.all(ResolveConfigurationDependenciesBuildOperationType)
        resolveOperations.size() == 2
        resolveOperations[0].details.configurationName == "classpath"
        resolveOperations[0].details.projectPath == null
        resolveOperations[0].details.buildPath == ":"
        resolveOperations[0].details.scriptConfiguration == true
        resolveOperations[0].details.configurationDescription == null
        resolveOperations[0].details.configurationVisible == true
        resolveOperations[0].details.configurationTransitive == true
        resolveOperations[0].result.resolvedDependenciesCount == 2

        resolveOperations[1].details.configurationName == "compileClasspath"
        resolveOperations[1].details.projectPath == ":"
        resolveOperations[1].details.buildPath == ":my-composite-app"
        resolveOperations[1].details.scriptConfiguration == false
        resolveOperations[1].details.configurationDescription == "Compile classpath for source set 'main'."
        resolveOperations[1].details.configurationVisible == false
        resolveOperations[1].details.configurationTransitive == true
        resolveOperations[1].result.resolvedDependenciesCount == 1
    }

    @Unroll
    def "#scriptType script classpath configurations are exposed"() {
        setup:
        def m1 = mavenHttpRepo.module('org.foo', 'root-dep').publish()

        def initScript = file('init.gradle')
        initScript << ''
        executer.usingInitScript(initScript)

        file('scriptPlugin.gradle') << '''
        task foo
        '''

        buildFile << '''
        apply from: 'scriptPlugin.gradle'
        '''

        file(scriptFileName) << """
            $scriptBlock {
                repositories {
                    maven { url '${mavenHttpRepo.uri}' }
                }
                dependencies {
                    classpath 'org.foo:root-dep:1.0'
                }
            }

        """

        m1.allowAll()
        when:
        run "foo"

        then:
        def resolveOperations = operations.all(ResolveConfigurationDependenciesBuildOperationType)
        resolveOperations.size() == 1
        resolveOperations[0].details.buildPath == ":"
        resolveOperations[0].details.configurationName == "classpath"
        resolveOperations[0].details.projectPath == null
        resolveOperations[0].details.scriptConfiguration == true
        resolveOperations[0].details.configurationDescription == null
        resolveOperations[0].details.configurationVisible == true
        resolveOperations[0].details.configurationTransitive == true
        resolveOperations[0].result.resolvedDependenciesCount == 1

        where:
        scriptType      | scriptBlock   | scriptFileName
        "project build" | 'buildscript' | getDefaultBuildFileName()
        "script plugin" | 'buildscript' | "scriptPlugin.gradle"
        "settings"      | 'buildscript' | 'settings.gradle'
        "init"          | 'initscript'  | 'init.gradle'
    }

    def "included build classpath configuration resolution result is exposed"() {
        setup:
        def m1 = mavenHttpRepo.module('org.foo', 'some-dep').publish()

        file("projectB/settings.gradle") << """
        rootProject.name = 'project-b'
        include "sub1"
        """

        file("projectB/build.gradle") << """
                buildscript {
                    repositories {
                        maven { url '${mavenHttpRepo.uri}' }
                    }
                    dependencies {
                        classpath "org.foo:some-dep:1.0"
                    }
                }
                allprojects {
                    apply plugin: 'java'
                    group "org.sample"
                    version "1.0"
                }

        """

        settingsFile << """
            includeBuild 'projectB'
        """

        buildFile << """
            buildscript {
                dependencies {

                    classpath 'org.sample:sub1:1.0'
                }
            }
            task foo
        """

        m1.allowAll()
        executer.requireIsolatedDaemons()
        when:
        run "foo"

        then:
        def op = operations.first(ResolveConfigurationDependenciesBuildOperationType) {
            it.details.configurationName == 'classpath' && it.details.buildPath == ':projectB'
        }
        op.result.resolvedDependenciesCount == 1
    }

    private void setupComposite() {
        file("my-composite-app/src/main/java/App.java") << "public class App {}"
        file("my-composite-app/build.gradle") << """
            group = "org.foo"
            version = '1.0'

            apply plugin: "java"
            repositories {
                maven { url '${mavenHttpRepo.uri}' }
            }

            dependencies {
                implementation 'org.foo:app-dep:1.0'
            }

            tasks.withType(JavaCompile) {
                options.annotationProcessorPath = files()
            }
        """
        file("my-composite-app/settings.gradle") << "rootProject.name = 'my-composite-app'"

        settingsFile << """
        rootProject.name='root'
        includeBuild 'my-composite-app'
        """
        mavenHttpRepo.module('org.foo', 'app-dep').publish().allowAll()
    }

    def "failed resolved configurations are exposed via build operation"() {
        given:
        MavenHttpModule a
        MavenHttpModule b
        MavenHttpModule leaf1
        MavenHttpModule leaf2
        mavenHttpRepo.with {
            a = module('org', 'a', '1.0').dependsOn('org', 'leaf', '1.0').publish()
            b = module('org', 'b', '1.0').dependsOn('org', 'leaf', '2.0').publish()
            leaf1 = module('org', 'leaf', '1.0').publish()
            leaf2 = module('org', 'leaf', '2.0').publish()
        }

        when:
        buildFile << """
            repositories {
                maven { url = '${mavenHttpRepo.uri}' }
            }

            configurations {
                compile {
                    resolutionStrategy.failOnVersionConflict()
                }
            }

            dependencies {
               compile 'org:a:1.0'
               compile 'org:b:1.0'
            }

            task resolve {
              doLast {
                  println(configurations.compile.files.name)
              }
            }
"""
        a.pom.expectGet()
        b.pom.expectGet()
        leaf1.pom.expectGet()
        leaf2.pom.expectGet()

        then:
        fails "resolve"

        and:
        def op = operations.first(ResolveConfigurationDependenciesBuildOperationType)
        op.details.configurationName == "compile"
        op.failure == "org.gradle.api.artifacts.ResolveException: Could not resolve all dependencies for configuration ':compile'."
        failure.assertHasCause("""Conflict(s) found for the following module(s):
  - org:leaf between versions 2.0 and 1.0""")
        op.result != null
        op.result.resolvedDependenciesCount == 2
    }

    // This documents the current behavior, not necessarily the smartest one.
    // FTR This behaves the same in 4.7, 4.8 and 4.9
    def "non fatal errors incur no resolution failure"() {
        def mod = mavenHttpRepo.module('org', 'a', '1.0')
        mod.pomFile << "corrupt"

        when:
        buildFile << """
            repositories {
                maven { url = '${mavenHttpRepo.uri}' }
            }

            configurations {
                compile
            }

            dependencies {
               compile 'org:a:1.0'
            }

            task resolve {
              doLast {
                  println(configurations.compile.files.name)
              }
            }
"""
        then:
        mod.allowAll()
        fails "resolve"

        and:
        def op = operations.first(ResolveConfigurationDependenciesBuildOperationType)
        op.details.configurationName == "compile"
        op.failure == null
        op.result.resolvedDependenciesCount == 1
    }

    @ToBeFixedForConfigurationCache
    def "resolved components contain their source repository name, even when taken from the cache"() {
        setup:
        def secondMavenHttpRepo = new MavenHttpRepository(server, '/repo-2', new MavenFileRepository(file('maven-repo-2')))

        // 'direct1' 'transitive1' and 'child-transitive1' are found in 'maven1'
        mavenHttpRepo.module('org.foo', 'transitive1').publish().allowAll()
        mavenHttpRepo.module('org.foo', 'direct1').publish().allowAll()
        mavenHttpRepo.module('org.foo', 'child-transitive1').publish().allowAll()

        // 'direct2' 'transitive2', and 'child-transitive2' are found in 'maven2' (unpublished in 'maven1')
        mavenHttpRepo.module('org.foo', 'direct2').allowAll()
        secondMavenHttpRepo.module('org.foo', 'direct2')
            .dependsOn('org.foo', 'transitive1', '1.0')
            .dependsOn('org.foo', 'transitive2', '1.0')
            .publish().allowAll()
        mavenHttpRepo.module('org.foo', 'transitive2').allowAll()
        secondMavenHttpRepo.module('org.foo', 'transitive2').publish().allowAll()
        mavenHttpRepo.module('org.foo', 'child-transitive2').allowAll()
        secondMavenHttpRepo.module('org.foo', 'child-transitive2').publish().allowAll()

        buildFile << """
            apply plugin: "java"
            repositories {
                maven {
                    name 'maven1'
                    url '${mavenHttpRepo.uri}'
                }
                maven {
                    name 'maven2'
                    url '${secondMavenHttpRepo.uri}'
                }
            }
            dependencies {
                implementation 'org.foo:direct1:1.0'
                implementation 'org.foo:direct2:1.0'
                implementation project(':child')
            }

            task resolve { doLast { configurations.runtimeClasspath.resolve() } }

            project(':child') {
                apply plugin: "java"
                dependencies {
                    implementation 'org.foo:child-transitive1:1.0'
                    implementation 'org.foo:child-transitive2:1.0'
                }
            }
        """
        settingsFile << "include 'child'"

        def verifyExpectedOperation = {
            def ops = operations.all(ResolveConfigurationDependenciesBuildOperationType)
            assert ops.size() == 1
            def op = ops[0]
            assert op.result.resolvedDependenciesCount == 3
            def resolvedComponents = op.result.components
            assert resolvedComponents.size() == 8
            assert resolvedComponents.'project :'.repoName == null
            assert resolvedComponents.'org.foo:direct1:1.0'.repoName == 'maven1'
            assert resolvedComponents.'org.foo:direct2:1.0'.repoName == 'maven2'
            assert resolvedComponents.'org.foo:transitive1:1.0'.repoName == 'maven1'
            assert resolvedComponents.'org.foo:transitive2:1.0'.repoName == 'maven2'
            assert resolvedComponents.'project :child'.repoName == null
            assert resolvedComponents.'org.foo:child-transitive1:1.0'.repoName == 'maven1'
            assert resolvedComponents.'org.foo:child-transitive2:1.0'.repoName == 'maven2'
            return true
        }

        when:
        succeeds 'resolve'

        then:
        verifyExpectedOperation()

        when:
        server.resetExpectations()
        succeeds 'resolve'

        then:
        verifyExpectedOperation()
    }

    def "resolved components contain their source repository name when resolution fails"() {
        setup:
        mavenHttpRepo.module('org.foo', 'transitive1').publish().allowAll()
        mavenHttpRepo.module('org.foo', 'direct1')
            .dependsOn('org.foo', 'transitive1', '1.0')
            .publish().allowAll()

        buildFile << """
            apply plugin: "java"
            repositories {
                maven {
                    name 'maven1'
                    url '${mavenHttpRepo.uri}'
                }
            }
            dependencies {
                implementation 'org.foo:direct1:1.0'
                implementation 'org.foo:missing-direct:1.0' // does not exist
                implementation project(':child')
            }

            task resolve { doLast { configurations.runtimeClasspath.resolve() } }

            project(':child') {
                apply plugin: "java"
                dependencies {
                    implementation 'org.foo:broken-transitive:1.0' // throws exception trying to resolve
                }
            }
        """
        settingsFile << "include 'child'"

        when:
        mavenHttpRepo.module('org.foo', 'missing-direct').allowAll()
        mavenHttpRepo.module('org.foo', 'broken-transitive').pom.expectGetBroken()

        and:
        fails 'resolve'

        then:
        def op = operations.first(ResolveConfigurationDependenciesBuildOperationType)
        def resolvedComponents = op.result.components
        resolvedComponents.size() == 4
        resolvedComponents.'project :'.repoName == null
        resolvedComponents.'project :child'.repoName == null
        resolvedComponents.'org.foo:direct1:1.0'.repoName == 'maven1'
        resolvedComponents.'org.foo:transitive1:1.0'.repoName == 'maven1'
    }

    @ToBeFixedForConfigurationCache
    def "resolved components contain their source repository id, even when they are structurally identical"() {
        setup:
        buildFile << """
            apply plugin: "java"
            repositories {
                maven {
                    name 'withoutCreds'
                    url '${mavenHttpRepo.uri}'
                }
                maven {
                    name 'withCreds'
                    url '${mavenHttpRepo.uri}'
                    credentials {
                        username = 'foo'
                        password = 'bar'
                    }
                }
            }
            dependencies {
                implementation 'org.foo:good:1.0'
            }

            task resolve { doLast { configurations.compileClasspath.resolve() } }
        """
        def module = mavenHttpRepo.module('org.foo', 'good').publish()
        server.authenticationScheme = AuthScheme.BASIC
        server.allowGetOrHead('/repo/org/foo/good/1.0/good-1.0.pom', 'foo', 'bar', module.pomFile)
        server.allowGetOrHead('/repo/org/foo/good/1.0/good-1.0.jar', 'foo', 'bar', module.artifactFile)

        when:
        succeeds 'resolve'

        then:
        def op = operations.first(ResolveConfigurationDependenciesBuildOperationType)
        def resolvedComponents = op.result.components
        resolvedComponents.size() == 2
        resolvedComponents.'org.foo:good:1.0'.repoName == 'withCreds'

        when:
        server.resetExpectations()
        succeeds 'resolve'

        then:
        // This demonstrates a bug in Gradle, where we ignore the requirement for credentials when retrieving from the cache
        def op2 = operations.first(ResolveConfigurationDependenciesBuildOperationType)
        def resolvedComponents2 = op2.result.components
        resolvedComponents2.size() == 2
        resolvedComponents2.'org.foo:good:1.0'.repoName == 'withoutCreds'
    }

    def "resolved component op includes configuration requested attributes"() {
        setup:
        mavenHttpRepo.module('org.foo', 'stuff').publish().allowAll()

        settingsFile << "include 'fixtures'"
        buildFile << """
            allprojects {
                apply plugin: "java"
                apply plugin: "java-test-fixtures"
                repositories {
                    maven { url '${mavenHttpRepo.uri}' }
                }
            }
            dependencies {
                testImplementation(testFixtures(project(':fixtures')))
            }

            project(':fixtures') {
                dependencies {
                    testFixturesApi('org.foo:stuff:1.0')
                }
            }
        """
        file("fixtures/src/testFixtures/java/SomeClass.java") << "class SomeClass {}"
        file("src/test/java/SomeTest.java") << "class SomeClass {}"

        when:
        succeeds ':test'

        then:
        operations.all(ResolveConfigurationDependenciesBuildOperationType, { it.details.configurationName.endsWith('Classpath') }).result.every {
            it.requestedAttributes.find { it.name == 'org.gradle.dependency.bundling' }.value == 'external'
            it.requestedAttributes.find { it.name == 'org.gradle.jvm.version' }.value
        }
        operations.all(ResolveConfigurationDependenciesBuildOperationType, { it.details.configurationName.endsWith('CompileClasspath') }).result.every {
            it.requestedAttributes.find { it.name == 'org.gradle.usage' }.value == 'java-api'
            it.requestedAttributes.find { it.name == 'org.gradle.libraryelements' }.value == 'classes'
        }
        operations.all(ResolveConfigurationDependenciesBuildOperationType, { it.details.configurationName.endsWith('RuntimeClasspath') }).result.every {
            it.requestedAttributes.find { it.name == 'org.gradle.usage' }.value == 'java-runtime'
            it.requestedAttributes.find { it.name == 'org.gradle.libraryelements' }.value == 'jar'
        }
    }
}
