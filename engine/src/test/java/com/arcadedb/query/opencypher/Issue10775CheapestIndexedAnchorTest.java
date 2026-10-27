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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #10775: with two indexed predicates on one node the anchor was the first indexed property found, never the
 * cheapest, so a NOTUNIQUE index could win over a UNIQUE one depending only on the property names. Every candidate is
 * now costed and the cheapest one anchors the pattern. {@code Doc} and {@code Doc2} hold the same data with the UNIQUE
 * role swapped between the two properties, so a plan that depends on the names picks the wrong index on one of them.
 */
class Issue10775CheapestIndexedAnchorTest extends TestHelper {
  private static final int VERTICES = 4_000;

  @Override
  protected void beginTest() {
    database.command("sql", "CREATE VERTEX TYPE Doc");
    database.command("sql", "CREATE PROPERTY Doc.uid STRING");
    database.command("sql", "CREATE PROPERTY Doc.grp STRING");
    database.command("sql", "CREATE INDEX ON Doc (uid) UNIQUE");
    database.command("sql", "CREATE INDEX ON Doc (grp) NOTUNIQUE");

    database.command("sql", "CREATE VERTEX TYPE Doc2");
    database.command("sql", "CREATE PROPERTY Doc2.grp STRING");
    database.command("sql", "CREATE PROPERTY Doc2.uid STRING");
    database.command("sql", "CREATE INDEX ON Doc2 (grp) UNIQUE");
    database.command("sql", "CREATE INDEX ON Doc2 (uid) NOTUNIQUE");

    database.transaction(() -> {
      for (int i = 0; i < VERTICES; i++) {
        final String id = "id" + i;
        final String group = i < VERTICES / 2 ? "A" : "B";
        database.newVertex("Doc").set("uid", id).set("grp", group).save();
        database.newVertex("Doc2").set("grp", id).set("uid", group).save();
      }
    });
  }

  @Test
  void inListsAnchorOnTheUniqueIndexWhicheverPropertyCarriesIt() {
    final Map<String, Object> params = Map.of("ids", List.of("id0", "id1", "id2", "id3", "id4"), "groups", List.of("A"));

    final String onDoc = "MATCH (r:Doc) WHERE r.uid IN $ids AND r.grp IN $groups RETURN r.uid AS u";
    assertThat(explain(onDoc)).contains("index=Doc[uid]").doesNotContain("index=Doc[grp]");
    assertThat(column(onDoc, params)).containsExactlyInAnyOrder("id0", "id1", "id2", "id3", "id4");

    final String onDoc2 = "MATCH (r:Doc2) WHERE r.grp IN $ids AND r.uid IN $groups RETURN r.grp AS u";
    assertThat(explain(onDoc2)).contains("index=Doc2[grp]").doesNotContain("index=Doc2[uid]");
    assertThat(column(onDoc2, params)).containsExactlyInAnyOrder("id0", "id1", "id2", "id3", "id4");
  }

  @Test
  void literalInListsBehaveTheSame() {
    final String onDoc = "MATCH (r:Doc) WHERE r.uid IN ['id0','id1','id2','id3','id4'] AND r.grp IN ['A'] RETURN r.uid AS u";
    assertThat(explain(onDoc)).contains("index=Doc[uid]").doesNotContain("index=Doc[grp]");
    assertThat(column(onDoc, Map.of())).containsExactlyInAnyOrder("id0", "id1", "id2", "id3", "id4");

    final String onDoc2 = "MATCH (r:Doc2) WHERE r.grp IN ['id0','id1','id2','id3','id4'] AND r.uid IN ['A'] RETURN r.grp AS u";
    assertThat(explain(onDoc2)).contains("index=Doc2[grp]").doesNotContain("index=Doc2[uid]");
    assertThat(column(onDoc2, Map.of())).containsExactlyInAnyOrder("id0", "id1", "id2", "id3", "id4");
  }

