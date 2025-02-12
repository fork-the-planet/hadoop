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
package org.apache.hadoop.hdfs.server.namenode;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CreateFlag;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.fs.permission.PermissionStatus;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.net.DFSNetworkTopology;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.HdfsFileStatus;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockPlacementPolicy;
import org.apache.hadoop.hdfs.server.blockmanagement.DatanodeManager;
import org.apache.hadoop.hdfs.server.blockmanagement.DatanodeDescriptor;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockManager;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockPlacementStatus;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockPlacementPolicyRackFaultTolerant;
import org.apache.hadoop.hdfs.server.protocol.NamenodeProtocols;
import org.apache.hadoop.hdfs.util.RwLockMode;
import org.apache.hadoop.net.StaticMapping;
import org.apache.hadoop.test.GenericTestUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestBlockPlacementPolicyRackFaultTolerant {

  private static final int DEFAULT_BLOCK_SIZE = 1024;
  private MiniDFSCluster cluster = null;
  private NamenodeProtocols nameNodeRpc = null;
  private FSNamesystem namesystem = null;
  private PermissionStatus perm = null;

  @Before
  public void setup() throws IOException {
    StaticMapping.resetMap();
    Configuration conf = new HdfsConfiguration();
    final ArrayList<String> rackList = new ArrayList<String>();
    final ArrayList<String> hostList = new ArrayList<String>();
    for (int i = 0; i < 10; i++) {
      for (int j = 0; j < 2; j++) {
        rackList.add("/rack" + i);
        hostList.add("/host" + i + j);
      }
    }
    conf.setClass(DFSConfigKeys.DFS_BLOCK_REPLICATOR_CLASSNAME_KEY,
        BlockPlacementPolicyRackFaultTolerant.class,
        BlockPlacementPolicy.class);
    conf.setLong(DFSConfigKeys.DFS_BLOCK_SIZE_KEY, DEFAULT_BLOCK_SIZE);
    conf.setInt(DFSConfigKeys.DFS_BYTES_PER_CHECKSUM_KEY, DEFAULT_BLOCK_SIZE / 2);
    cluster = new MiniDFSCluster.Builder(conf)
        .numDataNodes(hostList.size())
        .racks(rackList.toArray(new String[rackList.size()]))
        .hosts(hostList.toArray(new String[hostList.size()]))
        .build();
    cluster.waitActive();
    nameNodeRpc = cluster.getNameNodeRpc();
    namesystem = cluster.getNamesystem();
    perm = new PermissionStatus("TestBlockPlacementPolicyEC", null,
        FsPermission.getDefault());
  }

  @After
  public void teardown() {
    if (cluster != null) {
      cluster.shutdown();
      cluster = null;
    }
  }

  @Test
  public void testChooseTarget() throws Exception {
    doTestChooseTargetNormalCase();
    doTestChooseTargetSpecialCase();
  }

  private void doTestChooseTargetNormalCase() throws Exception {
    String clientMachine = "client.foo.com";
    short[][] testSuite = {
        {3, 2}, {3, 7}, {3, 8}, {3, 10}, {9, 1}, {10, 1}, {10, 6}, {11, 6},
        {11, 9}
    };
    // Test 5 files
    int fileCount = 0;
    for (int i = 0; i < 5; i++) {
      for (short[] testCase : testSuite) {
        short replication = testCase[0];
        short additionalReplication = testCase[1];
        String src = "/testfile" + (fileCount++);
        // Create the file with client machine
        HdfsFileStatus fileStatus = namesystem.startFile(src, perm,
            clientMachine, clientMachine, EnumSet.of(CreateFlag.CREATE), true,
            replication, DEFAULT_BLOCK_SIZE, null, null, null, false);

        //test chooseTarget for new file
        LocatedBlock locatedBlock = nameNodeRpc.addBlock(src, clientMachine,
            null, null, fileStatus.getFileId(), null, null);
        doTestLocatedBlock(replication, locatedBlock);

        //test chooseTarget for existing file.
        LocatedBlock additionalLocatedBlock =
            nameNodeRpc.getAdditionalDatanode(src, fileStatus.getFileId(),
                locatedBlock.getBlock(), locatedBlock.getLocations(),
                locatedBlock.getStorageIDs(), DatanodeInfo.EMPTY_ARRAY,
                additionalReplication, clientMachine);
        doTestLocatedBlock(replication + additionalReplication, additionalLocatedBlock);
      }
    }
  }

  /**
   * Test more randomly. So it covers some special cases.
   * Like when some racks already have 2 replicas, while some racks have none,
   * we should choose the racks that have none.
   */
  private void doTestChooseTargetSpecialCase() throws Exception {
    String clientMachine = "client.foo.com";
    // Test 5 files
    String src = "/testfile_1_";
    // Create the file with client machine
    HdfsFileStatus fileStatus = namesystem.startFile(src, perm,
        clientMachine, clientMachine, EnumSet.of(CreateFlag.CREATE), true,
        (short) 20, DEFAULT_BLOCK_SIZE, null, null, null, false);

    //test chooseTarget for new file
    LocatedBlock locatedBlock = nameNodeRpc.addBlock(src, clientMachine,
        null, null, fileStatus.getFileId(), null, null);
    doTestLocatedBlock(20, locatedBlock);

    DatanodeInfo[] locs = locatedBlock.getLocations();
    String[] storageIDs = locatedBlock.getStorageIDs();

    for (int time = 0; time < 5; time++) {
      shuffle(locs, storageIDs);
      for (int i = 1; i < locs.length; i++) {
        DatanodeInfo[] partLocs = new DatanodeInfo[i];
        String[] partStorageIDs = new String[i];
        System.arraycopy(locs, 0, partLocs, 0, i);
        System.arraycopy(storageIDs, 0, partStorageIDs, 0, i);
        for (int j = 1; j < 20 - i; j++) {
          LocatedBlock additionalLocatedBlock =
              nameNodeRpc.getAdditionalDatanode(src, fileStatus.getFileId(),
                  locatedBlock.getBlock(), partLocs,
                  partStorageIDs, DatanodeInfo.EMPTY_ARRAY,
                  j, clientMachine);
          doTestLocatedBlock(i + j, additionalLocatedBlock);
        }
      }
    }
  }

  /**
   * Verify decommission a dn which is an only node in its rack.
   */
  @Test
  public void testPlacementWithOnlyOneNodeInRackDecommission() throws Exception {
    Configuration conf = new HdfsConfiguration();
    final String[] racks = {"/RACK0", "/RACK0", "/RACK2", "/RACK3", "/RACK4", "/RACK5", "/RACK2"};
    final String[] hosts = {"/host0", "/host1", "/host2", "/host3", "/host4", "/host5", "/host6"};

    // enables DFSNetworkTopology
    conf.setClass(DFSConfigKeys.DFS_BLOCK_REPLICATOR_CLASSNAME_KEY,
        BlockPlacementPolicyRackFaultTolerant.class,
        BlockPlacementPolicy.class);
    conf.setBoolean(DFSConfigKeys.DFS_USE_DFS_NETWORK_TOPOLOGY_KEY, true);
    conf.setLong(DFSConfigKeys.DFS_BLOCK_SIZE_KEY, DEFAULT_BLOCK_SIZE);
    conf.setInt(DFSConfigKeys.DFS_BYTES_PER_CHECKSUM_KEY,
        DEFAULT_BLOCK_SIZE / 2);

    if (cluster != null) {
      cluster.shutdown();
    }
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(7).racks(racks)
        .hosts(hosts).build();
    cluster.waitActive();
    nameNodeRpc = cluster.getNameNodeRpc();
    namesystem = cluster.getNamesystem();
    DistributedFileSystem fs = cluster.getFileSystem();
    fs.enableErasureCodingPolicy("RS-3-2-1024k");
    fs.setErasureCodingPolicy(new Path("/"), "RS-3-2-1024k");

    final BlockManager bm = cluster.getNamesystem().getBlockManager();
    final DatanodeManager dm = bm.getDatanodeManager();
    assertTrue(dm.getNetworkTopology() instanceof DFSNetworkTopology);

    String clientMachine = "/host4";
    String clientRack = "/RACK4";
    String src = "/test";

    final DatanodeManager dnm = namesystem.getBlockManager().getDatanodeManager();
    DatanodeDescriptor dnd4 = dnm.getDatanode(cluster.getDataNodes().get(4).getDatanodeId());
    assertEquals(dnd4.getNetworkLocation(), clientRack);
    dnm.getDatanodeAdminManager().startDecommission(dnd4);
    short replication = 5;
    short additionalReplication = 1;

    try {
      // Create the file with client machine
      HdfsFileStatus fileStatus = namesystem.startFile(src, perm,
          clientMachine, clientMachine, EnumSet.of(CreateFlag.CREATE), true,
          replication, DEFAULT_BLOCK_SIZE * 1024 * 10, null, null, null, false);

      //test chooseTarget for new file
      LocatedBlock locatedBlock = nameNodeRpc.addBlock(src, clientMachine,
          null, null, fileStatus.getFileId(), null, null);
      HashMap<String, Integer> racksCount = new HashMap<String, Integer>();
      doTestLocatedBlockRacks(racksCount, replication, 4, locatedBlock);

      //test chooseTarget for existing file.
      LocatedBlock additionalLocatedBlock =
          nameNodeRpc.getAdditionalDatanode(src, fileStatus.getFileId(),
              locatedBlock.getBlock(), locatedBlock.getLocations(),
              locatedBlock.getStorageIDs(), DatanodeInfo.EMPTY_ARRAY,
              additionalReplication, clientMachine);

      racksCount.clear();
      doTestLocatedBlockRacks(racksCount, additionalReplication + replication,
          4, additionalLocatedBlock);
      assertEquals(racksCount.get("/RACK0"), (Integer)2);
      assertEquals(racksCount.get("/RACK2"), (Integer)2);
    } finally {
      dnm.getDatanodeAdminManager().stopDecommission(dnd4);
    }

    //test if decommission succeeded
    DatanodeDescriptor dnd3 = dnm.getDatanode(cluster.getDataNodes().get(3).getDatanodeId());
    cluster.getNamesystem().writeLock(RwLockMode.BM);
    try {
      dm.getDatanodeAdminManager().startDecommission(dnd3);
    } finally {
      cluster.getNamesystem().writeUnlock(RwLockMode.BM,
          "testPlacementWithOnlyOneNodeInRackDecommission");
    }

    // make sure the decommission finishes and the block in on 4 racks
    GenericTestUtils.waitFor(new Supplier<Boolean>() {
      @Override
      public Boolean get() {
        return dnd3.isDecommissioned();
      }
    }, 1000, 10 * 1000);

    LocatedBlocks locatedBlocks =
        cluster.getFileSystem().getClient().getLocatedBlocks(
            src, 0, DEFAULT_BLOCK_SIZE);
    assertEquals(4, bm.getDatanodeManager().
        getNetworkTopology().getNumOfNonEmptyRacks());
    for (LocatedBlock block : locatedBlocks.getLocatedBlocks()) {
      BlockPlacementStatus status = bm.getStriptedBlockPlacementPolicy()
              .verifyBlockPlacement(block.getLocations(), 5);
      Assert.assertTrue(status.isPlacementPolicySatisfied());
    }
  }

  private void shuffle(DatanodeInfo[] locs, String[] storageIDs) {
    int length = locs.length;
    Object[][] pairs = new Object[length][];
    for (int i = 0; i < length; i++) {
      pairs[i] = new Object[]{locs[i], storageIDs[i]};
    }
    Collections.shuffle(Arrays.asList(pairs));
    for (int i = 0; i < length; i++) {
      locs[i] = (DatanodeInfo) pairs[i][0];
      storageIDs[i] = (String) pairs[i][1];
    }
  }

  private void doTestLocatedBlock(int replication, LocatedBlock locatedBlock) {
    assertEquals(replication, locatedBlock.getLocations().length);

    HashMap<String, Integer> racksCount = new HashMap<String, Integer>();
    for (DatanodeInfo node :
        locatedBlock.getLocations()) {
      addToRacksCount(node.getNetworkLocation(), racksCount);
    }

    int minCount = Integer.MAX_VALUE;
    int maxCount = Integer.MIN_VALUE;
    for (Integer rackCount : racksCount.values()) {
      minCount = Math.min(minCount, rackCount);
      maxCount = Math.max(maxCount, rackCount);
    }
    assertTrue(maxCount - minCount <= 1);
  }

  private void doTestLocatedBlockRacks(HashMap<String, Integer> racksCount, int replication,
                                       int validracknum, LocatedBlock locatedBlock) {
    assertEquals(replication, locatedBlock.getLocations().length);

    for (DatanodeInfo node :
        locatedBlock.getLocations()) {
      addToRacksCount(node.getNetworkLocation(), racksCount);
    }
    assertEquals(validracknum, racksCount.size());
  }

  private void addToRacksCount(String rack, HashMap<String, Integer> racksCount) {
    Integer count = racksCount.get(rack);
    if (count == null) {
      racksCount.put(rack, 1);
    } else {
      racksCount.put(rack, count + 1);
    }
  }
}
