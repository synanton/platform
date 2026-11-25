<!-- SPDX-License-Identifier: CC0-1.0 -->
# Relational Storage Notes

This is a synthetic, original technical-book excerpt dedicated to the public domain under CC0-1.0. It is not copied from a copyrighted book.

## Pages and Records

A page is the unit of physical storage. A record is the unit of logical access. The storage engine maps records to pages and keeps that mapping stable for the lifetime of a table.

## Indexes

An index orders references to records. The index does not duplicate the record body; it stores the key and a stable locator.

## Recovery

A write-ahead log records changes before pages are flushed. Recovery replays committed records and discards incomplete transactions.
