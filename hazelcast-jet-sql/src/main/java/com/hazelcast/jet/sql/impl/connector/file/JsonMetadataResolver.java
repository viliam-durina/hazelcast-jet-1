/*
 * Copyright (c) 2008-2020, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.sql.impl.connector.file;

import com.fasterxml.jackson.jr.stree.JrsObject;
import com.hazelcast.jet.pipeline.file.FileFormat;
import com.hazelcast.jet.sql.impl.extract.JsonQueryTarget;
import com.hazelcast.jet.sql.impl.schema.MappingField;

import java.util.List;
import java.util.Map;

final class JsonMetadataResolver extends MetadataResolver<JrsObject> {

    static final JsonMetadataResolver INSTANCE = new JsonMetadataResolver();

    private static final FileFormat<Map<String, String>> FORMAT = FileFormat.json();

    @Override
    protected FileFormat<?> sampleFormat() {
        return FORMAT;
    }

    @Override
    Metadata resolveMetadata(List<MappingField> resolvedFields, Map<String, ?> options) {
        return new Metadata(
                toFields(resolvedFields),
                toProcessorMetaSupplier(options, FORMAT),
                JsonQueryTarget::new);
    }

    @Override
    protected List<MappingField> resolveFieldsFromSample(JrsObject object) {
        return JsonResolver.resolveFields(object);
    }
}