/*
 * Copyright (c) YugaByte, Inc.
 */

package create

import (
	"fmt"
	"strconv"
	"strings"

	"github.com/sirupsen/logrus"
	"github.com/spf13/cobra"
	ybaclient "github.com/yugabyte/platform-go-client"
	"github.com/yugabyte/yugabyte-db/managed/yba-cli/cmd/util"
	ybaAuthClient "github.com/yugabyte/yugabyte-db/managed/yba-cli/internal/client"
	"github.com/yugabyte/yugabyte-db/managed/yba-cli/internal/formatter"
)

// createGCSStorageConfigurationCmd represents the storage config command
var createGCSStorageConfigurationCmd = &cobra.Command{
	Use:   "gcs",
	Short: "Create an GCS YugabyteDB Anywhere storage configuration",
	Long:  "Create an GCS storage configuration in YugabyteDB Anywhere",
	PreRun: func(cmd *cobra.Command, args []string) {
		storageNameFlag, err := cmd.Flags().GetString("storage-config-name")
		if err != nil {
			logrus.Fatalf(formatter.Colorize(err.Error()+"\n", formatter.RedColor))
		}
		if len(strings.TrimSpace(storageNameFlag)) == 0 {
			cmd.Help()
			logrus.Fatalln(
				formatter.Colorize(
					"No storage configuration name found to create\n",
					formatter.RedColor))
		}
	},
	Run: func(cmd *cobra.Command, args []string) {
		authAPI, err := ybaAuthClient.NewAuthAPIClient()
		if err != nil {
			logrus.Fatalf(formatter.Colorize(err.Error()+"\n", formatter.RedColor))
		}
		authAPI.GetCustomerUUID()
		storageName, err := cmd.Flags().GetString("storage-config-name")
		if err != nil {
			logrus.Fatalf(formatter.Colorize(err.Error()+"\n", formatter.RedColor))
		}

		backupLocation, err := cmd.Flags().GetString("backup-location")
		if err != nil {
			logrus.Fatalf(formatter.Colorize(err.Error()+"\n", formatter.RedColor))
		}

		storageCode := "GCS"

		data := map[string]interface{}{
			"BACKUP_LOCATION": backupLocation,
		}
		isIAM, err := cmd.Flags().GetBool("use-gcp-iam")
		if err != nil {
			logrus.Fatalf(formatter.Colorize(err.Error()+"\n", formatter.RedColor))
		}

		if isIAM {
			data[util.UseGCPIAM] = strconv.FormatBool(isIAM)
		} else {
			gcsFilePath, err := cmd.Flags().GetString("gcs-credentials-file-path")
			if err != nil {
				logrus.Fatalf(formatter.Colorize(err.Error()+"\n", formatter.RedColor))
			}
			var gcsCreds string
			if len(gcsFilePath) == 0 {
				gcsCreds, err = util.GcpGetCredentialsAsString()
				if err != nil {
					logrus.Fatalf(formatter.Colorize(err.Error()+"\n", formatter.RedColor))
				}

			} else {
				gcsCreds, err = util.GcpGetCredentialsAsStringFromFilePath(gcsFilePath)
				if err != nil {
					logrus.Fatalf(formatter.Colorize(err.Error()+"\n", formatter.RedColor))
				}
			}
			data[util.GCSCredentialsJSON] = gcsCreds
		}

		requestBody := ybaclient.CustomerConfig{
			Name:         storageCode,
			CustomerUUID: authAPI.CustomerUUID,
			ConfigName:   storageName,
			Type:         util.StorageCustomerConfigType,
			Data:         data,
		}

		rCreate, response, err := authAPI.CreateCustomerConfig().
			Config(requestBody).Execute()
		if err != nil {
			errMessage := util.ErrorFromHTTPResponse(
				response, err, "Storage Configuration", "Create GCS")
			logrus.Fatalf(formatter.Colorize(errMessage.Error()+"\n", formatter.RedColor))
		}

		storageUUID := rCreate.GetConfigUUID()
		createStorageConfigurationUtil(authAPI, storageName, storageUUID)
	},
}

func init() {
	createGCSStorageConfigurationCmd.Flags().SortFlags = false

	// Flags needed for GCS
	createGCSStorageConfigurationCmd.Flags().String("gcs-credentials-file-path", "",
		fmt.Sprintf("GCS Credentials File Path. %s "+
			"Can also be set using environment variable %s.",
			formatter.Colorize(
				"Required for non IAM role based storage configurations.",
				formatter.GreenColor),
			util.GCPCredentialsEnv))
	createGCSStorageConfigurationCmd.Flags().Bool("use-gcp-iam", false,
		"[Optional] Use IAM Role from the YugabyteDB Anywhere Host. "+
			"Supported for Kubernetes GKE clusters with workload identity. Configuration "+
			"creation will fail on insufficient permissions on the host, defaults to false.")

}
