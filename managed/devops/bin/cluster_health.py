#!/usr/bin/env python
#
# Copyright 2019 YugaByte, Inc. and Contributors
#
# Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
# may not use this file except in compliance with the License. You
# may obtain a copy of the License at
#
# https://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt

import argparse
import json
import logging
import os
import re
import subprocess
import sys
import requests
import time

try:
    from builtins import RuntimeError
except Exception as e:
    from exceptions import RuntimeError
from datetime import datetime, timedelta
from dateutil import tz
from multiprocessing import Pool
from six import string_types, PY2, PY3


# Try to read home dir from environment variable, else assume it's /home/yugabyte.
ALERT_ENHANCEMENTS_RELEASE = "2.6.0.0"
RELEASE_VERSION_PATTERN = "(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)(.*)"
YB_HOME_DIR = os.environ.get("YB_HOME_DIR", "/home/yugabyte")
YB_TSERVER_DIR = os.path.join(YB_HOME_DIR, "tserver")
YB_CORES_DIR = os.path.join(YB_HOME_DIR, "cores/")
YB_PROCESS_LOG_PATH_FORMAT = os.path.join(YB_HOME_DIR, "{}/logs/")
VM_ROOT_CERT_FILE_PATH = os.path.join(YB_HOME_DIR, "yugabyte-tls-config/ca.crt")
VM_ROOT_C2N_CERT_FILE_PATH = os.path.join(YB_HOME_DIR, "yugabyte-client-tls-config/ca.crt")

VM_CLIENT_CA_CERT_FILE_PATH = os.path.join(YB_HOME_DIR, ".yugabytedb/root.crt")
VM_CLIENT_CERT_FILE_PATH = os.path.join(YB_HOME_DIR, ".yugabytedb/yugabytedb.crt")

K8S_CERTS_PATH = "/opt/certs/yugabyte/"
K8S_CERT_FILE_PATH = os.path.join(K8S_CERTS_PATH, "ca.crt")

K8S_CLIENT_CERTS_PATH = "/root/.yugabytedb/"
K8S_CLIENT_CA_CERT_FILE_PATH = os.path.join(K8S_CLIENT_CERTS_PATH, "root.crt")
K8S_CLIENT_CERT_FILE_PATH = os.path.join(K8S_CLIENT_CERTS_PATH, "yugabytedb.crt")

RECENT_FAILURE_THRESHOLD_SEC = 8 * 60
FATAL_TIME_THRESHOLD_MINUTES = 12
DISK_UTILIZATION_THRESHOLD_PCT = 80
FD_THRESHOLD_PCT = 50
SSH_TIMEOUT_SEC = 10
CMD_TIMEOUT_SEC = 20
MAX_CONCURRENT_PROCESSES = 10
MAX_TRIES = 2

DEFAULT_SSL_VERSION = "TLSv1_2"
SSL_PROTOCOL_TO_SSL_VERSION = {
    "ssl2": "SSLv23",
    "ssl3": "SSLv23",
    "tls10": "TLSv1",
    "tls11": "TLSv1_1",
    "tls12": "TLSv1_2"
}

###################################################################################################
# Reporting
###################################################################################################


def generate_ts():
    return local_time().strftime('%Y-%m-%d %H:%M:%S')


class EntryType:
    NODE = "node"
    NODE_NAME = "node_name"
    TIMESTAMP = "timestamp"
    MESSAGE = "message"
    DETAILS = "details"
    METRIC_VALUE = "metric_value"
    HAS_ERROR = "has_error"
    HAS_WARNING = "has_warning"
    PROCESS = "process"


class Entry:
    def __init__(self, message, node, process=None, node_name=None):
        self.timestamp = generate_ts()
        self.message = message
        self.node = node
        self.node_name = node_name
        self.process = process
        # To be filled in.
        self.details = None
        self.metric_value = None
        self.has_error = None
        self.has_warning = None
        self.ignore_result = False

    def ignore_check(self):
        self.has_error = False
        self.has_warning = False
        self.ignore_result = True
        return self

    def fill_and_return_entry(self, details, has_error=False):
        return self.fill_and_return_entry(details, has_error, None)

    def fill_and_return_entry(self, details, has_error=False, metric_value=None):
        self.details = details
        self.has_error = has_error
        self.has_warning = False
        self.metric_value = metric_value
        return self

    def fill_and_return_warning_entry(self, details):
        self.details = details
        self.has_error = False
        self.has_warning = True
        return self

    def as_json(self):
        j = {
            EntryType.NODE: self.node,
            EntryType.NODE_NAME: self.node_name or "",
            EntryType.TIMESTAMP: self.timestamp,
            EntryType.MESSAGE: self.message,
            EntryType.DETAILS: self.details,
            EntryType.HAS_ERROR: self.has_error,
            EntryType.HAS_WARNING: self.has_warning
        }
        if self.metric_value is not None:
            j[EntryType.METRIC_VALUE] = self.metric_value
        if self.process:
            j[EntryType.PROCESS] = self.process
        return j


