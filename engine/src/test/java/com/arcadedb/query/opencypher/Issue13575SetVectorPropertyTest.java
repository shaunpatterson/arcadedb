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
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #13575: {@code db.create.setNodeVectorProperty(node, key, vector)} and
 * {@code db.create.setRelationshipVectorProperty(relationship, key, vector)}, the way neo4j-graphrag and langchain-neo4j write
 * the property a vector index covers. Every expectation below was observed on Neo4j 5.26.33: both are void procedures
 * (YIELD is refused), they store 32-bit floats (a list of integers reads back as floats), and they refuse a null argument, a
 * vector that is not a list of numbers, an empty one and one with a value no 32-bit float carries, leaving the property as it
 * was.
 */
class Issue13575SetVectorPropertyTest extends TestHelper {

  @Test
  void aNodeVectorIsStoredAsFloatsAndTheCallPassesTheRowOn() {
    final List<Map<String, Object>> rows = run(
        "CREATE (e:E {name: 'a'}) WITH e CALL db.create.setNodeVectorProperty(e, 'v', [0.5, 0.25, 1]) RETURN e.v AS v, e.name AS n");
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().get("n")).isEqualTo("a");
    assertThat(floats(rows.getFirst().get("v"))).containsExactly(0.5f, 0.25f, 1.0f);

