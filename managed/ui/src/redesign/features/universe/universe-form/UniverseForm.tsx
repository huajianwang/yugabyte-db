import React, { useContext, FC } from 'react';
import * as Yup from 'yup';
import { yupResolver } from '@hookform/resolvers/yup';
import { useTranslation } from 'react-i18next';
import { useForm, FormProvider } from 'react-hook-form';
import { Typography, Grid, Box } from '@material-ui/core';
import { YBButton } from '../../../components';
import {
  AdvancedConfiguration,
  CloudConfiguration,
  GFlags,
  InstanceConfiguration,
  UserTags
} from './sections';
import { UniverseFormData, ClusterType, ClusterModes } from './utils/dto';
import { useFormMainStyles } from './universeMainStyle';
import { api } from './utils/api';
import { UniverseFormContext } from './UniverseFormContainer';

interface UniverseFormProps {
  defaultFormData: UniverseFormData;
  title: React.ReactNode;
  onFormSubmit: (data: UniverseFormData) => void;
  onCancel: () => void;
  onClusterTypeChange?: (data: UniverseFormData) => void;
}

export const UniverseForm: FC<UniverseFormProps> = ({
  defaultFormData,
  title,
  onFormSubmit,
  onCancel,
  onClusterTypeChange
}) => {
  const classes = useFormMainStyles();
  const { t } = useTranslation();
  const [state] = useContext(UniverseFormContext);

  //Form Validation
  const PASSWORD_REGEX = /^(?=.*[0-9])(?=.*[!@#$%^&*])(?=.*[a-z])(?=.*[A-Z])[a-zA-Z0-9!@#$%^&*]{8,256}$/;
  const validateUniverseName = async (value_: unknown) => {
    const value = value_ as string;
    try {
      const universeList = await api.findUniverseByName(value);
      return universeList?.length ? false : true;
    } catch (error) {
      // skip exceptions happened due to canceling previous request
      return !api.isRequestCancelError(error) ? true : false;
    }
  };
  const validationSchema = Yup.object({
    advancedConfig: Yup.object({
      accessKeyCode: Yup.mixed().required(
        t('universeForm.validation.required', { field: t('universeForm.advancedConfig.accessKey') })
      ),
      ybSoftwareVersion: Yup.mixed().required(
        t('universeForm.validation.required', { field: t('universeForm.advancedConfig.dbVersion') })
      )
    }),
    cloudConfig: Yup.object({
      universeName: Yup.string()
        .required(
          t('universeForm.validation.required', {
            field: t('universeForm.cloudConfig.universeName')
          })
        )
        .test(
          'unique-name-check',
          t('universeForm.cloudConfig.universeNameInUse'),
          validateUniverseName
        ),
      provider: Yup.mixed().required(
        t('universeForm.validation.required', {
          field: t('universeForm.cloudConfig.providerField')
        })
      ),
      regionList: Yup.array().required(
        t('universeForm.validation.required', { field: t('universeForm.cloudConfig.regionsField') })
      )
    }),
    instanceConfig: Yup.object({
      instanceType: Yup.mixed().required(
        t('universeForm.validation.required', {
          field: t('universeForm.instanceConfig.instanceType')
        })
      ),
      kmsConfig: Yup.mixed().when('enableEncryptionAtRest', {
        is: (kmsConfig) => kmsConfig === true,
        then: Yup.mixed().required(
          t('universeForm.validation.required', {
            field: t('universeForm.instanceConfig.kmsConfig')
          })
        )
      }),
      ycqlPassword: Yup.string().when('enableYCQLAuth', {
        is: (enableYCQLAuth) => enableYCQLAuth === true,
        then: Yup.string().matches(PASSWORD_REGEX, {
          message: t('universeForm.validation.passwordStrength')
        })
      }),
      ycqlConfirmPassword: Yup.string().oneOf(
        [Yup.ref('ycqlPassword')],
        t('universeForm.validation.confirmPassword')
      ),
      ysqlPassword: Yup.string().when('enableYSQLAuth', {
        is: (enableYSQLAuth) => enableYSQLAuth === true,
        then: Yup.string().matches(PASSWORD_REGEX, {
          message: t('universeForm.validation.passwordStrength')
        })
      }),
      ysqlConfirmPassword: Yup.string().oneOf(
        [Yup.ref('ysqlPassword')],
        t('universeForm.validation.confirmPassword')
      )
    })
  });
  //Form Validation

  const formMethods = useForm<UniverseFormData>({
    mode: 'onChange',
    reValidateMode: 'onChange',
    defaultValues: defaultFormData,
    resolver: yupResolver(validationSchema)
  });

  const { getValues } = formMethods;

  const onSubmit = (formData: UniverseFormData) => {
    onFormSubmit(formData);
  };

  return (
    <Box className={classes.mainConatiner}>
      <FormProvider {...formMethods}>
        <form key={state.clusterType} onSubmit={formMethods.handleSubmit(onSubmit)}>
          <Box className={classes.formHeader}>
            <Typography variant="h3">{title}</Typography>
          </Box>
          <Box className={classes.formContainer}>
            <CloudConfiguration />
            <InstanceConfiguration />
            <AdvancedConfiguration />
            <GFlags />
            <UserTags />
          </Box>
          <Box className={classes.formFooter} mt={4}>
            <Grid container justifyContent="space-between">
              <Grid item lg={6}>
                <Box width="100%" display="flex" justifyContent="flex-start" alignItems="center">
                  Placeholder to Paint Cost estimation
                </Box>
              </Grid>
              <Grid item lg={6}>
                <Box width="100%" display="flex" justifyContent="flex-end">
                  <YBButton variant="secondary" size="large" onClick={() => onCancel()}>
                    {t('common.cancel')}
                  </YBButton>
                  &nbsp;
                  {state.mode === ClusterModes.CREATE && onClusterTypeChange && (
                    <YBButton
                      variant="secondary"
                      size="large"
                      onClick={() => onClusterTypeChange(getValues())}
                    >
                      {state.clusterType === ClusterType.PRIMARY
                        ? t('universeForm.actions.configureRR')
                        : t('universeForm.actions.backPrimary')}
                    </YBButton>
                  )}
                  &nbsp;
                  <YBButton variant="primary" size="large" type="submit">
                    {t('common.create')}
                  </YBButton>
                </Box>
              </Grid>
            </Grid>
          </Box>
        </form>
      </FormProvider>
    </Box>
  );
};
