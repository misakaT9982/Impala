// Copyright 2012 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.impala.planner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.impala.analysis.Analyzer;
import com.cloudera.impala.analysis.Expr;
import com.cloudera.impala.analysis.TupleId;
import com.cloudera.impala.common.InternalException;
import com.cloudera.impala.thrift.TExchangeNode;
import com.cloudera.impala.thrift.TPlanNode;
import com.cloudera.impala.thrift.TPlanNodeType;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

/**
 * Receiver side of a 1:n data stream. Logically, an ExchangeNode consumes the data
 * produced by its children. For each of the sending child nodes the actual data
 * transmission is performed by the DataStreamSink of the PlanFragment housing
 * that child node. Typically, an ExchangeNode only has a single sender child but,
 * e.g., for distributed union queries an ExchangeNode may have one sender child per
 * union operand.
 *
 * TODO: merging of sorted inputs.
 */
public class ExchangeNode extends PlanNode {
  private final static Logger LOG = LoggerFactory.getLogger(ExchangeNode.class);

  public ExchangeNode(PlanNodeId id) {
    super(id, "EXCHANGE");
  }

  public void addChild(PlanNode node, boolean copyConjuncts) {
    // This ExchangeNode 'inherits' several parameters from its children.
    // Ensure that all children agree on them.
    if (!children.isEmpty()) {
      Preconditions.checkState(limit == node.limit);
      Preconditions.checkState(tupleIds.equals(node.tupleIds));
      Preconditions.checkState(rowTupleIds.equals(node.rowTupleIds));
      Preconditions.checkState(nullableTupleIds.equals(node.nullableTupleIds));
      Preconditions.checkState(compactData == node.compactData);
    } else {
      limit = node.limit;
      tupleIds = Lists.newArrayList(node.tupleIds);
      rowTupleIds = Lists.newArrayList(node.rowTupleIds);
      nullableTupleIds = Sets.newHashSet(node.nullableTupleIds);
      compactData = node.compactData;
    }
    if (copyConjuncts) conjuncts.addAll(Expr.cloneList(node.conjuncts, null));
    children.add(node);
  }

  @Override
  public void addChild(PlanNode node) { addChild(node, false); }

  @Override
  public void setCompactData(boolean on) { this.compactData = on; }

  @Override
  public void computeStats(Analyzer analyzer) {
    Preconditions.checkState(!children.isEmpty(),
        "ExchangeNode must have at least one child");
    cardinality = 0;
    for (PlanNode child: children) {
      if (child.getCardinality() == -1) {
        cardinality = -1;
        break;
      }
      cardinality += child.getCardinality();
    }

    if (hasLimit()) {
      if (cardinality == -1) {
        cardinality = limit;
      } else {
        cardinality = Math.min(limit, cardinality);
      }
    }

    // Pick the max numNodes and avgRowSize of all children.
    numNodes = Integer.MIN_VALUE;
    avgRowSize = Integer.MIN_VALUE;
    for (PlanNode child: children) {
      numNodes = Math.max(child.numNodes, numNodes);
      avgRowSize =  Math.max(child.numNodes, numNodes);
    }
  }

  @Override
  protected void toThrift(TPlanNode msg) {
    Preconditions.checkState(!children.isEmpty(),
        "ExchangeNode must have at least one child");
    msg.node_type = TPlanNodeType.EXCHANGE_NODE;
    msg.exchange_node = new TExchangeNode();
    for (TupleId tid: tupleIds) {
      msg.exchange_node.addToInput_row_tuples(tid.asInt());
    }
  }
}
