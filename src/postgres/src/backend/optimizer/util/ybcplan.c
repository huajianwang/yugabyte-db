/*--------------------------------------------------------------------------------------------------
 *
 * ybcplan.c
 *	  Utilities for YugaByte scan.
 *
 * Copyright (c) YugaByte, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied.  See the License for the specific language governing permissions and limitations
 * under the License.
 *
 * src/backend/executor/ybcplan.c
 *
 *--------------------------------------------------------------------------------------------------
 */


#include "postgres.h"

#include "optimizer/ybcplan.h"
#include "access/htup_details.h"
#include "access/relation.h"
#include "catalog/pg_proc.h"
#include "catalog/pg_type.h"
#include "executor/ybcExpr.h"
#include "nodes/makefuncs.h"
#include "nodes/nodes.h"
#include "nodes/plannodes.h"
#include "nodes/print.h"
#include "utils/datum.h"
#include "utils/rel.h"
#include "utils/syscache.h"
#include "utils/lsyscache.h"

/* YB includes. */
#include "catalog/yb_catalog_version.h"
#include "yb/yql/pggate/ybc_pggate.h"
#include "pg_yb_utils.h"

/*
 * Check if statement can be implemented by a single request to the DocDB.
 *
 * An insert, update, or delete command makes one or more write requests to
 * the DocDB to apply the changes, and may also make read requests to find
 * the target row, its id, current values, etc. Complex expressions (e.g.
 * subqueries, stored functions) may also make requests to DocDB.
 *
 * Typically multiple requests require a transaction to maintain consistency.
 * However, if the command is about to make single write request, it is OK to
 * skip the transaction. The ModifyTable plan node makes one write request per
 * row it fetches from its subplans, therefore the key criteria of single row
 * modify is a single Result plan node in the ModifyTable's plans list.
 * Plain Result plan node produces exactly one row without making requests to
 * the DocDB, unless it has a subplan or complex expressions to evaluate.
 *
 * Full list of the conditions we check here:
 *  - there is only one target table;
 *  - there is no ON CONFLICT clause;
 *  - there is no init plan;
 *  - there is only one source plan, which is a simple form of Result;
 *  - all expressions in the Result's target list and in the returning list are
 *    simple, that means they do not need to access the DocDB.
 *
 * Additionally, during execution we will also check:
 *  - not in transaction block;
 *  - is a single-plan execution;
 *  - target table has no triggers to fire;
 *  - target table has no indexes to update.
 * And if all are true we will execute this op as a single-row transaction
 * rather than a distributed transaction.
 */
