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

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #13603: a chain of fixed-length hops whose last node an index seek answers, with the value of the seek read from a variable
 * an earlier clause bound ({@code UNWIND}, {@code WITH}, the variable a {@code CALL} subquery imports: LDBC SNB BI 16), was walked
 * in the order written, from a scan of every vertex of its first node, where the same chain with a literal or a parameter is an
 * index seek. The step-based plan now starts from the indexed node. Each test checks the plan's starting node and compares the
 * rows with the same pattern written from the indexed end.
 */
class Issue13603IndexedLastNodeAnchorTest {
  private static final String PATH     = "./target/databases/issue-13603-indexed-last-node";
  private static final int    PERSONS  = 40;
  private static final int    TAGS     = 20;
  private static final int    MESSAGES = 400;

  private Database database;

  @BeforeEach
  void setUp() {
    final DatabaseFactory factory = new DatabaseFactory(PATH);
    if (factory.exists())
      factory.open().drop();
    database = factory.create();
    database.command("sql", "CREATE VERTEX TYPE Person");
    database.command("sql", "CREATE VERTEX TYPE Message");
    database.command("sql", "CREATE VERTEX TYPE Tag");
    database.command("sql", "CREATE VERTEX TYPE Country");
    database.command("sql", "CREATE EDGE TYPE HAS_CREATOR");
    database.command("sql", "CREATE EDGE TYPE HAS_TAG");
    database.command("sql", "CREATE EDGE TYPE IS_LOCATED_IN");
    database.command("opencypher", "CREATE INDEX FOR (n:Tag) ON (n.name)");
    database.command("opencypher", "CREATE INDEX FOR (n:Message) ON (n.id)");
    database.transaction(() -> {
      database.command("opencypher", "UNWIND range(0, $t - 1) AS i CREATE (:Tag {id: i, name: 'tag' + toString(i)})", Map.of("t", TAGS));
      database.command("opencypher", "UNWIND range(0, $p - 1) AS i CREATE (:Person {id: i})", Map.of("p", PERSONS));
      database.command("opencypher", "UNWIND range(0, $m - 1) AS i MATCH (p:Person {id: i % $p}), (t:Tag {id: (i * 7) % $t}) "
          + "CREATE (p)<-[:HAS_CREATOR]-(:Message {id: i})-[:HAS_TAG]->(t)", Map.of("m", MESSAGES, "p", PERSONS, "t", TAGS));
    });
  }

  @AfterEach
  void tearDown() {
    if (database != null) {
      database.drop();
      database = null;
    }
  }

  private static final String WRITTEN_FROM_PERSON =
      "MATCH (p:Person)<-[:HAS_CREATOR]-(m:Message)-[:HAS_TAG]->(t:Tag {name: v}) RETURN p.id AS p, m.id AS m ORDER BY m";
  private static final String WRITTEN_FROM_TAG =
      "MATCH (t:Tag {name: v})<-[:HAS_TAG]-(m:Message)-[:HAS_CREATOR]->(p:Person) RETURN p.id AS p, m.id AS m ORDER BY m";

  @Test
  void anUnwindValueSeeksTheIndexedLastNodeFirst() {
    final String query = "UNWIND ['tag5', 'tag6'] AS v " + WRITTEN_FROM_PERSON;
    final String plan = profile(query);
    assertThat(plan).as(plan).doesNotContain("MATCH NODE (p:Person").contains("MATCH NODE (t:Tag");

    final List<String> rows = rows(query);
    assertThat(rows).hasSize(2 * MESSAGES / TAGS);
    assertThat(rows).isEqualTo(rows("UNWIND ['tag5', 'tag6'] AS v " + WRITTEN_FROM_TAG));
  }

  @Test
  void aWithValueAndTheWhereFormSeekTheIndexedLastNodeFirst() {
    final String with = "WITH 'tag5' AS v " + WRITTEN_FROM_PERSON;
    assertThat(profile(with)).doesNotContain("MATCH NODE (p:Person");
    assertThat(rows(with)).hasSize(MESSAGES / TAGS).isEqualTo(rows("WITH 'tag5' AS v " + WRITTEN_FROM_TAG));

    final String where = "UNWIND ['tag5'] AS v MATCH (p:Person)<-[:HAS_CREATOR]-(m:Message)-[:HAS_TAG]->(t:Tag) WHERE t.name = v "
        + "RETURN p.id AS p, m.id AS m ORDER BY m";
    assertThat(profile(where)).doesNotContain("MATCH NODE (p:Person");
    assertThat(rows(where)).isEqualTo(rows(with));
  }

