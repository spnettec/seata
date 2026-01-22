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
package org.apache.seata.common.json.impl;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.apache.seata.common.exception.JsonParseException;
import org.apache.seata.common.json.JsonSerializer;
import org.apache.seata.common.loader.LoadLevel;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Jackson implementation of JsonSerializer
 */
@LoadLevel(name = JacksonJsonSerializer.NAME)
public class JacksonJsonSerializer implements JsonSerializer {
    public static final String NAME = "jackson";

    private final ObjectMapper defaultObjectMapper;

    private final ObjectMapper objectMapperWithAutoType;

    private final ObjectMapper mapper;

    public JacksonJsonSerializer() {
        this.defaultObjectMapper = JsonMapper.builder()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(MapperFeature.PROPAGATE_TRANSIENT_MARKER, true)
                .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
                .build();
        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(Object.class)
                .build();
        this.objectMapperWithAutoType = JsonMapper.builder()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(MapperFeature.PROPAGATE_TRANSIENT_MARKER, true)
                .activateDefaultTypingAsProperty(ptv, DefaultTyping.NON_FINAL, "@type")
                .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
                .build();
        this.mapper = JsonMapper.builder()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .activateDefaultTyping(ptv, DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY)
                .configure(MapperFeature.PROPAGATE_TRANSIENT_MARKER, true)
                .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
                .build();
    }

    @Override
    public String toJSONString(Object object) {
        try {
            return mapper.writeValueAsString(object);
        } catch (JacksonException e) {
            throw new JsonParseException("Jackson serialize error", e);
        }
    }

    @Override
    public <T> T parseObject(String text, Class<T> clazz) {
        if (text == null || clazz == null) {
            return null;
        }
        try {
            return mapper.readValue(text, clazz);
        } catch (JacksonException e) {
            throw new JsonParseException("Jackson deserialize error", e);
        }
    }

    @Override
    public <T> T parseObjectWithType(String text, Type type) {
        if (text == null || type == null) {
            return null;
        }
        try {
            return objectMapperWithAutoType.readValue(text, objectMapperWithAutoType.constructType(type));
        } catch (JacksonException e) {
            throw new JsonParseException("Jackson deserialize error", e);
        }
    }

    // advanced methods for Saga
    @Override
    public boolean useAutoType(String json) {
        return json != null && json.contains("\"@type\"");
    }

    @Override
    public String toJSONString(Object o, boolean prettyPrint) {
        return toJSONString(o, false, prettyPrint);
    }

    @Override
    public String toJSONString(Object o, boolean ignoreAutoType, boolean prettyPrint) {
        try {
            if (o instanceof List && ((List<?>) o).isEmpty()) {
                return "[]";
            }
            if (prettyPrint) {
                if (ignoreAutoType) {
                    return defaultObjectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(o);
                } else {
                    return objectMapperWithAutoType
                            .writerWithDefaultPrettyPrinter()
                            .writeValueAsString(o);
                }
            } else {
                if (ignoreAutoType) {
                    return defaultObjectMapper.writeValueAsString(o);
                } else {
                    return objectMapperWithAutoType.writeValueAsString(o);
                }
            }
        } catch (JacksonException e) {
            throw new JsonParseException("Jackson serialize error", e);
        }
    }

    @Override
    public <T> T parseObject(String json, Class<T> type, boolean ignoreAutoType) {
        if (json == null || type == null) {
            return null;
        }
        try {
            if ("[]".equals(json)) {
                return (T) new ArrayList<>(0);
            }
            if (ignoreAutoType) {
                return defaultObjectMapper.readValue(json, type);
            } else {
                return objectMapperWithAutoType.readValue(json, type);
            }
        } catch (JacksonException e) {
            throw new JsonParseException("Jackson deserialize error", e);
        }
    }
}
