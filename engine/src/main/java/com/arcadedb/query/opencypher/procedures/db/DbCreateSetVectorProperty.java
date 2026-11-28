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
package com.arcadedb.query.opencypher.procedures.db;

import com.arcadedb.database.Document;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.graph.Edge;
import com.arcadedb.graph.LightEdge;
import com.arcadedb.graph.Vertex;
import com.arcadedb.query.opencypher.executor.RowAliases;
import com.arcadedb.query.opencypher.procedures.CypherProcedure;
import com.arcadedb.query.sql.executor.CommandContext;
import com.arcadedb.query.sql.executor.Result;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

/**
 * Neo4j-compatible procedures {@code db.create.setNodeVectorProperty(node, key, vector)} and
 * {@code db.create.setRelationshipVectorProperty(relationship, key, vector)} (Neo4j 5.18+), the documented way to write the
 * property a vector index covers (issue #13575). Both are void in Neo4j: {@code YIELD} is refused and the input row goes on
 * unchanged, which {@code CallStep} does for a procedure that declares no output.
 * <p>
 * {@code vector} is a list (or a numeric array) of numbers; it is stored as a {@code float[]} (Neo4j stores 32-bit floats too:
 * {@code [0.1]} reads back as {@code 0.10000000149011612}), the representation the vector indexes read. As in Neo4j a null
 * node, relationship, key or vector, a vector that is not a list of numbers, an empty one or one holding a value no 32-bit float
 * can carry (NaN, an infinity, {@code 1.5e40}) is refused, and the property is left as it was. The dimension of a vector index is
 * not checked here, in Neo4j neither: the index decides what it covers.
 * <p>
 * Example:
 * <pre>
 * MATCH (c:Chunk {id: $id}) CALL db.create.setNodeVectorProperty(c, 'embedding', $vector)
 * </pre>
 */
public class DbCreateSetVectorProperty implements CypherProcedure {
  public static final String NODE_NAME         = "db.create.setnodevectorproperty";
  public static final String RELATIONSHIP_NAME = "db.create.setrelationshipvectorproperty";

  private final boolean onRelationship;
  private final String  name;
  private final String  displayName;

  private DbCreateSetVectorProperty(final boolean onRelationship) {
    this.onRelationship = onRelationship;
    // the registry key is lower-cased; error messages quote the call the way the caller wrote it
    this.displayName = onRelationship ? "db.create.setRelationshipVectorProperty" : "db.create.setNodeVectorProperty";
    this.name = displayName.toLowerCase();
  }

  public static DbCreateSetVectorProperty forNodes() {
    return new DbCreateSetVectorProperty(false);
  }

  public static DbCreateSetVectorProperty forRelationships() {
    return new DbCreateSetVectorProperty(true);
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public int getMinArgs() {
    return 3;
  }

  @Override
  public int getMaxArgs() {
    return 3;
  }

  @Override
  public String getDescription() {
    return "Sets the vector property of a " + (onRelationship ? "relationship" : "node")
        + " to a list of numbers, stored as 32-bit floats, as a vector index reads it.";
  }

  /** Void, as in Neo4j. */
  @Override
  public List<String> getYieldFields() {
    return List.of();
  }

  @Override
  public boolean isWriteProcedure() {
    return true;
  }

  @Override
  public Stream<Result> execute(final Object[] args, final Result inputRow, final CommandContext context) {
    validateArgs(args);

    final String argumentName = onRelationship ? "relationship" : "node";
    final Object entity = args[0];
    if (entity == null)
      throw new NullPointerException("'" + argumentName + "' must not be null");
    if (args[1] == null)
      throw new NullPointerException("'key' must not be null");
    if (args[2] == null)
      throw new NullPointerException("'vector' must not be null");
    if (!(args[1] instanceof String key))
      throw new IllegalArgumentException("'key' must be a string, got " + args[1].getClass().getSimpleName());

    final Document document;
    if (onRelationship) {
      if (!(entity instanceof Edge edge))
        throw new IllegalArgumentException("'relationship' must be a relationship, got " + entity.getClass().getSimpleName());
      if (edge instanceof LightEdge)
        throw new IllegalStateException("edge type '" + edge.getTypeName()
            + "' is declared LIGHTWEIGHT, so its edges cannot have properties");
      document = edge;
    } else {
      if (!(entity instanceof Vertex vertex))
        throw new IllegalArgumentException("'node' must be a node, got " + entity.getClass().getSimpleName());
      document = vertex;
    }

    final float[] vector = toVector(args[2]);

    final MutableDocument mutable = document.modify();
    // the vector is a constant of the call, not computed from the record read above: nothing for the stale-read refusal (#8610)
    mutable.clearBasedOnStaleRead();
    mutable.set(key, vector);
    mutable.save();

    // A row holds record snapshots: the aliases of the record answer the new vector to the clauses after the call
    if (mutable != document && inputRow != null)
      RowAliases.propagateUpdate(inputRow, document, mutable);

    return Stream.empty();
  }

  /** The 32-bit float vector of a list or array of numbers; refuses what Neo4j refuses (not numeric, empty, not finite). */
  static float[] toVector(final Object value) {
    final float[] vector;
    if (value instanceof float[] f)
      vector = f.clone();
    else if (value instanceof double[] d) {
      vector = new float[d.length];
      for (int i = 0; i < d.length; i++)
        vector[i] = (float) d[i];
    } else if (value instanceof long[] l) {
      vector = new float[l.length];
      for (int i = 0; i < l.length; i++)
        vector[i] = l[i];
    } else if (value instanceof int[] n) {
      vector = new float[n.length];
      for (int i = 0; i < n.length; i++)
        vector[i] = n[i];
    } else if (value instanceof short[] s) {
      vector = new float[s.length];
      for (int i = 0; i < s.length; i++)
        vector[i] = s[i];
    } else if (value instanceof byte[] b) {
      vector = new float[b.length];
      for (int i = 0; i < b.length; i++)
        vector[i] = b[i];
    } else if (value instanceof Collection<?> collection)
      vector = fromElements(collection.toArray());
    else if (value instanceof Object[] array)
      vector = fromElements(array);
    else
      throw new IllegalArgumentException("'vector' must be a non-null numerical array");

    if (vector.length == 0)
      throw new IllegalArgumentException("Vector must only contain finite values. Provided: []");
    for (final float element : vector)
      if (!Float.isFinite(element))
        throw new IllegalArgumentException("Vector must only contain finite values. Provided: " + value);
    return vector;
  }

  private static float[] fromElements(final Object[] elements) {
    final float[] vector = new float[elements.length];
    for (int i = 0; i < elements.length; i++) {
      if (!(elements[i] instanceof Number number))
        throw new IllegalArgumentException("Vector must only contain finite values. Provided: " + Arrays.toString(elements));
      vector[i] = number.floatValue();
    }
    return vector;
  }
}
