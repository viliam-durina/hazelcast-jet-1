/*
 * Copyright 2020 Hazelcast Inc.
 *
 * Licensed under the Hazelcast Community License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://hazelcast.com/hazelcast-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.hadoop.file;

import com.fasterxml.jackson.dataformat.csv.CsvMappingException;
import com.google.common.collect.ImmutableMap;
import com.hazelcast.jet.hadoop.file.model.User;
import com.hazelcast.jet.pipeline.file.FileFormat;
import com.hazelcast.jet.pipeline.file.FileSourceBuilder;
import com.hazelcast.jet.pipeline.file.FileSources;
import org.junit.Test;

import java.io.CharConversionException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.util.Lists.newArrayList;

public class CsvFileFormatTest extends BaseFileFormatTest {

    @Test
    public void shouldReadCsvFile() {
        FileSourceBuilder<Map<String, String>> source = FileSources.files(currentDir + "/src/test/resources")
                                                                   .glob("file.csv")
                                                                   .format(FileFormat.csv());

        assertItemsInSource(source,
                ImmutableMap.of("name", "Frantisek", "favoriteNumber", "7"),
                ImmutableMap.of("name", "Ali", "favoriteNumber", "42")
        );
    }

    @Test
    public void shouldReadCsvFileToObject() {
        FileSourceBuilder<User> source = FileSources.files(currentDir + "/src/test/resources")
                                                    .glob("file.csv")
                                                    .format(FileFormat.csv(User.class));

        assertItemsInSource(source,
                new User("Frantisek", 7),
                new User("Ali", 42)
        );
    }

    @Test
    public void shouldReadCsvFileWithMoreColumnsThanTargetClass() {
        FileSourceBuilder<User> source = FileSources.files(currentDir + "/src/test/resources")
                                                    .glob("file-more-columns.csv")
                                                    .format(FileFormat.csv(User.class));

        assertItemsInSource(source,
                new User("Frantisek", 7),
                new User("Ali", 42)
        );
    }

    @Test
    public void shouldReadCsvFileWithLessColumnsThanTargetClass() {
        FileSourceBuilder<User> source = FileSources.files(currentDir + "/src/test/resources")
                                                    .glob("file-less-columns.csv")
                                                    .format(FileFormat.csv(User.class));

        assertItemsInSource(source,
                new User("Frantisek", 0),
                new User("Ali", 0)
        );
    }

    @Test
    public void shouldReadEmptyCsvFile() {
        FileSourceBuilder<User> source = FileSources.files(currentDir + "/src/test/resources")
                                                    .glob("file-empty.csv")
                                                    .format(FileFormat.csv(User.class));

        assertItemsInSource(source, items -> assertThat(items).isEmpty());
    }

    @Test
    public void shouldThrowWhenInvalidFileType() {
        FileSourceBuilder<User> source = FileSources.files(currentDir + "/src/test/resources")
                                                    .glob("invalid-data.png")
                                                    .format(FileFormat.csv(User.class));

        assertJobFailed(source, CharConversionException.class, "Invalid UTF-8");
    }

    @Test
    public void shouldThrowWhenWrongFormatting() {
        FileSourceBuilder<User> source = FileSources.files(currentDir + "/src/test/resources")
                                                    .glob("file-invalid.csv")
                                                    .format(FileFormat.csv(User.class));

        assertJobFailed(source, CsvMappingException.class, "Too many entries");
    }

    @Test
    public void shouldRemapFields() {
        FileFormat<String[]> format = FileFormat.csv(String[].class)
                                                .withStringArrayFieldList(newArrayList("favoriteNumber", "name"));

        FileSourceBuilder<String[]> source = FileSources.files(currentDir + "/src/test/resources")
                                                        .glob("file.csv")
                                                        .format(format);

            assertItemsInSource(source,
                    new String[]{"7", "Frantisek"},
                    new String[]{"42", "Ali"}
            );
    }

    @Test
    public void shouldRemapSubsetOfFields() {
        FileFormat<String[]> format = FileFormat.csv(String[].class)
                                                .withStringArrayFieldList(newArrayList("name"));

        FileSourceBuilder<String[]> source = FileSources.files(currentDir + "/src/test/resources")
                                                        .glob("file.csv")
                                                        .format(format);

            assertItemsInSource(source,
                    new String[]{"Frantisek"},
                    new String[]{"Ali"}
            );
    }
}
