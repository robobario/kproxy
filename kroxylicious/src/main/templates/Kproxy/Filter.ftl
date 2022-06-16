<#--

    Licensed to the Apache Software Foundation (ASF) under one or more
    contributor license agreements. See the NOTICE file distributed with
    this work for additional information regarding copyright ownership.
    The ASF licenses this file to You under the Apache License, Version 2.0
    (the "License"); you may not use this file except in compliance with
    the License. You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

-->
<#assign
  dataClass="${messageSpec.name}Data"
  filterClass="${messageSpec.name}Filter"
  msgType=messageSpec.type?lower_case
/>
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kroxylicious.proxy.filter;

import org.apache.kafka.common.message.${messageSpec.name}Data;

/**
 * A stateless filter for ${messageSpec.name}s.
 * The same instance may be invoked on multiple channels.
 */
public interface ${filterClass} extends KrpcFilter {

    /**
     * Handle the given {@code ${msgType}},
     * returning the {@code ${messageSpec.name}Data} instance to be passed to the next filter.
     * The implementation may modify the given {@code data} in-place and return it,
     * or instantiate a new one.
     *
     * @param ${msgType} The KRPC message to handle.
     * @param context The context.
     * @return the {@code ${msgType}} to be passed to the next filter.
     * If null is returned then the given {@code ${msgType}} will be used.
     */
    public void on${messageSpec.name}(${dataClass} ${msgType}, KrpcFilterContext context);

}