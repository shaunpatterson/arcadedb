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
package com.arcadedb.query.sql.executor;

import com.arcadedb.TestHelper;
import com.arcadedb.database.Document;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.database.RID;
import com.arcadedb.query.sql.executor.FetchFromIndexStep.SeekCheck;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The checks {@link FetchFromIndexStep} makes on the records and the keys of the entries it served, for
 * {@link GetValueFromIndexEntryStep} to reconcile a lookup a commit overlapped (#9902, #11790): whether a record still holds the key
 * of its entry, whether a range entry still belongs to the range, which entries make up one lookup, and which lookups are point
 * lookups. Each is observable from the outside only through a commit landing at the right instruction, so they are checked here
 * on the step the planner builds for the statement, with records changed in memory the way a commit changes them.
 */
class FetchFromIndexStepKeyChecksTest extends TestHelper {

  private void load() {
    database.command("sql", "CREATE DOCUMENT TYPE T");
    database.command("sql", "CREATE PROPERTY T.a STRING");
    database.command("sql", "CREATE PROPERTY T.b INTEGER");
    database.command("sql", "CREATE PROPERTY T.v INTEGER");
    database.command("sql", "CREATE INDEX ON T (a, b) NOTUNIQUE");
    database.command("sql", "CREATE INDEX ON T (v) NOTUNIQUE");
    database.transaction(() -> {
      database.newDocument("T").set("a", "x").set("b", 1).set("v", 1).save();
      database.newDocument("T").set("a", "x").set("b", 2).set("v", 2).save();
      database.newDocument("T").set("a", "y").set("b", 1).set("v", 3).save();
    });
  }

  /** The index step the planner builds for the statement, and the plan's result set it came from (the step reads the database lazily). */
  private FetchFromIndexStep stepOf(final String sql) {
    try (final ResultSet resultSet = database.query("sql", sql)) {
      final List<FetchFromIndexStep> found = new ArrayList<>();
      collect(resultSet.getExecutionPlan().orElseThrow().getSteps(), found);
      assertThat(found).as("the statement must have planned one index fetch: " + sql).hasSize(1);
      return found.getFirst();
    }
  }

  private void collect(final List<ExecutionStep> steps, final List<FetchFromIndexStep> found) {
    for (final ExecutionStep step : steps) {
      if (step instanceof final FetchFromIndexStep fetch)
        found.add(fetch);
      collect(step.getSubSteps(), found);
    }
  }

  private Document record(final String where) {
    try (final ResultSet rows = database.query("sql", "SELECT FROM T WHERE " + where)) {
      return rows.next().getElement().orElseThrow();
    }
  }

  @Test
  void aRecordHoldsTheKeyItIsIndexedUnder() {
    load();
    final FetchFromIndexStep step = stepOf("SELECT FROM T WHERE a = 'x' AND b = 1");
    final Document x1 = record("a = 'x' AND b = 1");

    assertThat(step.isKeyOf(x1, new Object[] { "x", 1 })).isTrue();
    assertThat(step.isKeyOf(x1, new Object[] { "x", 2 })).isFalse();
    assertThat(step.isKeyOf(x1, new Object[] { "y", 1 })).isFalse();
    // a lookup of a prefix of a composite key is a range of keys: the record is one of its rows when it holds the prefix
    assertThat(step.isKeyOf(x1, new Object[] { "x" })).isTrue();
    assertThat(step.isKeyOf(x1, new Object[] { "y" })).isFalse();
    // a key is read as the index reads it: the value narrowed to the declared type
    assertThat(step.isKeyOf(x1, new Object[] { "x", "1" })).isTrue();
    assertThat(step.isKeyOf(x1, new Object[] { "x", 1L })).isTrue();
  }

  @Test
  void aKeyTheIndexCannotReadIsHeldByNoRecord() {
    load();
    final FetchFromIndexStep step = stepOf("SELECT FROM T WHERE a = 'x' AND b = 1");
    final Document x1 = record("a = 'x' AND b = 1");

    final Object[] unreadable = { "x", "not a number" };
    // the answer for a key does not leak into the next key checked, whichever order they come in, nor into a repeat of the same key
    assertThat(step.isKeyOf(x1, unreadable)).isFalse();
    assertThat(step.isKeyOf(x1, new Object[] { "x", 1 })).isTrue();
    assertThat(step.isKeyOf(x1, unreadable)).isFalse();
    assertThat(step.isKeyOf(x1, unreadable)).isFalse();
    assertThat(step.isKeyOf(x1, new Object[] { "x", 1 })).isTrue();
    assertThat(step.isKeyOf(x1, new Object[] { "x", 1 })).isTrue();
    assertThat(step.isKeyOf(x1, new Object[] { "x", 2 })).isFalse();
    assertThat(step.isKeyOf(x1, new Object[] { "x", 1 })).isTrue();
  }

  @Test
  void aRecordChangedAfterItsEntryWasReadHoldsItsNewKeyOnly() {
    load();
    final FetchFromIndexStep step = stepOf("SELECT FROM T WHERE a = 'x' AND b = 1");
    // the commit moved the record to another key: the record as it reads now is not indexed under the key of the entry
    final MutableDocument moved = record("a = 'x' AND b = 1").modify();
    moved.set("b", 5);

    assertThat(step.isKeyOf(moved, new Object[] { "x", 1 })).isFalse();
    assertThat(step.isKeyOf(moved, new Object[] { "x", 5 })).isTrue();
    assertThat(step.isKeyOf(moved, new Object[] { "x" })).isTrue();

    moved.set("a", "z");
    assertThat(step.isKeyOf(moved, new Object[] { "x" })).isFalse();
    assertThat(step.isKeyOf(moved, new Object[] { "z", 5 })).isTrue();
  }

  @Test
  void aRangeEntryStaysARowWhileItsRecordSatisfiesTheSeek() {
    load();
    final FetchFromIndexStep step = stepOf("SELECT FROM T WHERE v >= 2 AND v < 4");
    final MutableDocument record = record("v = 3").modify();

    assertThat(step.isStillInRange(record, new Object[] { 3 })).isTrue();

    // moved to another key of the range: still a row of the statement
    record.set("v", 2);
    assertThat(step.isStillInRange(record, new Object[] { 3 })).isTrue();

    // moved out of the range, either way: not a row of the statement
    record.set("v", 100);
    assertThat(step.isStillInRange(record, new Object[] { 3 })).isFalse();
    record.set("v", 1);
    assertThat(step.isStillInRange(record, new Object[] { 3 })).isFalse();
    record.set("v", 4);
    assertThat(step.isStillInRange(record, new Object[] { 3 })).isFalse();

    // the boundary values themselves: 2 is in, 4 is out
    record.set("v", 2);
    assertThat(step.isStillInRange(record, new Object[] { 3 })).isTrue();
  }

  @Test
  void theSeekCheckEvaluatesTheWholeConditionOnTheRecord() {
    load();
    final FetchFromIndexStep step = stepOf("SELECT FROM T WHERE v >= 2 AND v < 4");
    final SeekCheck check = step.seekCheck();
    assertThat(check).isNotNull();
    assertThat(check.matches(record("v = 3"), step.context)).isTrue();
    assertThat(check.matches(record("v = 2"), step.context)).isTrue();
    assertThat(check.matches(record("v = 1"), step.context)).isFalse();
    // a copy of the check is handed to each worker of a parallel load: it answers the same
    final SeekCheck copy = check.copy();
    assertThat(copy.matches(record("v = 3"), step.context)).isTrue();
    assertThat(copy.matches(record("v = 1"), step.context)).isFalse();
  }

  @Test
  void aPrefixLookupIsCutToTheSlotsItFills() {
    load();
    final FetchFromIndexStep prefix = stepOf("SELECT FROM T WHERE a = 'x'");
    final Object[] entryKey = { "x", 2 };
    assertThat(prefix.lookupKeyOf(entryKey)).isEqualTo(new Object[] { "x" });

    final FetchFromIndexStep whole = stepOf("SELECT FROM T WHERE a = 'x' AND b = 2");
    // nothing to cut: the same instance, which is what lets a run of entries of one key skip the conversion
    assertThat(whole.lookupKeyOf(entryKey)).isSameAs(entryKey);
    final Object scalar = "x";
    assertThat(whole.lookupKeyOf(scalar)).isSameAs(scalar);
  }

  @Test
  void anEntryBelongsToTheLookupItsKeyStartsWith() {
    final Object[] lookup = { "x" };
    assertThat(FetchFromIndexStep.isEntryOf(new Object[] { "x", 1 }, lookup)).isTrue();
    assertThat(FetchFromIndexStep.isEntryOf(new Object[] { "x", 2 }, lookup)).isTrue();
    assertThat(FetchFromIndexStep.isEntryOf(new Object[] { "y", 1 }, lookup)).isFalse();
    // a difference in the last slot of the prefix is a different lookup
    assertThat(FetchFromIndexStep.isEntryOf(new Object[] { "x", 1, 5 }, new Object[] { "x", 2 })).isFalse();
    assertThat(FetchFromIndexStep.isEntryOf(new Object[] { "x", 2, 5 }, new Object[] { "x", 2 })).isTrue();
    // as long as the lookup, the keys are compared whole
    assertThat(FetchFromIndexStep.isEntryOf(new Object[] { "x", 1 }, new Object[] { "x", 1 })).isTrue();
    assertThat(FetchFromIndexStep.isEntryOf(new Object[] { "x", 1 }, new Object[] { "x", 2 })).isFalse();
    // shorter than the lookup: not of it
    assertThat(FetchFromIndexStep.isEntryOf(new Object[] { "x" }, new Object[] { "x", 1 })).isFalse();
    // a scalar key
    assertThat(FetchFromIndexStep.isEntryOf(7, 7)).isTrue();
    assertThat(FetchFromIndexStep.isEntryOf(7, 8)).isFalse();
    assertThat(FetchFromIndexStep.isEntryOf(null, null)).isTrue();
    assertThat(FetchFromIndexStep.isEntryOf(7, null)).isFalse();
  }

  @Test
  void twoKeysAreTheSameKeyWhenTheirSlotsAre() {
    assertThat(FetchFromIndexStep.sameKey(new Object[] { "x", 1 }, new Object[] { "x", 1 })).isTrue();
    assertThat(FetchFromIndexStep.sameKey(new Object[] { "x", 1 }, new Object[] { "x", 2 })).isFalse();
    assertThat(FetchFromIndexStep.sameKey(new Object[] { "x" }, new Object[] { "x", 1 })).isFalse();
    assertThat(FetchFromIndexStep.sameKey("x", "x")).isTrue();
    assertThat(FetchFromIndexStep.sameKey("x", "y")).isFalse();
    assertThat(FetchFromIndexStep.sameKey(null, null)).isTrue();
    assertThat(FetchFromIndexStep.sameKey(null, "x")).isFalse();
    // an array is never the scalar it wraps
    assertThat(FetchFromIndexStep.sameKey(new Object[] { "x" }, "x")).isFalse();
    assertThat(FetchFromIndexStep.sameKey("x", new Object[] { "x" })).isFalse();
  }

  @Test
  void onlyALookupOfKeysFixedAtTheStartIsAPointLookup() {
    load();
    assertThat(stepOf("SELECT FROM T WHERE v = 2").isPointLookup()).isTrue();
    assertThat(stepOf("SELECT FROM T WHERE a = 'x' AND b = 1").isPointLookup()).isTrue();
    // a prefix of a composite key is a lookup of one range, whose keys are fixed too
    assertThat(stepOf("SELECT FROM T WHERE a = 'x'").isPointLookup()).isTrue();
    assertThat(stepOf("SELECT FROM T WHERE v IN [1, 2]").isPointLookup()).isTrue();
    assertThat(stepOf("SELECT FROM T WHERE a = 'x' AND b IN [1, 2]").isPointLookup()).isTrue();
    // a range is read as the walk goes
    assertThat(stepOf("SELECT FROM T WHERE v > 1").isPointLookup()).isFalse();
    assertThat(stepOf("SELECT FROM T WHERE v BETWEEN 1 AND 2").isPointLookup()).isFalse();
    assertThat(stepOf("SELECT FROM T WHERE v >= 1 AND v < 3").isPointLookup()).isFalse();
    assertThat(stepOf("SELECT FROM T WHERE a = 'x' AND b > 1").isPointLookup()).isFalse();
  }

  @Test
  void aKeyIsLookedUpAgainAsTheIndexReadsIt() {
    load();
    final FetchFromIndexStep step = stepOf("SELECT FROM T WHERE a = 'x'");
    final WorkGuard guard = WorkGuard.forCommandDeadline(step.context);

    final Set<RID> both = new HashSet<>(step.lookupKeyAgain(new Object[] { "x" }, guard));
    assertThat(both).containsExactlyInAnyOrder(record("a = 'x' AND b = 1").getIdentity(), record("a = 'x' AND b = 2").getIdentity());

    // a whole key reads the one entry
    assertThat(step.lookupKeyAgain(new Object[] { "x", 2 }, guard)).containsExactly(record("a = 'x' AND b = 2").getIdentity());
    assertThat(step.lookupKeyAgain(new Object[] { "x", 9 }, guard)).isEmpty();
    assertThat(step.lookupKeyAgain(new Object[] { "none" }, guard)).isEmpty();
  }
}
