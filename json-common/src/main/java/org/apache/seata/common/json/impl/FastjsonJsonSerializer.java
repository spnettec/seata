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

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;
import org.apache.seata.common.exception.JsonParseException;
import org.apache.seata.common.json.JsonAllowlistManager;
import org.apache.seata.common.json.JsonSerializer;
import org.apache.seata.common.loader.LoadLevel;

import java.lang.reflect.Type;

/**
 * FastJSON implementation of JsonSerializer
 */
@LoadLevel(name = FastjsonJsonSerializer.NAME)
public class FastjsonJsonSerializer implements JsonSerializer {

    private static final JSONWriter.Feature[] SERIALIZER_FEATURES =
            new JSONWriter.Feature[] {JSONWriter.Feature.WriteClassName};

    private static final JSONWriter.Feature[] SERIALIZER_FEATURES_PRETTY =
            new JSONWriter.Feature[] {JSONWriter.Feature.WriteClassName, JSONWriter.Feature.PrettyFormat};

    private static final JSONWriter.Feature[] FEATURES_PRETTY =
            new JSONWriter.Feature[] {JSONWriter.Feature.PrettyFormat};

    private static final JSONReader.Feature[] READER_FEATURES_SUPPORT_AUTO_TYPE =
            new JSONReader.Feature[] {JSONReader.Feature.SupportAutoType, JSONReader.Feature.FieldBased};

    private static final JSONReader.AutoTypeBeforeHandler ALLOWLIST_PARSER_CONFIG =
            new JSONReader.AutoTypeBeforeHandler() {
                @Override
                public Class<?> apply(String typeName, Class<?> expectClass, long features) {
                    JsonAllowlistManager.getInstance().checkClass(typeName);
                    return expectClass;
                }
            };
    private static final JSONReader.Feature[] READER_FEATURES_IGNORE_AUTO_TYPE =
            new JSONReader.Feature[] {JSONReader.Feature.FieldBased};

    public static final String NAME = "fastjson";

    @Override
    public String toJSONString(Object object) {
        try {
            return JSON.toJSONString(object);
        } catch (Exception e) {
            throw new JsonParseException("FastJSON serialize error", e);
        }
    }

    @Override
    public <T> T parseObject(String text, Class<T> clazz) {
        if (text == null || clazz == null) {
            return null;
        }
        try {
            return JSON.parseObject(text, clazz);
        } catch (Exception e) {
            throw new JsonParseException("FastJSON deserialize error", e);
        }
    }

    @Override
    public <T> T parseObjectWithType(String text, Type type) {
        if (text == null || type == null) {
            return null;
        }
        try {
            return JSON.parseObject(text, type, ALLOWLIST_PARSER_CONFIG);
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            rethrowIfSecurityException(e);
            throw new JsonParseException("FastJSON deserialize error", e);
        }
    }

    // advanced methods for Saga
    @Override
    public boolean useAutoType(String json) {
        return json != null && json.contains("\"@type\"");
    }

    @Override
    public String toJSONString(Object object, boolean prettyPrint) {
        return toJSONString(object, false, prettyPrint);
    }

    @Override
    public String toJSONString(Object object, boolean ignoreAutoType, boolean prettyPrint) {
        try {
            if (prettyPrint) {
                if (ignoreAutoType) {
                    return JSON.toJSONString(object, "yyyy-MM-dd HH:mm:ss", FEATURES_PRETTY);
                } else {
                    return JSON.toJSONString(object, "yyyy-MM-dd HH:mm:ss", SERIALIZER_FEATURES_PRETTY);
                }
            } else {
                if (ignoreAutoType) {
                    return JSON.toJSONString(object);
                } else {
                    return JSON.toJSONString(object, "yyyy-MM-dd HH:mm:ss", SERIALIZER_FEATURES);
                }
            }
        } catch (Exception e) {
            throw new JsonParseException("FastJSON serialize error", e);
        }
    }

    @Override
    public <T> T parseObject(String text, Class<T> type, boolean ignoreAutoType) {
        if (text == null || type == null) {
            return null;
        }
        try {
            if ("[]".equals(text)) {
                return (T) new java.util.ArrayList<>();
            }

            if (ignoreAutoType) {
                return JSON.parseObject(text, type, READER_FEATURES_IGNORE_AUTO_TYPE);
            } else {
                return JSON.parseObject(text, type, ALLOWLIST_PARSER_CONFIG, READER_FEATURES_SUPPORT_AUTO_TYPE);
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            rethrowIfSecurityException(e);
            throw new JsonParseException("FastJSON deserialize error", e);
        }
    }

    private static void rethrowIfSecurityException(Throwable e) {
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof SecurityException) {
                throw (SecurityException) cause;
            }
            cause = cause.getCause();
        }
    }
}
