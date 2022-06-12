/*
 *  Copyright 1999-2019 Seata.io Group.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.seata.core.protocol;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The type Rpc message test.
 *
 * @author guoyao
 */
public class RpcMessageTest {

    private static final String BODY_FIELD = "test_body";
    private static final int ID_FIELD = 100;
    private static final byte CODEC_FIELD = 1;
    private static final byte COMPRESS_FIELD = 2;
    private static final byte MSG_TYPE_FIELD = 3;
    private static final HashMap<String, String> HEAD_FIELD = new HashMap<>();

    /**
     * Test field get set from json.
     */
}
