/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.management.internal.cli.functions;

import org.apache.geode.cache.Cache;
import org.apache.geode.cache.execute.FunctionAdapter;
import org.apache.geode.cache.execute.FunctionContext;
import org.apache.geode.distributed.internal.InternalDistributedSystem;
import org.apache.geode.internal.InternalEntity;
import org.apache.geode.internal.cache.tier.sockets.CacheClientNotifier;
import org.apache.geode.internal.cache.tier.sockets.CacheClientProxy;
import org.apache.geode.management.internal.cli.CliUtil;
import org.apache.geode.management.internal.cli.domain.MemberResult;
import org.apache.geode.management.internal.cli.i18n.CliStrings;

/***
 * Function to close a durable client
 *
 */
public class CloseDurableClientFunction extends FunctionAdapter implements InternalEntity {

  private static final long serialVersionUID = 1L;

  @Override
  public void execute(FunctionContext context) {
    String durableClientId = (String) context.getArguments();
    final Cache cache = CliUtil.getCacheIfExists();
    final String memberNameOrId =
        CliUtil.getMemberNameOrId(cache.getDistributedSystem().getDistributedMember());
    MemberResult memberResult = new MemberResult(memberNameOrId);

    try {
      CacheClientNotifier cacheClientNotifier = CacheClientNotifier.getInstance();

      if (cacheClientNotifier != null) {
        CacheClientProxy ccp = cacheClientNotifier.getClientProxy(durableClientId);
        if (ccp != null) {
          boolean isClosed = cacheClientNotifier.closeDurableClientProxy(durableClientId);
          if (isClosed) {
            memberResult.setSuccessMessage(
                CliStrings.format(CliStrings.CLOSE_DURABLE_CLIENTS__SUCCESS, durableClientId));
          } else {
            memberResult.setErrorMessage(
                CliStrings.format(CliStrings.NO_CLIENT_FOUND_WITH_CLIENT_ID, durableClientId));
          }
        } else {
          memberResult.setErrorMessage(
              CliStrings.format(CliStrings.NO_CLIENT_FOUND_WITH_CLIENT_ID, durableClientId));
        }
      } else {
        memberResult.setErrorMessage(CliStrings.NO_CLIENT_FOUND);
      }
    } catch (Exception e) {
      memberResult.setExceptionMessage(e.getMessage());
    } finally {
      context.getResultSender().lastResult(memberResult);
    }
  }

  @Override
  public String getId() {
    return CloseDurableClientFunction.class.getName();
  }

}
