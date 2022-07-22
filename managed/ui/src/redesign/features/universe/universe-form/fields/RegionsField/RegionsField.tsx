import React, { ChangeEvent, ReactElement } from 'react';
import _ from 'lodash';
import { useUpdateEffect } from 'react-use';
import { useQuery } from 'react-query';
import { useTranslation } from 'react-i18next';
import { Controller, useFormContext, useWatch } from 'react-hook-form';
import { Box } from '@material-ui/core';
import { YBLabel, YBAutoComplete } from '../../../../../components';
import { api, QUERY_KEY } from '../../../../../helpers/api';
import { UniverseFormData } from '../../utils/dto';

interface RegionsFieldProps {
  disabled?: boolean;
}

const REGIONS_FIELD_NAME = 'cloudConfig.regionList';

const getOptionLabel = (option: Record<string, string>): string => option?.name;

export const RegionsField = ({ disabled }: RegionsFieldProps): ReactElement => {
  const { control, setValue, getValues } = useFormContext<UniverseFormData>();
  const { t } = useTranslation();
  //Listen to provider value change
  const provider = useWatch({ name: 'cloudConfig.provider' });

  const { isFetching, data } = useQuery(
    [QUERY_KEY.getRegionsList, provider?.uuid],
    () => api.getRegionsList(provider?.uuid),
    {
      enabled: !!provider?.uuid, // make sure query won't run when there's no provider defined
      onSuccess: (regions) => {
        // if regions are not selected and there's single region available - automatically preselect it
        if (_.isEmpty(getValues(REGIONS_FIELD_NAME)) && regions.length === 1) {
          setValue(REGIONS_FIELD_NAME, [regions[0].uuid], { shouldValidate: true });
        }
      }
    }
  );

  const regionsList = _.isEmpty(data) ? [] : _.sortBy(data, 'name');
  const regionsListMap = _.keyBy(regionsList, 'uuid');
  const handleChange = (e: ChangeEvent<{}>, option: any) => {
    setValue(
      REGIONS_FIELD_NAME,
      (option || []).map((item: any) => item.uuid),
      { shouldValidate: true }
    );
  };

  // reset selected regions on provider change
  useUpdateEffect(() => {
    setValue(REGIONS_FIELD_NAME, []);
  }, [provider]);

  return (
    <Box display="flex" width="100%" flexDirection={'row'}>
      <Controller
        name={REGIONS_FIELD_NAME}
        control={control}
        render={({ field, fieldState }) => {
          const value = field.value.map((region) => regionsListMap[region]);
          return (
            <>
              <YBLabel>{t('universeForm.cloudConfig.regionsField')}</YBLabel>
              <Box flex={1}>
                <YBAutoComplete
                  multiple={true}
                  loading={isFetching}
                  filterSelectedOptions={true}
                  value={(value as unknown) as string[]}
                  options={(regionsList as unknown) as Record<string, string>[]}
                  getOptionLabel={getOptionLabel}
                  onChange={handleChange}
                  disabled={disabled}
                  ybInputProps={{
                    error: !!fieldState.error,
                    helperText: fieldState.error?.message
                  }}
                />
              </Box>
            </>
          );
        }}
      />
    </Box>
  );
};
