/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.internal.schema.utils;

import org.apache.avro.Schema;
import org.apache.hudi.common.util.collection.Pair;
import org.apache.hudi.internal.schema.InternalSchema;
import org.apache.hudi.internal.schema.action.TableChanges;

import org.apache.avro.Schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.apache.hudi.common.util.CollectionUtils.reduce;
import static org.apache.hudi.internal.schema.convert.AvroInternalSchemaConverter.convert;

/**
 * Utility methods to support evolve old avro schema based on a given schema.
 */
public class AvroSchemaEvolutionUtils {

  /**
   * Support reconcile from a new avroSchema.
   * 1) incoming data has missing columns that were already defined in the table –> null values will be injected into missing columns
   * 2) incoming data contains new columns not defined yet in the table -> columns will be added to the table schema (incoming dataframe?)
   * 3) incoming data has missing columns that are already defined in the table and new columns not yet defined in the table ->
   *     new columns will be added to the table schema, missing columns will be injected with null values
   * 4) support type change
   * 5) support nested schema change.
   * Notice:
   *    the incoming schema should not have delete/rename semantics.
   *    for example: incoming schema:  int a, int b, int d;   oldTableSchema int a, int b, int c, int d
   *    we must guarantee the column c is missing semantic, instead of delete semantic.
   * @param incomingSchema implicitly evolution of avro when hoodie write operation
   * @param oldTableSchema old internalSchema
   * @return reconcile Schema
   */
  public static InternalSchema reconcileSchema(Schema incomingSchema, InternalSchema oldTableSchema) {
    InternalSchema inComingInternalSchema = convert(incomingSchema);
    // check column add/missing
    List<String> colNamesFromIncoming = inComingInternalSchema.getAllColsFullName();
    List<String> colNamesFromOldSchema = oldTableSchema.getAllColsFullName();
    List<String> diffFromOldSchema = colNamesFromOldSchema.stream().filter(f -> !colNamesFromIncoming.contains(f)).collect(Collectors.toList());
    List<String> diffFromEvolutionColumns = colNamesFromIncoming.stream().filter(f -> !colNamesFromOldSchema.contains(f)).collect(Collectors.toList());
    // check type change.
    List<String> typeChangeColumns = colNamesFromIncoming
        .stream()
        .filter(f -> colNamesFromOldSchema.contains(f) && !inComingInternalSchema.findType(f).equals(oldTableSchema.findType(f)))
        .collect(Collectors.toList());
    if (colNamesFromIncoming.size() == colNamesFromOldSchema.size() && diffFromOldSchema.size() == 0 && typeChangeColumns.isEmpty()) {
      return oldTableSchema;
    }

    // Remove redundancy from diffFromEvolutionSchema.
    // for example, now we add a struct col in evolvedSchema, the struct col is " user struct<name:string, age:int> "
    // when we do diff operation: user, user.name, user.age will appeared in the resultSet which is redundancy, user.name and user.age should be excluded.
    // deal with add operation
    TreeMap<Integer, String> finalAddAction = new TreeMap<>();
    for (int i = 0; i < diffFromEvolutionColumns.size(); i++)  {
      String name = diffFromEvolutionColumns.get(i);
      int splitPoint = name.lastIndexOf(".");
      String parentName = splitPoint > 0 ? name.substring(0, splitPoint) : "";
      if (!parentName.isEmpty() && diffFromEvolutionColumns.contains(parentName)) {
        // find redundancy, skip it
        continue;
      }
      finalAddAction.put(inComingInternalSchema.findIdByName(name), name);
    }

    TableChanges.ColumnAddChange addChange = TableChanges.ColumnAddChange.get(oldTableSchema);
    finalAddAction.entrySet().stream().forEach(f -> {
      String name = f.getValue();
      int splitPoint = name.lastIndexOf(".");
      String parentName = splitPoint > 0 ? name.substring(0, splitPoint) : "";
      String rawName = splitPoint > 0 ? name.substring(splitPoint + 1) : name;
      // try to infer add position.
      java.util.Optional<String> inferPosition =
          colNamesFromIncoming.stream().filter(c ->
              c.lastIndexOf(".") == splitPoint
                  && c.startsWith(parentName)
                  && inComingInternalSchema.findIdByName(c) >  inComingInternalSchema.findIdByName(name)
                  && oldTableSchema.findIdByName(c) > 0).sorted((s1, s2) -> oldTableSchema.findIdByName(s1) - oldTableSchema.findIdByName(s2)).findFirst();
      addChange.addColumns(parentName, rawName, inComingInternalSchema.findType(name), null);
      inferPosition.map(i -> addChange.addPositionChange(name, i, "before"));
    });

    // do type evolution.
    InternalSchema internalSchemaAfterAddColumns = SchemaChangeUtils.applyTableChanges2Schema(oldTableSchema, addChange);
    TableChanges.ColumnUpdateChange typeChange = TableChanges.ColumnUpdateChange.get(internalSchemaAfterAddColumns);
    typeChangeColumns.stream().filter(f -> !inComingInternalSchema.findType(f).isNestedType()).forEach(col -> {
      typeChange.updateColumnType(col, inComingInternalSchema.findType(col));
    });

    return SchemaChangeUtils.applyTableChanges2Schema(internalSchemaAfterAddColumns, typeChange);
  }