class Report:
    def __init__(self, yb_version):
        self.entries = []
        self.start_ts = generate_ts()
        self.yb_version = yb_version

        logging.info("Script started at {}".format(self.start_ts))

    def add_entry(self, entry):
        self.entries.append(entry)

    def has_errors_or_warnings(self):
        return True in [e.has_error or e.has_warning for e in self.entries]

    def write_to_log(self, log_file):
        if not self.has_errors_or_warnings() or log_file is None:
            logging.info("Not logging anything to a file.")
            return

        # Write to stderr.
        logging.info(str(self))
        # Write to the log file.
        file = open(log_file, 'a')
        file.write(str(self))
        file.close()

    def as_json(self, only_errors=False):
        j = {
            "timestamp": self.start_ts,
            "yb_version": self.yb_version,
            "data": [e.as_json() for e in self.entries
                     if not only_errors or e.has_error or e.has_warning],
            "has_error": True in [e.has_error for e in self.entries],
            "has_warning": True in [e.has_warning for e in self.entries]
        }
        return json.dumps(j, indent=2)

    def __str__(self):
        return self.as_json()


###################################################################################################
# Checking
###################################################################################################
def check_output(cmd, env):
    try:
        output = subprocess.check_output(
            cmd, stderr=subprocess.STDOUT, env=env, timeout=CMD_TIMEOUT_SEC)
        return str(output.decode('utf-8').encode("ascii", "ignore").decode("ascii"))
    except subprocess.CalledProcessError as ex:
        return 'Error executing command {}: {}'.format(
            cmd, ex.output.decode("utf-8").encode("ascii", "ignore"))
    except subprocess.TimeoutExpired:
        return 'Error: timed out executing command {} for {} seconds'.format(
            cmd, CMD_TIMEOUT_SEC)


def safe_pipe(command_str):
    return "set -o pipefail; " + command_str


def has_errors(str):
    return str.startswith('Error')


class KubernetesDetails():

    def __init__(self, node_fqdn, config_map):
        self.namespace = node_fqdn.split('.')[2]
        self.pod_name = node_fqdn.split('.')[0]
        # The pod names are yb-master-n/yb-tserver-n where n is the pod number
        # and yb-master/yb-tserver are the container names.

        # TODO(bhavin192): need to change in case of multiple releases
        # in one namespace. Something like find the word 'master' in
        # the name.

        self.container = self.pod_name.rsplit('-', 1)[0]
        self.config = config_map[self.namespace]


