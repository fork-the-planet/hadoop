/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.mapreduce.v2.hs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.v2.api.MRDelegationTokenIdentifier;
import org.apache.hadoop.mapreduce.v2.hs.HistoryServerStateStoreService.HistoryServerState;
import org.apache.hadoop.mapreduce.v2.jobhistory.JHAdminConfig;
import org.apache.hadoop.security.token.delegation.DelegationKey;
import org.apache.hadoop.service.ServiceStateException;
import org.apache.hadoop.yarn.server.records.Version;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestHistoryServerLeveldbStateStoreService {

  private static final File testDir = new File(
      System.getProperty("test.build.data",
          System.getProperty("java.io.tmpdir")),
      "TestHistoryServerLeveldbSystemStateStoreService");

  private Configuration conf;

  @BeforeEach
  public void setup() {
    FileUtil.fullyDelete(testDir);
    testDir.mkdirs();
    conf = new Configuration();
    conf.setBoolean(JHAdminConfig.MR_HS_RECOVERY_ENABLE, true);
    conf.setClass(JHAdminConfig.MR_HS_STATE_STORE,
        HistoryServerLeveldbStateStoreService.class,
        HistoryServerStateStoreService.class);
    conf.set(JHAdminConfig.MR_HS_LEVELDB_STATE_STORE_PATH,
        testDir.getAbsoluteFile().toString());
  }

  @AfterEach
  public void cleanup() {
    FileUtil.fullyDelete(testDir);
  }

  private HistoryServerStateStoreService createAndStartStore()
      throws IOException {
    HistoryServerStateStoreService store =
        HistoryServerStateStoreServiceFactory.getStore(conf);
    assertTrue(store instanceof HistoryServerLeveldbStateStoreService,
        "Factory did not create a leveldb store");
    store.init(conf);
    store.start();
    return store;
  }

  @Test
  public void testCheckVersion() throws IOException {
    HistoryServerLeveldbStateStoreService store =
        new HistoryServerLeveldbStateStoreService();
    store.init(conf);
    store.start();

    // default version
    Version defaultVersion = store.getCurrentVersion();
    assertEquals(defaultVersion, store.loadVersion());

    // compatible version
    Version compatibleVersion =
        Version.newInstance(defaultVersion.getMajorVersion(),
          defaultVersion.getMinorVersion() + 2);
    store.dbStoreVersion(compatibleVersion);
    assertEquals(compatibleVersion, store.loadVersion());
    store.close();
    store = new HistoryServerLeveldbStateStoreService();
    store.init(conf);
    store.start();

    // overwrite the compatible version
    assertEquals(defaultVersion, store.loadVersion());

    // incompatible version
    Version incompatibleVersion =
      Version.newInstance(defaultVersion.getMajorVersion() + 1,
          defaultVersion.getMinorVersion());
    store.dbStoreVersion(incompatibleVersion);
    store.close();
    store = new HistoryServerLeveldbStateStoreService();
    try {
      store.init(conf);
      store.start();
      fail("Incompatible version, should have thrown before here.");
    } catch (ServiceStateException e) {
      assertTrue(e.getMessage().contains("Incompatible version for state:"),
          "Exception message mismatch");
    }
    store.close();
  }

  @Test
  public void testTokenStore() throws IOException {
    HistoryServerStateStoreService store = createAndStartStore();

    // verify initially the store is empty
    HistoryServerState state = store.loadState();
    assertTrue(state.tokenState.isEmpty(), "token state not empty");
    assertTrue(state.tokenMasterKeyState.isEmpty(), "key state not empty");

    // store a key and some tokens
    final DelegationKey key1 = new DelegationKey(1, 2, "keyData1".getBytes());
    final MRDelegationTokenIdentifier token1 =
        new MRDelegationTokenIdentifier(new Text("tokenOwner1"),
            new Text("tokenRenewer1"), new Text("tokenUser1"));
    token1.setSequenceNumber(1);
    final Long tokenDate1 = 1L;
    final MRDelegationTokenIdentifier token2 =
        new MRDelegationTokenIdentifier(new Text("tokenOwner2"),
            new Text("tokenRenewer2"), new Text("tokenUser2"));
    token2.setSequenceNumber(12345678);
    final Long tokenDate2 = 87654321L;

    store.storeTokenMasterKey(key1);
    store.storeToken(token1, tokenDate1);
    store.storeToken(token2, tokenDate2);
    store.close();

    // verify the key and tokens can be recovered
    store = createAndStartStore();
    state = store.loadState();
    assertEquals(2, state.tokenState.size(), "incorrect loaded token count");
    assertTrue(state.tokenState.containsKey(token1), "missing token 1");
    assertEquals(tokenDate1,
        state.tokenState.get(token1), "incorrect token 1 date");
    assertTrue(state.tokenState.containsKey(token2), "missing token 2");
    assertEquals(tokenDate2,
        state.tokenState.get(token2), "incorrect token 2 date");
    assertEquals(1,
        state.tokenMasterKeyState.size(), "incorrect master key count");
    assertTrue(state.tokenMasterKeyState.contains(key1),
        "missing master key 1");

    // store some more keys and tokens, remove the previous key and one
    // of the tokens, and renew a previous token
    final DelegationKey key2 = new DelegationKey(3, 4, "keyData2".getBytes());
    final DelegationKey key3 = new DelegationKey(5, 6, "keyData3".getBytes());
    final MRDelegationTokenIdentifier token3 =
        new MRDelegationTokenIdentifier(new Text("tokenOwner3"),
            new Text("tokenRenewer3"), new Text("tokenUser3"));
    token3.setSequenceNumber(12345679);
    final Long tokenDate3 = 87654321L;

    store.removeToken(token1);
    store.storeTokenMasterKey(key2);
    final Long newTokenDate2 = 975318642L;
    store.updateToken(token2, newTokenDate2);
    store.removeTokenMasterKey(key1);
    store.storeTokenMasterKey(key3);
    store.storeToken(token3, tokenDate3);
    store.close();

    // verify the new keys and tokens are recovered, the removed key and
    // token are no longer present, and the renewed token has the updated
    // expiration date
    store = createAndStartStore();
    state = store.loadState();
    assertEquals(2, state.tokenState.size(), "incorrect loaded token count");
    assertFalse(state.tokenState.containsKey(token1), "token 1 not removed");
    assertTrue(state.tokenState.containsKey(token2), "missing token 2");
    assertEquals(newTokenDate2,
        state.tokenState.get(token2), "incorrect token 2 date");
    assertTrue(state.tokenState.containsKey(token3), "missing token 3");
    assertEquals(tokenDate3,
        state.tokenState.get(token3), "incorrect token 3 date");
    assertEquals(2,
        state.tokenMasterKeyState.size(), "incorrect master key count");
    assertFalse(state.tokenMasterKeyState.contains(key1),
        "master key 1 not removed");
    assertTrue(state.tokenMasterKeyState.contains(key2),
        "missing master key 2");
    assertTrue(state.tokenMasterKeyState.contains(key3),
        "missing master key 3");
    store.close();
  }
}