  @Test
  void equalitiesAnchorOnTheUniqueIndexWhicheverPropertyCarriesIt() {
    final Map<String, Object> params = Map.of("u", "id1", "g", "A");

    final String onDoc = "MATCH (r:Doc) WHERE r.uid = $u AND r.grp = $g RETURN r.uid AS u";
    assertThat(explain(onDoc)).contains("index=Doc[uid]").doesNotContain("index=Doc[grp]");
    assertThat(column(onDoc, params)).containsExactly("id1");

    final String onDoc2 = "MATCH (r:Doc2) WHERE r.grp = $u AND r.uid = $g RETURN r.grp AS u";
    assertThat(explain(onDoc2)).contains("index=Doc2[grp]").doesNotContain("index=Doc2[uid]");
    assertThat(column(onDoc2, params)).containsExactly("id1");
  }

  @Test
  void anEqualityOnTheNonUniqueIndexDoesNotBeatAnInListOnTheUniqueOne() {
    final String query = "MATCH (r:Doc) WHERE r.grp = $g AND r.uid IN $ids RETURN r.uid AS u";
    assertThat(explain(query)).contains("index=Doc[uid]").doesNotContain("index=Doc[grp]");
    assertThat(column(query, Map.of("g", "A", "ids", List.of("id0", "id1", "id2", "id3", "id4"))))
        .containsExactlyInAnyOrder("id0", "id1", "id2", "id3", "id4");
  }

  @Test
  void theWrittenOrderOfThePredicatesDoesNotMatter() {
    final String query = "MATCH (r:Doc2) WHERE r.uid IN $groups AND r.grp IN $ids RETURN r.grp AS u";
    assertThat(explain(query)).contains("index=Doc2[grp]").doesNotContain("index=Doc2[uid]");
    assertThat(column(query, Map.of("ids", List.of("id0", "id1", "id2", "id3", "id4"), "groups", List.of("A"))))
        .containsExactlyInAnyOrder("id0", "id1", "id2", "id3", "id4");
  }

  @Test
  void anOrSeekOnTheUniqueIndexBeatsAnEqualityOnTheNonUniqueOne() {
    final String query = "MATCH (r:Doc) WHERE (r.uid = 'id1' OR r.uid = 'id2') AND r.grp = 'A' RETURN r.uid AS u";
    assertThat(explain(query)).contains("index=Doc[uid]").doesNotContain("index=Doc[grp]");
    assertThat(column(query, Map.of())).containsExactlyInAnyOrder("id1", "id2");
  }

  @Test
  void everyIndexOnThePropertyIsCostedNotOnlyTheFirstOne() {
    // The NOTUNIQUE composite leads with uid and is created first, so it is the first index found for uid
    database.command("sql", "CREATE VERTEX TYPE Doc3");
    database.command("sql", "CREATE PROPERTY Doc3.uid STRING");
    database.command("sql", "CREATE PROPERTY Doc3.grp STRING");
    database.command("sql", "CREATE INDEX ON Doc3 (uid, grp) NOTUNIQUE");
    database.command("sql", "CREATE INDEX ON Doc3 (uid) UNIQUE");
    database.command("sql", "CREATE INDEX ON Doc3 (grp) NOTUNIQUE");
    database.transaction(() -> {
      for (int i = 0; i < VERTICES; i++)
        database.newVertex("Doc3").set("uid", "id" + i).set("grp", i < VERTICES / 2 ? "A" : "B").save();
    });

    final String equality = "MATCH (r:Doc3) WHERE r.uid = $u AND r.grp = $g RETURN r.uid AS u";
    assertThat(explain(equality)).contains("index=Doc3[uid]").doesNotContain("index=Doc3[uid,grp]").doesNotContain("index=Doc3[grp]");
    assertThat(column(equality, Map.of("u", "id1", "g", "A"))).containsExactly("id1");

    final String inList = "MATCH (r:Doc3) WHERE r.uid IN $ids AND r.grp = $g RETURN r.uid AS u";
    assertThat(explain(inList)).contains("index=Doc3[uid]").doesNotContain("index=Doc3[uid,grp]").doesNotContain("index=Doc3[grp]");
    assertThat(column(inList, Map.of("ids", List.of("id0", "id1", "id2", "id3", "id4"), "g", "A")))
        .containsExactlyInAnyOrder("id0", "id1", "id2", "id3", "id4");
  }