class NodeChecker():

    def __init__(self, node, node_name, identity_file, ssh_port, start_time_ms,
                 namespace_to_config, ysql_port, ycql_port, redis_port, enable_tls_client,
                 root_and_client_root_ca_same, ssl_protocol, enable_ysql_auth,
                 master_http_port, tserver_http_port, universe_version):
        self.node = node
        self.node_name = node_name
        self.identity_file = identity_file
        self.ssh_port = ssh_port
        self.start_time_ms = start_time_ms
        self.enable_tls_client = enable_tls_client
        self.root_and_client_root_ca_same = root_and_client_root_ca_same
        self.ssl_protocol = ssl_protocol
        # TODO: best way to do mark that this is a k8s deployment?
        self.is_k8s = ssh_port == 0 and not self.identity_file
        self.k8s_details = None
        if self.is_k8s:
            self.k8s_details = KubernetesDetails(node, namespace_to_config)

        # Some validation.
        if not self.is_k8s and not os.path.isfile(self.identity_file):
            raise RuntimeError('Error: cannot find identity file {}'.format(self.identity_file))
        self.ysql_port = ysql_port
        self.ycql_port = ycql_port
        self.redis_port = redis_port
        self.enable_ysql_auth = enable_ysql_auth
        self.master_http_port = master_http_port
        self.tserver_http_port = tserver_http_port
        self.universe_version = universe_version
        self.additional_info = {}

    def _new_entry(self, message, process=None):
        return Entry(message, self.node, process, self.node_name)

    def _remote_check_output(self, command):
        cmd_to_run = []
        command = safe_pipe(command) if isinstance(command, string_types) else command
        env_conf = os.environ.copy()
        if self.is_k8s:
            env_conf["KUBECONFIG"] = self.k8s_details.config
            cmd_to_run.extend([
                'kubectl',
                'exec',
                '-t',
                '-n={}'.format(self.k8s_details.namespace),
                self.k8s_details.pod_name,
                '-c',
                self.k8s_details.container,
                '--',
                'bash',
                '-c',
                command
            ])
        else:
            cmd_to_run.extend(
                ['ssh', 'yugabyte@{}'.format(self.node), '-p', str(self.ssh_port),
                 '-o', 'StrictHostKeyChecking no',
                 '-o', 'ConnectTimeout={}'.format(SSH_TIMEOUT_SEC),
                 '-o', 'UserKnownHostsFile /dev/null',
                 '-o', 'LogLevel ERROR',
                 '-i', self.identity_file])
            cmd_to_run.append(command)

        output = check_output(cmd_to_run, env_conf).strip()

        # TODO: this is only relevant for SSH, so non-k8s.
        if output.endswith('No route to host'):
            return 'Error: Node {} is unreachable'.format(self.node)
        return output

    def get_disk_utilization(self):
        remote_cmd = 'df -hl -x squashfs -x overlay 2>/dev/null'
        return self._remote_check_output(remote_cmd)

    def check_disk_utilization(self):
        logging.info("Checking disk utilization on node {}".format(self.node))
        e = self._new_entry("Disk utilization")
        found_error = False
        output = self.get_disk_utilization()
        msgs = []
        if has_errors(output):
            return e.fill_and_return_entry([output], True)

        # Do not process the headers.
        lines = output.split('\n')
        if len(lines) < 2:
            return e.fill_and_return_entry([output], True)

        found_header = False
        for line in lines:
            msgs.append(line)
            if not line:
                continue
            if line.startswith("Filesystem"):
                found_header = True
                continue
            if not found_header:
                continue
            percentage = line.split()[4][:-1]
            if int(percentage) > DISK_UTILIZATION_THRESHOLD_PCT:
                found_error = True

        if not found_header:
            return e.fill_and_return_entry([output], True)

        return e.fill_and_return_entry(msgs, found_error)

    def get_certificate_expiration_date(self, cert_path):
        remote_cmd = 'if [ -f {} ]; then openssl x509 -enddate -noout -in {} 2>/dev/null;' \
                     ' else echo \"File not found\"; fi;'.format(cert_path, cert_path)
        return self._remote_check_output(remote_cmd)

    def check_certificate_expiration(self, cert_name, cert_path, fail_if_not_found):
        logging.info("Checking {} certificate on node {}".format(cert_name, self.node))
        e = self._new_entry(cert_name + " Cert Expiry Days")
        ssl_installed = self.additional_info.get("ssl_installed:" + self.node)
        if ssl_installed is not None and not ssl_installed:
            return e.fill_and_return_warning_entry(["OpenSSL is not installed, skipped"])

        output = self.get_certificate_expiration_date(cert_path)
        if has_errors(output):
            return e.fill_and_return_entry([output], True)

        if output == 'File not found':
            if fail_if_not_found:
                return e.fill_and_return_entry(
                    ["Certificate file {} not found".format(cert_path)], True)
            else:
                return e.ignore_check()

        try:
            ssl_date_fmt = r'%b %d %H:%M:%S %Y %Z'
            date = output.replace('notAfter=', '')
            expiry_date = datetime.strptime(date, ssl_date_fmt)
        except Exception as ex:
            message = str(ex)
            return e.fill_and_return_entry([message], True)

        delta = expiry_date - datetime.now()
        days_till_expiry = delta.days
        if days_till_expiry == 0:
            return e.fill_and_return_entry(
                ['{} certificate expires today'.format(cert_name)],
                True,
                days_till_expiry)
        if days_till_expiry < 0:
            return e.fill_and_return_entry(
                ['{} certificate expired {} day(s) ago'.format(cert_name, -days_till_expiry)],
                True,
                days_till_expiry)
        return e.fill_and_return_entry(
            ['{} days'.format(days_till_expiry)],
            False,
            days_till_expiry)

    def get_node_to_node_ca_certificate_path(self):
        if self.is_k8s:
            return K8S_CERT_FILE_PATH

        return VM_ROOT_CERT_FILE_PATH

    def get_node_to_node_certificate_path(self):
        if self.is_k8s:
            return os.path.join(K8S_CERTS_PATH, "node.{}.crt".format(self.node))

        return os.path.join(YB_HOME_DIR,
                            "yugabyte-tls-config/node.{}.crt".format(self.node))

    def get_client_to_node_ca_certificate_path(self):
        if self.root_and_client_root_ca_same:
            return self.get_node_to_node_ca_certificate_path()
        # Our helm chart do not support separate c2n certificate right now -
        # hence no special logic for k8s

        return VM_ROOT_C2N_CERT_FILE_PATH

    def get_client_to_node_certificate_path(self):
        if self.root_and_client_root_ca_same:
            return self.get_node_to_node_certificate_path()
        # Our helm chart do not support separate c2n certificate right now -
        # hence no special logic for k8s

        return os.path.join(
            YB_HOME_DIR, "yugabyte-client-tls-config/node.{}.crt".format(self.node))

    def get_client_ca_certificate_path(self):
        if self.is_k8s:
            return K8S_CLIENT_CA_CERT_FILE_PATH

        return VM_CLIENT_CA_CERT_FILE_PATH

    def get_client_certificate_path(self):
        if self.is_k8s:
            return K8S_CLIENT_CERT_FILE_PATH

        return VM_CLIENT_CERT_FILE_PATH

    def check_node_to_node_ca_certificate_expiration(self):
        return self.check_certificate_expiration("Node To Node CA",
                                                 self.get_node_to_node_ca_certificate_path(),
                                                 True  # fail_if_not_found
                                                 )

    def check_node_to_node_certificate_expiration(self):
        return self.check_certificate_expiration("Node To Node",
                                                 self.get_node_to_node_certificate_path(),
                                                 True  # fail_if_not_found
                                                 )

    def check_client_to_node_ca_certificate_expiration(self):
        return self.check_certificate_expiration("Client To Node CA",
                                                 self.get_client_to_node_ca_certificate_path(),
                                                 True  # fail_if_not_found
                                                 )

    def check_client_to_node_certificate_expiration(self):
        return self.check_certificate_expiration("Client To Node",
                                                 self.get_client_to_node_certificate_path(),
                                                 True  # fail_if_not_found
                                                 )

    def check_client_ca_certificate_expiration(self):
        return self.check_certificate_expiration("Client CA",
                                                 self.get_client_ca_certificate_path(),
                                                 False  # fail_if_not_found
                                                 )

    def check_client_certificate_expiration(self):
        return self.check_certificate_expiration("Client",
                                                 self.get_client_certificate_path(),
                                                 False  # fail_if_not_found
                                                 )

    def check_yb_version(self, ip_address, process, port, expected):
        logging.info("Checking YB Version on node {} process {}".format(self.node, process))
        e = self._new_entry("YB Version")

        remote_cmd = 'curl --silent http://{}:{}/api/v1/version'.format(ip_address, port)
        output = self._remote_check_output(remote_cmd)
        if has_errors(output):
            return e.fill_and_return_entry([output], True)

        try:
            json_version = json.loads(output)
            version = json_version["version_number"] + "-b" + json_version["build_number"]
        except Exception as ex:
            message = str(ex)
            return e.fill_and_return_entry([message], True)

        matched = version == expected
        if not matched:
            return e.fill_and_return_entry(
                ['Version from platform metadata {}, version reported by instance process {}'.
                 format(expected, version)],
                True)
        return e.fill_and_return_entry([version])

    def check_master_yb_version(self, process):
        return self.check_yb_version(
            self.node, process, self.master_http_port, self.universe_version)

    def check_tserver_yb_version(self, process):
        return self.check_yb_version(
            self.node, process, self.tserver_http_port, self.universe_version)

    def check_logs_find_output(self, output):
        log_tuples = []
        if output:
            for line in output.strip().split('\n'):
                splits = line.strip().split()
                # In case of errors, we might get other outputs from the find command, such as
                # the stderr output. In that case, best we can do is validate it has 2
                # components and first one is an epoch with fraction...
                if len(splits) != 2:
                    continue
                epoch, filename = splits
                epoch = epoch.split('.')[0]
                if not epoch.isdigit():
                    continue
                log_tuples.append((epoch, filename,
                                   seconds_to_human_readable_time(int(time.time() - int(epoch)))))

        sorted_logs = sorted(log_tuples, key=lambda log: log[0], reverse=True)
        return list(map(lambda log: '{} ({} old)'.format(log[1], log[2]), sorted_logs))

    def check_for_error_logs(self, process):
        logging.info("Checking for error logs on node {}".format(self.node))
        e = self._new_entry("Fatal log files")
        logs = []
        process_name = process.strip("yb-")
        search_dir = YB_PROCESS_LOG_PATH_FORMAT.format(process_name)

        metric_value = 0
        for log_severity in ["FATAL", "ERROR"]:
            remote_cmd = ('find {} {} -name "*{}*" -type f -printf "%T@ %p\\n"'.format(
                search_dir,
                '-mmin -{}'.format(FATAL_TIME_THRESHOLD_MINUTES),
                log_severity))
            output = self._remote_check_output(remote_cmd)
            if has_errors(output):
                return e.fill_and_return_entry([output], True)

            log_files = self.check_logs_find_output(output)

            # For now only show error on fatal logs - until error logs are cleaned up enough
            if log_severity == "FATAL":
                logs.extend(log_files)

            # 0 = no error and fatal logs
            # 1 = error logs only
            # 2 = fatal logs only
            # 3 = error and fatal logs
            if len(log_files) > 0:
                if log_severity == "ERROR":
                    metric_value = metric_value + 1
                if log_severity == "FATAL":
                    metric_value = metric_value + 2

        return e.fill_and_return_entry(logs, len(logs) > 0, metric_value)

    def check_for_core_files(self):
        logging.info("Checking for core files on node {}".format(self.node))
        e = self._new_entry("Core files")
        remote_cmd = 'if [ -d {} ]; then find {} {} -name "core_*"; fi'.format(
            YB_CORES_DIR, YB_CORES_DIR,
            '-mmin -{}'.format(FATAL_TIME_THRESHOLD_MINUTES))
        output = self._remote_check_output(remote_cmd)
        if has_errors(output):
            return e.fill_and_return_entry([output], True)

        files = []
        if output.strip():
            files = output.strip().split('\n')
        return e.fill_and_return_entry(files, len(files) > 0)

    def get_uptime_for_process(self, process):
        remote_cmd = "ps -C {} -o etimes=".format(process)
        return self._remote_check_output(remote_cmd).strip()

    def get_boot_time_for_process(self, process):
        remote_cmd = "date +%s -d \"$(ps -C {} -o lstart=)\"".format(process)
        return self._remote_check_output(remote_cmd).strip()

    def get_uptime_in_dhms(self, uptime):
        dtime = timedelta(seconds=int(uptime))
        d = {"days": dtime.days}
        d["hours"], rem = divmod(dtime.seconds, 3600)
        d["minutes"], d["seconds"] = divmod(rem, 60)
        return "{days} days {hours} hours {minutes} minutes {seconds} seconds".format(**d)

    def check_uptime_for_process(self, process):
        logging.info("Checking uptime for {} process {}".format(self.node, process))
        e = self._new_entry("Uptime", process)
        uptime = self.get_uptime_for_process(process)
        if has_errors(uptime):
            return e.fill_and_return_entry([uptime], True)

        boot_time = self.get_boot_time_for_process(process)
        if has_errors(boot_time):
            return e.fill_and_return_entry([boot_time], True)

        if uptime.isdigit():
            # To prevent emails right after the universe creation or restart, we pass in a
            # potential start time. If the reported uptime is in our threshold, but so is the
            # time since the last operation, then we do not report this as an error.
            recent_operation = self.start_time_ms is not None and \
                (int(time.time()) - self.start_time_ms / 1000 <= RECENT_FAILURE_THRESHOLD_SEC)
            # Server went down recently.
            if int(uptime) <= RECENT_FAILURE_THRESHOLD_SEC and not recent_operation:
                return e.fill_and_return_entry(['Uptime: {} seconds ({})'.format(
                    uptime, self.get_uptime_in_dhms(uptime))], True, boot_time)
            else:
                return e.fill_and_return_entry(['Uptime: {} seconds ({})'.format(
                    uptime, self.get_uptime_in_dhms(uptime))], False, boot_time)
        elif not uptime:
            return e.fill_and_return_entry(['Process is not running'], True)
        else:
            return e.fill_and_return_entry(['Invalid uptime {}'.format(uptime)], True)

    def check_file_descriptors(self):
        logging.info("Checking for open file descriptors on node {}".format(self.node))
        e = self._new_entry("Opened file descriptors")

        remote_cmd = 'ulimit -n; cat /proc/sys/fs/file-max; awk \'{print $1}\' /proc/sys/fs/file-nr'
        output = self._remote_check_output(remote_cmd)

        if has_errors(output):
            return e.fill_and_return_entry([output], True)

        counts = output.split('\n')

        if len(counts) != 3:
            return e.fill_and_return_entry(
                ['Error checking file descriptors: {}'.format(counts)], True)

        if not all(count.isdigit() for count in counts):
            return e.fill_and_return_entry(
                ['Received invalid counts: {}'.format(counts)], True)

        ulimit = int(counts[0])
        file_max = int(counts[1])
        open_fd = int(counts[2])
        max_fd = min(ulimit, file_max)
        used_fd_percentage = open_fd * 100 / max_fd
        if open_fd > max_fd * FD_THRESHOLD_PCT / 100.0:
            return e.fill_and_return_entry(
                ['Open file descriptors: {}. Max file descriptors: {}'.format(open_fd, max_fd)],
                True,
                used_fd_percentage)

        return e.fill_and_return_entry([], False, used_fd_percentage)

    def check_cqlsh(self):
        logging.info("Checking cqlsh works for node {}".format(self.node))
        e = self._new_entry("Connectivity with cqlsh")

        cqlsh = '{}/bin/cqlsh'.format(YB_TSERVER_DIR)
        remote_cmd = '{} {} {} -e "SHOW HOST"'.format(cqlsh, self.node, self.ycql_port)
        if self.enable_tls_client:
            cert_file = self.get_client_to_node_ca_certificate_path()
            protocols = re.split('\\W+', self.ssl_protocol or "")
            ssl_version = DEFAULT_SSL_VERSION
            for protocol in protocols:
                cur_version = SSL_PROTOCOL_TO_SSL_VERSION.get(protocol)
                if cur_version is not None:
                    ssl_version = cur_version
                    break

            remote_cmd = 'SSL_VERSION={} SSL_CERTFILE={} {} {}'.format(
                ssl_version, cert_file, remote_cmd, '--ssl')

        output = self._remote_check_output(remote_cmd).strip()

        errors = []
        if not ('Connected to local cluster at {}:{}'
                .format(self.node, self.ycql_port) in output or
                "AuthenticationFailed('Remote end requires authentication.'" in output):
            errors = [output]
        return e.fill_and_return_entry(errors, len(errors) > 0)

    def check_redis_cli(self):
        logging.info("Checking redis cli works for node {}".format(self.node))
        e = self._new_entry("Connectivity with redis-cli")
        redis_cli = '{}/bin/redis-cli'.format(YB_TSERVER_DIR)
        remote_cmd = '{} -h {} -p {} ping'.format(redis_cli, self.node, self.redis_port)

        output = self._remote_check_output(remote_cmd).strip()

        errors = []
        if output not in ("PONG", "NOAUTH ping: Authentication required."):
            errors = [output]

        return e.fill_and_return_entry(errors, len(errors) > 0)

    def check_ysqlsh(self):
        logging.info("Checking ysqlsh works for node {}".format(self.node))
        e = self._new_entry("Connectivity with ysqlsh")

        ysqlsh = '{}/bin/ysqlsh'.format(YB_TSERVER_DIR)
        port_args = "-p {}".format(self.ysql_port)
        host = self.node
        errors = []
        # If YSQL-auth is enabled, we'll try connecting over the UNIX domain socket in the hopes
        # that we can circumvent md5 authentication (assumption made:
        # "local all yugabyte trust" is in the hba file)
        if self.enable_ysql_auth:
            socket_fds_output = self._remote_check_output("ls /tmp/.yb.*/.s.PGSQL.*").strip()
            socket_fds = socket_fds_output.split()
            if ("Error" not in socket_fds_output) and len(socket_fds):
                host = os.path.dirname(socket_fds[0])
            else:
                errors = ["Could not find local socket"]
                return e.fill_and_return_entry(errors, True)

        if not self.enable_tls_client:
            remote_cmd = "{} -h {} {} -U yugabyte -c \"\\conninfo\"".format(
                ysqlsh, host, port_args)
        else:
            remote_cmd = "{} {} -h {} {} -U yugabyte -c \"\\conninfo\"".format(
                'env sslmode="require"', ysqlsh, host, port_args,)
        # Passing dbname template1 explicitly as ysqlsh fails to connect if
        # yugabyte database is deleted. We assume template1 will always be there
        # in ysqlsh.
        remote_cmd = "{} --dbname=template1".format(remote_cmd)

        output = self._remote_check_output(remote_cmd).strip()
        if 'You are connected to database' not in output:
            errors = [output]
        return e.fill_and_return_entry(errors, len(errors) > 0)

    def check_clock_skew(self):
        logging.info("Checking clock synchronization on node {}".format(self.node))
        e = self._new_entry("Clock synchronization")
        errors = []

        remote_cmd = "timedatectl status"
        output = self._remote_check_output(remote_cmd).strip()

        clock_re = re.match(r'((.|\n)*)((NTP enabled: )|(NTP service: )|(Network time on: )|' +
                            r'(systemd-timesyncd\.service active: ))(.*)$', output, re.MULTILINE)
        if clock_re:
            ntp_enabled_answer = clock_re.group(8)
        else:
            return e.fill_and_return_entry(["Error getting NTP state - incorrect answer format"],
                                           True)

        if ntp_enabled_answer not in ("yes", "active"):
            if ntp_enabled_answer in ("no", "inactive"):
                return e.fill_and_return_entry(["NTP disabled"], True)

            return e.fill_and_return_entry(["Error getting NTP state {}"
                                            .format(ntp_enabled_answer)], True)

        clock_re = re.match(r'((.|\n)*)((NTP synchronized: )|(System clock synchronized: ))(.*)$',
                            output, re.MULTILINE)
        if clock_re:
            ntp_synchronized_answer = clock_re.group(6)
        else:
            return e.fill_and_return_entry([
                "Error getting NTP synchronization state - incorrect answer format"], True)

        if ntp_synchronized_answer != "yes":
            if ntp_synchronized_answer == "no":
                errors = ["NTP desynchronized"]
            else:
                errors = ["Error getting NTP synchronization state {}"
                          .format(ntp_synchronized_answer)]

        return e.fill_and_return_entry(errors, len(errors) > 0)

    def check_openssl_availability(self):
        remote_cmd = "which openssl &>/dev/null; echo $?"
        output = self._remote_check_output(remote_cmd).rstrip()
        logging.info("OpenSSL installed state for node %s: %s",  self.node, output)
        return {"ssl_installed:" + self.node: (output == "0")
                if not has_errors(output) else None}


