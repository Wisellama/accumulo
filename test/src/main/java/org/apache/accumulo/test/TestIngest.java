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
package org.apache.accumulo.test;

import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import org.apache.accumulo.core.cli.BatchWriterOpts;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.client.MutationsRejectedException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.impl.TabletServerBatchWriter;
import org.apache.accumulo.core.client.security.SecurityErrorCode;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.data.ConstraintViolationSummary;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.KeyExtent;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.file.FileOperations;
import org.apache.accumulo.core.file.FileSKVWriter;
import org.apache.accumulo.core.file.rfile.RFile;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.ColumnVisibility;
import org.apache.accumulo.core.trace.DistributedTrace;
import org.apache.accumulo.core.util.CachedConfiguration;
import org.apache.accumulo.core.util.FastFormat;
import org.apache.accumulo.fate.zookeeper.ZooReader;
import org.apache.accumulo.server.cli.ClientOnDefaultTable;
import org.apache.accumulo.server.conf.ServerConfiguration;
import org.apache.accumulo.trace.instrument.Trace;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.io.Text;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import com.beust.jcommander.Parameter;


public class TestIngest {
  public static final Authorizations AUTHS = new Authorizations("L1", "L2", "G1", "GROUP2");
  
  static class Opts extends ClientOnDefaultTable {
    
    @Parameter(names="--createTable")
    boolean createTable = false;
    
    @Parameter(names="--splits", description="the number of splits to use when creating the table")
    int numsplits = 1;
    
    @Parameter(names="--start", description="the starting row number")
    int startRow = 0;
    
    @Parameter(names="--rows", description="the number of rows to ingest")
    int rows = 100000;
    
    @Parameter(names="--cols", description="the number of columns to ingest per row")
    int cols = 1;
    
    @Parameter(names="--random", description="insert random rows and use the given number to seed the psuedo-random number generator")
    Integer random = null;
    
    @Parameter(names="--size", description="the size of the value to ingest")
    int dataSize = 1000;
    
    @Parameter(names="--delete", description="delete values instead of inserting them")
    boolean delete = false;
    
    @Parameter(names={"-ts", "--timestamp"}, description="timestamp to use for all values")
    long timestamp = -1;
    
    @Parameter(names="--rfile", description="generate data into a file that can be imported")
    String outputFile = null;
    
    @Parameter(names="--stride", description="the difference between successive row ids")
    int stride;

    @Parameter(names={"-cf","--columnFamily"}, description="place columns in this column family")
    String columnFamily = "colf";

    @Parameter(names={"-cv","--columnVisibility"}, description="place columns in this column family", converter=VisibilityConverter.class)
    ColumnVisibility columnVisibility = new ColumnVisibility();

    Opts() { super("test_ingest"); }
  }
  
  @SuppressWarnings("unused")
  private static final Logger log = Logger.getLogger(TestIngest.class);
  
  public static void createTable(Opts args) throws Exception {
    if (args.createTable) {
      TreeSet<Text> splits = getSplitPoints(args.startRow, args.startRow + args.rows, args.numsplits);
      
      Connector conn = args.getConnector();
      if (!conn.tableOperations().exists(args.getTableName()))
        conn.tableOperations().create(args.getTableName());
      try {
        conn.tableOperations().addSplits(args.getTableName(), splits);
      } catch (TableNotFoundException ex) {
        // unlikely
        throw new RuntimeException(ex);
      }
    }
  }
  
  public static TreeSet<Text> getSplitPoints(long start, long end, long numsplits) {
    long splitSize = (end - start) / numsplits;
    
    long pos = start + splitSize;
    
    TreeSet<Text> splits = new TreeSet<Text>();
    
    while (pos < end) {
      splits.add(new Text(String.format("row_%010d", pos)));
      pos += splitSize;
    }
    return splits;
  }
  
  public static byte[][] generateValues(Opts ingestArgs) {
    
    byte[][] bytevals = new byte[10][];
    
    byte[] letters = {'1', '2', '3', '4', '5', '6', '7', '8', '9', '0'};
    
    for (int i = 0; i < 10; i++) {
      bytevals[i] = new byte[ingestArgs.dataSize];
      for (int j = 0; j < ingestArgs.dataSize; j++)
        bytevals[i][j] = letters[i];
    }
    return bytevals;
  }
  
  private static byte ROW_PREFIX[] = "row_".getBytes();
  private static byte COL_PREFIX[] = "col_".getBytes();
  
  public static Text generateRow(int rowid, int startRow) {
    return new Text(FastFormat.toZeroPaddedString(rowid + startRow, 10, 10, ROW_PREFIX));
  }
  
  public static byte[] genRandomValue(Random random, byte dest[], int seed, int row, int col) {
    random.setSeed((row ^ seed) ^ col);
    random.nextBytes(dest);
    toPrintableChars(dest);
    
    return dest;
  }
  
  public static void toPrintableChars(byte[] dest) {
    // transform to printable chars
    for (int i = 0; i < dest.length; i++) {
      dest[i] = (byte) (((0xff & dest[i]) % 92) + ' ');
    }
  }
  
