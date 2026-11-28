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
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #13576: the GQL path search prefixes {@code SHORTEST 1}, {@code ANY SHORTEST} and {@code ALL SHORTEST} were refused, so
 * the statements Neo4j 5.21+ tools generate (Cypher-DSL's {@code Cypher.shortestK(1)}) did not run. The ones whose meaning
 * {@code shortestPath()} / {@code allShortestPaths()} already implement run as those searches; the rest stays refused.
 * <p>
 * Every expected value below was read off Neo4j 5.26.33. The graph:
 * <pre>
 * A -R-> M &lt;-R- B      A -R-> X &lt;-R- C -R-> M
 * </pre>
 */
class Issue13576ShortestSelectorPrefixTest extends TestHelper {
  private static final String A_TO_B = "(a:Person {name:'A'})-[*]-(b:Person {name:'B'})";
  private static final String A_TO_C = "(a:Person {name:'A'})-[*]-(c:Person {name:'C'})";

  @Override
  public void beginTest() {
    database.transaction(() -> database.command("opencypher",
        "CREATE (a:Person {name:'A'})-[:R]->(m:Movie {title:'M'})<-[:R]-(b:Person {name:'B'}), "
            + "(a)-[:R]->(x:Movie {title:'X'})<-[:R]-(c:Person {name:'C'})-[:R]->(m)"));
  }

  private List<Object> column(final String query, final String column) {
    final List<Object> values = new ArrayList<>();
    try (final ResultSet rs = database.query("opencypher", query, Map.of())) {
      while (rs.hasNext())
        values.add(rs.next().getProperty(column));
    }
    return values;
  }

  @Test
  void oneShortestPathPerPairOfEnds() {
    for (final String prefix : new String[] { "SHORTEST 1", "ANY SHORTEST", "SHORTEST 1 PATH", "ANY SHORTEST PATHS", "any shortest" }) {
      assertThat(column("MATCH p = " + prefix + " " + A_TO_B + " RETURN length(p) AS l", "l")).as(prefix).containsExactly(2L);
      // two paths of length 2 join A and C: the selector keeps one
      assertThat(column("MATCH p = " + prefix + " " + A_TO_C + " RETURN length(p) AS l", "l")).as(prefix).containsExactly(2L);
    }
    // no path variable
    assertThat(column("MATCH SHORTEST 1 " + A_TO_B + " RETURN a.name + b.name AS n", "n")).containsExactly("AB");
    // no path at all: no row, and an OPTIONAL MATCH keeps its row
    assertThat(column("MATCH p = SHORTEST 1 (a:Person {name:'A'})-[*]->(b:Person {name:'B'}) RETURN length(p) AS l", "l")).isEmpty();
    assertThat(column("OPTIONAL MATCH p = ANY SHORTEST (a:Person {name:'A'})-[*]-(b:Person {name:'Nobody'}) RETURN p IS NULL AS n", "n"))
        .containsExactly(true);
  }

  @Test
  void everyShortestPathForAllShortest() {
    assertThat(column("MATCH p = ALL SHORTEST " + A_TO_B + " RETURN length(p) AS l", "l")).containsExactly(2L);
    assertThat(column("MATCH p = ALL SHORTEST " + A_TO_C
        + " RETURN [n IN nodes(p) | coalesce(n.name, n.title)] AS names ORDER BY names", "names"))
        .containsExactly(List.of("A", "M", "C"), List.of("A", "X", "C"));
  }

  @Test
  void aSelectorWithEndsToFindFromTheGraph() {
    // both ends unbound: one shortest path per pair of different people. The pairs of one person, whose shortest path is a
    // cycle, are not supported (see theShortestCycleIsRefused)
    assertThat(column("MATCH p = SHORTEST 1 (a:Person)-[*]-(b:Person) WHERE a.name < b.name RETURN a.name + b.name + length(p) AS n "
        + "ORDER BY n", "n")).containsExactly("AB2", "AC2", "BC2");
    assertThat(column("MATCH p = SHORTEST 1 (a:Person {name:'A'})-[:R*]->(b:Movie) RETURN b.title + length(p) AS n ORDER BY n", "n"))
        .containsExactly("M1", "X1");
  }

  @Test
  void hopBounds() {
    assertThat(column("MATCH p = SHORTEST 1 (a:Person {name:'A'})-[:R*..2]-(c:Person {name:'C'}) RETURN length(p) AS l", "l"))
        .containsExactly(2L);
    assertThat(column("MATCH p = SHORTEST 1 (a:Person {name:'A'})-[:R*..1]-(c:Person {name:'C'}) RETURN length(p) AS l", "l"))
        .isEmpty();
    assertThat(column("MATCH p = ALL SHORTEST (a:Person {name:'A'})-[:R*1..1]-(b:Person {name:'B'}) RETURN length(p) AS l", "l"))
        .isEmpty();
  }

  /**
   * A WHERE of the MATCH filters the paths the selector chose, as in Neo4j: the shortest path A..B has 2 relationships and
   * crosses M, so a predicate it fails leaves no row, where the WHERE of a shortestPath() restricts the search and finds the
   * 4-relationship path (issue #13574).
   */
  @Test
  void aWhereOfTheMatchFiltersTheSelectedPaths() {
    for (final String prefix : new String[] { "SHORTEST 1", "ANY SHORTEST", "ALL SHORTEST" }) {
      assertThat(column("MATCH p = " + prefix + " " + A_TO_B + " WHERE length(p) > 2 RETURN length(p) AS l", "l")).as(prefix).isEmpty();
      assertThat(column("MATCH p = " + prefix + " " + A_TO_B
          + " WHERE all(n IN nodes(p) WHERE n.title IS NULL OR n.title <> 'M') RETURN length(p) AS l", "l")).as(prefix).isEmpty();
      assertThat(column("MATCH p = " + prefix + " " + A_TO_B + " WHERE length(p) = 2 RETURN length(p) AS l", "l")).as(prefix)
          .containsExactly(2L);
    }
    assertThat(column("MATCH p = shortestPath((a:Person {name:'A'})-[*]-(b:Person {name:'B'})) WHERE length(p) > 2 "
        + "RETURN length(p) AS l", "l")).containsExactly(4L);
  }

  @Test
  void aQuantifiedRelationshipIsAVariableLengthOne() {
    assertThat(column("MATCH p = SHORTEST 1 (a:Person {name:'A'})-[:R]-+(b:Person {name:'B'}) RETURN length(p) AS l", "l"))
        .containsExactly(2L);
    assertThat(column("MATCH p = ALL SHORTEST (a:Person {name:'A'})-[:R]-+(c:Person {name:'C'}) RETURN length(p) AS l", "l"))
        .containsExactly(2L, 2L);
  }

  @Test
  void aSelectorInASubqueryBody() {
    assertThat(column("MATCH (a:Person {name:'A'}) CALL (a) { MATCH p = ANY SHORTEST (a)-[*]-(b:Person {name:'B'}) RETURN length(p) AS l }"
        + " RETURN l", "l")).containsExactly(2L);
  }

  /** A selector inside EXISTS {}, COUNT {} and COLLECT {} bodies, read off Neo4j 5.26.33 on the graph of CypherGqlConstructsTest. */
  @Test
  void aSelectorInAnExistenceCountOrCollectBody() {
    database.transaction(() -> database.command("opencypher",
        "CREATE (a:Walker {name:'A'}), (b:Walker {name:'B'}), (c:Walker {name:'C'}), (d:Walker {name:'D'}), "
            + "(a)-[:KNOWS]->(b), (b)-[:KNOWS]->(c), (c)-[:KNOWS]->(d), (a)-[:KNOWS]->(d)"));
    final String a = "MATCH (a:Walker {name:'A'}) ";
    assertThat(column(a + "WHERE EXISTS { ANY SHORTEST (a)-[:KNOWS]->+(:Walker {name:'D'}) } RETURN a.name AS n", "n")).containsExactly("A");
    assertThat(column(a + "RETURN COUNT { ANY SHORTEST (a)-[:KNOWS]->+(:Walker {name:'D'}) } AS n", "n")).containsExactly(1L);
    assertThat(column(a + "RETURN COUNT { MATCH ANY SHORTEST (a)-[:KNOWS]->+(:Walker {name:'D'}) } AS n", "n")).containsExactly(1L);
    assertThat(column(a + "RETURN COLLECT { MATCH p = ANY SHORTEST (a)-[:KNOWS]->+(:Walker {name:'D'}) RETURN length(p) } AS n", "n"))
        .containsExactly(List.of(1L));
    assertThat(column(a + "CALL (a) { MATCH p = ANY SHORTEST (a)-[:KNOWS]->+(:Walker {name:'D'}) RETURN length(p) AS l } RETURN l", "l"))
        .containsExactly(1L);
  }

  @Test
  void theOtherSelectorsStayRefused() {
    final String[] refused = {
        "MATCH p = SHORTEST 2 " + A_TO_B + " RETURN p",
        "MATCH p = SHORTEST 2 PATHS " + A_TO_B + " RETURN p",
        "MATCH p = SHORTEST 0 " + A_TO_B + " RETURN p",
        "MATCH p = SHORTEST $k " + A_TO_B + " RETURN p",
        "MATCH p = SHORTEST 1 GROUPS " + A_TO_B + " RETURN p",
        "MATCH p = SHORTEST GROUPS " + A_TO_B + " RETURN p",
        "MATCH p = ANY " + A_TO_B + " RETURN p",
        "MATCH p = ANY 2 " + A_TO_B + " RETURN p",
        // a path mode: Neo4j refuses SHORTEST 1 WALK too
        "MATCH p = ANY SHORTEST WALK " + A_TO_B + " RETURN p",
        "MATCH p = ALL SHORTEST TRAIL " + A_TO_B + " RETURN p",
        // shapes shortestPath() cannot answer: a fixed length, a minimum other than 1, more than one relationship, one node twice
        "MATCH p = SHORTEST 1 (a:Person {name:'A'})-[:R]->(b) RETURN p",
        "MATCH p = SHORTEST 1 (a:Person {name:'A'})-[*2..]-(b:Person {name:'B'}) RETURN p",
        "MATCH p = SHORTEST 1 (a:Person {name:'A'})-[*0..]-(b:Person {name:'B'}) RETURN p",
        "MATCH p = ANY SHORTEST (a:Person {name:'A'})-[:R*3]-(b) RETURN p",
        "MATCH p = ALL SHORTEST (a:Person {name:'A'})-->(m)<--(b) RETURN p",
        "MATCH p = SHORTEST 1 (a:Person {name:'A'})-[*]-(a) RETURN p" };
    for (final String query : refused)
      assertThatThrownBy(() -> column(query, "p")).as(query).hasMessageContaining("path search prefix");
  }

  /** GQL answers the shortest cycle of a node to itself, which the shortestPath() search does not compute: refused, not dropped. */
  @Test
  void theShortestCycleIsRefused() {
    assertThatThrownBy(() -> column("MATCH (a:Person {name:'A'}), (b:Person {name:'A'}) MATCH p = SHORTEST 1 (a)-[*]-(b) RETURN length(p) AS l",
        "l")).hasMessageContaining("node to itself");
    assertThatThrownBy(() -> column("MATCH p = ALL SHORTEST (a:Person)-[*]-(b:Person) RETURN length(p) AS l", "l"))
        .hasMessageContaining("node to itself");
  }
}