    // committed: a later statement reads the same vector, 32-bit precision included
    final List<Map<String, Object>> stored = run("MATCH (e:E) RETURN e.v AS v, size(e.v) AS s");
    assertThat(stored).hasSize(1);
    assertThat(stored.getFirst().get("s")).isEqualTo(3L);
    assertThat(floats(stored.getFirst().get("v"))).containsExactly(0.5f, 0.25f, 1.0f);
  }

  @Test
  void aSinglePrecisionValueRoundsLikeNeo4j() {
    run("CREATE (:E {name: 'a'})");
    run("MATCH (e:E) CALL db.create.setNodeVectorProperty(e, 'v', [0.1, 0.2, 0.3])");
    final Object stored = run("MATCH (e:E) RETURN e.v AS v").getFirst().get("v");
    // Neo4j answers [0.10000000149011612, 0.20000000298023224, 0.30000001192092896]
    assertThat(floats(stored)).containsExactly(0.1f, 0.2f, 0.3f);
  }

  @Test
  void aStandaloneCallAnswersNoRowAndStillWrites() {
    run("CREATE (:E {name: 'a'})");
    assertThat(run("MATCH (e:E {name: 'a'}) CALL db.create.setNodeVectorProperty(e, 'v', [1.0, 2.0])")).isEmpty();
    assertThat(floats(run("MATCH (e:E) RETURN e.v AS v").getFirst().get("v"))).containsExactly(1.0f, 2.0f);
  }

  @Test
  void theCallOverwritesAndChainsAndAnotherClauseSeesTheNewValue() {
    run("CREATE (:E {name: 'a', v: [9.0]})");
    final List<Map<String, Object>> rows = run("MATCH (e:E {name: 'a'}) "
        + "CALL db.create.setNodeVectorProperty(e, 'v', [1.0, 2.0]) "
        + "CALL db.create.setNodeVectorProperty(e, 'u', [3]) "
        + "SET e.z = 1 RETURN e.v AS v, e.u AS u, e.z AS z");
    assertThat(rows).hasSize(1);
    assertThat(floats(rows.getFirst().get("v"))).containsExactly(1.0f, 2.0f);
    assertThat(floats(rows.getFirst().get("u"))).containsExactly(3.0f);
    assertThat(rows.getFirst().get("z")).isEqualTo(1L);
  }

  @Test
  void aBatchOfRowsEachGetItsOwnVector() {
    run("UNWIND range(1, 3) AS i CREATE (:E {id: i})");
    run("UNWIND [{id: 1, e: [1.0, 0.0]}, {id: 2, e: [0.0, 1.0]}, {id: 3, e: [0.5, 0.5]}] AS row "
        + "MATCH (n:E {id: row.id}) CALL db.create.setNodeVectorProperty(n, 'v', row.e)");
    final List<Map<String, Object>> rows = run("MATCH (n:E) RETURN n.id AS id, n.v AS v ORDER BY id");
    assertThat(rows).hasSize(3);
    assertThat(floats(rows.get(0).get("v"))).containsExactly(1.0f, 0.0f);
    assertThat(floats(rows.get(1).get("v"))).containsExactly(0.0f, 1.0f);
    assertThat(floats(rows.get(2).get("v"))).containsExactly(0.5f, 0.5f);
  }

  @Test
  void aParameterVectorOfDoublesIsAccepted() {
    run("CREATE (:E {name: 'a'})");
    database.command("opencypher", "MATCH (e:E) CALL db.create.setNodeVectorProperty(e, 'v', $vector)",
        Map.of("vector", List.of(0.5d, 1.5d))).close();
    assertThat(floats(run("MATCH (e:E) RETURN e.v AS v").getFirst().get("v"))).containsExactly(0.5f, 1.5f);
  }

  @Test
  void aRelationshipVectorIsStoredAsFloats() {
    final List<Map<String, Object>> rows = run("CREATE (:A)-[r:R]->(:B) WITH r "
        + "CALL db.create.setRelationshipVectorProperty(r, 'v', [0.5, 0.25]) RETURN r.v AS v");
    assertThat(rows).hasSize(1);
    assertThat(floats(rows.getFirst().get("v"))).containsExactly(0.5f, 0.25f);

    run("MATCH ()-[r:R]->() CALL db.create.setRelationshipVectorProperty(r, 'v', [1, 2])");
    assertThat(floats(run("MATCH ()-[r:R]->() RETURN r.v AS v").getFirst().get("v"))).containsExactly(1.0f, 2.0f);
  }

  @Test
  void aCallThatRefusesLeavesTheStoredVectorAsItWas() {
    run("CREATE (:E {name: 'a'})-[:R]->(:B)");
    run("MATCH (e:E) CALL db.create.setNodeVectorProperty(e, 'v', [1.0, 2.0])");

    for (final String bad : List.of("null", "'x'", "5", "[]", "[1, 'a']", "[null]", "[true]", "[1.5e40]"))
      assertThatThrownBy(() -> run("MATCH (e:E) CALL db.create.setNodeVectorProperty(e, 'v', " + bad + ")"))
          .as("vector %s", bad).isInstanceOf(Exception.class).hasMessageContaining("vector");
    assertThatThrownBy(() -> run("MATCH (e:E) CALL db.create.setNodeVectorProperty(e, null, [1.0])"))
        .hasMessageContaining("'key' must not be null");
    assertThatThrownBy(() -> run("MATCH (e:E) CALL db.create.setNodeVectorProperty(null, 'v', [1.0])"))
        .hasMessageContaining("'node' must not be null");
    assertThatThrownBy(() -> run("MATCH ()-[r:R]->() CALL db.create.setRelationshipVectorProperty(null, 'v', [1.0])"))
        .hasMessageContaining("'relationship' must not be null");

    assertThat(floats(run("MATCH (e:E) RETURN e.v AS v").getFirst().get("v"))).containsExactly(1.0f, 2.0f);
  }

  @Test
  void aNodeIsNotARelationshipAndAWrongArgumentCountIsRefused() {
    run("CREATE (:E {name: 'a'})-[:R]->(:E {name: 'b'})");
    assertThatThrownBy(() -> run("MATCH (e:E {name: 'a'}) CALL db.create.setRelationshipVectorProperty(e, 'v', [1.0])"))
        .hasMessageContaining("relationship");
    assertThatThrownBy(() -> run("MATCH ()-[r:R]->() CALL db.create.setNodeVectorProperty(r, 'v', [1.0])"))
        .hasMessageContaining("node");
    assertThatThrownBy(() -> run("MATCH (e:E {name: 'a'}) CALL db.create.setNodeVectorProperty(e, 'v')"))
        .isInstanceOf(Exception.class);
  }

  @Test
  void theWrittenPropertyIsWhatAVectorIndexSearchesOn() {
    database.command("opencypher", "CREATE VECTOR INDEX chunks FOR (n:Chunk) ON n.embedding "
        + "OPTIONS {indexConfig: {`vector.dimensions`: 2, `vector.similarity_function`: 'cosine'}}").close();
    run("UNWIND [{id: 'a', e: [1.0, 0.0]}, {id: 'b', e: [0.0, 1.0]}, {id: 'c', e: [0.9, 0.1]}] AS row "
        + "CREATE (n:Chunk {id: row.id}) WITH n, row CALL db.create.setNodeVectorProperty(n, 'embedding', row.e)");
    final List<Map<String, Object>> rows = run(
        "CALL db.index.vector.queryNodes('chunks', 2, [1.0, 0.0]) YIELD node, score RETURN node.id AS id ORDER BY score DESC");
    assertThat(rows).extracting(r -> r.get("id")).containsExactly("a", "c");
  }

  private List<Map<String, Object>> run(final String statement) {
    final List<Map<String, Object>> rows = new ArrayList<>();
    // a write statement runs in a transaction of its own, as over Bolt
    database.transaction(() -> {
      try (final ResultSet rs = database.command("opencypher", statement)) {
        while (rs.hasNext()) {
          final Result r = rs.next();
          rows.add(r.toMap());
        }
      }
    });
    return rows;
  }

  private static List<Float> floats(final Object value) {
    final List<Float> out = new ArrayList<>();
    if (value instanceof float[] f)
      for (final float x : f)
        out.add(x);
    else if (value instanceof List<?> list)
      for (final Object x : list)
        out.add(((Number) x).floatValue());
    else
      throw new AssertionError("not a vector: " + value);
    return out;
  }
}
