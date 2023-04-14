/*
 * Created on Thu Mar 31 2022
 *
 * Copyright 2021 YugaByte, Inc. and Contributors
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License")
 * You may not use this file except in compliance with the License. You may obtain a copy of the License at
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

import { IBackup } from './IBackup';

interface Schedule_Task_Params extends Pick<IBackup, 'backupType' | 'sse' | 'storageConfigUUID'| 'isTableByTableBackup'> {
  firstTry: boolean;
  encryptionAtRestConfig: {
    encryptionAtRestEnabled: boolean;
  };
  timeBeforeDelete: number;
  minNumBackupsToRetain: number;
  schedulingFrequency: number;
  useTablespaces: boolean;
  parallelism: number;
  tableUUIDList: string[];
  universeUUID: string;
  fullBackup: boolean;
  keyspaceList: IBackup['responseList'];
  expiryTimeUnit: string;
}

export interface IBackupSchedule extends Pick<IBackup, 'customerUUID' | 'universeUUID'> {
  scheduleUUID: string;
  taskType: 'BackupUniverse' | 'MultiTableBackup';
  failureCount: number;
  frequency: number;
  runningState: boolean;
  cronExpression: string;
  status: 'Active' | 'Stopped' | 'Paused';
  backupInfo: Schedule_Task_Params;
  scheduleName: string;
  prevCompletedTask: number;
  nextExpectedTask: number;
  frequencyTimeUnit: string;
}
