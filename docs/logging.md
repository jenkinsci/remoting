Logging
===

In Remoting logging system behavior depends on the [Work Directory](workDir.md) mode.

### With work directory

With work directory Remoting automatically writes logs to the disk.
This is a main difference from the legacy mode without workDir.

Logging destinations:

* STDOUT and STDERR
  * Logs include `java.util.logging` and messages printed to _STDOUT/STDERR_ directly.
* Files - `${workDir}/${internalDir}/logs` directory
  * File base name - `remoting.log`
  * Logs are being automatically rotated.
    By default, Remoting keeps 5 10MB files
  * Default logging level - `INFO`
  * If the legacy `-agentLog` or `-slaveLog` option is enabled, this file logging will be disabled.

In order to configure logging it is possible to use an external property file, path to which can be defined using the `-loggingConfig` CLI option. 
See details about the file format
[in this guide](http://tutorials.jenkov.com/java-logging/configuration.html).
Note that `ConsoleHandler` won't be enabled by default if this option is specified.

### Without work directory (legacy mode)

* By default, all logs within the system are being sent to _STDOUT/STDERR_ using `java.util.logging`.
* If `-agentLog` or `-slaveLog` option is specified, the log will be also forwarded to the specified file
  * The existing file will be overridden on startup
  * Remoting does not perform automatic log rotation of this log file
  
Particular Jenkins components use external features to provide better logging in the legacy mode.
E.g. Windows agent services generate logs using features provided by [Windows Service Wrapper (WinSW)](https://github.com/kohsuke/winsw/).