  @Test
  void aSingleHopAndAnAnonymousLastNode() {
    final String single = "UNWIND ['tag5'] AS v MATCH (m:Message)-[:HAS_TAG]->(:Tag {name: v}) RETURN m.id AS m ORDER BY m";
    assertThat(profile(single)).doesNotContain("MATCH NODE (m:Message");
    assertThat(rows(single)).hasSize(MESSAGES / TAGS);
  }

  /** The same statement in a CALL subquery reads the same rows (LDBC SNB BI 16). */
  @Test
  void aCallSubqueryReadsTheSameRows() {
    final List<String> expected = rows("UNWIND ['tag5', 'tag6'] AS v " + WRITTEN_FROM_TAG);
    assertThat(rows("UNWIND ['tag5', 'tag6'] AS v CALL (v) { MATCH (p:Person)<-[:HAS_CREATOR]-(m:Message)-[:HAS_TAG]->(t:Tag {name: v}) "
        + "RETURN p.id AS p, m.id AS m } RETURN p, m ORDER BY m")).isEqualTo(expected);
    assertThat(rows("UNWIND ['tag5', 'tag6'] AS v CALL { WITH v MATCH (p:Person)<-[:HAS_CREATOR]-(m:Message)-[:HAS_TAG]->(t:Tag {name: v}) "
        + "RETURN p.id AS p, m.id AS m } RETURN p, m ORDER BY m")).isEqualTo(expected);
  }

  @Test
  void aValueThatMatchesNoTagKeepsNoRow() {
    assertThat(rows("UNWIND ['nope'] AS v " + WRITTEN_FROM_PERSON)).isEmpty();
    assertThat(rows("UNWIND [null] AS v " + WRITTEN_FROM_PERSON)).isEmpty();
  }

  @Test
  void whatTheWrittenOrderDecidesStaysInTheWrittenOrder() {
    // an OPTIONAL MATCH and a path variable bind the pattern as written
    assertThat(profile("UNWIND ['tag5'] AS v OPTIONAL MATCH (p:Person)<-[:HAS_CREATOR]-(m:Message)-[:HAS_TAG]->(t:Tag {name: v}) RETURN count(*)"))
        .contains("MATCH NODE (p:Person");
    assertThat(profile("UNWIND ['tag5'] AS v MATCH path = (p:Person)<-[:HAS_CREATOR]-(m:Message)-[:HAS_TAG]->(t:Tag {name: v}) RETURN count(*)"))
        .contains("MATCH NODE (p:Person");
    // a first node that is itself pinned stays where it is
    assertThat(profile("UNWIND ['tag5'] AS v MATCH (p:Person {id: 3})<-[:HAS_CREATOR]-(m:Message)-[:HAS_TAG]->(t:Tag {name: v}) RETURN count(*)"))
        .contains("MATCH NODE (p:Person");
    // the indexed node is bound already: nothing to seek
    assertThat(profile("UNWIND ['tag5'] AS v MATCH (t:Tag {name: v}) MATCH (p:Person)<-[:HAS_CREATOR]-(m:Message)-[:HAS_TAG]->(t) RETURN count(*)"))
        .doesNotContain("MATCH NODE (p:Person");
    // a label of no index is no seek
    assertThat(profile("UNWIND ['tag5'] AS v MATCH (m:Message)-[:HAS_CREATOR]->(p:Person {id: v}) RETURN count(*)"))
        .contains("MATCH NODE (m:Message");
  }

  @Test
  void theRowsOfEveryDirectionAreThoseOfTheIndexedEnd() {
    assertThat(rows("UNWIND ['tag5'] AS v MATCH (p:Person)-[:HAS_CREATOR]-(m:Message)-[:HAS_TAG]-(t:Tag {name: v}) "
        + "RETURN p.id AS p, m.id AS m ORDER BY m")).isEqualTo(rows("WITH 'tag5' AS v " + WRITTEN_FROM_TAG));
    // a direction no edge has still finds nothing
    assertThat(rows("UNWIND ['tag5'] AS v MATCH (p:Person)-[:HAS_CREATOR]->(m:Message)-[:HAS_TAG]->(t:Tag {name: v}) RETURN p")).isEmpty();
  }

  private List<String> rows(final String query) {
    final List<String> rows = new ArrayList<>();
    try (final ResultSet rs = database.query("opencypher", query)) {
      while (rs.hasNext()) {
        final Result row = rs.next();
        final StringBuilder value = new StringBuilder();
        for (final String column : row.getPropertyNames())
          value.append(value.isEmpty() ? "" : ":").append((Object) row.getProperty(column));
        rows.add(value.toString());
      }
    }
    return rows;
  }

  private String profile(final String query) {
    try (final ResultSet rs = database.query("opencypher", "PROFILE " + query)) {
      while (rs.hasNext())
        rs.next();
      return rs.getExecutionPlan().orElseThrow().prettyPrint(0, 2);
    }
  }
}