  /**
   * Reconciles nullability requirements b/w {@code source} and {@code target} schemas,
   * by adjusting these of the {@code source} schema to be in-line with the ones of the
   * {@code target} one
   *
   * @param sourceSchema source schema that needs reconciliation
   * @param targetSchema target schema that source schema will be reconciled against
   * @return schema (based off {@code source} one) that has nullability constraints reconciled
   */
  public static Schema reconcileNullability(Schema sourceSchema, Schema targetSchema) {
    if (sourceSchema.getFields().isEmpty() || targetSchema.getFields().isEmpty()) {
      return sourceSchema;
    }

    InternalSchema sourceInternalSchema = convert(sourceSchema);
    InternalSchema targetInternalSchema = convert(targetSchema);

    List<String> colNamesSourceSchema = sourceInternalSchema.getAllColsFullName();
    List<String> colNamesTargetSchema = targetInternalSchema.getAllColsFullName();
    List<String> candidateUpdateCols = colNamesSourceSchema.stream()
        .filter(f -> colNamesTargetSchema.contains(f)
            && sourceInternalSchema.findField(f).isOptional() != targetInternalSchema.findField(f).isOptional())
        .collect(Collectors.toList());

    if (candidateUpdateCols.isEmpty()) {
      return sourceSchema;
    }

    // Reconcile nullability constraints (by executing phony schema change)
    TableChanges.ColumnUpdateChange schemaChange =
        reduce(candidateUpdateCols, TableChanges.ColumnUpdateChange.get(sourceInternalSchema),
          (change, field) -> change.updateColumnNullability(field, true));

    return convert(SchemaChangeUtils.applyTableChanges2Schema(sourceInternalSchema, schemaChange), sourceSchema.getName());
  }

  /**
   * Reconciles field names of {@code source} schema (produced in case-insensitive context, like Spark),
   * w/ the field names of the {@code target} schema such that {@code source} schema could
   * be used in case-sensitive context as well (like Avro)
   *
   * @param sourceSchema source schema that needs reconciliation
   * @param targetSchema target schema that source schema will be reconciled against
   * @return schema (based off {@code source} one) that has overlapping field-names in the same
   *         case as the target schema
   */
  public static Schema reconcileFieldNamesCasing(Schema sourceSchema, Schema targetSchema) {
    if (sourceSchema.getFields().isEmpty() || targetSchema.getFields().isEmpty()) {
      return sourceSchema;
    }

    InternalSchema sourceInternalSchema = convert(sourceSchema);
    InternalSchema targetInternalSchema = convert(targetSchema);

    // Collect field's fully-qualified name pairs of the fields whose names diverge only
    // in its letters' casing
    List<Pair<String, String>> reconciliationTargetColumnNamePairs =
        resolveCaseMismatchingFieldNamePairs(sourceInternalSchema, targetInternalSchema);

    if (reconciliationTargetColumnNamePairs.isEmpty()) {
      return sourceSchema;
    }

    // Reconcile field names (by executing phony schema change)
    TableChanges.ColumnUpdateChange schemaChange =
        reduce(reconciliationTargetColumnNamePairs, TableChanges.ColumnUpdateChange.get(sourceInternalSchema, true),
            (change, sourceTargetPair) -> change.renameColumn(sourceTargetPair.getLeft(), sourceTargetPair.getRight()));

    return convert(SchemaChangeUtils.applyTableChanges2Schema(sourceInternalSchema, schemaChange), sourceSchema.getName());
  }

  /**
   * Resolves fields in the {@code source} schema matching {@code target} schema, even though
   * their letter casing might be mismatched. Returns fully-qualified names pairs for the fields from
   * the {@code source} schema for which, there is correspondent field in the {@code target} schema
   * that only differs in its letters case.
   *
   * For example, provided two schemas {@code A} and {@code B}, such that:
   * {@code struct A { fieldA: ... }}, while {@code struct B { FieldA: ... }}, this method will
   * produce following list: {@code Map("fieldA" -> "FieldA")}
   *
   * NOTE: This method doesn't perform validation whether types of the correspondent fields are
   *       conformant, and simply applies a case-insensitive resolution logic
   */
  public static Map<String, String> resolveCaseMismatchingFieldNamePairs(Schema sourceSchema,
                                                                                Schema targetSchema) {
    if (sourceSchema.getFields().isEmpty() || targetSchema.getFields().isEmpty()) {
      return Collections.emptyMap();
    }

    InternalSchema sourceInternalSchema = convert(sourceSchema);
    InternalSchema targetInternalSchema = convert(targetSchema);

    return resolveCaseMismatchingFieldNamePairs(sourceInternalSchema, targetInternalSchema)
        .stream()
        .collect(Collectors.toMap(Pair::getLeft, Pair::getRight));
  }

  private static List<Pair<String, String>> resolveCaseMismatchingFieldNamePairs(InternalSchema sourceSchema,
                                                                                 InternalSchema targetSchema) {
    List<String> colNamesSourceSchema = sourceSchema.getAllColsFullName();

    // To reconcile field names of the source schema we enumerate all possible fields (including
    // nested ones) and produce a mapping convert them to lower-case to correlate across
    // source and target schemas
    Map<String, String> colNamesMapTargetSchema =
        targetSchema.getAllColsFullName().stream()
            .collect(Collectors.toMap(String::toLowerCase, Function.identity()));

    return colNamesSourceSchema.stream()
        .map(sourceFieldName -> {
          // NOTE: Here we're only reconciling the fields that differ only in casing of their
          //       corresponding names (therefore a) fields have to be present in both schemas and
          //       b) they have to differ)
          String lowerCaseSourceFieldName = sourceFieldName.toLowerCase(Locale.ROOT);
          String targetFieldName = colNamesMapTargetSchema.get(lowerCaseSourceFieldName);
          if (targetFieldName != null && !Objects.equals(sourceFieldName, targetFieldName)) {
            return Pair.of(sourceFieldName, targetFieldName);
          } else {
            return null;
          }
        })
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }
}

