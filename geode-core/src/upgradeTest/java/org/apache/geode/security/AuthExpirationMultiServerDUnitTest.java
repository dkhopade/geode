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
package org.apache.geode.security;

import static org.apache.geode.distributed.ConfigurationProperties.SECURITY_CLIENT_AUTH_INIT;
import static org.apache.geode.security.ClientAuthenticationTestUtils.combineSecurityManagerResults;
import static org.apache.geode.security.ClientAuthenticationTestUtils.getSecurityManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Fail.fail;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.RegionShortcut;
import org.apache.geode.cache.client.ServerOperationException;
import org.apache.geode.internal.cache.InternalCache;
import org.apache.geode.test.dunit.rules.ClusterStartupRule;
import org.apache.geode.test.dunit.rules.MemberVM;
import org.apache.geode.test.junit.categories.SecurityTest;
import org.apache.geode.test.junit.rules.ClientCacheRule;

@Category({SecurityTest.class})
public class AuthExpirationMultiServerDUnitTest implements Serializable {
  public static final String REPLICATE_REGION = "replicateRegion";
  public static final String PARTITION_REGION = "partitionRegion";
  private MemberVM locator;
  private MemberVM server1;
  private MemberVM server2;
  private int locatorPort;

  @Rule
  public ClusterStartupRule lsRule = new ClusterStartupRule();

  @Rule
  public ClientCacheRule clientCacheRule = new ClientCacheRule();

  @Before
  public void setup() {
    locator = lsRule.startLocatorVM(0, l -> l.withSecurityManager(ExpirableSecurityManager.class));
    locatorPort = locator.getPort();
    server1 = lsRule.startServerVM(1, s -> s.withSecurityManager(ExpirableSecurityManager.class)
        .withCredential("test", "test")
        .withRegion(RegionShortcut.REPLICATE, REPLICATE_REGION)
        .withRegion(RegionShortcut.PARTITION, PARTITION_REGION)
        .withConnectionToLocator(locatorPort));
    server2 = lsRule.startServerVM(2, s -> s.withSecurityManager(ExpirableSecurityManager.class)
        .withCredential("test", "test")
        .withRegion(RegionShortcut.REPLICATE, REPLICATE_REGION)
        .withRegion(RegionShortcut.PARTITION, PARTITION_REGION)
        .withConnectionToLocator(locatorPort));
  }

  @Test
  public void clientConnectToServerShouldReauthenticate() throws Exception {
    UpdatableUserAuthInitialize.setUser("user1");
    clientCacheRule
        .withProperty(SECURITY_CLIENT_AUTH_INIT, UpdatableUserAuthInitialize.class.getName())
        .withPoolSubscription(true)
        .withServerConnection(server1.getPort());
    clientCacheRule.createCache();
    Region<Object, Object> region1 = clientCacheRule.createProxyRegion(REPLICATE_REGION);
    Region<Object, Object> region2 = clientCacheRule.createProxyRegion(PARTITION_REGION);
    region1.put("0", "value0");
    region2.put("0", "value0");

    expireUserOnAllVms("user1");

    UpdatableUserAuthInitialize.setUser("user2");
    region1.put("1", "value1");
    region2.put("1", "value1");

    // locator only validates peer
    locator.invoke(() -> {
      ExpirableSecurityManager securityManager = getSecurityManager();
      Map<String, List<String>> authorizedOps = securityManager.getAuthorizedOps();
      assertThat(authorizedOps.keySet()).containsExactly("test");
      Map<String, List<String>> unAuthorizedOps = securityManager.getUnAuthorizedOps();
      assertThat(unAuthorizedOps.keySet()).isEmpty();
    });

    // client is connected to server1, server1 gets all the initial contact,
    // authorization checks happens here
    server1.invoke(() -> {
      ExpirableSecurityManager securityManager = getSecurityManager();
      Map<String, List<String>> authorizedOps = securityManager.getAuthorizedOps();
      assertThat(authorizedOps.get("user1")).containsExactlyInAnyOrder(
          "DATA:WRITE:replicateRegion:0", "DATA:WRITE:partitionRegion:0");
      assertThat(authorizedOps.get("user2")).containsExactlyInAnyOrder(
          "DATA:WRITE:replicateRegion:1", "DATA:WRITE:partitionRegion:1");
      Map<String, List<String>> unAuthorizedOps = securityManager.getUnAuthorizedOps();
      assertThat(unAuthorizedOps.get("user1"))
          .containsExactly("DATA:WRITE:replicateRegion:1");
    });

    // server2 performs no authorization checks
    server2.invoke(() -> {
      ExpirableSecurityManager securityManager = getSecurityManager();
      Map<String, List<String>> authorizedOps = securityManager.getAuthorizedOps();
      Map<String, List<String>> unAuthorizedOps = securityManager.getUnAuthorizedOps();
      assertThat(authorizedOps.size()).isEqualTo(0);
      assertThat(unAuthorizedOps.size()).isEqualTo(0);
    });

    MemberVM.invokeInEveryMember(() -> {
      InternalCache cache = ClusterStartupRule.getCache();
      Region<Object, Object> serverRegion1 = cache.getRegion(REPLICATE_REGION);
      assertThat(serverRegion1.size()).isEqualTo(2);
      Region<Object, Object> serverRegion2 = cache.getRegion(PARTITION_REGION);
      assertThat(serverRegion2.size()).isEqualTo(2);
    }, server1, server2);
  }

