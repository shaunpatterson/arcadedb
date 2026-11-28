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
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #13588: the join order of a pattern that starts and ends at a bound hub (LDBC SNB BI 18:
 * {@code (t:Tag {name: $n})<-[:HAS_INTEREST]-(p1)-[:KNOWS]-(m)-[:KNOWS]-(p2)-[:HAS_INTEREST]->(t)}) priced a hop out of the hub as a
 * hop out of a leaf, the edges of the type over both ends' vertices, so the plan expanded the hub twice (every interested person
 * against every interested person) before it followed the path between the two ends. The expansion degree is now the one of the
 * bound end: the edges of the type over the vertices of its label.
 */
class Issue13588HubExpansionOrderTest {
  private static final String PATH    = "./target/databases/issue-13588-hub-expansion-order";
  private static final int    PERSONS = 300;
  private static final int    KNOWS   = 5;
  private static final String QUERY   = "MATCH (t:Tag {name: 'tag0'})<-[:HAS_INTEREST]-(p1:Person)-[:KNOWS]-(m:Person)-[:KNOWS]-(p2:Person)"
      + "-[:HAS_INTEREST]->(t) RETURN count(*) AS n";

  private Database database;

  @BeforeEach
  void setUp() {
    final DatabaseFactory factory = new DatabaseFactory(PATH);
    if (factory.exists())
      factory.open().drop();
    database = factory.create();
    database.command("sql", "CREATE VERTEX TYPE Person");
    database.command("sql", "CREATE VERTEX TYPE Tag");
    database.command("sql", "CREATE EDGE TYPE KNOWS");
    database.command("sql", "CREATE EDGE TYPE HAS_INTEREST");
    database.command("opencypher", "CREATE INDEX FOR (n:Tag) ON (n.name)");
    database.transaction(() -> {
      database.command("opencypher", "UNWIND range(0, 4) AS i CREATE (:Tag {id: i, name: 'tag' + toString(i)})");
      database.command("opencypher", "UNWIND range(0, $p - 1) AS i CREATE (:Person {id: i})", Map.of("p", PERSONS));
      // a ring: person i knows the K persons after it, so no pair of persons is joined twice
      database.command("opencypher", "UNWIND range(0, $p - 1) AS i UNWIND range(1, $k) AS j MATCH (a:Person {id: i}), "
          + "(b:Person {id: (i + j) % $p}) CREATE (a)-[:KNOWS]->(b)", Map.of("p", PERSONS, "k", KNOWS));
      // every third person is interested in tag0, every person in one of the other four tags: tag0 is a hub of 100 persons
      database.command("opencypher", "UNWIND range(0, $p - 1) AS i WITH i WHERE i % 3 = 0 MATCH (a:Person {id: i}), (t:Tag {id: 0}) "
          + "CREATE (a)-[:HAS_INTEREST]->(t)", Map.of("p", PERSONS));
      database.command("opencypher", "UNWIND range(0, $p - 1) AS i MATCH (a:Person {id: i}), (t:Tag {id: 1 + i % 4}) "
          + "CREATE (a)-[:HAS_INTEREST]->(t)", Map.of("p", PERSONS));
    });
  }

  @AfterEach
  void tearDown() {
    if (database != null) {
      database.drop();
      database = null;
    }
  }

  @Test
  void thePathBetweenTheEndsIsFollowedBeforeTheHubIsExpandedAgain() {
    final String plan = profile(QUERY);
    // the path t, p1, m, p2 is walked and closed on the hub at the end: the second end is never expanded out of the hub
    assertThat(plan).as(plan).contains("ExpandInto(p2)-[:HAS_INTEREST]->(t)").doesNotContain("ExpandInto(m)-[:KNOWS]-(p2)");
  }

  @Test
  void theCountIsThatOfTheDefinition() {
    // the persons a person m knows, each way, who are interested in tag0: c(m); a path is an ordered pair of two of them
    // (they are two different relationships, so two different persons on a ring with no pair joined twice)
    long expected = 0;
    for (int m = 0; m < PERSONS; m++) {
      long interested = 0;
      for (int j = 1; j <= KNOWS; j++) {
        if (((m + j) % PERSONS) % 3 == 0)
          interested++;
        if (((m - j + PERSONS) % PERSONS) % 3 == 0)
          interested++;
      }
      expected += interested * (interested - 1);
    }
    try (final ResultSet rs = database.query("opencypher", QUERY)) {
      assertThat(rs.next().<Number>getProperty("n").longValue()).isEqualTo(expected);
    }
  }

  private String profile(final String query) {
    try (final ResultSet rs = database.query("opencypher", "PROFILE " + query)) {
      while (rs.hasNext())
        rs.next();
      return rs.getExecutionPlan().orElseThrow().prettyPrint(0, 2);
    }
  }
}