  public static void main(String[] args) throws Exception {
    
    Opts opts = new Opts();
    BatchWriterOpts bwOpts = new BatchWriterOpts();
    opts.parseArgs(TestIngest.class.getName(), args, bwOpts);
    opts.getInstance().setConfiguration(ServerConfiguration.getSiteConfiguration());

    createTable(opts);
    
    Instance instance = opts.getInstance();
    
    String name = TestIngest.class.getSimpleName();
    DistributedTrace.enable(instance, new ZooReader(instance.getZooKeepers(), instance.getZooKeepersSessionTimeOut()), name, null);
    
    try {
      opts.startTracing(name);
      
      Logger.getLogger(TabletServerBatchWriter.class.getName()).setLevel(Level.TRACE);
      
      // test batch update
      
      long stopTime;
      
      byte[][] bytevals = generateValues(opts);
      
      byte randomValue[] = new byte[opts.dataSize];
      Random random = new Random();
      
      long bytesWritten = 0;
      
      BatchWriter bw = null;
      FileSKVWriter writer = null;
      
      if (opts.outputFile != null) {
        Configuration conf = CachedConfiguration.getInstance();
        FileSystem fs = FileSystem.get(conf);
        writer = FileOperations.getInstance().openWriter(opts.outputFile + "." + RFile.EXTENSION, fs, conf,
            AccumuloConfiguration.getDefaultConfiguration());
        writer.startDefaultLocalityGroup();
      } else {
        Connector connector = opts.getConnector();
        bw = connector.createBatchWriter(opts.getTableName(), bwOpts.getBatchWriterConfig());
        connector.securityOperations().changeUserAuthorizations(opts.principal, AUTHS);
      }
      Text labBA = new Text(opts.columnVisibility.getExpression());
      
      long startTime = System.currentTimeMillis();
      for (int i = 0; i < opts.rows; i++) {
        int rowid;
        if (opts.stride > 0) {
          rowid = ((i % opts.stride) * (opts.rows / opts.stride)) + (i / opts.stride);
        } else {
          rowid = i;
        }
        
        Text row = generateRow(rowid, opts.startRow);
        Mutation m = new Mutation(row);
        for (int j = 0; j < opts.cols; j++) {
          Text colf = new Text(opts.columnFamily);
          Text colq = new Text(FastFormat.toZeroPaddedString(j, 7, 10, COL_PREFIX));
          
          if (writer != null) {
            Key key = new Key(row, colf, colq, labBA);
            if (opts.timestamp >= 0) {
              key.setTimestamp(opts.timestamp);
            } else {
              key.setTimestamp(startTime);
            }
            
            if (opts.delete) {
              key.setDeleted(true);
            } else {
              key.setDeleted(false);
            }
            
            bytesWritten += key.getSize();
            
            if (opts.delete) {
              writer.append(key, new Value(new byte[0]));
            } else {
              byte value[];
              if (opts.random != null) {
                value = genRandomValue(random, randomValue, opts.random.intValue(), rowid + opts.startRow, j);
              } else {
                value = bytevals[j % bytevals.length];
              }
              
              Value v = new Value(value);
              writer.append(key, v);
              bytesWritten += v.getSize();
            }
            
          } else {
            Key key = new Key(row, colf, colq, labBA);
            bytesWritten += key.getSize();
            
            if (opts.delete) {
              if (opts.timestamp >= 0)
                m.putDelete(colf, colq, opts.columnVisibility, opts.timestamp);
              else
                m.putDelete(colf, colq, opts.columnVisibility);
            } else {
              byte value[];
              if (opts.random != null) {
                value = genRandomValue(random, randomValue, opts.random.intValue(), rowid + opts.startRow, j);
              } else {
                value = bytevals[j % bytevals.length];
              }
              bytesWritten += value.length;
              
              if (opts.timestamp >= 0) {
                m.put(colf, colq, opts.columnVisibility, opts.timestamp, new Value(value, true));
              } else {
                m.put(colf, colq, opts.columnVisibility, new Value(value, true));
                
              }
            }
          }
          
        }
        if (bw != null)
          bw.addMutation(m);
        
      }
      
      if (writer != null) {
        writer.close();
      } else if (bw != null) {
        try {
          bw.close();
        } catch (MutationsRejectedException e) {
          if (e.getAuthorizationFailuresMap().size() > 0) {
            for (Entry<KeyExtent,Set<SecurityErrorCode>> entry : e.getAuthorizationFailuresMap().entrySet()) {
              System.err.println("ERROR : Not authorized to write to : " + entry.getKey() + " due to " + entry.getValue());
            }
          }
          
          if (e.getConstraintViolationSummaries().size() > 0) {
            for (ConstraintViolationSummary cvs : e.getConstraintViolationSummaries()) {
              System.err.println("ERROR : Constraint violates : " + cvs);
            }
          }
          
          throw e;
        }
      }
      
      stopTime = System.currentTimeMillis();
      
      int totalValues = opts.rows * opts.cols;
      double elapsed = (stopTime - startTime) / 1000.0;
      
      System.out.printf("%,12d records written | %,8d records/sec | %,12d bytes written | %,8d bytes/sec | %6.3f secs   %n", totalValues,
          (int) (totalValues / elapsed), bytesWritten, (int) (bytesWritten / elapsed), elapsed);
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      Trace.off();
    }
  }
}