  @Test
  public void clientConnectToLocatorShouldReAuthenticate() throws Exception {
    UpdatableUserAuthInitialize.setUser("user1");
    clientCacheRule
        .withProperty(SECURITY_CLIENT_AUTH_INIT, UpdatableUserAuthInitialize.class.getName())
        .withPoolSubscription(true)
        .withLocatorConnection(locator.getPort());
    clientCacheRule.createCache();
    Region<Object, Object> region = clientCacheRule.createProxyRegion(PARTITION_REGION);
    expireUserOnAllVms("user1");
    UpdatableUserAuthInitialize.setUser("user2");
    IntStream.range(0, 100).forEach(i -> region.put(i, "value" + i));

    ExpirableSecurityManager consolidated = combineSecurityManagerResults(server1, server2);
    Map<String, List<String>> authorized = consolidated.getAuthorizedOps();
    Map<String, List<String>> unAuthorized = consolidated.getUnAuthorizedOps();

    assertThat(authorized.keySet()).containsExactly("user2");
    assertThat(authorized.get("user2")).hasSize(100);

    assertThat(unAuthorized.keySet()).containsExactly("user1");
    assertThat(unAuthorized.get("user1")).hasSize(1);
  }

  @Test
  public void clientConnectToLocatorShouldNotAllowOperationIfUserIsNotRefreshed() throws Exception {
    UpdatableUserAuthInitialize.setUser("user1");
    clientCacheRule
        .withProperty(SECURITY_CLIENT_AUTH_INIT, UpdatableUserAuthInitialize.class.getName())
        .withPoolSubscription(true)
        .withLocatorConnection(locator.getPort());
    clientCacheRule.createCache();
    Region<Object, Object> region = clientCacheRule.createProxyRegion(PARTITION_REGION);
    expireUserOnAllVms("user1");
    for (int i = 1; i < 100; i++) {
      try {
        region.put(1, "value1");
        fail("Exception expected");
      } catch (Exception e) {
        assertThat(e).isInstanceOf(ServerOperationException.class);
        assertThat(e.getCause()).isInstanceOfAny(AuthenticationFailedException.class,
            AuthenticationRequiredException.class, AuthenticationExpiredException.class);
      }
    }
    ExpirableSecurityManager consolidated = combineSecurityManagerResults(server1, server2);
    assertThat(consolidated.getAuthorizedOps().keySet()).isEmpty();
  }

  private void expireUserOnAllVms(String user) {
    MemberVM.invokeInEveryMember(() -> {
      getSecurityManager().addExpiredUser(user);
    }, locator, server1, server2);
  }
}