###################################################################################################
# Utility functions
###################################################################################################
def seconds_to_human_readable_time(seconds):
    m, s = divmod(seconds, 60)
    h, m = divmod(m, 60)
    d, h = divmod(h, 24)
    return ['{} days {:d}:{:02d}:{:02d}'.format(d, h, m, s)]


def local_time():
    return datetime.utcnow().replace(tzinfo=tz.tzutc())


def is_equal_or_newer_release(current_version, threshold_version):
    if not current_version:
        return False

    c_match = re.match(RELEASE_VERSION_PATTERN, current_version)
    t_match = re.match(RELEASE_VERSION_PATTERN, threshold_version)

    if c_match is None:
        raise RuntimeError("Invalid universe version format: {}".format(current_version))
    if t_match is None:
        raise RuntimeError("Invalid threshold version format: {}".format(threshold_version))

    for i in range(1, 5):
        c = int(c_match.group(i))
        t = int(t_match.group(i))
        if c < t:
            # If any component is behind, the whole version is older.
            return False
        elif c > t:
            # If any component is ahead, the whole version is newer.
            return True
    # If all components were equal, then the versions are compatible.
    return True


###################################################################################################
# Multi-threaded handling and main
###################################################################################################
def multithreaded_caller(instance, func_name,  sleep_interval=0, args=(), kwargs=None):
    if sleep_interval > 0:
        logging.debug("waiting for sleep to run " + func_name)
        time.sleep(sleep_interval)

    if kwargs is None:
        kwargs = {}
    return getattr(instance, func_name)(*args, **kwargs)


