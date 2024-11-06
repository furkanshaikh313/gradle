/*
 * Copyright 2020 the original author or authors.
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

package org.gradle.api.plugins.catalog.internal

import com.google.common.collect.Interners
import groovy.transform.Canonical
import org.gradle.api.InvalidUserDataException
import org.gradle.api.internal.catalog.DefaultVersionCatalog
import org.gradle.api.internal.catalog.DefaultVersionCatalogBuilder
import org.gradle.api.internal.catalog.parser.TomlCatalogFileParser
import org.gradle.api.problems.internal.DefaultProblems
import org.gradle.api.problems.internal.InternalProblems
import org.gradle.api.problems.internal.ProblemSummarizer
import org.gradle.util.TestUtil
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.function.Supplier

class TomlWriterTest extends Specification {

    @TempDir
    File tempTomlDir

    def "generates an equivalent file from an input (#file)"() {
        given:
        def sourceModel = parse("/org/gradle/api/plugins/catalog/internal/${file}.toml")

        when:
        def outputModel = generateFromModel(sourceModel)

        then:
        outputModel == sourceModel

        where:
        file << [
            'dependencies',
            'dependencies-notations',
            'plugin-notations',
        ]
    }

    def "generated file contains model version"() {
        given:
        def output = new StringWriter()
        def writer = new TomlWriter(output)

        when:
        writer.generate(Stub(DefaultVersionCatalog))

        then:
        output.toString().contains """#
# This file has been generated by Gradle and is intended to be consumed by Gradle
#
[metadata]
format.version = "1.1"
"""
    }

    def "error contains absolute path"() {
        when:
        parse("/org/gradle/api/plugins/catalog/internal/wrong.toml")

        then:
        def exception = thrown(InvalidUserDataException.class)
        exception.message.contains("In file '")
        exception.message.contains("wrong.toml'")
    }

    private Model generateFromModel(Model sourceModel) {
        def tomlFile = Files.createTempFile(tempTomlDir.toPath(), "test-", ".toml")

        def writer = Files.newBufferedWriter(tomlFile)
        writer.withCloseable {
            def tomlWriter = new TomlWriter(it)
            tomlWriter.generate(sourceModel.deps)
        }

        return parse(tomlFile)
    }

    private Model parse(String resourceName) {
        def resourceUri = this.class.getResource(resourceName).toURI()
        def resourcePath = Paths.get(resourceUri)

        return parse(resourcePath)
    }

    private Model parse(Path path) {
        def supplier = Stub(Supplier)
        def problems = new DefaultProblems(Mock(ProblemSummarizer))
        def builder = new DefaultVersionCatalogBuilder(
            "libs",
            Interners.newStrongInterner(),
            Interners.newStrongInterner(),
            TestUtil.objectFactory(),
            supplier) {
            @Override
            protected InternalProblems getProblemsService() {
                problems
            }
        }

        TomlCatalogFileParser.parse(path, builder, { problems })
        return new Model(builder.build())
    }

    @Canonical
    private static class Model {
        DefaultVersionCatalog deps
    }
}
