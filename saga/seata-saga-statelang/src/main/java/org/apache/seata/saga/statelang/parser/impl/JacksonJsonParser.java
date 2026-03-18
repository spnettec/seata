/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.seata.saga.statelang.parser.impl;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.apache.seata.common.loader.LoadLevel;
import org.apache.seata.saga.statelang.parser.JsonParser;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

import java.util.ArrayList;
import java.util.List;

/**
 * JsonParser implement by Jackson
 *
 * @deprecated use {@link org.apache.seata.common.json.impl.JacksonJsonSerializer} in json-common module instead.
 */
@Deprecated
@LoadLevel(name = JacksonJsonParser.NAME)
public class JacksonJsonParser implements JsonParser {
    private final ObjectMapper objectMapperWithAutoType = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(MapperFeature.PROPAGATE_TRANSIENT_MARKER)
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(Include.NON_NULL))
            .activateDefaultTyping(
                    BasicPolymorphicTypeValidator.builder()
                            .allowIfBaseType(Object.class)
                            .build(),
                    DefaultTyping.NON_FINAL,
                    JsonTypeInfo.As.PROPERTY)
            .build();

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .deactivateDefaultTyping()
            .enable(MapperFeature.PROPAGATE_TRANSIENT_MARKER)
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(Include.NON_NULL))
            .build();

    public static final String NAME = "jackson";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String toJsonString(Object o, boolean prettyPrint) {
        return toJsonString(o, false, prettyPrint);
    }

    @Override
    public boolean useAutoType(String json) {
        return json != null && (json.contains("\"@type\"") || json.contains("\"@class\""));
    }

    @Override
    public String toJsonString(Object o, boolean ignoreAutoType, boolean prettyPrint) {
        try {
            if (o instanceof List && ((List) o).isEmpty()) {
                return "[]";
            }
            if (prettyPrint) {
                if (ignoreAutoType) {
                    return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(o);
                } else {
                    return objectMapperWithAutoType
                            .writerWithDefaultPrettyPrinter()
                            .writeValueAsString(o);
                }

            } else {
                if (ignoreAutoType) {
                    return objectMapper.writeValueAsString(o);
                } else {
                    return objectMapperWithAutoType.writeValueAsString(o);
                }
            }
        } catch (JacksonException e) {
            throw new RuntimeException("Parse object to json error", e);
        }
    }

    @Override
    public <T> T parse(String json, Class<T> type, boolean ignoreAutoType) {
        try {
            if ("[]".equals(json)) {
                return (T) (new ArrayList(0));
            }
            if (ignoreAutoType) {
                return objectMapper.readValue(json, type);
            } else {
                return objectMapperWithAutoType.readValue(json.replaceAll("@type", "@class"), type);
            }
        } catch (JacksonIOException e) {
            throw new RuntimeException("Parse json to object error", e);
        }
    }
}
