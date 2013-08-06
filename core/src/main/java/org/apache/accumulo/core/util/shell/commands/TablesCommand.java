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
package org.apache.accumulo.core.util.shell.commands;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.TreeMap;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.TableNamespaceNotFoundException;
import org.apache.accumulo.core.client.impl.TableNamespaces;
import org.apache.accumulo.core.util.shell.Shell;
import org.apache.accumulo.core.util.shell.Shell.Command;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.collections.iterators.AbstractIteratorDecorator;

public class TablesCommand extends Command {
  private Option tableIdOption;
  private Option disablePaginationOpt;
  
  @SuppressWarnings("unchecked")
  @Override
  public int execute(final String fullCommand, final CommandLine cl, final Shell shellState) throws AccumuloException, AccumuloSecurityException, IOException,
      TableNamespaceNotFoundException {
    
    final Iterator<String> tableNames;
    final Iterator<String> tableIds;
    
    if (cl.hasOption(OptUtil.tableNamespaceOpt().getOpt())) {
      String namespace = shellState.getConnector().tableNamespaceOperations().namespaceIdMap().get(OptUtil.getTableNamespaceOpt(cl, shellState));
      tableNames = TableNamespaces.getTableNames(shellState.getConnector().getInstance(), namespace).iterator();
      tableIds = TableNamespaces.getTableIds(shellState.getConnector().getInstance(), namespace).iterator();
    } else {
      tableNames = shellState.getConnector().tableOperations().list().iterator();
      tableIds = new TableIdIterator(new TreeMap<String,String>(shellState.getConnector().tableOperations().tableIdMap()).entrySet().iterator());
    }
    
    if (cl.hasOption(tableIdOption.getOpt())) {
      shellState.printLines(tableIds, !cl.hasOption(disablePaginationOpt.getOpt()));
    } else {
      shellState.printLines(tableNames, !cl.hasOption(disablePaginationOpt.getOpt()));
    }
    
    return 0;
  }
  
  /**
   * Decorator that formats table id and name for display.
   */
  private static final class TableIdIterator extends AbstractIteratorDecorator {
    public TableIdIterator(Iterator<Entry<String,String>> iterator) {
      super(iterator);
    }
    
    @SuppressWarnings("rawtypes")
    @Override
    public Object next() {
      Entry entry = (Entry) super.next();
      return String.format("%-15s => %10s%n", entry.getKey(), entry.getValue());
    }
  }
  
  @Override
  public String description() {
    return "displays a list of all existing tables";
  }
  
  @Override
  public Options getOptions() {
    final Options o = new Options();
    tableIdOption = new Option("l", "list-ids", false, "display internal table ids along with the table name");
    o.addOption(tableIdOption);
    disablePaginationOpt = new Option("np", "no-pagination", false, "disable pagination of output");
    o.addOption(disablePaginationOpt);
    o.addOption(OptUtil.tableNamespaceOpt("name of table namespace to list only its tables"));
    return o;
  }
  
  @Override
  public int numArgs() {
    return 0;
  }
}
