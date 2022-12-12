import React, { createContext, FC } from 'react';
import { useQuery } from 'react-query';
import { useMethods } from 'react-use';
import { RouteComponentProps } from 'react-router-dom';
import { api, QUERY_KEY } from './utils/api';
import { Box } from '@material-ui/core';
import { YBLoading } from '../../../../components/common/indicators';
import { CreateReadReplica } from './CreateRR';
import { CreateUniverse } from './CreateUniverse';
import { EditReadReplica } from './EditRR';
import { EditUniverse } from './EditUniverse';
import {
  UniverseConfigure,
  ClusterType,
  ClusterModes,
  UniverseFormData,
  UniverseResource
} from './utils/dto';
import { useFormMainStyles } from './universeMainStyle';

export interface UniverseFormContextState {
  clusterType: ClusterType;
  universeConfigureTemplate: UniverseConfigure | null;
  primaryFormData?: UniverseFormData | null;
  asyncFormData?: UniverseFormData | null;
  mode: ClusterModes;
  isLoading: boolean; // To safeguard against bad defaults
  newUniverse: boolean; // Fresh Universe ( set to true only in Primary + RR flow )
  universeResourceTemplate: UniverseResource | null;
}

const initialState: UniverseFormContextState = {
  clusterType: ClusterType.PRIMARY,
  universeConfigureTemplate: null,
  primaryFormData: null,
  asyncFormData: null,
  mode: ClusterModes.CREATE,
  isLoading: true,
  newUniverse: false,
  universeResourceTemplate: null
};

//Avoiding using global state since we are using react-query
const createFormMethods = (contextState: UniverseFormContextState) => ({
  setUniverseConfigureTemplate: (data: UniverseConfigure): UniverseFormContextState => ({
    ...contextState,
    universeConfigureTemplate: data,
    isLoading: false
  }),
  setUniverseResourceTemplate: (data: UniverseResource): UniverseFormContextState => ({
    ...contextState,
    universeResourceTemplate: data
  }),
  //This method will be used only in case of Create Primary Cluster + Read Replica flow
  setPrimaryFormData: (data: UniverseFormData): UniverseFormContextState => ({
    ...contextState,
    primaryFormData: data
  }),
  //This method will be used only in case of Create Primary Cluster + Read Replica flow
  setAsyncFormData: (data: UniverseFormData): UniverseFormContextState => ({
    ...contextState,
    asyncFormData: data
  }),
  toggleClusterType: (type: ClusterType): UniverseFormContextState => ({
    ...contextState,
    clusterType: type
  }),
  initializeForm: (data: Partial<UniverseFormContextState>): UniverseFormContextState => ({
    ...contextState,
    ...data,
    isLoading: false
  }),
  setLoader: (val: boolean): UniverseFormContextState => ({
    ...contextState,
    isLoading: val
  }),
  reset: (): UniverseFormContextState => initialState
});

export const UniverseFormContext = createContext<any>(initialState);
export type FormContextMethods = ReturnType<typeof createFormMethods>;
interface UniverseFormContainerProps {
  mode: string;
  pathname: string;
  uuid: string;
  type: string;
}

export const UniverseFormContainer: FC<RouteComponentProps<{}, UniverseFormContainerProps>> = ({
  location,
  params
}) => {
  const classes = useFormMainStyles();
  const universeContextData = useMethods(createFormMethods, initialState);
  const { type: CLUSTER_TYPE, mode: MODE, uuid } = params;

  //route has it in lower case & enum has it in upper case
  const mode = MODE?.toUpperCase();
  const clusterType = CLUSTER_TYPE?.toUpperCase();

  //prefetch provider data for smooth painting
  const { isLoading: isProviderLoading } = useQuery(
    QUERY_KEY.getProvidersList,
    api.getProvidersList
  );

  //prefetch runtime configs
  const { isLoading: isRuntimeConfigsLoading } = useQuery(QUERY_KEY.fetchRunTimeConfigs, () =>
    api.fetchRunTimeConfigs(true)
  );

  //prefetch provider data for smooth painting
  const { isLoading: isProviderLoading } = useQuery(
    QUERY_KEY.getProvidersList,
    api.getProvidersList
  );

  //prefetch runtime configs
  const { isLoading: isRuntimeConfigsLoading } = useQuery(QUERY_KEY.fetchRunTimeConfigs, () =>
    api.fetchRunTimeConfigs(true)
  );

  const switchInternalRoutes = () => {
    //Create Primary + RR
    if (location.pathname === '/universes/create') return <CreateUniverse />;
    //NEW ASYNC
    else if (mode === ClusterModes.CREATE && clusterType === ClusterType.ASYNC)
      return <CreateReadReplica uuid={uuid} />;
    //EDIT PRIMARY
    else if (mode === ClusterModes.EDIT && clusterType === ClusterType.PRIMARY)
      return <EditUniverse uuid={uuid} />;
    //EDIT ASYNC
    else if (mode === ClusterModes.EDIT && clusterType === ClusterType.ASYNC)
      return <EditReadReplica uuid={uuid} />;
    //Page not found
    else return <div>Page not found</div>;
  };

  if (isProviderLoading || isRuntimeConfigsLoading) return <YBLoading />;
  else
    return (
      <UniverseFormContext.Provider value={universeContextData}>
        <Box className={classes.mainConatiner}>{switchInternalRoutes()}</Box>
      </UniverseFormContext.Provider>
    );
};