class CheckCoordinator:
    class PreCheckRunInfo:
        def __init__(self, instance, func_name):
            self.instance = instance
            if PY3:
                self.__name__ = func_name
            else:
                self.func_name = func_name
            self.result = {}

    class CheckRunInfo:
        def __init__(self, instance, func_name, yb_process):
            self.instance = instance
            if PY3:
                self.__name__ = func_name
            else:
                self.func_name = func_name
            self.yb_process = yb_process
            self.result = None
            self.entry = None
            self.tries = 0

    def __init__(self, retry_interval_secs):
        self.pool = Pool(MAX_CONCURRENT_PROCESSES)
        self.prechecks = []
        self.checks = []
        self.retry_interval_secs = retry_interval_secs

    def add_precheck(self, instance, func_name):
        self.prechecks.append(CheckCoordinator.PreCheckRunInfo(instance, func_name))

    def add_check(self, instance, func_name, yb_process=None):
        self.checks.append(CheckCoordinator.CheckRunInfo(instance, func_name, yb_process))

    def run(self):

        precheck_results = []
        for precheck in self.prechecks:
            precheck_func_name = precheck.__name__ if PY3 else precheck.func_name
            result = self.pool.apply_async(multithreaded_caller, (precheck.instance,
                                                                  precheck_func_name))
            precheck_results.append(result)

        # Getting results.
        additional_info = {}
        for result in precheck_results:
            additional_info.update(result.get())

        while True:
            checks_remaining = 0
            for check in self.checks:
                check.instance.additional_info = additional_info
                check.entry = check.result.get() if check.result else None

                # Run checks until they succeed, up to max tries. Wait for sleep_interval secs
                # before retrying to let transient errors correct themselves.
                if check.entry is None or (check.entry.has_error and check.tries < MAX_TRIES):
                    checks_remaining += 1
                    sleep_interval = self.retry_interval_secs if check.tries > 0 else 0

                    check_func_name = check.__name__ if PY3 else check.func_name
                    if check.tries > 0:
                        logging.info("Retry # " + str(check.tries) +
                                     " for check " + check_func_name)

                    if check.yb_process is None:
                        check.result = self.pool.apply_async(
                                            multithreaded_caller,
                                            (check.instance, check_func_name, sleep_interval))
                    else:
                        check.result = self.pool.apply_async(
                                            multithreaded_caller,
                                            (check.instance,
                                                check_func_name,
                                                sleep_interval,
                                                (check.yb_process,)))

                    check.tries += 1

            if checks_remaining == 0:
                break

        entries = []
        for check in self.checks:
            if not check.entry.ignore_result:
                entries.append(check.entry)
        return entries


