# Distributed Systems: A Short Technical Primer

**Author:** Alex Morgan  
**Edition:** 1  
**Document ID:** synthetic-distributed-systems-primer-v1

<!-- SPDX-License-Identifier: CC0-1.0 -->
<!-- Source: synthetic fixture; generated for ingestion testing -->
<!-- Version: fixture metadata; not domain knowledge -->

## Preface

This short primer introduces a small set of concepts used when describing distributed software. It is intentionally concise so that every expected structural element, entity mention, and relationship can be reviewed.

## Chapter 1: Nodes and Communication

### 1.1 Distributed Systems

A **distributed system** is a collection of independent computing nodes that communicate to coordinate work. The nodes do not share one physical memory space.

### 1.2 Definition: Node

A **node** is an independent computing participant in a distributed system. A node may be a physical machine or a software process.

### 1.3 Definition: Message

A **message** is a unit of information exchanged between nodes. Message passing is the communication mechanism used by the nodes in this primer.

A node sends a message to another node. The receiving node processes the message and may send a response.

### 1.4 Acronym: RPC

**Remote Procedure Call (RPC)** is a mechanism that lets one process invoke an operation exposed by another process. In this fixture, RPC is a communication mechanism; the acronym must not be extracted from the metadata comments above.

## Chapter 2: Coordination and Reliability

### 2.1 Consensus

**Consensus** is a process through which multiple nodes agree on a value or decision. Consensus protocols help coordinate replicated state.

### 2.2 Raft

**Raft** is a consensus algorithm. It uses leader election and log replication to coordinate a replicated log among participating nodes.

### 2.3 Replicated Log

A **replicated log** is an ordered sequence of entries maintained across multiple nodes. In the Raft example, log replication helps participating nodes converge on the same sequence of committed entries.

### 2.4 Fault Tolerance

**Fault tolerance** is the ability of a system to continue providing its intended service despite failures of some components. Replication can contribute to fault tolerance, but replication alone does not guarantee it.

## Appendix A: Compact Reference

| Term | Short description |
|---|---|
| Node | An independent participant |
| Message | Information exchanged between nodes |
| RPC | Remote Procedure Call |
| Consensus | Agreement on a value or decision |
| Raft | A consensus algorithm |
| Replicated log | An ordered sequence of replicated entries |
| Fault tolerance | Continued service despite component failures |

## Appendix B: Code Example

```text
node-a -> message -> node-b
node-b -> response -> node-a
```

The arrows in this example illustrate message flow; they are not additional domain entities or ontology predicates.
