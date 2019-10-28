Remoting Work directory
===

In Remoting work directory is an internal data storage, which may be used by Remoting to store caches, logs and other metadata.

Remoting work directory is available starting from Remoting `3.8`, which is available in [Jenkins 2.68](https://jenkins.io/changelog/#v2.68)).
Before this version there was no working directory concept in the library itself;
all operations were managed by library users (e.g. Jenkins agent workspaces).

### Before Remoting 3.8 (Jenkins 2.68)

* There is no work directory management in Remoting itself
* Logs are not being persisted to the disk unless `-agentLog` option is specified
* JAR Cache is being stored in `${user.home}/.jenkins` unless `-jarCache` option is specified

### After Remoting 3.8 (Jenkins 2.68)

Due to compatibility reasons, Remoting retains the legacy behavior by default.
Work directory can be enabled using the `-workDir` option in CLI.

Once the option is enabled, Remoting starts using the following structure:

```
${WORKDIR}
  |_ ${INTERNAL_DIR} - defined by '-internalDir', 'remoting' by default
    |_ jarCache - JAR Cache
    |_ logs     - Remoting logs
    |_ ...      - Other directories contributed by library users
```

Structure of the `logs` directory depends on the logging settings.
See [this page](logging.md) for more information.

### Migrating to work directories in Jenkins

:exclamation: Remoting does not perform migration from the previous structure, 
because it cannot identify potential external users of the data.

Once the `-workDir` flag is enabled in Remoting, admins are expected to do the following:

1. Remove the `${user.home}/.jenkins` directory if there is no other Remoting instances running under the same user.
2. Consider upgrading configurations of agents in order to enable Work Directories
  * SSH agents can be configured in agent settings.
  * JNLP agents should be started with the `-workDir` parameter.
  * See [JENKINS-44108](https://issues.jenkins-ci.org/browse/JENKINS-44108) for more information about changes in Jenkins plugins, which enable work directories by default.