static bool ModifyTableIsSingleRowWrite(ModifyTable *modifyTable)
{
#ifdef YB_TODO
	/* YB_TODO(neil@yugabyte) Need to redo this work. Both sides were changed.
	 * - NOTE work was done by Jayden.
	 */
	Plan *subplan;

	/* Support INSERT, UPDATE, and DELETE. */
	if (modifyTable->operation != CMD_INSERT &&
		modifyTable->operation != CMD_UPDATE &&
		modifyTable->operation != CMD_DELETE)
		return false;

	/* Multi-relation implies multi-shard. */
	if (list_length(modifyTable->resultRelations) != 1)
		return false;

	/* ON CONFLICT clause may require another write request */
	if (modifyTable->onConflictAction != ONCONFLICT_NONE)
		return false;

	/* Init plan execution would require request(s) to DocDB */
	if (modifyTable->plan.initPlan != NIL)
		return false;

<<<<<<< HEAD
	/* Check the data source is a single plan */
	if (list_length(modifyTable->plans) != 1)
		return false;

	Plan *plan = (Plan *) linitial(modifyTable->plans);

	/*
	 * Only Result plan without a subplan produces single tuple without making
	 * DocDB requests
	 */
	if (!IsA(plan, Result) || outerPlan(plan))
		return false;
=======
	/* YB_TODO(neil@yugabyte) Make sure that checking resultRelations is similar to checking
	 * old pg11 attributes "node->plans".
	 */
	/* Check the data source, only allow a values clause right now */
	if (list_length(modifyTable->resultRelations) != 1)
		return false;

	/* YB_TODO(neil@yugabyte) Make sure that this is correct fix.
	 * Postgres 13 don't use old fields "node->plans" for subplan.
	 */
	subplan = outerPlan(modifyTable);
	switch nodeTag(subplan)
	{
		case T_Result:
		{
			/* Simple values clause: one valueset (single row) */
			Result *values = (Result *)subplan;
			ListCell *lc;
			foreach(lc, values->plan.targetlist)
			{
				TargetEntry *target = (TargetEntry *) lfirst(lc);
				bool needs_pushdown = false;
				if (!YBCIsSupportedSingleRowModifyAssignExpr(target->expr,
				                                             target->resno,
				                                             &needs_pushdown))
				{
					return false;
				}
			}
			break;
		}
		case T_ValuesScan:
		{
			/*
			 * Simple values clause: multiple valueset (multi-row).
			 * TODO: Eventually we could inspect hash key values to check
			 *       if single shard and optimize that.
			 *       ---
			 *       In this case we'd need some other way to explicitly filter out
			 *       updates involving primary key - right now we simply rely on
			 *       planner not setting the node to Result.
			 */
			return false;
>>>>>>> Fix compilation error in src/optimizer - complete

	/* Complex expressions in the target list may require DocDB requests */
	if (YbIsTransactionalExpr((Node *) plan->targetlist))
		return false;

	/* Same for the returning expressions */
	if (YbIsTransactionalExpr((Node *) modifyTable->returningLists))
		return false;
#endif

	/* If all our checks passed return true */
	return true;
}

bool YBCIsSingleRowModify(PlannedStmt *pstmt)
{
	/* YB_TODO(neil@yugabyte)
	 * - Turn optimization back on after completing TODO tasks in this modules.
	 * - Remove "false" in this if block.
	 */
	if (false && pstmt->planTree && IsA(pstmt->planTree, ModifyTable))
	{
		ModifyTable *node = castNode(ModifyTable, pstmt->planTree);
		return ModifyTableIsSingleRowWrite(node);
	}

	return false;
}

/*
 * Returns true if this ModifyTable can be executed by a single RPC, without
 * an initial table scan fetching a target tuple.
 *
 * Right now, this is true iff:
 *  - it is UPDATE or DELETE command.
 *  - source data is a Result node (meaning we are skipping scan and thus
 *    are single row).
 */
bool YbCanSkipFetchingTargetTupleForModifyTable(ModifyTable *modifyTable)
{
	/* Support UPDATE and DELETE. */
	if (modifyTable->operation != CMD_UPDATE &&
		modifyTable->operation != CMD_DELETE)
		return false;

	/* YB_TODO(neil@yugabyte) Make sure that checking resultRelations is similar to checking
	 * old pg11 attributes "node->plans".
	 */
	/* Should only have one data source. */
	if (list_length(modifyTable->resultRelations) != 1)
		return false;

#ifdef YB_TODO
	/* YB_TODO(neil@yugabyte) Make sure that this check is no longer needed */
	/*
	 * Verify the single data source is a Result node and does not have outer plan.
	 * Note that Result node never has inner plan.
	 */
	if (!IsA(linitial(modifyTable->plans), Result) || outerPlan(linitial(modifyTable->plans)))
		return false;
#endif

	return true;
}

/*
 * Returns true if provided Bitmapset of attribute numbers
 * matches the primary key attribute numbers of the relation.
 * Expects YBGetFirstLowInvalidAttributeNumber to be subtracted from attribute numbers.
 */
bool YBCAllPrimaryKeysProvided(Relation rel, Bitmapset *attrs)
{
	if (bms_is_empty(attrs))
	{
		/*
		 * If we don't explicitly check for empty attributes it is possible
		 * for this function to improperly return true. This is because in the
		 * case where a table does not have any primary key attributes we will
		 * use a hidden RowId column which is not exposed to the PG side, so
		 * both the YB primary key attributes and the input attributes would
		 * appear empty and would be equal, even though this is incorrect as
		 * the YB table has the hidden RowId primary key column.
		 */
		return false;
	}

	Bitmapset *primary_key_attrs = YBGetTablePrimaryKeyBms(rel);

	/* Verify the sets are the same. */
	return bms_equal(attrs, primary_key_attrs);
}
