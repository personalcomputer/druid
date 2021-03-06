/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.segment.join;

import org.apache.druid.java.util.common.IAE;
import org.apache.druid.query.DataSource;
import org.apache.druid.query.JoinDataSource;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryDataSource;
import org.apache.druid.query.planning.PreJoinableClause;
import org.apache.druid.segment.SegmentReference;
import org.apache.druid.segment.column.ColumnHolder;
import org.apache.druid.segment.join.filter.JoinableClauses;
import org.apache.druid.segment.join.filter.rewrite.JoinFilterPreAnalysisGroup;
import org.apache.druid.segment.join.filter.rewrite.JoinFilterRewriteConfig;
import org.apache.druid.utils.JvmUtils;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Utility methods for working with {@link Joinable} related classes.
 */
public class Joinables
{
  private static final Comparator<String> DESCENDING_LENGTH_STRING_COMPARATOR = (s1, s2) ->
      Integer.compare(s2.length(), s1.length());

  /**
   * Checks that "prefix" is a valid prefix for a join clause (see {@link JoinableClause#getPrefix()}) and, if so,
   * returns it. Otherwise, throws an exception.
   */
  public static String validatePrefix(@Nullable final String prefix)
  {
    if (prefix == null || prefix.isEmpty()) {
      throw new IAE("Join clause cannot have null or empty prefix");
    } else if (isPrefixedBy(ColumnHolder.TIME_COLUMN_NAME, prefix) || ColumnHolder.TIME_COLUMN_NAME.equals(prefix)) {
      throw new IAE(
          "Join clause cannot have prefix[%s], since it would shadow %s",
          prefix,
          ColumnHolder.TIME_COLUMN_NAME
      );
    } else {
      return prefix;
    }
  }

  public static boolean isPrefixedBy(final String columnName, final String prefix)
  {
    return columnName.length() > prefix.length() && columnName.startsWith(prefix);
  }

  /**
   * Creates a Function that maps base segments to {@link HashJoinSegment} if needed (i.e. if the number of join
   * clauses is > 0). If mapping is not needed, this method will return {@link Function#identity()}.
   * @param clauses                 Pre-joinable clauses
   * @param joinableFactory         Factory for joinables
   * @param cpuTimeAccumulator      An accumulator that we will add CPU nanos to; this is part of the function to encourage
 *                                    callers to remember to track metrics on CPU time required for creation of Joinables
   * @param joinFilterRewriteConfig Configuration options for the join filter rewrites
   * @param query                   The query being processed
   */
  public static Function<SegmentReference, SegmentReference> createSegmentMapFn(
      final List<PreJoinableClause> clauses,
      final JoinableFactory joinableFactory,
      final AtomicLong cpuTimeAccumulator,
      final JoinFilterRewriteConfig joinFilterRewriteConfig,
      final Query query
  )
  {
    // compute column correlations here and RHS correlated values
    return JvmUtils.safeAccumulateThreadCpuTime(
        cpuTimeAccumulator,
        () -> {
          if (clauses.isEmpty()) {
            return Function.identity();
          } else {
            final JoinableClauses joinableClauses = JoinableClauses.createClauses(clauses, joinableFactory);

            List<Query> joinQueryLevels = new ArrayList<>();
            Joinables.gatherAllJoinQueryLevels(query, joinQueryLevels);

            final JoinFilterPreAnalysisGroup preAnalysisGroup = new JoinFilterPreAnalysisGroup(
                joinFilterRewriteConfig,
                joinQueryLevels.size() <= 1 // use single-level mode if there's one or fewer query levels with joins
            );

            for (Query joinQuery : joinQueryLevels) {
              preAnalysisGroup.computeJoinFilterPreAnalysisIfAbsent(
                  joinQuery.getFilter() == null ? null : joinQuery.getFilter().toFilter(),
                  joinableClauses.getJoinableClauses(),
                  joinQuery.getVirtualColumns()
              );
            }

            return baseSegment -> new HashJoinSegment(baseSegment, joinableClauses.getJoinableClauses(), preAnalysisGroup);
          }
        }
    );
  }

  /**
   * Walks a query and its subqueries, finding any queries that read from a JoinDatasource,
   * and adding them to a list provided by the caller.
   *
   * @param currentLevelQuery The query to analyze
   * @param allJoinQueryLevels A mutable list provided by the caller.
   */
  public static void gatherAllJoinQueryLevels(Query currentLevelQuery, List<Query> allJoinQueryLevels)
  {
    DataSource currentDatasource = currentLevelQuery.getDataSource();
    if (currentDatasource instanceof QueryDataSource) {
      gatherAllJoinQueryLevels(
          ((QueryDataSource) currentDatasource).getQuery(),
          allJoinQueryLevels
      );
    }
    if (currentDatasource instanceof JoinDataSource) {
      allJoinQueryLevels.add(currentLevelQuery);
      gatherAllJoinQueryLevelsJoinDatasourceHelper(
          (JoinDataSource) currentDatasource,
          allJoinQueryLevels
      );
    }
  }

  private static void gatherAllJoinQueryLevelsJoinDatasourceHelper(
      JoinDataSource joinDatasource,
      List<Query> allJoinQueryLevels
  )
  {
    if (joinDatasource.getLeft() instanceof QueryDataSource) {
      gatherAllJoinQueryLevels(
          ((QueryDataSource) joinDatasource.getLeft()).getQuery(),
          allJoinQueryLevels
      );
    }
    if (joinDatasource.getLeft() instanceof JoinDataSource) {
      gatherAllJoinQueryLevelsJoinDatasourceHelper(
          (JoinDataSource) joinDatasource.getLeft(),
          allJoinQueryLevels
      );
    }
    if (joinDatasource.getRight() instanceof QueryDataSource) {
      gatherAllJoinQueryLevels(
          ((QueryDataSource) joinDatasource.getRight()).getQuery(),
          allJoinQueryLevels
      );
    }
    if (joinDatasource.getRight() instanceof JoinDataSource) {
      gatherAllJoinQueryLevelsJoinDatasourceHelper(
          (JoinDataSource) joinDatasource.getRight(),
          allJoinQueryLevels
      );
    }
  }

  /**
   * Check if any prefixes in the provided list duplicate or shadow each other.
   *
   * @param prefixes A mutable list containing the prefixes to check. This list will be sorted by descending
   *                 string length.
   */
  public static void checkPrefixesForDuplicatesAndShadowing(
      final List<String> prefixes
  )
  {
    // this is a naive approach that assumes we'll typically handle only a small number of prefixes
    prefixes.sort(DESCENDING_LENGTH_STRING_COMPARATOR);
    for (int i = 0; i < prefixes.size(); i++) {
      String prefix = prefixes.get(i);
      for (int k = i + 1; k < prefixes.size(); k++) {
        String otherPrefix = prefixes.get(k);
        if (prefix.equals(otherPrefix)) {
          throw new IAE("Detected duplicate prefix in join clauses: [%s]", prefix);
        }
        if (isPrefixedBy(prefix, otherPrefix)) {
          throw new IAE("Detected conflicting prefixes in join clauses: [%s, %s]", prefix, otherPrefix);
        }
      }
    }
  }
}
