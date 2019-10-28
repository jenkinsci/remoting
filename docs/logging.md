# Logging and Diagnostics

## Standard logging

In Remoting logging is powered by the standard `java.util.logging` engine. 
The default behavior depends on the [Work Directory](workDir.md) mode.

:exclamation: Note that `-loggingConfig` option and [Work directories](workDir.md) are available starting from [Remoting 3.8](../CHANGELOG.md#38). 
Before this release you have to use the `-agentLog` option or to redirect STDOUT/STDERR streams.

### Configuration

In order to configure logging it is possible to use an external property file, path to which can be defined using the `-loggingConfig` CLI option or the `java.util.logging.config.file` system property. 

If logging is configured via `-loggingConfig`, some messages printed before the logging system initialization may be missing in startup logs configured by this option.

See details about the property file format
in [Oracle documentation](https://docs.oracle.com/cd/E19717-01/819-7753/6n9m71435/index.html)
and [this guide](http://tutorials.jenkov.com/java-logging/configuration.html).
Note that `ConsoleHandler` won't be enabled by default if this option is specified.

### Default behavior with work directory

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
  * If the legacy `-agentLog` option is enabled, this file logging will be disabled.

If `-agentLog` are not specified, `${workDir}/${internalDir}/logs` directory will be created during the work directory initialization (if required).

<!--TODO: Mention conflict with early initialization by java.util.logging.config.file?-->

### Default behavior without work directory (legacy mode)

* By default, all logs within the system are being sent to _STDOUT/STDERR_ using `java.util.logging`.
* If `-agentLog` option is specified, the log will be also forwarded to the specified file
  * The existing file will be overridden on startup
  * Remoting does not perform automatic log rotation of this log file
  
Particular Jenkins components use external features to provide better logging in the legacy mode.
E.g. Windows agent services generate logs using features provided by [Windows Service Wrapper (WinSW)](https://github.com/kohsuke/winsw/).

## Event listeners

The `hudson.remoting.Channel.Listener` interface can be used to intercept important events programmatically.
For example, you can be notified when a `Command` has been sent out over the channel, and obtain certain details.

Normally administrators need not use this API directly.
[Jenkins core will print events to a standard logger](https://github.com/jenkinsci/jenkins/pull/3071),
and the [Support Core plugin will gather aggregate statistics](https://github.com/jenkinsci/support-core-plugin/pull/128).
