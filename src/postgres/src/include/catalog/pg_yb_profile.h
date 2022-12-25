/*-------------------------------------------------------------------------
 *
 * pg_yb_profile.h
 *	  definition of the "profile" system catalog (pg_yb_profile)
 *
 *
 * Copyright (c) YugaByte, Inc.
 *
 * src/include/catalog/pg_yb_profile.h
 *
 * NOTES
 *	  The Catalog.pm module reads this file and derives schema
 *	  information.
 *
 *-------------------------------------------------------------------------
 */
#ifndef PG_YB_PROFILE_H
#define PG_YB_PROFILE_H

#include "catalog/genbki.h"
#include "catalog/pg_yb_profile_d.h"

/* ----------------
 *		pg_yb_profile definition.  cpp turns this into
 *		typedef struct FormData_pg_yb_profile
 * ----------------
 */
CATALOG(pg_yb_profile,8051,YbProfileRelationId) BKI_SHARED_RELATION BKI_ROWTYPE_OID(8053,YbProfileRelation_Rowtype_Id) BKI_SCHEMA_MACRO
{
	NameData	prfname;					/* profile name */
	int32		prfmaxfailedloginattempts;	/* no. of attempts allowed */
	int32		prfpasswordlocktime;		/* secs to lock out an account */
} FormData_pg_yb_profile;

/* ----------------
 *		Form_pg_yb_profile corresponds to a pointer to a tuple with
 *		the format of pg_yb_profile relation.
 * ----------------
 */
typedef FormData_pg_yb_profile *Form_pg_yb_profile;

#endif							/* PG_YB_PROFILE_H */
