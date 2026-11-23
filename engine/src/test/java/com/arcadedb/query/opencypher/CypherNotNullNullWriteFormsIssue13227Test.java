/*
 * Copyright © 2021-present Arcade Data Ltd (info@arcadedata.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.arcadedb.query.opencypher;

import com.arcadedb.TestHelper;
import com.arcadedb.exception.ValidationException;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Issue #13227: a null written to a NOTNULL property is refused by SQL, and #13039 makes {@code SET n.nn = null} refuse it too.
 * openCypher stores no null: the property is removed ({@code SET}) or never stored ({@code CREATE}), so every other write form
 * accepted the null silently. Every form below has to be refused with the same {@link ValidationException} as SQL, and the
 * stored state has to stay as it was.
 */
class CypherNotNullNullWriteFormsIssue13227Test extends TestHelper {

  @BeforeEach
  void createSchema() {
    database.command("sql", "CREATE VERTEX TYPE T");
    database.command("sql", "CREATE EDGE TYPE R");
    database.command("sql", "CREATE PROPERTY T.id STRING");
    database.command("sql", "CREATE INDEX ON T (id) UNIQUE");
    database.command("sql", "CREATE PROPERTY T.nn STRING (notnull true)");
    database.command("sql", "CREATE PROPERTY R.nn STRING (notnull true)");
    database.transaction(() -> {
      database.command("sql", "INSERT INTO T SET id = 'a', nn = 'x'");
      database.command("sql", "INSERT INTO T SET id = 'b', nn = 'y'");
      database.command("sql", "CREATE EDGE R FROM (SELECT FROM T WHERE id = 'a') TO (SELECT FROM T WHERE id = 'b') SET nn = 'z'");
    });
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {
      "MATCH (n:T {id:'a'}) SET n += {nn: null}",
      "MATCH (n:T {id:'a'}) SET n += $m",
      "MATCH (n:T {id:'a'}) SET n = {id: 'a', nn: null}",
      "MATCH (n:T {id:'a'}) SET n = $m",
      "UNWIND $rows AS row MATCH (n:T {id: row.id}) SET n += row.props",
      "UNWIND $rows AS row MATCH (n:T {id: row.id}) SET n = row.props",
      "UNWIND $rows AS row MERGE (n:T {id: row.id}) SET n += row.props",
      "MERGE (n:T {id:'a'}) ON MATCH SET n += $m",
      "MERGE (n:T {id:'a'}) ON MATCH SET n = $m",
      "MATCH (n:T {id:'a'}) FOREACH (_ IN [1] | SET n += $m)",
      "UNWIND $rows AS row MERGE (n:T {id: row.id}) WITH n, row FOREACH (_ IN CASE WHEN true THEN [1] ELSE [] END | SET n += row.props)",
      "MATCH ()-[r:R]->() SET r += {nn: null}",
      "MATCH ()-[r:R]->() SET r = {nn: null}" })
  void anUpdateOfAnExistingRecordRefusesTheNull(final String statement) {
    assertRefusedAndUnchanged(statement, "a");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {
      "CREATE (n:T {id:'c', nn: null})",
      "CREATE (n:T $m)",
      "CREATE (n:T {id:'c'}) SET n.nn = null",
      "CREATE (n:T) SET n += $m",
      "CREATE (n:T) SET n = $m",
      "UNWIND $rows AS row CREATE (n:T {id: row.id, nn: row.v})",
      "UNWIND $rows AS row CREATE (n:T) SET n += row.props",
      "UNWIND $rows AS row CREATE (n:T) SET n = row.props",
      "UNWIND $rows AS row MERGE (n:T {id: row.id}) SET n += row.props",
      "UNWIND $rows AS row MERGE (n:T {id: row.id}) ON CREATE SET n += row.props",
      "MERGE (n:T {id:'c'}) ON CREATE SET n.nn = null",
      "MERGE (n:T {id:'c'}) ON CREATE SET n += $m",
      "FOREACH (row IN $rows | CREATE (n:T {id: row.id, nn: row.v}))",
      "CREATE (:T {id:'e1'})-[:R {nn: null}]->(:T {id:'e2'})",
      "MATCH (a:T {id:'a'}), (b:T {id:'b'}) CREATE (a)-[:R $m]->(b)",
      "MATCH (a:T {id:'a'}), (b:T {id:'b'}) MERGE (a)-[r:R {id: 'k'}]->(b) SET r += $m",
      "MATCH (a:T {id:'a'}), (b:T {id:'b'}) CREATE (a)-[r:R]->(b) SET r.nn = null" })
  void aCreateRefusesTheNull(final String statement) {
    assertRefusedAndUnchanged(statement, "c");
  }