  @Test
  void aTinyTypeStillPrefersTheUniqueIndex() {
    // Below ten rows every seek rounds to the same row estimate; the unique index must still win, whatever the
    // written order. The NOTUNIQUE property is declared and written first so that order alone would pick it.
    database.command("sql", "CREATE VERTEX TYPE Tiny");
    database.command("sql", "CREATE PROPERTY Tiny.grp STRING");
    database.command("sql", "CREATE PROPERTY Tiny.uid STRING");
    database.command("sql", "CREATE INDEX ON Tiny (grp) NOTUNIQUE");
    database.command("sql", "CREATE INDEX ON Tiny (uid) UNIQUE");
    database.transaction(() -> {
      for (int i = 0; i < 5; i++)
        database.newVertex("Tiny").set("uid", "id" + i).set("grp", "A").save();
    });

    final String equality = "MATCH (r:Tiny) WHERE r.grp = $g AND r.uid = $u RETURN r.uid AS u";
    assertThat(explain(equality)).contains("index=Tiny[uid]").doesNotContain("index=Tiny[grp]");
    assertThat(column(equality, Map.of("u", "id1", "g", "A"))).containsExactly("id1");

    final String inLists = "MATCH (r:Tiny) WHERE r.grp IN $groups AND r.uid IN $ids RETURN r.uid AS u";
    assertThat(explain(inLists)).contains("index=Tiny[uid]").doesNotContain("index=Tiny[grp]");
    assertThat(column(inLists, Map.of("ids", List.of("id1", "id2"), "groups", List.of("A")))).containsExactlyInAnyOrder("id1", "id2");
  }

  @Test
  void theFirstWrittenInlinePropertyBreaksATieBetweenEqualIndexes() {
    // Two NOTUNIQUE indexes cost the same, so the tie goes to the property written first in the map - not to
    // the one whose name hashes first ('a' before 'b').
    database.command("sql", "CREATE VERTEX TYPE Pair");
    database.command("sql", "CREATE PROPERTY Pair.a STRING");
    database.command("sql", "CREATE PROPERTY Pair.b STRING");
    database.command("sql", "CREATE INDEX ON Pair (a) NOTUNIQUE");
    database.command("sql", "CREATE INDEX ON Pair (b) NOTUNIQUE");
    database.transaction(() -> {
      for (int i = 0; i < 100; i++)
        database.newVertex("Pair").set("a", "a" + (i % 10)).set("b", "b" + (i % 10)).save();
    });

    assertThat(explain("MATCH (r:Pair {b: 'b1', a: 'a1'}) RETURN r.a AS a")).contains("index=Pair[b]").doesNotContain("index=Pair[a]");
    assertThat(explain("MATCH (r:Pair {a: 'a1', b: 'b1'}) RETURN r.a AS a")).contains("index=Pair[a]").doesNotContain("index=Pair[b]");
    assertThat(column("MATCH (r:Pair {b: 'b1', a: 'a1'}) RETURN r.a AS a", Map.of())).containsExactlyElementsOf(
        java.util.Collections.nCopies(10, "a1"));
  }

  private List<Object> column(final String query, final Map<String, Object> params) {
    final List<Object> values = new ArrayList<>();
    try (final ResultSet rs = database.command("opencypher", query, params)) {
      while (rs.hasNext())
        values.add(new HashMap<>(rs.next().toMap()).values().iterator().next());
    }
    return values;
  }

  private String explain(final String query) {
    try (final ResultSet rs = database.query("opencypher", "EXPLAIN " + query, Map.of())) {
      return rs.getExecutionPlan().get().prettyPrint(0, 2);
    }
  }
}