class Cluster():
    def __init__(self, data):
        self.identity_file = data.get("identityFile")
        self.ssh_port = data["sshPort"]
        self.enable_tls = data["enableTls"]
        self.enable_tls_client = data["enableTlsClient"]
        self.root_and_client_root_ca_same = data['rootAndClientRootCASame']
        self.master_nodes = data["masterNodes"]
        self.tserver_nodes = data["tserverNodes"]
        self.yb_version = data["ybSoftwareVersion"]
        self.namespace_to_config = data["namespaceToConfig"]
        self.ssl_protocol = data["sslProtocol"]
        self.enable_ysql = data["enableYSQL"]
        self.enable_ycql = data["enableYCQL"]
        self.ysql_port = data["ysqlPort"]
        self.ycql_port = data["ycqlPort"]
        self.enable_yedis = data["enableYEDIS"]
        self.redis_port = data["redisPort"]
        self.enable_ysql_auth = data["enableYSQLAuth"]
        self.master_http_port = data["masterHttpPort"]
        self.tserver_http_port = data["tserverHttpPort"]


class UniverseDefinition():
    def __init__(self, json_blob):
        data = json.loads(json_blob)
        self.clusters = [Cluster(c) for c in data]


def main():
    parser = argparse.ArgumentParser(prog=sys.argv[0])
    parser.add_argument('--cluster_payload', type=str, default=None, required=False,
                        help='JSON serialized payload of cluster data: IPs, pem files, etc.')
    # TODO (Sergey P.): extract the next functionality to Java layer
    parser.add_argument('--log_file', type=str, default=None, required=False,
                        help='Log file to which the report will be written to.')
    parser.add_argument('--start_time_ms', type=int, required=False, default=None,
                        help='Potential start time of the universe, to prevent uptime confusion.')
    parser.add_argument('--retry_interval_secs', type=int, required=False, default=30,
                        help='Time to wait between retries of failed checks.')
    parser.add_argument('--check_clock', action="store_true",
                        help='Include NTP synchronization check into actions.')
    args = parser.parse_args()
    if args.cluster_payload is not None:
        universe = UniverseDefinition(args.cluster_payload)
        coordinator = CheckCoordinator(args.retry_interval_secs)
        summary_nodes = {}
        # Technically, each cluster can have its own version, but in practice,
        # we disallow that in YW.
        universe_version = universe.clusters[0].yb_version if universe.clusters else None
        alert_enhancements_version = is_equal_or_newer_release(
            universe_version, ALERT_ENHANCEMENTS_RELEASE)
        report = Report(universe_version)
        for c in universe.clusters:
            master_nodes = c.master_nodes
            tserver_nodes = c.tserver_nodes
            all_nodes = dict(master_nodes)
            all_nodes.update(dict(tserver_nodes))
            summary_nodes.update(dict(all_nodes))
            for (node, node_name) in all_nodes.items():
                checker = NodeChecker(
                        node, node_name, c.identity_file, c.ssh_port,
                        args.start_time_ms, c.namespace_to_config, c.ysql_port,
                        c.ycql_port, c.redis_port, c.enable_tls_client,
                        c.root_and_client_root_ca_same, c.ssl_protocol, c.enable_ysql_auth,
                        c.master_http_port, c.tserver_http_port, universe_version)

                coordinator.add_precheck(checker, "check_openssl_availability")

                # TODO: use paramiko to establish ssh connection to the nodes.
                if node in master_nodes:
                    coordinator.add_check(
                        checker, "check_uptime_for_process", "yb-master")
                    if alert_enhancements_version:
                        coordinator.add_check(checker, "check_master_yb_version", "yb-master")
                    coordinator.add_check(checker, "check_for_error_logs", "yb-master")
                if node in tserver_nodes:
                    coordinator.add_check(
                        checker, "check_uptime_for_process", "yb-tserver")
                    if alert_enhancements_version:
                        coordinator.add_check(checker, "check_tserver_yb_version", "yb-tserver")
                    coordinator.add_check(checker, "check_for_error_logs", "yb-tserver")
                    # Only need to check redis-cli/cqlsh for tserver nodes
                    # to be docker/k8s friendly.
                    if c.enable_ycql:
                        coordinator.add_check(checker, "check_cqlsh")
                    if c.enable_yedis:
                        coordinator.add_check(checker, "check_redis_cli")
                    # TODO: Enable check after addressing issue #1845.
                    if c.enable_ysql:
                        coordinator.add_check(checker, "check_ysqlsh")
                coordinator.add_check(checker, "check_disk_utilization")
                coordinator.add_check(checker, "check_for_core_files")
                coordinator.add_check(checker, "check_file_descriptors")
                if args.check_clock:
                    coordinator.add_check(checker, "check_clock_skew")
                if c.enable_tls:
                    coordinator.add_check(checker, "check_node_to_node_ca_certificate_expiration")
                    coordinator.add_check(checker, "check_node_to_node_certificate_expiration")
                if c.enable_tls_client:
                    coordinator.add_check(checker, "check_client_ca_certificate_expiration")
                    coordinator.add_check(checker, "check_client_certificate_expiration")
                    if not c.root_and_client_root_ca_same:
                        coordinator.add_check(
                            checker, "check_client_to_node_ca_certificate_expiration")
                        coordinator.add_check(
                            checker, "check_client_to_node_certificate_expiration")

        entries = coordinator.run()
        for e in entries:
            report.add_entry(e)

        report.write_to_log(args.log_file)

        # Write to stdout to be caught by YW subprocess.
        print(report)
    else:
        logging.error("Invalid argument combination")
        sys.exit(1)


if __name__ == '__main__':
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s: %(message)s")
    main()
