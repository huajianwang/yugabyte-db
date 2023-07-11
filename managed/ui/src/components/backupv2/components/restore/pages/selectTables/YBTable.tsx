/*
 * Created on Mon Jun 26 2023
 *
 * Copyright 2021 YugaByte, Inc. and Contributors
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License")
 * You may not use this file except in compliance with the License. You may obtain a copy of the License at
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

import React, { useEffect, useState } from 'react';
import {
  Box,
  Checkbox,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
  makeStyles
} from '@material-ui/core';
import { Control, FieldValues } from 'react-hook-form';
import { useMap } from 'react-use';
import { useTranslation } from 'react-i18next';
import { values } from 'lodash';
import Select from 'react-select';
import { YBCheckbox } from '../../../../../../redesign/components';
import { YBSearchInput } from '../../../../../common/forms/fields/YBSearchInput';

import Checked from '../../icons/checkbox/Checked.svg';
import UnChecked from '../../icons/checkbox/UnChecked.svg';
import Intermediate from '../../icons/checkbox/Intermediate.svg';

type YBTableProps<T, V extends FieldValues = FieldValues> = {
  table: T[];
  tableHeader: string[];
  control: Control<V>;
  name: keyof FieldValues;
  sortFn?: (tables: T[]) => T[];
  setValue: (table: T[]) => void;
};

type FilterOptions = 'ALL' | 'SELECTED';

const useStyles = makeStyles((theme) => ({
  actions: {
    display: 'flex',
    gap: theme.spacing(1),
    alignItems: 'center',
    marginBottom: theme.spacing(2)
  },
  ybSearchInput: {
    '& .search-input': {
      width: '630px'
    }
  },
  viewSelection: {
    width: '150px'
  },
  tablesCount: {
    color: '#67666C',
    textAlign: 'right',
    width: '100%'
  },
  table: {
    border: `1px solid ${theme.palette.ybacolors.ybBorderGray}`,
    borderRadius: theme.spacing(1),
    minHeight: '550px',
    '& .MuiTableCell-body, .MuiTableCell-head': {
      padding: `${theme.spacing(1.5)}px ${theme.spacing(1.25)}px`
    }
  },
  tableHead: {
    height: '40px',
    color: theme.palette.grey[900],
    fontSize: '13px',
    fontWeight: 400,
    textTransform: 'uppercase',
    padding: 0
  },
  tableBody: {
    '& .MuiFormControlLabel-root': {
      marginRight: 0
    }
  },
  tableCell: {
    paddingLeft: '0 !important',
    height: '40px'
  }
}));

export const YBTable = <T,>(props: YBTableProps<T>) => {
  const { tableHeader, table, name, setValue } = props;

  const [selected, { set, reset, setAll, get, remove }] = useMap<typeof table>();

  const selectedEntriesCount = Object.keys(selected).length;

  const [searchText, setSearchText] = useState('');

  const classes = useStyles();
  const { t } = useTranslation();

  const tableFilterOptions: { value: FilterOptions; label: string }[] = [
    {
      label: t('newRestoreModal.selectTables.viewFilter.viewAll'),
      value: 'ALL'
    },
    {
      label: t('newRestoreModal.selectTables.viewFilter.viewSelected'),
      value: 'SELECTED'
    }
  ];

  const [viewFilter, setViewFilter] = useState(tableFilterOptions[0]);

  useEffect(() => {
    setValue(values(selected));
  }, [selected, setValue]);

  const getTableHeader = () => {
    return (
      <TableHead className={classes.tableHead}>
        <TableRow>
          <TableCell width={8}>
            <Checkbox
              indeterminate={selectedEntriesCount > 0 && selectedEntriesCount < table.length}
              checked={selectedEntriesCount === table.length}
              onChange={(_, state) => {
                if (state) {
                  setAll(Object.fromEntries(table.map((obj, i) => [i, obj])) as any);
                } else {
                  reset();
                }
              }}
              icon={<img src={UnChecked} alt="unchecked" />}
              checkedIcon={<img src={Checked} alt="checked" />}
              indeterminateIcon={<img src={Intermediate} alt="intermediate" />}
            />
          </TableCell>
          {tableHeader.map((title, i) => (
            <TableCell className={classes.tableCell} key={i}>
              {title}
            </TableCell>
          ))}
        </TableRow>
      </TableHead>
    );
  };

  const getTableBody = () => {
    return (
      <TableBody className={classes.tableBody}>
        {table.map((row, i) => {
          if (viewFilter.value === 'SELECTED') {
            if (!get(i)) {
              return null;
            }
          }

          if (searchText) {
            if (typeof row === 'string') {
              if (!row.includes(searchText)) {
                return null;
              }
            }
          }

          return (
            <TableRow key={i}>
              <TableCell width={8}>
                <YBCheckbox
                  name={`${name}.${i}`}
                  label=""
                  checked={!!get(i)}
                  onChange={(_, state) => {
                    if (state) {
                      set(i, row);
                    } else {
                      remove(i);
                    }
                  }}
                  icon={<img src={UnChecked} alt="unchecked" />}
                  checkedIcon={<img src={Checked} alt="checked" />}
                />
              </TableCell>
              <TableCell className={classes.tableCell}>{row}</TableCell>
            </TableRow>
          );
        })}
      </TableBody>
    );
  };

  return (
    <>
      <Box className={classes.actions}>
        <div className={classes.ybSearchInput}>
          <YBSearchInput
            val={searchText}
            onValueChanged={(e: React.ChangeEvent<HTMLInputElement>) => {
              setSearchText(e.target.value);
            }}
            placeHolder={t('newRestoreModal.selectTables.searchTableName')}
          />
        </div>
        <div>
          <Select
            className={classes.viewSelection}
            onChange={(val) => setViewFilter(val as any)}
            value={viewFilter}
            options={tableFilterOptions}
          />
        </div>
        <div className={classes.tablesCount}>
          <Typography variant="body2">
            {t('newRestoreModal.selectTables.tablesCount', { count: table.length })}
          </Typography>
        </div>
      </Box>
      <Box className={classes.table}>
        <Table>
          {getTableHeader()}
          {getTableBody()}
        </Table>
      </Box>
    </>
  );
};