  @Test
  void aNullForAPropertyWithoutTheConstraintIsStillDroppedOrRemoved() {
    // only a NOTNULL property refuses the null: a property of the type without the flag, and one the type does not declare,
    // keep the openCypher meaning (the property is removed or never stored)
    database.command("sql", "CREATE PROPERTY T.other STRING");
    database.transaction(() -> database.command("sql", "UPDATE T SET other = 'o' WHERE id = 'a'"));

    database.command("opencypher", "MATCH (n:T {id:'a'}) SET n += {other: null, undeclared: null}");
    database.command("opencypher", "CREATE (n:T {id:'c', other: null, undeclared: null, nn: 'v'})");

    assertThat(state()).isEqualTo("a:nn=x other=<absent>; b:nn=y other=<absent>; c:nn=v other=<absent>; R:nn=z");
  }

  @Test
  void aValueIsStillWrittenThroughTheSameForms() {
    database.command("opencypher", "MATCH (n:T {id:'a'}) SET n += {nn: 'x2'}");
    database.command("opencypher", "MATCH (n:T {id:'b'}) SET n = {id: 'b', nn: 'y2'}");
    database.command("opencypher", "CREATE (n:T {id:'c', nn: 'v'})");
    database.command("opencypher", "UNWIND [{id: 'd', nn: 'w'}] AS row CREATE (n:T) SET n += row");

    assertThat(state()).isEqualTo("a:nn=x2 other=<absent>; b:nn=y2 other=<absent>; c:nn=v other=<absent>; d:nn=w other=<absent>; R:nn=z");
  }

  @Test
  void theRefusalIsTheOneSqlRaises() {
    final Throwable sql = catchThrowable(() -> database.command("sql", "UPDATE T SET nn = null WHERE id = 'a'"));
    final Throwable cypher = catchThrowable(() -> database.command("opencypher", "MATCH (n:T {id:'a'}) SET n += {nn: null}"));

    assertThat(validationOf(sql)).isNotNull();
    assertThat(validationOf(cypher)).isNotNull();
    assertThat(validationOf(cypher).getMessage()).startsWith("The property 'T.nn' cannot be null");
  }

  private void assertRefusedAndUnchanged(final String statement, final String id) {
    final String before = state();
    final Map<String, Object> props = new LinkedHashMap<>();
    props.put("id", id);
    props.put("nn", null);
    final Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", id);
    row.put("v", null);
    row.put("props", props);
    final Map<String, Object> params = Map.of("m", props, "rows", List.of(row));

    final Throwable thrown = catchThrowable(() -> {
      try (final ResultSet rs = database.command("opencypher", statement, params)) {
        while (rs.hasNext())
          rs.next();
      }
    });

    assertThat(thrown).as(statement).isNotNull();
    final ValidationException refusal = validationOf(thrown);
    assertThat(refusal).as(statement + ": " + thrown).isNotNull();
    assertThat(refusal.getMessage()).as(statement).contains("cannot be null");
    assertThat(state()).as(statement).isEqualTo(before);
  }

  private static ValidationException validationOf(final Throwable thrown) {
    Throwable cause = thrown;
    while (cause != null && !(cause instanceof ValidationException))
      cause = cause.getCause();
    return (ValidationException) cause;
  }

  /** Every vertex and edge with the NOTNULL property, and the plain one, in a fixed order. */
  private String state() {
    final List<String> rows = new ArrayList<>();
    try (final ResultSet rs = database.query("sql", "SELECT FROM T ORDER BY id")) {
      while (rs.hasNext()) {
        final Result r = rs.next();
        rows.add(r.getProperty("id") + ":nn=" + valueOf(r, "nn") + " other=" + valueOf(r, "other"));
      }
    }
    try (final ResultSet rs = database.query("sql", "SELECT FROM R")) {
      while (rs.hasNext())
        rows.add("R:nn=" + valueOf(rs.next(), "nn"));
    }
    return String.join("; ", rows);
  }

  private static String valueOf(final Result r, final String property) {
    return r.getPropertyNames().contains(property) ? String.valueOf((Object) r.getProperty(property)) : "<absent>";
  }
}
