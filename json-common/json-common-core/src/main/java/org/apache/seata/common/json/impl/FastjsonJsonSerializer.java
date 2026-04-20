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
import java.util.regex.Pattern;

/**
 * FastJSON2 implementation of JsonSerializer
 */
@LoadLevel(name = FastjsonJsonSerializer.NAME)
public class FastjsonJsonSerializer implements JsonSerializer {

    private static final Pattern AUTOTYPE_PATTERN = Pattern.compile("\"@type\"\\s*:");

    // 序列化特性
    private static final JSONWriter.Feature[] SERIALIZER_FEATURES = new JSONWriter.Feature[] {
        JSONWriter.Feature.WriteClassName, // 写入 @type
        JSONWriter.Feature.FieldBased // 基于字段访问，提高 Saga 场景兼容性
    };

    private static final JSONWriter.Feature[] SERIALIZER_FEATURES_PRETTY = new JSONWriter.Feature[] {
        JSONWriter.Feature.WriteClassName, JSONWriter.Feature.FieldBased, JSONWriter.Feature.PrettyFormat
    };

    private static final JSONWriter.Feature[] FEATURES_PRETTY =
            new JSONWriter.Feature[] {JSONWriter.Feature.FieldBased, JSONWriter.Feature.PrettyFormat};

    // 反序列化特性：移除已弃用的 SupportAutoType
    private static final JSONReader.Feature[] READER_FEATURES =
            new JSONReader.Feature[] {JSONReader.Feature.FieldBased};

    // 使用 Handler 替代全局开关，这是 Fastjson2 推荐的安全做法
    private static final JSONReader.AutoTypeBeforeHandler ALLOWLIST_HANDLER = (typeName, expectClass, features) -> {
        // 如果 checkClass 抛出异常，会被下方的 catch 捕获
        JsonAllowlistManager.getInstance().checkClass(typeName);
        return null; // 返回 null 表示允许加载该类，但由 Fastjson2 默认逻辑解析
    };

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
            // 直接传入 Handler，Fastjson2 会在遇到 @type 时自动回调它
            return JSON.parseObject(text, type, ALLOWLIST_HANDLER, READER_FEATURES);
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            rethrowIfSecurityException(e);
            throw new JsonParseException("FastJSON deserialize error", e);
        }
    }

    @Override
    public boolean useAutoType(String json) {
        return json != null && AUTOTYPE_PATTERN.matcher(json).find();
    }

    @Override
    public String toJSONString(Object object, boolean prettyPrint) {
        return toJSONString(object, false, prettyPrint);
    }

    @Override
    public String toJSONString(Object object, boolean ignoreAutoType, boolean prettyPrint) {
        try {
            if (prettyPrint) {
                return ignoreAutoType
                        ? JSON.toJSONString(object, FEATURES_PRETTY)
                        : JSON.toJSONString(object, SERIALIZER_FEATURES_PRETTY);
            } else {
                return ignoreAutoType ? JSON.toJSONString(object) : JSON.toJSONString(object, SERIALIZER_FEATURES);
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
            if ("[]".equals(text) && (java.util.Collection.class.isAssignableFrom(type) || type == Object.class)) {
                return (T) new java.util.ArrayList<>();
            }

            if (ignoreAutoType) {
                return JSON.parseObject(text, type, READER_FEATURES);
            } else {
                return JSON.parseObject(text, type, ALLOWLIST_HANDLER, READER_FEATURES);
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
